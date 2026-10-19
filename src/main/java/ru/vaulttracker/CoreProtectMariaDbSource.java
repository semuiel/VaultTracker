package ru.vaulttracker;

import java.sql.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Schema adapter for the verified CoreProtect 24.1 / API 12 installation. All SQL is fixed, read-only. */
final class CoreProtectMariaDbSource implements CoreProtectLookupSource {
    interface Connections {Connection open() throws SQLException;}
    private record Kind(Category category,String table,String condition,boolean material,boolean rollback,boolean amount) {}
    private static final List<Kind> KINDS=List.of(
            kind("block","Блоки","🧱","block","b.action IN (0,1,2)",true,true,false),
            kind("container","Контейнеры","📦","container","b.action IN (0,1)",true,true,true),
            kind("item","Предметы","🎒","item","1=1",true,true,true),
            kind("kill","Убийства сущностей","💀","block","b.action=3",false,true,false),
            kind("session","Входы и выходы","🚪","session","1=1",false,false,false),
            kind("chat","Чат","💬","chat","1=1",false,false,false),
            kind("command","Команды","⌨️","command","1=1",false,false,false),
            kind("sign","Таблички","🪧","sign","1=1",false,false,false));
    private static Kind kind(String id,String label,String emoji,String table,String condition,boolean material,boolean rollback,boolean amount) {
        return new Kind(new Category(id,label,emoji,material,Set.of("chat","command","sign").contains(id)),table,condition,material,rollback,amount);
    }
    private final CoreProtectWebConfig config;
    private final Connections connections;
    private final BooleanSupplier available;
    private final boolean checkGrants;
    private volatile Capabilities capabilities;
    CoreProtectMariaDbSource(CoreProtectWebConfig config,BooleanSupplier available) {
        this(config,()-> {
            var properties=new Properties();properties.setProperty("user",config.dbUser());properties.setProperty("password",config.dbPassword());
            properties.setProperty("connectTimeout","2000");properties.setProperty("socketTimeout",Integer.toString(config.timeoutSeconds()*1000));
            properties.setProperty("allowMultiQueries","false");properties.setProperty("allowLocalInfile","false");
            return DriverManager.getConnection("jdbc:mariadb://"+config.dbHost()+":"+config.dbPort()+"/"+config.database(),properties);
        },available,true);
    }
    // Test seam: fixtures use an isolated H2 database, never a production connection or credentials.
    CoreProtectMariaDbSource(CoreProtectWebConfig config,Connections connections,BooleanSupplier available,boolean checkGrants) {
        this.config=config;this.connections=connections;this.available=available;this.checkGrants=checkGrants;
    }
    private Connection open() throws SQLException {
        if(!available.getAsBoolean()) throw new SQLException("CoreProtect unavailable");
        Connection c=connections.open();
        try {
            if(checkGrants) {
                List<String> grants=new ArrayList<>();
                try(var s=c.createStatement()) {s.setQueryTimeout(config.timeoutSeconds());try(var r=s.executeQuery("SHOW GRANTS FOR CURRENT_USER")) {while(r.next()) grants.add(r.getString(1));}}
                verifyGrants(grants,config.database());
            }
            c.setReadOnly(true);return c;
        } catch(Exception failure) {c.close();throw new SQLException("Viewer needs an exclusive SELECT-only database account");}
    }
    static void verifyGrants(List<String> grants,String database) throws SQLException {
        boolean select=false;
        for(String raw:grants) {
            // Roles, proxy grants, GRANT OPTION, global SELECT and any extra privilege fail closed.
            String g=raw.toUpperCase(Locale.ROOT);
            String target="`"+database.toUpperCase(Locale.ROOT)+"`.*";
            if(g.startsWith("GRANT USAGE ON *.* TO ")&&!g.contains("WITH GRANT OPTION")) continue;
            if(g.startsWith("GRANT SELECT ON "+target+" TO ")&&!g.contains("WITH GRANT OPTION")) {select=true;continue;}
            throw new SQLException("Non read-only grant");
        }
        if(!select) throw new SQLException("Missing database SELECT grant");
    }
    @Override public Capabilities capabilities() throws Exception {
        if(!available.getAsBoolean()) throw new SQLException("CoreProtect unavailable");
        if(capabilities!=null) return capabilities;
        try(Connection c=open()) {return inspect(c);}
    }
    private synchronized Capabilities inspect(Connection c) throws Exception {
        if(capabilities!=null) return capabilities;
        Map<String,Set<String>> columns=new HashMap<>();
        try(var s=statement(c,"SELECT TABLE_NAME,COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()",List.of(),5000)) {
            try(var r=s.executeQuery()) {while(r.next()) columns.computeIfAbsent(r.getString(1).toLowerCase(Locale.ROOT),key->new HashSet<>()).add(r.getString(2).toLowerCase(Locale.ROOT));}
        }
        requireColumns(columns,"user",Set.of("rowid","user","uuid"));requireColumns(columns,"world",Set.of("id","world"));
        requireColumns(columns,"material_map",Set.of("id","material"));
        List<Category> categories=new ArrayList<>();
        for(Kind k:KINDS) {
            var needed=new HashSet<>(Set.of("rowid","time","user","wid","x","y","z"));
            if(!Set.of("chat","command").contains(k.category.id())) needed.add("action");
            if(k.material||k.category.id().equals("kill")) needed.add("type");
            if(k.amount) needed.add("amount");if(k.rollback) needed.add("rolled_back");
            if(k.category.text()) needed.addAll(k.category.id().equals("sign")?List.of("line_1","line_2","line_3","line_4","line_5","line_6","line_7","line_8"):List.of("message"));
            if(k.category.id().equals("kill")&&!columns.getOrDefault(table("entity_map"),Set.of()).containsAll(Set.of("id","entity"))) continue;
            if(columns.getOrDefault(table(k.table),Set.of()).containsAll(needed)) categories.add(k.category);
        }
        if(categories.stream().anyMatch(k->k.id().equals("container"))&&categories.stream().anyMatch(k->k.id().equals("item"))&&categories.stream().anyMatch(k->k.id().equals("block")))
            categories.add(new Category("inventory","Операции инвентаря","🎒",true,false));
        List<String> worlds=new ArrayList<>();
        try(var s=statement(c,"SELECT world FROM "+quoted("world")+" ORDER BY id LIMIT 100",List.of(),100)) {try(var r=s.executeQuery()) {while(r.next()) worlds.add(r.getString(1));}}
        capabilities=new Capabilities(List.copyOf(categories),List.copyOf(worlds),columns.getOrDefault(table("username_log"),Set.of()).containsAll(Set.of("user","uuid")));
        return capabilities;
    }
    private void requireColumns(Map<String,Set<String>> columns,String table,Set<String> required) throws SQLException {
        if(!columns.getOrDefault(table(table),Set.of()).containsAll(required)) throw new SQLException("Unsupported CoreProtect schema");
    }
    @Override public Page lookup(CoreProtectQuery q) throws Exception {
        long deadline=System.nanoTime()+config.timeoutSeconds()*1_000_000_000L;
        try(Connection c=open()) {
            Capabilities caps=inspect(c);
            Set<String> ids=new HashSet<>();caps.categories().forEach(k->ids.add(k.id()));
            if(!q.category().equals("all")&&!ids.contains(q.category())) throw new IllegalArgumentException("Категория недоступна в этой версии CoreProtect");
            if(!q.world().isEmpty()&&!caps.worlds().contains(q.world())) throw new IllegalArgumentException("Неизвестный мир");
            List<Integer> users=users(c,q.player(),caps.usernameHistory());
            if(!q.player().isEmpty()&&users.isEmpty()) return new Page(List.of(),false);
            List<String> branches=new ArrayList<>();List<Object> args=new ArrayList<>();
            boolean inventory=q.category().equals("inventory");
            for(Kind k:KINDS) {
                if(!ids.contains(k.category.id())) continue;
                if(inventory&&!Set.of("block","container","item").contains(k.category.id())) continue;
                if(!inventory&&!q.category().equals("all")&&!q.category().equals(k.category.id())) continue;
                if((!q.include().isEmpty()||!q.exclude().isEmpty())&&!k.material) continue;
                String branch=branch(k,q,users,args,inventory);
                branches.add("("+branch+" ORDER BY b.time DESC,b.rowid DESC LIMIT "+(q.offset()+q.limit()+1)+")");
            }
            if(branches.isEmpty()) return new Page(List.of(),false);
            if(System.nanoTime()>=deadline||Thread.currentThread().isInterrupted()) throw new SQLTimeoutException();
            String sql="SELECT * FROM ("+String.join(" UNION ALL ",branches)+") events ORDER BY timestamp DESC,category,id DESC LIMIT ? OFFSET ?";
            args.add(q.limit()+1);args.add(q.offset());
            List<Event> events=new ArrayList<>();
            try(var s=statement(c,sql,args,q.limit()+1)) {
                s.setQueryTimeout(Math.max(1,(int)((deadline-System.nanoTime()+999_999_999L)/1_000_000_000L)));
                try(var r=s.executeQuery()) {while(r.next()&&events.size()<=q.limit()) {
                    String category=r.getString("category");int action=r.getInt("action");
                    String normalized=inventory?((Set.of(0,3,4,10,12).contains(action)?"inventory_add":"inventory_remove")):action(category,action);
                    events.add(new Event(category+":"+r.getString("id"),r.getLong("timestamp"),r.getString("player"),r.getString("uuid"),
                            inventory?"inventory":category,normalized,material(r.getString("material")),(Integer)r.getObject("amount"),
                            r.getString("world"),r.getInt("x"),r.getInt("y"),r.getInt("z"),r.getObject("rolled_back")==null?null:r.getInt("rolled_back")!=0,r.getString("text")));
                }}
            }
            boolean more=events.size()>q.limit()&&q.offset()+q.limit()<=config.maxOffset();
            return new Page(List.copyOf(events.subList(0,Math.min(q.limit(),events.size()))),more);
        }
    }
    @Override public Players players(String search,int offset,int limit) throws Exception {
        if(search==null||search.length()>36||!search.matches("[A-Za-z0-9_\\-]*")||offset<0||offset>1000||limit<1||limit>30)
            throw new IllegalArgumentException("Проверьте ник или UUID. Список ограничен 30 игроками за запрос.");
        try(Connection c=open()) {
            var caps=inspect(c);
            String source="SELECT `user` AS name,uuid FROM "+quoted("user");
            if(caps.usernameHistory())source+=" UNION SELECT `user` AS name,uuid FROM "+quoted("username_log");
            String escaped=search.toLowerCase(Locale.ROOT).replace("_","!_");
            String sql="SELECT DISTINCT name,uuid FROM ("+source+") names WHERE name REGEXP '^[A-Za-z0-9_]{1,16}$' AND (LOWER(name) LIKE ? ESCAPE '!' OR LOWER(uuid) LIKE ? ESCAPE '!') ORDER BY name,uuid LIMIT ? OFFSET ?";
            var rows=new ArrayList<Player>();
            try(var s=statement(c,sql,List.of("%"+escaped+"%","%"+escaped+"%",limit+1,offset),limit+1);var r=s.executeQuery()) {
                while(r.next()) {
                    String name=r.getString(1),uuid=r.getString(2);
                    if(name!=null&&name.matches("[A-Za-z0-9_]{1,16}"))rows.add(new Player(name,uuid==null?"":uuid));
                }
            }
            boolean more=rows.size()>limit&&offset+limit<=1000;
            return new Players(List.copyOf(rows.subList(0,Math.min(limit,rows.size()))),more);
        }
    }
    private List<Integer> users(Connection c,String player,boolean history) throws Exception {
        if(player.isEmpty()) return List.of();
        Set<String> uuids=new LinkedHashSet<>();
        String query="SELECT uuid FROM "+quoted("user")+" WHERE LOWER(`user`)=LOWER(?) OR LOWER(uuid)=LOWER(?)";
        var args=new ArrayList<Object>(List.of(player,player));
        if(history) {query+=" UNION SELECT uuid FROM "+quoted("username_log")+" WHERE LOWER(`user`)=LOWER(?) OR LOWER(uuid)=LOWER(?)";args.add(player);args.add(player);}
        try(var s=statement(c,query+" LIMIT 3",args,3)) {try(var r=s.executeQuery()) {while(r.next()) {String id=r.getString(1);if(id!=null&&!id.isBlank()) uuids.add(id);}}}
        if(uuids.size()>1) throw new IllegalArgumentException("Этот ник связан с несколькими UUID. Укажите UUID игрока");
        String where=uuids.isEmpty()?"LOWER(`user`)=LOWER(?)":"LOWER(uuid)=LOWER(?)";
        String value=uuids.isEmpty()?player:uuids.iterator().next();
        List<Integer> users=new ArrayList<>();
        try(var s=statement(c,"SELECT rowid FROM "+quoted("user")+" WHERE "+where+" LIMIT 33",List.of(value),33)) {
            try(var r=s.executeQuery()) {while(r.next()) users.add(r.getInt(1));}
        }
        if(users.size()>32) throw new IllegalArgumentException("Слишком много записей игрока");return users;
    }
    private String branch(Kind k,CoreProtectQuery q,List<Integer> users,List<Object> args,boolean inventory) {
        String message=k.category.id().equals("sign")?"CONCAT_WS(CHAR(10),b.line_1,b.line_2,b.line_3,b.line_4,b.line_5,b.line_6,b.line_7,b.line_8)":k.category.text()?"b.message":"NULL";
        String type=k.material?"m.material":k.category.id().equals("kill")?"e.entity":"NULL";
        String action=Set.of("chat","command").contains(k.category.id())?"-1":"b.action";
        StringBuilder sql=new StringBuilder("SELECT b.rowid AS id,b.time AS timestamp,u.user AS player,u.uuid AS uuid,'"+k.category.id()+"' AS category,"+action+" AS action,"+type+" AS material,"+(k.amount?"b.amount":k.material?"1":"NULL")+" AS amount,w.world AS world,b.x AS x,b.y AS y,b.z AS z,"+(k.rollback?"b.rolled_back":"NULL")+" AS rolled_back,"+message+" AS text FROM "+quoted(k.table)+" b JOIN "+quoted("user")+" u ON u.rowid=b.user LEFT JOIN "+quoted("world")+" w ON w.id=b.wid ");
        if(k.material) sql.append("LEFT JOIN ").append(quoted("material_map")).append(" m ON m.id=b.type ");
        if(k.category.id().equals("kill")) sql.append("LEFT JOIN ").append(quoted("entity_map")).append(" e ON e.id=b.type ");
        sql.append("WHERE ").append(k.condition).append(" AND b.time>=? AND b.time<=?");args.add(q.from());args.add(q.to());
        if(inventory&&k.table.equals("block")) sql.append(" AND b.action=1");
        if(!users.isEmpty()) {sql.append(" AND b.user IN (").append(placeholders(users.size())).append(")");args.addAll(users);}
        if(!q.world().isEmpty()) {sql.append(" AND w.world=?");args.add(q.world());}
        if(q.x()!=null) {
            sql.append(" AND b.x BETWEEN ? AND ? AND b.z BETWEEN ? AND ? AND b.y BETWEEN ? AND ?");
            args.add(q.x()-q.radius());args.add(q.x()+q.radius());args.add(q.z()-q.radius());args.add(q.z()+q.radius());args.add(q.y()-q.radius());args.add(q.y()+q.radius());
        }
        if(!q.action().isEmpty()) {
            List<Integer> values=actionIds(k.category.id(),q.action(),inventory);
            if(values.isEmpty()) sql.append(" AND 1=0");
            else {sql.append(" AND ").append(action).append(" IN (").append(placeholders(values.size())).append(")");args.addAll(values);}
        }
        if(!q.include().isEmpty()) {sql.append(" AND m.material IN (").append(placeholders(q.include().size())).append(")");args.addAll(q.include());}
        if(!q.exclude().isEmpty()) {sql.append(" AND m.material NOT IN (").append(placeholders(q.exclude().size())).append(")");args.addAll(q.exclude());}
        if(!q.text().isEmpty()) {sql.append(" AND ").append(message).append(" LIKE ? ESCAPE '!'");String escaped=q.text().replace("!","!!").replace("%","!%").replace("_","!_");args.add((q.textMode().equals("contains")?"%":"")+escaped+"%");}
        return sql.toString();
    }
    private static List<Integer> actionIds(String category,String action,boolean inventory) {
        List<Integer> ids=new ArrayList<>();
        for(int i=-1;i<=12;i++) if((inventory?(Set.of(0,3,4,10,12).contains(i)?"inventory_add":"inventory_remove"):action(category,i)).equals(action)) ids.add(i);
        return ids;
    }
    static String action(String category,int id) {
        return switch(category) {
            case "block"->switch(id) {case 0->"break";case 1->"place";case 2->"interact";default->"unknown";};
            case "kill"->"kill";
            case "container"->id==0?"container_remove":id==1?"container_add":"unknown";
            case "session"->id==0?"logout":id==1?"login":"unknown";
            case "item"->switch(id) {case 0->"remove";case 1->"add";case 2->"drop";case 3->"pickup";case 4->"withdraw";case 5->"deposit";case 6->"throw";case 7->"shoot";case 8->"break";case 9->"destroy";case 10->"create";case 11->"sell";case 12->"buy";default->"unknown";};
            default->category;
        };
    }
    private PreparedStatement statement(Connection c,String sql,List<?> args,int maxRows) throws SQLException {
        if(!sql.startsWith("SELECT ")) throw new SQLException("Read only");
        PreparedStatement s=c.prepareStatement(sql);
        try {s.setQueryTimeout(config.timeoutSeconds());s.setMaxRows(maxRows);for(int i=0;i<args.size();i++) s.setObject(i+1,args.get(i));return s;}
        catch(SQLException e) {s.close();throw e;}
    }
    private String table(String name) {return config.prefix()+name;}
    private String quoted(String name) {return "`"+table(name)+"`";}
    private static String placeholders(int n) {return String.join(",",Collections.nCopies(n,"?"));}
    private static String material(String raw) {return raw==null?null:raw.replace("minecraft:","").toUpperCase(Locale.ROOT);}
}

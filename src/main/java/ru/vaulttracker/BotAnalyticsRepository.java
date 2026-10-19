package ru.vaulttracker;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Forever-retained minimal events plus indexed hourly, user and session dimensions. No message bodies. */
final class BotAnalyticsRepository implements AutoCloseable {
    final ZoneId zone;
    private final String url;
    private final Connection writer;
    private final byte[] secret;
    final long started;
    BotAnalyticsRepository(Path folder,ZoneId zone,long now) throws Exception {
        Files.createDirectories(folder);this.zone=zone;
        url="jdbc:h2:"+folder.resolve("bot-analytics").toAbsolutePath()+";DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=2000;NON_KEYWORDS=QUERY,ROLE,ACTION,SESSION,MODE";
        writer=DriverManager.getConnection(url,"sa","");
        try {
            exec(writer,"CREATE TABLE IF NOT EXISTS ba_settings(k VARCHAR(40) PRIMARY KEY,v VARCHAR(512) NOT NULL)");
            String version=setting("schema_version");if(version!=null&&!version.equals("1"))throw new SQLException("Unsupported bot analytics schema");
            // All v1 DDL is idempotent, including recovery after interruption before the version marker.
            for(String sql:List.of(
                "CREATE TABLE IF NOT EXISTS ba_events(id VARCHAR(64) PRIMARY KEY,at BIGINT,actor VARCHAR(64),role VARCHAR(12),feature VARCHAR(40),action VARCHAR(80),kind VARCHAR(30),outcome VARCHAR(20),mode VARCHAR(12),query VARCHAR(64),language VARCHAR(12),source VARCHAR(16),results INT,duration BIGINT)",
                "CREATE INDEX IF NOT EXISTS ba_events_time ON ba_events(at)",
                "CREATE TABLE IF NOT EXISTS ba_hours(bucket BIGINT,role VARCHAR(12),feature VARCHAR(40),action VARCHAR(80),kind VARCHAR(30),outcome VARCHAR(20),mode VARCHAR(12),query VARCHAR(64),language VARCHAR(12),source VARCHAR(16),dow INT,hour_of_day INT,n BIGINT,duration BIGINT,results BIGINT,PRIMARY KEY(bucket,role,feature,action,kind,outcome,mode,query,language,source))",
                "CREATE INDEX IF NOT EXISTS ba_hours_feature ON ba_hours(feature,role,bucket)",
                "CREATE TABLE IF NOT EXISTS ba_users(actor VARCHAR(64) PRIMARY KEY,first_at BIGINT,last_at BIGINT,first_day DATE)",
                "CREATE INDEX IF NOT EXISTS ba_users_first ON ba_users(first_at)",
                "CREATE TABLE IF NOT EXISTS ba_user_hours(bucket BIGINT,actor VARCHAR(64),role VARCHAR(12),feature VARCHAR(40),n BIGINT,actions BIGINT,PRIMARY KEY(bucket,role,feature,actor))",
                "CREATE INDEX IF NOT EXISTS ba_user_hours_actor ON ba_user_hours(actor,bucket)",
                "CREATE INDEX IF NOT EXISTS ba_user_hours_feature ON ba_user_hours(feature,role,bucket)",
                "CREATE TABLE IF NOT EXISTS ba_user_days(day_at DATE,actor VARCHAR(64),role VARCHAR(12),feature VARCHAR(40),n BIGINT,PRIMARY KEY(day_at,role,feature,actor))",
                "CREATE INDEX IF NOT EXISTS ba_user_days_actor ON ba_user_days(actor,day_at)",
                "CREATE TABLE IF NOT EXISTS ba_queries(bucket BIGINT,actor VARCHAR(64),role VARCHAR(12),feature VARCHAR(40),mode VARCHAR(12),query VARCHAR(64),n BIGINT,PRIMARY KEY(bucket,actor,role,feature,mode,query))",
                "CREATE INDEX IF NOT EXISTS ba_queries_time ON ba_queries(bucket,role,feature)",
                "CREATE TABLE IF NOT EXISTS ba_search_users(bucket BIGINT,actor VARCHAR(64),role VARCHAR(12),feature VARCHAR(40),mode VARCHAR(12),n BIGINT,PRIMARY KEY(bucket,actor,role,feature,mode))",
                "CREATE TABLE IF NOT EXISTS ba_sessions(id VARCHAR(64) PRIMARY KEY,actor VARCHAR(64),started BIGINT,last_at BIGINT)",
                "CREATE INDEX IF NOT EXISTS ba_sessions_actor ON ba_sessions(actor,last_at)",
                "CREATE TABLE IF NOT EXISTS ba_session_features(session VARCHAR(64),role VARCHAR(12),feature VARCHAR(40),started BIGINT,last_at BIGINT,actions BIGINT,PRIMARY KEY(session,role,feature))",
                "CREATE INDEX IF NOT EXISTS ba_session_features_time ON ba_session_features(started,role,feature)",
                "CREATE TABLE IF NOT EXISTS ba_flows(id VARCHAR(64) PRIMARY KEY,actor VARCHAR(64),role VARCHAR(12),feature VARCHAR(40),started BIGINT,last_at BIGINT,submitted BOOLEAN,success BOOLEAN)",
                "CREATE INDEX IF NOT EXISTS ba_flows_actor ON ba_flows(actor,feature,last_at)",
                "CREATE INDEX IF NOT EXISTS ba_flows_time ON ba_flows(started,role,feature)",
                "CREATE TABLE IF NOT EXISTS ba_health(at BIGINT PRIMARY KEY,dropped BIGINT,failures BIGINT,gap_seconds BIGINT)"
            ))exec(writer,sql);
            String storedZone=setting("timezone");if(storedZone!=null&&!storedZone.equals(zone.getId()))throw new SQLException("Stored bot analytics timezone differs");
            put("timezone",zone.getId());put("schema_version","1");
            String key=setting("hmac_key");if(key==null){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);key=Base64.getEncoder().encodeToString(bytes);put("hmac_key",key);}secret=Base64.getDecoder().decode(key);
            String first=setting("started");started=first==null?now:Long.parseLong(first);if(first==null)put("started",Long.toString(now));
            long last=Long.parseLong(Optional.ofNullable(setting("heartbeat")).orElse(Long.toString(now)));
            if(now-last>120)health(now,0,0,now-last);
            put("heartbeat",Long.toString(now));writer.setAutoCommit(false);
        }catch(Exception failure){writer.close();throw failure;}
    }
    String hash(String value) {
        try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException("Bot analytics identity unavailable");}
    }
    private String setting(String key)throws SQLException{try(var s=writer.prepareStatement("SELECT v FROM ba_settings WHERE k=?")){bind(s,key);try(var r=s.executeQuery()){return r.next()?r.getString(1):null;}}}
    private void put(String key,String value)throws SQLException{exec(writer,"MERGE INTO ba_settings KEY(k) VALUES(?,?)",key,value);}
    Connection read()throws SQLException{var c=DriverManager.getConnection(url,"sa","");c.setReadOnly(true);c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);return c;}
    synchronized void batch(List<BotAnalyticsService.Event> batch,long now,long dropped,long failures)throws Exception {
        batch(batch,now,dropped,failures,0);
    }
    synchronized void batch(List<BotAnalyticsService.Event> batch,long now,long dropped,long failures,long gap)throws Exception {
        try {
            for(var e:batch)write(e);
            if(dropped>0||failures>0||gap>0)health(now,dropped,failures,gap);
            put("heartbeat",Long.toString(now));writer.commit();
        }catch(Exception failure){writer.rollback();throw failure;}
    }
    private void write(BotAnalyticsService.Event e)throws SQLException {
        try(var s=writer.prepareStatement("SELECT 1 FROM ba_events WHERE id=?")){bind(s,e.id());try(var r=s.executeQuery()){if(r.next())return;}}
        exec(writer,"INSERT INTO ba_events VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",e.id(),e.at(),e.actor(),e.role(),e.feature(),e.action(),e.kind(),e.outcome(),e.mode(),e.query(),e.language(),e.source(),e.results(),e.duration());
        if(e.kind().equals("flow_start"))exec(writer,"INSERT INTO ba_flows VALUES(?,?,?,?,?,?,FALSE,FALSE)",e.id(),e.actor(),e.role(),e.feature(),e.at(),e.at());
        else if(Set.of("form_submit","flow_complete").contains(e.kind())) {
            String field=e.kind().equals("form_submit")?"submitted":"success";
            exec(writer,"UPDATE ba_flows SET "+field+"=TRUE,last_at=? WHERE id=(SELECT id FROM ba_flows WHERE actor=? AND feature=? AND success=FALSE ORDER BY last_at DESC,id LIMIT 1)",e.at(),e.actor(),e.feature());
        }
        var local=Instant.ofEpochSecond(e.at()).atZone(zone).truncatedTo(java.time.temporal.ChronoUnit.HOURS);long hour=local.toEpochSecond();boolean action=Set.of("button_click","form_submit").contains(e.kind());
        counter("ba_hours",new String[]{"bucket","role","feature","action","kind","outcome","mode","query","language","source"},
            new Object[]{hour,e.role(),e.feature(),e.action(),e.kind(),e.outcome(),e.mode(),e.query(),e.language(),e.source()},
            new String[]{"dow","hour_of_day","n","duration","results"},new long[]{local.getDayOfWeek().getValue()-1,local.getHour(),1,e.duration(),e.results()},Set.of("n","duration","results"));
        counter("ba_user_hours",new String[]{"bucket","role","feature","actor"},new Object[]{hour,e.role(),e.feature(),e.actor()},new String[]{"n","actions"},new long[]{1,action?1:0},Set.of("n","actions"));
        if(e.feature().equals("search")&&e.kind().equals("form_submit")&&!e.query().isEmpty())
            counter("ba_queries",new String[]{"bucket","actor","role","feature","mode","query"},new Object[]{hour,e.actor(),e.role(),e.feature(),e.mode(),e.query()},new String[]{"n"},new long[]{1},Set.of("n"));
        if(e.feature().equals("search")&&e.kind().equals("form_submit")&&e.action().equals("search.result"))
            counter("ba_search_users",new String[]{"bucket","actor","role","feature","mode"},new Object[]{hour,e.actor(),e.role(),e.feature(),e.mode()},new String[]{"n"},new long[]{1},Set.of("n"));
        counter("ba_user_days",new String[]{"day_at","actor","role","feature"},new Object[]{local.toLocalDate(),e.actor(),e.role(),e.feature()},new String[]{"n"},new long[]{1},Set.of("n"));
        if(exec(writer,"UPDATE ba_users SET last_at=GREATEST(last_at,?) WHERE actor=?",e.at(),e.actor())==0)exec(writer,"INSERT INTO ba_users VALUES(?,?,?,?)",e.actor(),e.at(),e.at(),local.toLocalDate());
        String session=null;long previous=0;
        try(var s=writer.prepareStatement("SELECT id,last_at FROM ba_sessions WHERE actor=? ORDER BY last_at DESC LIMIT 1")){bind(s,e.actor());try(var r=s.executeQuery()){if(r.next()){session=r.getString(1);previous=r.getLong(2);}}}
        if(session==null||e.at()-previous>=1800){session=e.id();exec(writer,"INSERT INTO ba_sessions VALUES(?,?,?,?)",session,e.actor(),e.at(),e.at());}
        else exec(writer,"UPDATE ba_sessions SET last_at=GREATEST(last_at,?) WHERE id=?",e.at(),session);
        if(exec(writer,"UPDATE ba_session_features SET last_at=GREATEST(last_at,?),actions=actions+? WHERE session=? AND role=? AND feature=?",e.at(),action?1:0,session,e.role(),e.feature())==0)
            exec(writer,"INSERT INTO ba_session_features VALUES(?,?,?,?,?,?)",session,e.role(),e.feature(),e.at(),e.at(),action?1:0);
    }
    private void health(long now,long dropped,long failures,long gap)throws SQLException {
        counter("ba_health",new String[]{"at"},new Object[]{now},new String[]{"dropped","failures","gap_seconds"},new long[]{dropped,failures,gap},Set.of("dropped","failures","gap_seconds"));
    }
    private void counter(String table,String[] keys,Object[] values,String[] fields,long[] amounts,Set<String> additive)throws SQLException {
        String where=String.join(" AND ",Arrays.stream(keys).map(k->k+"=?").toList());
        var updateFields=new ArrayList<String>();var args=new ArrayList<Object>();
        for(int i=0;i<fields.length;i++)if(additive.contains(fields[i])){updateFields.add(fields[i]+"="+fields[i]+"+?");args.add(amounts[i]);}
        args.addAll(List.of(values));
        if(exec(writer,"UPDATE "+table+" SET "+String.join(",",updateFields)+" WHERE "+where,args.toArray())==0){
            var insert=new ArrayList<Object>(List.of(values));for(long value:amounts)insert.add(value);
            exec(writer,"INSERT INTO "+table+"("+String.join(",",keys)+","+String.join(",",fields)+") VALUES("+String.join(",",Collections.nCopies(insert.size(),"?"))+")",insert.toArray());
        }
    }
    static void bind(PreparedStatement s,Object...args)throws SQLException{for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);}
    static int exec(Connection db,String sql,Object...args)throws SQLException{try(var s=db.prepareStatement(sql)){bind(s,args);return s.executeUpdate();}}
    @Override public synchronized void close()throws Exception{writer.close();}
}

package ru.vaulttracker;

import org.bukkit.Material;
import java.time.Instant;
import java.util.*;

record CoreProtectQuery(String player,String category,String action,long from,long to,String world,
                        Integer x,Integer y,Integer z,int radius,List<String> include,List<String> exclude,
                        String text,String textMode,int offset,int limit) {
    static CoreProtectQuery parse(Map<String,String> p,CoreProtectWebConfig c,long now) {
        Set<String> allowed=Set.of("player","category","action","from","to","world","x","y","z","radius","include","exclude","text","textMode","offset","limit");
        if(!allowed.containsAll(p.keySet())) throw new IllegalArgumentException("Неизвестный фильтр");
        String player=p.getOrDefault("player","").strip();
        if(!player.isEmpty()&&!player.matches("[A-Za-z0-9_]{1,16}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new IllegalArgumentException("Введите игровой ник или UUID");
        String category=p.getOrDefault("category","all"),action=p.getOrDefault("action","");
        if(!category.matches("[a-z]{1,16}")||!action.matches("[a-z_]{0,24}")) throw new IllegalArgumentException("Некорректный тип события");
        long to=time(p.get("to"),now),from=time(p.get("from"),to-86400);
        if(from<0||to>now+30||from>=to||to-from>c.maxDays()*86400L) throw new IllegalArgumentException("Период: от 1 секунды до "+c.maxDays()+" дней");
        String world=p.getOrDefault("world","").strip();
        if(!world.matches("[\\p{L}\\p{N}_ .:/-]{0,128}")) throw new IllegalArgumentException("Некорректный мир");
        boolean coords=p.containsKey("x")||p.containsKey("y")||p.containsKey("z");
        Integer x=null,y=null,z=null;
        if(coords) {
            if(world.isEmpty()) throw new IllegalArgumentException("Для координат выберите мир");
            x=integer(p,"x",null,-30_000_000,30_000_000);y=integer(p,"y",null,-2048,2048);z=integer(p,"z",null,-30_000_000,30_000_000);
        }
        int radius=integer(p,"radius",0,0,c.maxRadius());
        if(!coords&&radius!=0) throw new IllegalArgumentException("Укажите координаты");
        if(player.isEmpty()&&!coords) throw new IllegalArgumentException("Укажите игрока или координаты: поиск по всему серверу запрещён");
        String text=p.getOrDefault("text","").strip(),mode=p.getOrDefault("textMode","contains");
        if(text.length()>200||!Set.of("contains","starts").contains(mode)||text.indexOf('\0')>=0) throw new IllegalArgumentException("Некорректный текстовый фильтр");
        if(!text.isEmpty()&&!Set.of("chat","command","sign").contains(category)) throw new IllegalArgumentException("Текстовый фильтр: выберите чат, команды или таблички");
        return new CoreProtectQuery(player,category,action,from,to,world,x,y,z,radius,materials(p.get("include")),materials(p.get("exclude")),
                text,mode,integer(p,"offset",0,0,c.maxOffset()),integer(p,"limit",c.pageSize(),1,c.maxPageSize()));
    }
    private static int integer(Map<String,String> p,String key,Integer fallback,int min,int max) {
        try {int v=p.containsKey(key)?Integer.parseInt(p.get(key)):Objects.requireNonNull(fallback);if(v<min||v>max) throw new Exception();return v;}
        catch(Exception invalid) {throw new IllegalArgumentException("Проверьте "+key+" ("+min+"…"+max+")");}
    }
    private static long time(String value,long fallback) {
        if(value==null||value.isBlank()) return fallback;
        try {return value.matches("[0-9]{1,12}")?Long.parseLong(value):Instant.parse(value).getEpochSecond();}
        catch(Exception bad) {throw new IllegalArgumentException("Некорректная дата");}
    }
    static List<String> materials(String raw) {
        if(raw==null||raw.isBlank()) return List.of();
        if(raw.length()>400) throw new IllegalArgumentException("Слишком много материалов");
        List<String> result=new ArrayList<>();
        for(String part:raw.split(",",-1)) {
            String name=part.strip().toUpperCase(Locale.ROOT).replace("MINECRAFT:","");
            Material m=Material.getMaterial(name);
            if(m==null) throw new IllegalArgumentException("Неизвестный материал: "+part.strip());
            result.add("minecraft:"+m.name().toLowerCase(Locale.ROOT));
        }
        if(result.size()>8) throw new IllegalArgumentException("Не более 8 материалов");
        return List.copyOf(result);
    }
    String auditSummary() {
        return "player="+player+" category="+category+" action="+action+" time="+from+".."+to+
                " world="+world+" xyz="+(x==null?"-":x+","+y+","+z)+" radius="+radius+" offset="+offset+" limit="+limit;
    }
}

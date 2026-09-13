package ru.vaulttracker;

import java.util.*;

final class ResourceGroups {
    record Group(String code,String title,List<String> materials,String displayMaterial) {}
    static final Group DIAMOND_ORES=new Group("AR","Алмазная руда (обычная + глубинная)",
            List.of("DIAMOND_ORE","DEEPSLATE_DIAMOND_ORE"),"DIAMOND_ORE");
    static final Group EMERALD_ORES=new Group("IR","Изумрудная руда (обычная + глубинная)",
            List.of("EMERALD_ORE","DEEPSLATE_EMERALD_ORE"),"EMERALD_ORE");

    static Group resolve(String raw) {
        String value=raw==null ? "" : raw.trim();
        if(value.equalsIgnoreCase("AR") || value.equalsIgnoreCase("АР")) return DIAMOND_ORES;
        if(value.equalsIgnoreCase("IR") || value.equalsIgnoreCase("ИР")) return EMERALD_ORES;
        return null;
    }
    static String query(Group group) { return "@"+group.code(); }
    static Group fromQuery(String query) {
        if(query==null || !query.startsWith("@")) return null;
        return resolve(query.substring(1));
    }
    private ResourceGroups() {}
}

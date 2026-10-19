package ru.vaulttracker;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Stable operation IDs only. Callback values, labels and free text never enter this catalogue. */
final class BotAnalyticsCatalog {
    record Entry(String id,String feature,String type,String requiredRole,String outcomes,String owner,String arguments) {}
    private final Map<String,Entry> entries=new ConcurrentHashMap<>();
    static final Set<String> ADMIN_OPERATIONS=Set.of("addSuper","removeSuper","addAdmin","removeAdmin","chat","privateMessage","teleportPlayer","teleportCoords","scale","flySpeed","walkSpeed","bastionAdd","bastionRemove","freeze","unfreeze","ban","permanentBan","kick","unban","toggleLp","addLp","removeLp","heal","kill","repair","op","deop");
    static String actionId(String prefix,String operation){return ADMIN_OPERATIONS.contains(operation)?prefix+operation:prefix;}
    static final Map<String,String> FEATURES=Map.ofEntries(
        Map.entry("navigation","Меню и кабинет"),Map.entry("search","Поиск игроков и предметов"),Map.entry("resources","Ресурсы и история"),
        Map.entry("link","Привязка аккаунта"),Map.entry("premium","Премиум"),Map.entry("donations","Пожертвования"),
        Map.entry("voting","Голосования"),Map.entry("support","Обращения"),Map.entry("friends","Друзья и доступ"),
        Map.entry("messages","Личные сообщения"),Map.entry("settings","Настройки"),Map.entry("statistics","Статистика"),
        Map.entry("admin","Администрирование"),Map.entry("root","Root"),Map.entry("coreprotect","CoreProtect Mini App"),
        Map.entry("chat_analytics","Статистика чатов"),Map.entry("bot_analytics","Аналитика бота"));
    void register(String id,String owner,String requiredRole,boolean operation) {
        register(id,owner,requiredRole,operation,"none_or_handler");
    }
    void register(String id,String owner,String requiredRole,boolean operation,String arguments) {
        if(!id.matches("[A-Za-z0-9_.:-]{1,80}"))throw new IllegalArgumentException("Unsafe analytics action ID");
        String feature=feature(id,owner);
        entries.putIfAbsent(id,new Entry(id,feature,operation?"operation":"screen_or_button",requiredRole,
            "click/view; business success only from explicit committed-result hook",owner,arguments));
    }
    Entry entry(String id){return entries.get(id);}
    void event(String id,String feature,String kind){entries.putIfAbsent(id,new Entry(id,feature,kind,"handler","explicit outcome when available","BusinessHook","metadata_only"));}
    List<Entry> entries(){return entries.values().stream().sorted(Comparator.comparing(Entry::id)).toList();}
    static String feature(String id,String owner) {
        String lower=id.toLowerCase(Locale.ROOT);
        if(lower.startsWith("link"))return "link";
        if(lower.contains("premium"))return "premium";
        if(lower.startsWith("donation"))return "donations";
        if(lower.startsWith("voting")||owner.equals("VotingMenu"))return "voting";
        if(lower.startsWith("support"))return "support";
        if(lower.startsWith("friend")||lower.startsWith("shared"))return "friends";
        if(lower.startsWith("dialog")||lower.startsWith("message"))return "messages";
        if(lower.startsWith("resource")||lower.startsWith("personaltop"))return "resources";
        if(lower.startsWith("root")||owner.equals("SuperAdminMenu"))return "root";
        return switch(owner){case "SupportMenu"->"support";case "ResourceMenu"->"resources";case "FriendsMenu"->"friends";case "MessagesMenu"->"messages";case "SettingsMenu"->"settings";case "StatisticsMenu"->"statistics";case "AdminMenu"->"admin";case "FreeCoinsMenu"->"link";case "Search"->"search";default->"navigation";};
    }
}

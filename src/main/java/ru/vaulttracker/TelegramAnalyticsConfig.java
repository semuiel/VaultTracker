package ru.vaulttracker;

import java.net.URI;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

record TelegramAnalyticsConfig(boolean enabled,Set<Long> chats,ZoneId zone,long accessChatId,String publicUrl,int snapshotMinutes) {
    static TelegramAnalyticsConfig load(Path folder) {
        var y=YamlConfiguration.loadConfiguration(folder.resolve("telegram.yml").toFile());String p="telegramAnalytics.";
        Set<Long> chats=new LinkedHashSet<>();
        for(Object raw:y.getList(p+"trackedChats",List.of())) {long id=Long.parseLong(raw.toString());if(id>=0)throw new IllegalArgumentException("trackedChats: нужны ID групп/каналов");chats.add(id);}
        var c=new TelegramAnalyticsConfig(y.getBoolean(p+"enabled",false),Set.copyOf(chats),ZoneId.of(y.getString(p+"timezone",ZoneId.systemDefault().getId())),
                y.getLong(p+"accessChatId",y.getLong("coreprotectWeb.accessChatId",0)),y.getString(p+"publicUrl","").strip(),y.getInt(p+"memberSnapshotMinutes",60));
        c.validate();return c;
    }
    void validate() {
        if(chats.size()>100||snapshotMinutes<15||snapshotMinutes>1440)throw new IllegalArgumentException("Analytics: до 100 чатов, snapshots 15–1440 минут");
        if(enabled) {
            URI u=URI.create(publicUrl);
            if(chats.isEmpty()||accessChatId>=0||!"https".equals(u.getScheme())||u.getHost()==null||u.getPort()==0||u.getPort()>65535||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||!u.getPath().matches("/[A-Za-z0-9_-]+/"))
                throw new IllegalArgumentException("Analytics: нужны trackedChats, accessChatId и HTTPS URL отдельного раздела с / на конце");
        }
    }
    String basePath(){return URI.create(publicUrl).getPath();}
    String origin(){return "https://"+URI.create(publicUrl).getRawAuthority();}
}

package ru.vaulttracker;

import java.net.URI;
import java.nio.file.Path;
import java.time.ZoneId;
import org.bukkit.configuration.file.YamlConfiguration;

/** Reuses the shared Mini App listener and administrative chat; disabled until configured. */
record BotAnalyticsConfig(boolean enabled,long accessChatId,String publicUrl,ZoneId zone) {
    static BotAnalyticsConfig load(Path folder) {
        var y=YamlConfiguration.loadConfiguration(folder.resolve("telegram.yml").toFile());String p="botAnalytics.";
        var c=new BotAnalyticsConfig(y.getBoolean(p+"enabled",false),y.getLong(p+"accessChatId",0)==0?y.getLong("coreprotectWeb.accessChatId",0):y.getLong(p+"accessChatId",0),
                y.getString(p+"publicUrl","").strip(),ZoneId.of(y.getString(p+"timezone","Asia/Novosibirsk")));
        if(c.enabled()) {
            URI u=URI.create(c.publicUrl());
            if(c.accessChatId()>=0||!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||!u.getPath().matches("/[A-Za-z0-9_-]+/"))
                throw new IllegalArgumentException("botAnalytics: нужны административный accessChatId и отдельный HTTPS URL с / на конце");
        }
        return c;
    }
    String basePath(){return URI.create(publicUrl).getPath();}
    String origin(){return "https://"+URI.create(publicUrl).getRawAuthority();}
}

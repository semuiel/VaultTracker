package ru.vaulttracker;

import org.bukkit.configuration.file.YamlConfiguration;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Settings exclusively owned by the chat bridge; transport inherits the catalogue proxy. */
final class TelegramChatConfig {
    record Target(long chatId,int topicId) {boolean matches(TelegramApi.Incoming update) {return chatId==update.chatId() && topicId==update.topicId();}}
    record Emoji(String normal,String customId) {
        String html() {String fallback=TelegramChatFormat.escape(normal);return customId.isEmpty()?fallback:"<tg-emoji emoji-id=\""+customId+"\">"+fallback+"</tg-emoji>";}
    }
    final YamlConfiguration yaml;
    final boolean enabled;
    final String token;
    final Target chat,advancements;
    final Map<String,Emoji> dimensions;
    private TelegramChatConfig(YamlConfiguration y) {
        yaml=y;enabled=y.getBoolean("enabled",false);
        String env=System.getenv("VAULT_TELEGRAM_CHAT_TOKEN");token=env==null||env.isBlank()?y.getString("token","").trim():env.trim();
        chat=target(y,"chat",null);advancements=target(y,"advancements",chat);
        if(enabled) {
            if(!token.matches("[0-9]+:[A-Za-z0-9_-]{20,}")) throw new IllegalArgumentException("telegramchat.yml: заполните token");
            if(chat.chatId()>=0 || (advancementEnabled() && advancements.chatId()>=0)) throw new IllegalArgumentException("telegramchat.yml: укажите отрицательный chatId группы");
        }
        int window=y.getInt("messages.mergeWindowSeconds",5);
        if(window<0||window>30) throw new IllegalArgumentException("telegramchat.yml: mergeWindowSeconds от 0 до 30");
        int poll=y.getInt("advanced.pollTimeoutSeconds",30);long initial=y.getLong("advanced.connectionRetry.initialDelay",1000),maximum=y.getLong("advanced.connectionRetry.maxDelay",300000);
        if(poll<5||poll>50||initial<100||initial>300000||maximum<initial||maximum>1800000) throw new IllegalArgumentException("telegramchat.yml: проверьте pollTimeoutSeconds и connectionRetry");
        java.net.URI uri;
        try {uri=java.net.URI.create(text("advanced.botApiUrl","https://api.telegram.org"));} catch(IllegalArgumentException e) {throw new IllegalArgumentException("telegramchat.yml: неверный botApiUrl");}
        if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null) throw new IllegalArgumentException("telegramchat.yml: botApiUrl должен быть HTTPS-адресом без логина и параметров");
        Map<String,Emoji> icons=new HashMap<>();
        for(String key:List.of("overworld","nether","end","other")) {
            String base="list.dimensions."+key;String normal=y.getString(base+".emoji",switch(key){case "overworld"->"🌍";case "nether"->"🔥";case "end"->"🌌";default->"❓";});
            String custom=y.getString(base+".customEmojiId","").trim();
            if(!custom.isEmpty() && !custom.matches("[0-9]{1,30}")) throw new IllegalArgumentException("telegramchat.yml: customEmojiId должен содержать только цифры");
            if(normal.isBlank() || normal.length()>32) throw new IllegalArgumentException("telegramchat.yml: задайте обычный emoji длиной до 32 символов");
            icons.put(key,new Emoji(normal,custom));
        }
        dimensions=Map.copyOf(icons);
        var templates=y.getConfigurationSection("formats");
        if(templates!=null) for(String key:templates.getKeys(false)) if(y.getString("formats."+key,"").length()>1000) throw new IllegalArgumentException("telegramchat.yml: слишком длинный шаблон "+key);
    }
    private static Target target(YamlConfiguration y,String key,Target fallback) {
        long chat=y.getLong(key+".chatId",fallback==null?0:fallback.chatId());
        long topic=y.getLong(key+".topicId",fallback==null?0:fallback.topicId());
        if(topic<0||topic>Integer.MAX_VALUE) throw new IllegalArgumentException("telegramchat.yml: topicId от 0 до 2147483647");
        return new Target(chat,(int)topic);
    }
    static TelegramChatConfig load(Path folder) throws Exception {
        Files.createDirectories(folder);Path file=folder.resolve("telegramchat.yml");
        if(!Files.exists(file)) try(InputStream in=TelegramChatConfig.class.getResourceAsStream("/telegramchat.yml")) {
            if(in==null) throw new IOException("Нет шаблона telegramchat.yml");Files.copy(in,file);
        }
        YamlConfiguration y=new YamlConfiguration();y.load(file.toFile());return new TelegramChatConfig(y);
    }
    static TelegramChatConfig parse(String yaml) throws Exception {YamlConfiguration y=new YamlConfiguration();y.loadFromString(yaml);return new TelegramChatConfig(y);}
    boolean flag(String key,boolean fallback) {return yaml.getBoolean(key,fallback);}
    String text(String key,String fallback) {return yaml.getString(key,fallback);}
    boolean advancementEnabled() {return flag("advancements.enabled",true);}
    boolean listTarget(TelegramApi.Incoming update) {return chat.matches(update)||(advancementEnabled()&&advancements.matches(update));}
    int listLifetimeSeconds() {return Math.max(30,Math.min(3600,yaml.getInt("list.messageLifetimeSeconds",300)));}
    int mergeSeconds() {return yaml.getInt("messages.mergeWindowSeconds",5);}
    String format(String key,String fallback) {return text("formats."+key,fallback);}
    boolean shared(TelegramConfig catalogue) {return enabled && catalogue.enabled() && token.equals(catalogue.token());}
    TelegramConfig transport(TelegramConfig catalogue) {
        // Token-specific offsets prevent accidentally skipping updates after changing bots.
        String botId=token.split(":",2)[0];
        return new TelegramConfig(enabled,token,Set.of(chat.chatId()),Set.of(),List.of(),20,yaml.getInt("advanced.pollTimeoutSeconds",30),300,text("advanced.botApiUrl","https://api.telegram.org").replaceAll("/+$",""),catalogue.proxy(),new TelegramConfig.Retry(0,yaml.getLong("advanced.connectionRetry.initialDelay",1000),yaml.getLong("advanced.connectionRetry.maxDelay",300000)),catalogue.offsetFile().resolveSibling("telegramchat-"+botId+".offset"));
    }
}

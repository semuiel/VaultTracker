package ru.vaulttracker;

import org.bukkit.configuration.file.YamlConfiguration;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

record TelegramConfig(boolean enabled, String token, Set<Long> allowedChatIds, Set<Long> adminUserIds, List<Chat> chats, int pageSize,
                      int pollTimeoutSeconds, int messageLifetimeSeconds, String botApiUrl, Proxy proxy, Retry retry, Path offsetFile) {
    static final int LIST_PAGE_SIZE=20;
    static final int BUTTON_PAGE_SIZE=10;
    enum ProxyType { NONE, SOCKS5, HTTP }
    record Proxy(ProxyType type,String host,int port,String username,String password) {
        boolean enabled() { return type != ProxyType.NONE; }
        boolean authenticated() { return enabled() && !username.isBlank(); }
    }
    record Retry(int maxAttempts,long initialDelay,long maxDelay) {}
    record Chat(boolean isDefault,long chatId,int topicId) {}

    static TelegramConfig load(Path dataFolder) throws IOException {
        return load(dataFolder,true);
    }
    static TelegramConfig load(Path dataFolder,boolean validateToken) throws IOException {
        Files.createDirectories(dataFolder);
        Path path=dataFolder.resolve("telegram.yml");
        if(!Files.exists(path)) try(InputStream input=TelegramConfig.class.getResourceAsStream("/telegram.yml")) {
            if(input==null) throw new IOException("Шаблон telegram.yml отсутствует в JAR");
            Files.copy(input,path);
        }
        YamlConfiguration y=YamlConfiguration.loadConfiguration(path.toFile());
        boolean enabled=y.getBoolean("enabled",false);
        String token=secret(y.getString("token",""),"VAULT_TELEGRAM_TOKEN");
        if(enabled && validateToken && !token.matches("[0-9]+:[A-Za-z0-9_-]{20,}"))
            throw new IllegalArgumentException("В telegram.yml не задан корректный token");
        Set<Long> allowed=new LinkedHashSet<>();
        for(Object value:list(y,"allowedChatIds","allowed-chat-ids")) try { allowed.add(Long.parseLong(value.toString())); }
        catch(NumberFormatException e) { throw new IllegalArgumentException("Некорректный allowedChatIds: "+value); }
        Set<Long> admins=new LinkedHashSet<>();
        for(Object value:y.getList("adminUserIds",List.of())) try {
            long userId=Long.parseLong(value.toString());
            if(userId<=0) throw new NumberFormatException();
            admins.add(userId);
        } catch(NumberFormatException e) { throw new IllegalArgumentException("Некорректный adminUserIds: "+value); }
        List<Chat> chats=new ArrayList<>(); int defaults=0;
        for(Map<?,?> value:y.getMapList("chats")) {
            boolean isDefault=Boolean.parseBoolean(String.valueOf(value.containsKey("isDefault") ? value.get("isDefault") : false));
            long chatId=number(value.get("chatId"),"chats.chatId").longValue();
            long rawTopicId=value.containsKey("topicId") ? number(value.get("topicId"),"chats.topicId").longValue() : 0;
            if(chatId==0) throw new IllegalArgumentException("chats.chatId не может быть равен 0");
            if(rawTopicId<0 || rawTopicId>Integer.MAX_VALUE) throw new IllegalArgumentException("Некорректный chats.topicId: "+rawTopicId);
            int topicId=(int)rawTopicId;
            if(isDefault) defaults++;
            Chat chat=new Chat(isDefault,chatId,topicId);
            if(chats.stream().anyMatch(existing->existing.chatId()==chatId && existing.topicId()==topicId))
                throw new IllegalArgumentException("Одинаковые chatId и topicId указаны в chats дважды");
            chats.add(chat);
        }
        if(defaults>1) throw new IllegalArgumentException("В chats только одна тема может иметь isDefault: true");
        String api=string(y,"advanced.botApiUrl","advanced.bot-api-url","https://api.telegram.org").replaceAll("/+$","");
        URI uri;
        try { uri=URI.create(api); } catch(IllegalArgumentException e) { throw new IllegalArgumentException("Некорректный advanced.botApiUrl"); }
        if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null)
            throw new IllegalArgumentException("advanced.botApiUrl должен быть HTTPS-адресом без логина и параметров");
        String rawType=y.getString("advanced.proxy.type","none").toUpperCase(Locale.ROOT);
        ProxyType type;
        try { type=ProxyType.valueOf(rawType); } catch(IllegalArgumentException e) { throw new IllegalArgumentException("proxy.type: none, socks5 или http"); }
        String host=y.getString("advanced.proxy.host","").trim();
        int port=y.getInt("advanced.proxy.port",1080);
        String username=secret(y.getString("advanced.proxy.username",""),"VAULT_TELEGRAM_PROXY_USERNAME");
        String password=secret(y.getString("advanced.proxy.password",""),"VAULT_TELEGRAM_PROXY_PASSWORD");
        if(type!=ProxyType.NONE && (!host.matches("[A-Za-z0-9.:-]+") || port<1 || port>65535))
            throw new IllegalArgumentException("Проверьте host и port прокси в telegram.yml");
        if(username.isBlank()!=password.isBlank()) throw new IllegalArgumentException("Укажите одновременно username и password прокси");
        int pageSize=LIST_PAGE_SIZE;
        int poll=bounded(integer(y,"pollTimeoutSeconds","poll-timeout-seconds",30),5,50,"pollTimeoutSeconds");
        int lifetime=bounded(y.getInt("messageLifetimeSeconds",300),30,86400,"messageLifetimeSeconds");
        int attempts=integer(y,"advanced.connectionRetry.maxAttempts","advanced.connection-retry.max-attempts",10);
        long initial=boundedLong(longValue(y,"advanced.connectionRetry.initialDelay","advanced.connection-retry.initial-delay",1000),100,300000,"initialDelay");
        long maximum=boundedLong(longValue(y,"advanced.connectionRetry.maxDelay","advanced.connection-retry.max-delay",300000),initial,1800000,"maxDelay");
        return new TelegramConfig(enabled,token,Set.copyOf(allowed),Set.copyOf(admins),List.copyOf(chats),pageSize,poll,lifetime,api,
                new Proxy(type,host,port,username,password),new Retry(attempts,initial,maximum),dataFolder.resolve("telegram.offset"));
    }
    private static String secret(String configured,String environment) {
        String value=System.getenv(environment); return value!=null && !value.isBlank() ? value.trim() : configured.trim();
    }
    private static List<?> list(YamlConfiguration y,String key,String legacy) {
        return y.contains(key) ? y.getList(key,List.of()) : y.getList(legacy,List.of());
    }
    private static String string(YamlConfiguration y,String key,String legacy,String fallback) {
        return y.contains(key) ? y.getString(key,fallback) : y.getString(legacy,fallback);
    }
    private static int integer(YamlConfiguration y,String key,String legacy,int fallback) {
        return y.contains(key) ? y.getInt(key,fallback) : y.getInt(legacy,fallback);
    }
    private static long longValue(YamlConfiguration y,String key,String legacy,long fallback) {
        return y.contains(key) ? y.getLong(key,fallback) : y.getLong(legacy,fallback);
    }
    private static Number number(Object value,String name) {
        if(value instanceof Number number) return number;
        try { return Long.parseLong(String.valueOf(value)); }
        catch(NumberFormatException e) { throw new IllegalArgumentException("Некорректный "+name+": "+value); }
    }
    private static int bounded(int value,int min,int max,String name) {
        if(value<min || value>max) throw new IllegalArgumentException(name+" должно быть от "+min+" до "+max); return value;
    }
    private static long boundedLong(long value,long min,long max,String name) {
        if(value<min || value>max) throw new IllegalArgumentException(name+" должно быть от "+min+" до "+max); return value;
    }
    boolean allowed(long chatId,int topicId) {
        if(!chats.isEmpty()) return chats.stream().anyMatch(chat->chat.chatId()==chatId && chat.topicId()==topicId);
        return allowedChatIds.isEmpty() || allowedChatIds.contains(chatId);
    }
    boolean admin(long userId) { return adminUserIds.contains(userId); }
}

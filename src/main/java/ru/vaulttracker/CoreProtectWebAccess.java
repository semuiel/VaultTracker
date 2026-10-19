package ru.vaulttracker;

import java.security.SecureRandom;
import java.util.*;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;

/** Shared internal roles plus the same live Telegram checker as /offtop. Fail closed. */
final class CoreProtectWebAccess {
    interface ChatAdmin {boolean check(long chat,long user) throws Exception;}
    private record Session(long user,long expires) {}
    private record Check(boolean allowed,long expires) {}
    private record Window(long start,int count) {}
    static final long ACL_TTL=30_000,SESSION_TTL=10*60_000;
    private final boolean enabled;
    private final long accessChatId;
    private final int requestsPerMinute;
    private final LongPredicate role;
    private final ChatAdmin chatAdmin;
    private final LongSupplier clock;
    private final Map<String,Session> sessions=new HashMap<>();
    private final Map<Long,Check> checks=new HashMap<>();
    private final Map<Long,Window> rates=new HashMap<>();
    private final SecureRandom random=new SecureRandom();
    CoreProtectWebAccess(CoreProtectWebConfig config,LongPredicate role,ChatAdmin checker,LongSupplier clock) {
        this(config.enabled(),config.accessChatId(),config.requestsPerMinute(),role,checker,clock);
    }
    CoreProtectWebAccess(boolean enabled,long accessChatId,int requestsPerMinute,LongPredicate role,ChatAdmin checker,LongSupplier clock) {
        this.enabled=enabled;this.accessChatId=accessChatId;this.requestsPerMinute=requestsPerMinute;this.role=role;chatAdmin=checker;this.clock=clock;
    }
    synchronized boolean allowed(long user,boolean fresh) {
        if(!enabled||user<=0||!role.test(user)) return false;
        long now=clock.getAsLong();
        checks.entrySet().removeIf(e->e.getValue().expires<=now);
        Check previous=checks.get(user);
        if(!fresh&&previous!=null) return previous.allowed;
        boolean allowed=false;
        try {allowed=chatAdmin.check(accessChatId,user);}catch(Exception unavailable) { /* deny */ }
        if(checks.size()<4096) checks.put(user,new Check(allowed,now+ACL_TTL));
        return allowed;
    }
    synchronized String open(long user) {
        if(!allowed(user,true)) throw new SecurityException("Нет доступа");
        long now=clock.getAsLong();sessions.entrySet().removeIf(e->e.getValue().expires<=now);
        if(sessions.size()>=1024) throw new SecurityException("Повторите позже");
        byte[] bytes=new byte[32];random.nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(token,new Session(user,now+SESSION_TTL));return token;
    }
    synchronized long require(String token) {
        long now=clock.getAsLong();
        Session s=token==null?null:sessions.get(token);
        if(s==null||s.expires<=now||!allowed(s.user,false)) {
            if(token!=null) sessions.remove(token);
            throw new SecurityException("Доступ истёк. Откройте панель через бота заново");
        }
        return s.user;
    }
    synchronized boolean rate(long user) {
        long now=clock.getAsLong();rates.entrySet().removeIf(e->now-e.getValue().start>=60_000);
        Window w=rates.getOrDefault(user,new Window(now,0));
        if(w.count>=requestsPerMinute||(!rates.containsKey(user)&&rates.size()>=4096)) return false;
        rates.put(user,new Window(w.start,w.count+1));return true;
    }
    synchronized void clear() {sessions.clear();checks.clear();rates.clear();}
}

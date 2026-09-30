package ru.vaulttracker;

import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;

/** The vg: callback catalogue and bounded in-process state for buttons carrying action parameters. */
final class TelegramActionRegistry {
    static final int MAX_PER_USER=2048,MAX_TOTAL=100_000;
    static final long TTL_MILLIS=Duration.ofDays(365).toMillis();
    static final String STALE_NOTICE="Кнопка больше недоступна. Откройте /menu.";

    enum Arguments {NONE,VALUE,PAGE,VALUE_AND_PAGE}
    enum Requirement {HANDLER,ADMIN,SUPER,ROOT}
    record Action(long user,String op,String value,int page,long createdAt) {}
    record Route(String id,String owner,Arguments arguments,Requirement requirement,boolean oneShot,ViewHandler handler) {}
    @FunctionalInterface interface ViewHandler {TelegramCommands.View handle(long user,Action action,String data) throws Exception;}
    @FunctionalInterface interface DirectHandler {TelegramCommands.View handle(long user,String data) throws Exception;}
    @FunctionalInterface interface NoticeHandler {TelegramCommands.Callback handle(long user,Action action,String data) throws Exception;}
    private record PrefixRoute(String prefix,Route route) {}
    private record DirectRoute(String prefix,String owner,boolean exact,Arguments arguments,DirectHandler handler) {}

    private final GuardService guard;
    private final LongSupplier clock;
    private final Map<String,Route> routes=new HashMap<>();
    private final Map<String,NoticeHandler> notices=new HashMap<>();
    private final List<PrefixRoute> prefixes=new ArrayList<>();
    private final List<DirectRoute> direct=new ArrayList<>();
    private final LinkedHashMap<String,Action> pending=new LinkedHashMap<>();
    private long lastSweep;

    TelegramActionRegistry(GuardService guard) {this(guard,System::currentTimeMillis);}
    TelegramActionRegistry(GuardService guard,LongSupplier clock) {this.guard=guard;this.clock=clock;}
    Scope scope(String owner) {return new Scope(owner);}
    final class Scope {
        private final String owner;
        private Scope(String owner) {this.owner=owner;}
        void on(String id,Arguments arguments,Requirement requirement,boolean oneShot,ViewHandler handler) {
            add(new Route(id,owner,arguments,requirement,oneShot,handler));
        }
        void on(String id,Arguments arguments,ViewHandler handler) {on(id,arguments,Requirement.HANDLER,false,handler);}
        void notice(String id,Arguments arguments,NoticeHandler handler) {
            add(new Route(id,owner,arguments,Requirement.HANDLER,false,null));
            notices.put(id,handler);
        }
        void prefix(String id,Arguments arguments,boolean oneShot,ViewHandler handler) {
            PrefixRoute route=new PrefixRoute(id,new Route(id,owner,arguments,Requirement.HANDLER,oneShot,handler));
            if(prefixes.stream().anyMatch(existing->existing.prefix().equals(id))) throw new IllegalArgumentException("Duplicate vg: prefix: "+id);
            prefixes.add(route);
        }
        void direct(String callback,DirectHandler handler) {addDirect(callback,true,handler);}
        void directPrefix(String callback,DirectHandler handler) {addDirect(callback,false,handler);}
        private void addDirect(String callback,boolean exact,DirectHandler handler) {
            if(direct.stream().anyMatch(existing->existing.prefix().equals(callback))) throw new IllegalArgumentException("Duplicate direct vg: route: "+callback);
            direct.add(new DirectRoute(callback,owner,exact,exact?Arguments.NONE:Arguments.VALUE,handler));
        }
    }
    private void add(Route route) {
        if(routes.putIfAbsent(route.id(),route)!=null) throw new IllegalArgumentException("Duplicate vg: action: "+route.id());
    }
    Route route(String id) {
        Route route=routes.get(id);
        if(route!=null) return route;
        for(PrefixRoute prefix:prefixes) if(id.startsWith(prefix.prefix())) return prefix.route();
        return null;
    }
    synchronized TelegramCommands.Button button(long user,String label,String id,String value,int page,int row) {
        if(route(id)==null) throw new IllegalArgumentException("Unregistered vg: action: "+id);
        long now=clock.getAsLong();sweep(now);
        String key="vg:"+UUID.randomUUID().toString().replace("-","");
        pending.put(key,new Action(user,id,value,page,now));
        long count=pending.values().stream().filter(action->action.user()==user).count();
        if(count>MAX_PER_USER) for(var iterator=pending.entrySet().iterator();iterator.hasNext() && count>MAX_PER_USER;) {
            if(iterator.next().getValue().user()==user) {iterator.remove();count--;}
        }
        while(pending.size()>MAX_TOTAL) pending.remove(pending.keySet().iterator().next());
        return new TelegramCommands.Button(label,key,row);
    }
    TelegramCommands.Callback dispatch(long user,String data) throws Exception {
        if(data!=null) for(DirectRoute route:direct) if(route.exact()?data.equals(route.prefix()):data.startsWith(route.prefix()))
            return TelegramGuardMenu.answer(route.handler().handle(user,data));
        Action action;
        Route route;
        synchronized(this) {
            long now=clock.getAsLong();sweep(now);
            action=pending.get(data);
            if(action==null || action.user()!=user) return stale();
            if(now-action.createdAt()>TTL_MILLIS) {pending.remove(data);return stale();}
            route=route(action.op());
            if(route==null) return stale();
            if(route.oneShot()) pending.remove(data);
        }
        require(route.requirement(),user);
        NoticeHandler notice=notices.get(action.op());
        if(notice!=null) return notice.handle(user,action,data);
        return TelegramGuardMenu.answer(route.handler().handle(user,action,data));
    }
    synchronized void consume(String data) {pending.remove(data);}
    synchronized void cancelOperations(long user) {pending.entrySet().removeIf(entry->entry.getValue().user()==user && entry.getValue().op().startsWith("do:"));}
    synchronized int pendingCount() {return pending.size();}
    private void require(Requirement requirement,long user) {
        switch(requirement) {case ADMIN -> guard.requireAdmin(user);case SUPER -> guard.requireSuper(user);case ROOT -> guard.requireRoot(user);case HANDLER -> {}}
    }
    private void sweep(long now) {
        if(now-lastSweep<60_000) return;
        pending.entrySet().removeIf(entry->now-entry.getValue().createdAt()>TTL_MILLIS);
        lastSweep=now;
    }
    private static TelegramCommands.Callback stale() {return new TelegramCommands.Callback(null,STALE_NOTICE,true);}
}

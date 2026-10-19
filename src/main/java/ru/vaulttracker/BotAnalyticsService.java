package ru.vaulttracker;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;

/** Analytics failure must never fail a user operation or block a Folia region thread. */
final class BotAnalyticsService implements AutoCloseable {
    record Event(String id,long at,String actor,String role,String feature,String action,String kind,String outcome,String mode,String query,String language,String source,int results,long duration) {}
    final BotAnalyticsRepository repository;
    final BotAnalyticsCatalog catalog;
    private final ArrayBlockingQueue<Event> queue=new ArrayBlockingQueue<>(4096);
    private final AtomicBoolean stopping=new AtomicBoolean();
    private final AtomicLong dropped=new AtomicLong(),failures=new AtomicLong();
    private final AtomicLong offlineAt=new AtomicLong(),pendingGap=new AtomicLong();
    private final LongSupplier clock;
    private final Thread writer;
    BotAnalyticsService(Path folder,BotAnalyticsConfig config,BotAnalyticsCatalog catalog) throws Exception {this(folder,config,catalog,()->System.currentTimeMillis()/1000);}
    BotAnalyticsService(Path folder,BotAnalyticsConfig config,BotAnalyticsCatalog catalog,LongSupplier clock) throws Exception {
        this.catalog=catalog;this.clock=clock;repository=new BotAnalyticsRepository(folder,config.zone(),clock.getAsLong());
        writer=Thread.ofVirtual().name("VaultTracker-bot-analytics").unstarted(this::loop);writer.start();
    }
    void capture(BotAnalytics.Context c,String feature,String action,String kind,String outcome,String mode,String query,int results,String language) {
        if(stopping.get())return;
        try {
            if(c.user()<=0||!Set.of("player","admin","super","root").contains(c.role()))return;
            if(!BotAnalyticsCatalog.FEATURES.containsKey(feature)||!action.matches("[A-Za-z0-9_.:-]{1,80}")||!Set.of("screen_view","button_click","flow_start","form_submit","operation_success","operation_failure","operation_denied","flow_complete").contains(kind))return;
            String safe="";
            if(feature.equals("search")&&mode.equals("item")&&query.matches("[A-Z0-9_]{1,64}")&&(org.bukkit.Material.getMaterial(query)!=null||ResourceGroups.resolve(query)!=null))safe=query;
            if(feature.equals("search")&&mode.equals("player")&&query.matches("[A-Za-z0-9_]{1,16}"))safe=query.toLowerCase(Locale.ROOT);
            String result=Set.of("success","empty","validation","denied","unavailable","cancelled","").contains(outcome)?outcome:"unavailable";
            String source=Set.of("manual","suggestion","pagination","command").contains(c.source())?c.source():"manual";
            catalog.event(action,feature,kind);
            Event event=new Event(repository.hash(c.correlation()+":"+feature+":"+action+":"+kind),clock.getAsLong(),repository.hash("actor:"+c.user()),c.role(),feature,action,kind,result,
                Set.of("item","player","").contains(mode)?mode:"",safe,Set.of("ru","en","unknown").contains(language)?language:"unknown",source,Math.max(0,results),Math.max(0,(System.nanoTime()-c.started())/1_000_000));
            if(!queue.offer(event))dropped.incrementAndGet();
        }catch(RuntimeException failure){failures.incrementAndGet();}
    }
    void committed(long user,String role,String stableKey,String feature,String action) {
        var c=new BotAnalytics.Context(this,user,stableKey,role,System.nanoTime(),"manual");
        capture(c,feature,action,"operation_success","success","","",0,"unknown");
        capture(c,feature,action,"flow_complete","success","","",0,"unknown");
    }
    void transport(boolean healthy){long now=clock.getAsLong();if(!healthy)offlineAt.compareAndSet(0,now);else{long start=offlineAt.getAndSet(0);if(start>0)pendingGap.addAndGet(Math.max(0,now-start));}}
    private void loop() {
        long heartbeat=0;
        try {
            while(!stopping.get()||!queue.isEmpty()) {
                var batch=new ArrayList<Event>();
                long lost=0,failed=0,gap=0;
                try {
                    var first=queue.poll(250,TimeUnit.MILLISECONDS);if(first!=null)batch.add(first);queue.drainTo(batch,99);
                    long now=clock.getAsLong();
                    if(!batch.isEmpty()||now-heartbeat>=60){lost=dropped.getAndSet(0);failed=failures.getAndSet(0);gap=pendingGap.getAndSet(0);long start=offlineAt.get();if(start>0&&offlineAt.compareAndSet(start,now))gap+=Math.max(0,now-start);repository.batch(batch,now,lost,failed,gap);heartbeat=now;}
                }catch(InterruptedException interrupted){if(!stopping.get())Thread.currentThread().interrupt();}
                catch(Exception unavailable){failures.addAndGet(failed+1);dropped.addAndGet(lost+batch.size());pendingGap.addAndGet(gap);}
            }
            transport(true);repository.batch(List.of(),clock.getAsLong(),dropped.getAndSet(0),failures.getAndSet(0),pendingGap.getAndSet(0));
        }catch(Exception unavailable){failures.incrementAndGet();}
        finally{try{repository.close();}catch(Exception ignored){}}
    }
    public void close(){if(!stopping.compareAndSet(false,true))return;try{writer.join(10000);if(writer.isAlive())writer.interrupt();}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}

package ru.vaulttracker;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;

/** Capture never blocks Telegram or gameplay on JDBC. One bounded writer, one separate snapshot scheduler. */
final class TelegramAnalyticsService implements AutoCloseable {
    final TelegramAnalyticsConfig config;
    final TelegramAnalyticsRepository repository;
    final TelegramAnalyticsQueries queries;
    private final LongSupplier clock;
    private final Map<Long,Long> collectionStarts;
    private final ArrayBlockingQueue<TelegramAnalyticsRepository.Write> queue=new ArrayBlockingQueue<>(2048);
    private final AtomicBoolean stopping=new AtomicBoolean();
    private final AtomicLong lostAt=new AtomicLong(),failures=new AtomicLong();
    private final ScheduledExecutorService snapshots=Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("VaultTracker-analytics-snapshot-",0).factory());
    private final Thread writer;
    private final Map<TelegramApi,Long> transports=new ConcurrentHashMap<>();
    TelegramAnalyticsService(java.nio.file.Path folder,TelegramAnalyticsConfig c) throws Exception {this(folder,c,()->System.currentTimeMillis()/1000);}
    TelegramAnalyticsService(java.nio.file.Path folder,TelegramAnalyticsConfig c,LongSupplier clock) throws Exception {
        config=c;this.clock=clock;repository=new TelegramAnalyticsRepository(folder,c.zone());queries=new TelegramAnalyticsQueries(repository,c);
        try{repository.batch(List.of(r->r.begin(c.chats(),clock.getAsLong())));collectionStarts=repository.collectionStarts();}catch(Exception failure){repository.close();throw failure;}
        writer=Thread.ofVirtual().name("VaultTracker-analytics-writer").unstarted(this::writeLoop);writer.start();
    }
    void attach(TelegramApi api){if(api!=null&&!stopping.get()){transports.put(api,0L);api.analytics(this);}}
    void startSnapshots(){snapshots.scheduleWithFixedDelay(this::snapshot,1,config.snapshotMinutes()*60L,TimeUnit.SECONDS);}
    private void snapshot() {
        for(long chat:config.chats()) {
            if(stopping.get())return;
            for(var api:transports.keySet())try {
                var value=api.analyticsAudience(chat);long at=clock.getAsLong();submit(r->r.snapshot(chat,at,value.count(),config.snapshotMinutes(),value.title(),value.type()));break;
            }catch(Exception unavailable){/* Another configured bot may have access; absent points stay absent. */}
        }
    }
    void outgoing(String method,JsonElement result,JsonObject request) {
        if(stopping.get()||result==null||result.isJsonNull())return;
        try {
            if(method.equals("copyMessage")&&request!=null&&result.isJsonObject()) {
                long chat=TelegramAnalyticsEvent.number(request,"chat_id"),now=clock.getAsLong();
                int message=(int)TelegramAnalyticsEvent.number(result.getAsJsonObject(),"message_id");
                if(config.chats().contains(chat)&&message>0&&now>=collectionStarts.getOrDefault(chat,Long.MAX_VALUE)) {
                    var event=new TelegramAnalyticsEvent(chat,message,(int)TelegramAnalyticsEvent.number(request,"message_thread_id"),now,0,null,"other",false,false,false,0,"","","");
                    submit(r->r.message(event));
                }
                return;
            }
            if(!method.startsWith("send")&&!method.equals("forwardMessage"))return;
            if(result.isJsonArray()) {for(var item:result.getAsJsonArray())outgoingMessage(item);}
            else outgoingMessage(result);
        } catch(RuntimeException malformed) {lostAt.compareAndSet(0,clock.getAsLong());}
    }
    private void outgoingMessage(JsonElement result) {
        if(!result.isJsonObject())return;
        var message=result.getAsJsonObject();
        if(!message.has("message_id")||!message.has("chat")||!message.has("date"))return;
        if(!config.chats().contains(TelegramAnalyticsEvent.number(message.getAsJsonObject("chat"),"id")))return;
        // Bot output contributes to activity, but never invents human authors from a forwarded sender.
        var copy=message.deepCopy();copy.remove("from");
        var update=new JsonObject();update.add("message",copy);capture(update);
    }
    void capture(JsonObject update) {
        if(stopping.get())return;
        try {
            var event=TelegramAnalyticsEvent.parse(update,config.chats(),repository::pseudonym);
            if(event!=null&&event.message()>0&&event.at()<=clock.getAsLong()) {
                var topic=TelegramAnalyticsEvent.topicName(update,config.chats());
                boolean count=event.at()>=collectionStarts.getOrDefault(event.chat(),Long.MAX_VALUE);
                if(count||topic!=null)submit(r->{if(topic!=null)r.topicName(topic);if(count)r.message(event);});
            }
            if(update.has("chat_member")) {
                var member=update.getAsJsonObject("chat_member");var chat=member.getAsJsonObject("chat");long id=TelegramAnalyticsEvent.number(chat,"id");
                if(!config.chats().contains(id)||TelegramAnalyticsEvent.text(chat,"type").equals("channel"))return;
                boolean old=TelegramAnalyticsEvent.member(member.getAsJsonObject("old_chat_member")),next=TelegramAnalyticsEvent.member(member.getAsJsonObject("new_chat_member"));
                if(old!=next){long at=TelegramAnalyticsEvent.number(member,"date");if(at<collectionStarts.getOrDefault(id,Long.MAX_VALUE)||at>clock.getAsLong())return;String key=repository.pseudonym(member.getAsJsonObject("new_chat_member").getAsJsonObject("user").get("id").getAsLong());submit(r->r.membership(id,at,key,next));}
            }
        }catch(RuntimeException malformed){lostAt.compareAndSet(0,clock.getAsLong());}
    }
    void transport(TelegramApi api,boolean healthy) {
        if(!transports.containsKey(api))return;long now=clock.getAsLong();
        if(!healthy){transports.computeIfPresent(api,(key,old)->old==0?now:old);long start=transports.getOrDefault(api,now);for(long chat:config.chats())submit(r->r.gap(chat,start,now+1,"telegram_transport"));}
        else {Long old=transports.replace(api,0L);if(old!=null&&old>0)for(long chat:config.chats())submit(r->r.gap(chat,old,now,"telegram_transport"));}
    }
    void submit(TelegramAnalyticsRepository.Write write){if(!stopping.get()&&!queue.offer(write))lostAt.compareAndSet(0,clock.getAsLong());}
    private void writeLoop() {
        long heartbeat=0;
        while(!stopping.get()||!queue.isEmpty()) {
            List<TelegramAnalyticsRepository.Write> batch=new ArrayList<>();
            try {
                var item=queue.poll(250,TimeUnit.MILLISECONDS);if(item!=null)batch.add(item);queue.drainTo(batch,99);
                long now=clock.getAsLong(),lost=lostAt.getAndSet(0);
                if(lost>0)for(long chat:config.chats())batch.add(r->r.gap(chat,lost,now,"queue_or_storage_failure"));
                if(now-heartbeat>=60){batch.add(r->r.heartbeat(config.chats(),now));for(long start:transports.values())if(start>0)for(long chat:config.chats())batch.add(r->r.gap(chat,start,now+1,"telegram_transport"));heartbeat=now;}
                if(!batch.isEmpty())repository.batch(batch);
            }catch(InterruptedException stop){if(!stopping.get())Thread.currentThread().interrupt();}
            catch(Exception failure){failures.incrementAndGet();lostAt.compareAndSet(0,clock.getAsLong());}
        }
        try{long now=clock.getAsLong();List<TelegramAnalyticsRepository.Write> last=new ArrayList<>();long lost=lostAt.get();if(lost>0)for(long chat:config.chats())last.add(r->r.gap(chat,lost,now,"shutdown_failure"));last.add(r->r.heartbeat(config.chats(),now));repository.batch(last);}catch(Exception failure){failures.incrementAndGet();}
        finally{try{repository.close();}catch(Exception failure){failures.incrementAndGet();}}
    }
    long failures(){return failures.get();}
    boolean awaitIdle(long millis) throws Exception {var latch=new CountDownLatch(1);if(stopping.get()||!queue.offer(r->latch.countDown(),millis,TimeUnit.MILLISECONDS)||!latch.await(millis,TimeUnit.MILLISECONDS))return false;repository.batch(List.of());return true;}
    public void close() {
        if(!stopping.compareAndSet(false,true))return;
        transports.keySet().forEach(api->api.analytics(null));snapshots.shutdownNow();
        try{writer.join(10_000);if(writer.isAlive()){writer.interrupt();writer.join(2000);}}catch(Exception failure){failures.incrementAndGet();}
    }
}

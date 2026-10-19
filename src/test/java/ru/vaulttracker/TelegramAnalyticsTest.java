package ru.vaulttracker;

import com.google.gson.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TelegramAnalyticsTest {
    @TempDir Path folder;
    final ZoneId zone=ZoneId.of("Asia/Novosibirsk");
    final long chat=-1001,other=-1002;
    TelegramAnalyticsConfig config(){return new TelegramAnalyticsConfig(true,Set.of(chat,other),zone,chat,"https://admin.example/telegram-stats/",60);}
    long at(String day){return LocalDate.parse(day).atTime(12,0).atZone(zone).toEpochSecond();}
    TelegramAnalyticsEvent event(TelegramAnalyticsRepository repo,long chat,int id,int topic,String day,long user,String type){return new TelegramAnalyticsEvent(chat,id,topic,at(day),0,repo.pseudonym(user),type,false,false,type.equals("photo"),15,"Сообщество","supergroup","");}
    void begin(TelegramAnalyticsRepository repo) throws Exception{repo.batch(List.of(r->r.begin(config().chats(),at("2020-01-01")),r->r.heartbeat(config().chats(),at("2026-10-17"))));}
    TelegramAnalyticsQuery query(Map<String,String> p){return TelegramAnalyticsQuery.parse(p,config(),LocalDate.parse("2026-10-17"),LocalDate.parse("2020-01-01"));}
    @SuppressWarnings("unchecked") Map<String,Object> part(Map<String,Object> report,String key){return (Map<String,Object>)report.get(key);}
    @SuppressWarnings("unchecked") List<Map<String,Object>> list(Map<String,Object> report,String key){return (List<Map<String,Object>>)report.get(key);}
    Map<String,Object> report(TelegramAnalyticsRepository repo,Map<String,String> p)throws Exception{return new TelegramAnalyticsQueries(repo,config()).report(query(p),at("2026-10-17"));}
    long n(Map<String,Object> row,String key){return TelegramAnalyticsQueries.n(row,key);}
    @Test void configIsExplicitAllowlistOffByDefaultAndDatesAreNotLimitedToThirtyDays()throws Exception{
        Files.writeString(folder.resolve("telegram.yml"),"enabled: false\n");assertFalse(TelegramAnalyticsConfig.load(folder).enabled());assertTrue(TelegramAnalyticsConfig.load(folder).chats().isEmpty());
        assertThrows(IllegalArgumentException.class,()->new TelegramAnalyticsConfig(true,Set.of(),zone,chat,"https://admin.example/telegram-stats/",60).validate());
        for(String period:List.of("1","yesterday","7","30","90","180","365","all"))assertNotNull(query(Map.of("period",period)));
        assertEquals("month",query(Map.of("period","custom","from","2021-01-01","to","2026-01-01")).grain());
        assertThrows(IllegalArgumentException.class,()->query(Map.of("period","custom","from","2026-10-18","to","2026-10-20")));
        assertThrows(IllegalArgumentException.class,()->query(Map.of("chat","-888")));
        assertThrows(IllegalArgumentException.class,()->query(Map.of("topic","1")));
    }
    @Test void captureMetadataPrivacyTypesAndStableIdentity()throws Exception{
        try(var repo=new TelegramAnalyticsRepository(folder,zone)){
            String raw="{\"update_id\":1,\"message\":{\"message_id\":7,\"message_thread_id\":42,\"date\":1800000000,\"chat\":{\"id\":-1001,\"type\":\"supergroup\",\"title\":\"Community\"},\"from\":{\"id\":12345,\"username\":\"NeverStoreMe\"},\"photo\":[{}],\"caption\":\"SECRET BODY\",\"reply_to_message\":{},\"forward_origin\":{}}}";
            var update=JsonParser.parseString(raw).getAsJsonObject();var event=TelegramAnalyticsEvent.parse(update,config().chats(),repo::pseudonym);
            assertEquals("photo",event.type());assertTrue(event.reply());assertTrue(event.forwarded());assertEquals(42,event.topic());assertEquals(11,event.textLength());assertEquals(64,event.author().length());assertFalse(event.toString().contains("SECRET BODY"));assertFalse(event.toString().contains("12345"));assertFalse(event.toString().contains("NeverStoreMe"));
            update.getAsJsonObject("message").getAsJsonObject("chat").addProperty("type","private");assertNull(TelegramAnalyticsEvent.parse(update,config().chats(),repo::pseudonym));
            assertNull(TelegramAnalyticsEvent.parse(JsonParser.parseString("{\"callback_query\":{}}").getAsJsonObject(),config().chats(),repo::pseudonym));
            for(String kind:List.of("video","voice","video_note","poll","sticker","document")){var m=new JsonObject();m.addProperty("date",1800000000);m.addProperty("message_id",1);m.add("chat",JsonParser.parseString("{\"id\":-1001,\"type\":\"channel\"}"));m.add(kind,new JsonObject());var u=new JsonObject();u.add("channel_post",m);var parsed=TelegramAnalyticsEvent.parse(u,config().chats(),repo::pseudonym);assertEquals(kind,parsed.type());assertNull(parsed.author());}
            String key=repo.pseudonym(12345);repo.batch(List.of(r->r.begin(config().chats(),at("2020-01-01"))));Files.writeString(folder.resolve("fixture-key"),key);
        }
        try(var reopened=new TelegramAnalyticsRepository(folder,zone)){assertEquals(Files.readString(folder.resolve("fixture-key")),reopened.pseudonym(12345));}
        assertThrows(Exception.class,()->new TelegramAnalyticsRepository(folder,ZoneId.of("UTC")));
    }
    @Test void duplicatesEditsAndOutOfOrderUpdatesDoNotInflateCountsAndPersistenceSurvivesRestart()throws Exception{
        try(var repo=new TelegramAnalyticsRepository(folder,zone)){
            begin(repo);var message=event(repo,chat,1,10,"2026-09-01",7,"text");
            var edit=new TelegramAnalyticsEvent(chat,1,20,message.at(),message.at()+30,message.author(),"photo",true,true,true,4,"Сообщество","supergroup","");
            repo.batch(List.of(r->r.message(message),r->r.message(message),r->r.message(edit),r->r.message(message)));
            var report=report(repo,Map.of("period","all"));assertEquals(1,n(part(report,"summary"),"messages"));assertEquals(1,n(part(report,"summary"),"authors"));assertEquals(1,n(part(report,"summary"),"replies"));assertEquals(1,n(part(report,"diagnostics"),"metadatarows"));
            assertEquals(1,list(part(report,"topics"),"rows").size());assertEquals(20,n(list(part(report,"topics"),"rows").getFirst(),"topic"));
        }
        try(var reopened=new TelegramAnalyticsRepository(folder,zone)){assertEquals(1,n(part(report(reopened,Map.of("period","all")),"summary"),"messages"));}
    }
    @Test void combinedUniqueUsersDoNotDoubleCountAndTopicsKeepChatContext()throws Exception{
        try(var repo=new TelegramAnalyticsRepository(folder,zone)){
            begin(repo);repo.batch(List.of(r->r.message(event(repo,chat,1,10,"2026-09-01",7,"text")),r->r.message(event(repo,other,1,10,"2026-09-02",7,"photo")),r->r.message(event(repo,chat,2,10,"2026-09-02",8,"text"))));
            var report=report(repo,Map.of("period","all"));assertEquals(3,n(part(report,"summary"),"messages"));assertEquals(2,n(part(report,"summary"),"authors"));assertEquals(2,list(part(report,"topics"),"rows").size());assertEquals(1,n(part(report,"summary"),"multiDayAuthors"));
            var scoped=report(repo,Map.of("period","all","chat",Long.toString(other),"topic","10"));assertEquals(1,n(part(scoped,"summary"),"messages"));
        }
    }
    @Test void timezoneBoundariesLongPeriodsHeatmapAndMonthlyTimelineUseAggregates()throws Exception{
        try(var repo=new TelegramAnalyticsRepository(folder,zone)){
            begin(repo);var e=event(repo,chat,1,1,"2021-01-01",1,"text");long boundary=Instant.parse("2021-01-01T18:30:00Z").getEpochSecond();
            repo.batch(List.of(r->r.message(new TelegramAnalyticsEvent(e.chat(),e.message(),e.topic(),boundary,0,e.author(),e.type(),false,false,false,1,"","supergroup","")),r->r.message(event(repo,chat,2,1,"2025-01-01",1,"text"))));
            var only=report(repo,Map.of("period","custom","from","2021-01-02","to","2021-01-02"));assertEquals(1,n(part(only,"summary"),"messages"));assertEquals(1,n(list(only,"heatmap").getFirst(),"hour"));assertEquals(5,n(list(only,"heatmap").getFirst(),"dow"));
            var all=report(repo,Map.of("period","all"));assertEquals("month",all.get("grain"));assertEquals(2,list(all,"timeline").size());
            for(String range:List.of("7","30","90","180","365"))assertNotNull(report(repo,Map.of("period",range)));
            try(var db=repo.read()) {String plan=TelegramAnalyticsQueries.rows(db,"EXPLAIN SELECT SUM(n) FROM ta_daily WHERE chat=-1001 AND day>='2021-01-01' AND day<'2026-01-01'").getFirst().values().iterator().next().toString();assertTrue(plan.contains("TA_DAILY"));assertFalse(plan.contains("TA_MESSAGES"));}
        }
    }
    @Test void newReturningAndCompletedSevenDayCohortsExcludeImmatureData()throws Exception{
        try(var repo=new TelegramAnalyticsRepository(folder,zone)){
            begin(repo);repo.batch(List.of(r->r.message(event(repo,chat,1,1,"2026-09-01",7,"text")),r->r.message(event(repo,chat,2,1,"2026-09-05",7,"text")),r->r.message(event(repo,chat,3,1,"2026-09-07",8,"text")),r->r.message(event(repo,chat,4,1,"2026-10-16",9,"text"))));
            var result=report(repo,Map.of("period","custom","from","2026-09-01","to","2026-10-17"));assertEquals(2,n(part(result,"retention"),"eligible"));assertEquals(1,n(part(result,"retention"),"returned"));assertEquals(1,n(part(result,"retention"),"incomplete"));
            var later=report(repo,Map.of("period","custom","from","2026-09-04","to","2026-09-08"));assertEquals(1,n(part(later,"summary"),"newAuthors"));assertEquals(1,n(part(later,"summary"),"returningAuthors"));
        }
    }
    @Test void observedJoinsAndLeavesIdempotentAndSnapshotsAreNotFakeJoins()throws Exception{
        try(var repo=new TelegramAnalyticsRepository(folder,zone)){
            begin(repo);repo.batch(List.of(r->r.membership(chat,at("2026-09-01"),repo.pseudonym(7),true),r->r.membership(chat,at("2026-09-01"),repo.pseudonym(7),true),r->r.membership(chat,at("2026-09-02"),repo.pseudonym(7),false),r->r.snapshot(chat,at("2026-09-01"),100,60,"Группа","supergroup"),r->r.snapshot(chat,at("2026-09-02"),120,60,"Группа","supergroup"),r->r.snapshot(other,at("2026-09-01"),500,60,"Канал","channel"),r->r.snapshot(other,at("2026-09-02"),530,60,"Канал","channel")));
            var group=part(report(repo,Map.of("chat",Long.toString(chat),"period","custom","from","2026-09-01","to","2026-09-02")),"audience");assertEquals(20,n(group,"delta"));assertEquals(1,n(group,"observedJoins"));assertEquals(1,n(group,"observedLeaves"));assertNull(group.get("current"));
            var channel=part(report(repo,Map.of("chat",Long.toString(other),"period","custom","from","2026-09-01","to","2026-09-02")),"audience");assertEquals(30,n(channel,"delta"));assertNull(channel.get("observedJoins"));
        }
    }
    @Test void comparisonsRequireRealCoverageAndZerosDoNotInventHistory()throws Exception{
        try(var repo=new TelegramAnalyticsRepository(folder,zone)){
            repo.batch(List.of(r->r.begin(config().chats(),at("2026-09-01")),r->r.heartbeat(config().chats(),at("2026-10-17")),r->r.message(event(repo,chat,1,1,"2026-09-10",7,"text")),r->r.message(event(repo,chat,2,1,"2026-09-11",7,"text"))));
            var absent=report(repo,Map.of("period","custom","from","2026-08-01","to","2026-08-10"));assertEquals(false,absent.get("hasCoverage"));assertNull(absent.get("previous"));
            var comparable=report(repo,Map.of("period","custom","from","2026-09-11","to","2026-09-11"));assertNotNull(comparable.get("previous"));assertEquals(0,((java.math.BigDecimal)part(comparable,"changes").get("messages")).signum());
            repo.batch(List.of(r->r.gap(chat,at("2026-09-10"),at("2026-09-11"),"test_gap")));
            assertNull(report(repo,Map.of("period","custom","from","2026-09-11","to","2026-09-11")).get("previous"));
        }
    }
    @Test void serviceCaptureIsBoundedAsyncFlushesAndSurvivesMalformedUpdates()throws Exception{
        long now=at("2026-10-17");
        try(var service=new TelegramAnalyticsService(folder,config(),()->now)){
            var update=JsonParser.parseString("{\"update_id\":1,\"message\":{\"message_id\":1,\"date\":"+now+",\"chat\":{\"id\":-1001,\"type\":\"supergroup\"},\"from\":{\"id\":7},\"text\":\"secret\"}}").getAsJsonObject();service.capture(update);service.capture(update);
            service.capture(JsonParser.parseString("{\"message\":{}}").getAsJsonObject());assertTrue(service.awaitIdle(5000));
        }
        try(var repo=new TelegramAnalyticsRepository(folder,zone)){try(var db=repo.read()){assertEquals(1,TelegramAnalyticsQueries.n(TelegramAnalyticsQueries.one(db,"SELECT COUNT(*) n FROM ta_messages"),"n"));}}
    }
    @Test void topicFirstMessageDoesNotMakeAnExistingChatAuthorNewAgain()throws Exception{
        try(var repo=new TelegramAnalyticsRepository(folder,zone)){
            begin(repo);repo.batch(List.of(r->r.message(event(repo,chat,1,1,"2026-08-01",7,"text")),r->r.message(event(repo,chat,2,2,"2026-09-01",7,"text"))));
            var result=report(repo,Map.of("chat",Long.toString(chat),"topic","2","period","custom","from","2026-09-01","to","2026-09-02"));
            assertEquals(0,n(part(result,"summary"),"newAuthors"));assertEquals(1,n(part(result,"summary"),"returningAuthors"));
        }
    }
    @Test void allTimeDiagnosticsUseH2DirectCountsAndTimestampIndex()throws Exception{
        try(var repo=new TelegramAnalyticsRepository(folder,zone);var db=repo.read()){
            String count=TelegramAnalyticsQueries.rows(db,"EXPLAIN SELECT COUNT(*) FROM ta_messages").getFirst().values().iterator().next().toString();assertTrue(count.toLowerCase(Locale.ROOT).contains("direct lookup"),count);
            String times=TelegramAnalyticsQueries.rows(db,"EXPLAIN SELECT MIN(at),MAX(at) FROM ta_messages").getFirst().values().iterator().next().toString();assertTrue(times.toLowerCase(Locale.ROOT).contains("direct lookup"),times);
        }
    }
    @Test void failedBatchRecoversAndQueueOverflowIsMarkedWithoutBlockingCapture()throws Exception{
        long now=at("2026-10-17");
        try(var service=new TelegramAnalyticsService(folder,config(),()->now)){
            var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
            service.submit(r->{entered.countDown();release.await(3,java.util.concurrent.TimeUnit.SECONDS);});assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
            long start=System.nanoTime();for(int i=0;i<3000;i++)service.submit(r->{});assertTrue(System.nanoTime()-start<1_000_000_000L);release.countDown();
            assertTrue(service.awaitIdle(5000));
            service.submit(r->{throw new java.sql.SQLException("local fixture failure");});
            long deadline=System.nanoTime()+3_000_000_000L;while(service.failures()==0&&System.nanoTime()<deadline)Thread.sleep(10);assertTrue(service.failures()>0);
            assertTrue(service.awaitIdle(5000));
            try(var db=service.repository.read()){assertTrue(n(TelegramAnalyticsQueries.one(db,"SELECT COUNT(*) n FROM ta_gaps"),"n")>0);}
        }
    }
    @Test void namesAreReadFromTheMatchingTopicRootAndNotFromUserTextOrOtherChats() {
        var update=JsonParser.parseString("{\"message\":{\"message_id\":100,\"message_thread_id\":42,\"chat\":{\"id\":-1001,\"type\":\"supergroup\"},\"text\":\"Не название\",\"reply_to_message\":{\"message_id\":42,\"chat\":{\"id\":-1001},\"forum_topic_created\":{\"name\":\"Торговля\"}}}}").getAsJsonObject();
        var name=TelegramAnalyticsEvent.topicName(update,config().chats());
        assertEquals("Торговля",name.title());assertEquals(42,name.topic());assertFalse(name.renamed());
        var root=update.getAsJsonObject("message").getAsJsonObject("reply_to_message");
        root.addProperty("message_id",43);assertNull(TelegramAnalyticsEvent.topicName(update,config().chats()));
        root.addProperty("message_id",42);root.getAsJsonObject("chat").addProperty("id",other);assertNull(TelegramAnalyticsEvent.topicName(update,config().chats()));
        update.getAsJsonObject("message").remove("reply_to_message");assertNull(TelegramAnalyticsEvent.topicName(update,config().chats()));
    }
    @Test void historicalTopicNamesAreLearnedWithoutInventingHistoricalStatisticsAndRenamesPersist()throws Exception{
        long now=at("2026-10-17");
        var created=JsonParser.parseString("{\"message\":{\"message_id\":42,\"message_thread_id\":42,\"date\":"+(now-86400)+",\"chat\":{\"id\":-1001,\"type\":\"supergroup\"},\"forum_topic_created\":{\"name\":\"Старое название\"}}}").getAsJsonObject();
        try(var service=new TelegramAnalyticsService(folder,config(),()->now)) {
            service.capture(created);assertTrue(service.awaitIdle(5000));
            try(var db=service.repository.read()){assertEquals("Старое название",TelegramAnalyticsQueries.one(db,"SELECT title FROM ta_topics WHERE chat=? AND topic=?",chat,42).get("title"));}
            var report=service.queries.report(query(Map.of("period","all")),now);
            assertEquals(0,n(part(report,"summary"),"messages"));assertEquals(0,n(part(report,"summary"),"authors"));
            var renamed=created.deepCopy();var message=renamed.getAsJsonObject("message");message.remove("forum_topic_created");message.add("forum_topic_edited",JsonParser.parseString("{\"name\":\"Жалобы игроков\"}"));
            service.capture(renamed);service.capture(created);assertTrue(service.awaitIdle(5000));
        }
        try(var service=new TelegramAnalyticsService(folder,config(),()->now)) {
            service.capture(created);assertTrue(service.awaitIdle(5000));
            try(var db=service.repository.read()){assertEquals("Жалобы игроков",TelegramAnalyticsQueries.one(db,"SELECT title FROM ta_topics WHERE chat=? AND topic=?",chat,42).get("title"));}
        }
    }
    @Test void repeatedMessagesCanFillMissingTopicNamesWithoutInflatingCounts()throws Exception{
        long now=at("2026-10-17");
        try(var service=new TelegramAnalyticsService(folder,config(),()->now)) {
            var update=JsonParser.parseString("{\"message\":{\"message_id\":100,\"message_thread_id\":42,\"date\":"+now+",\"chat\":{\"id\":-1001,\"type\":\"supergroup\"},\"text\":\"secret\"}}").getAsJsonObject();
            service.capture(update);
            update.getAsJsonObject("message").add("reply_to_message",JsonParser.parseString("{\"message_id\":42,\"chat\":{\"id\":-1001},\"forum_topic_created\":{\"name\":\"Чат\"}}"));
            service.capture(update);service.capture(update);assertTrue(service.awaitIdle(5000));
            var report=service.queries.report(query(Map.of("period","all")),now);
            assertEquals(1,n(part(report,"summary"),"messages"));
            assertEquals("Чат",list(part(report,"topics"),"rows").getFirst().get("title"));
            assertEquals("Чат",list(service.queries.options(),"topics").getFirst().get("title"));
        }
    }
    @Test void preCollectionBacklogAndEditsDoNotInventHistoricalMessagesOrAuthors()throws Exception{
        long now=at("2026-10-17");
        try(var service=new TelegramAnalyticsService(folder,config(),()->now)){
            var old=JsonParser.parseString("{\"update_id\":1,\"edited_message\":{\"message_id\":1,\"date\":"+(now-86400)+",\"edit_date\":"+now+",\"chat\":{\"id\":-1001,\"type\":\"supergroup\"},\"from\":{\"id\":7},\"text\":\"old fixture\"}}").getAsJsonObject();
            service.capture(old);assertTrue(service.awaitIdle(5000));var result=service.queries.report(query(Map.of("period","all")),now);
            assertEquals(0,n(part(result,"summary"),"messages"));assertEquals(0,n(part(result,"summary"),"authors"));assertTrue(list(result,"heatmap").isEmpty());
        }
    }
}

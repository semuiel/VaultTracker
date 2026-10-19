package ru.vaulttracker;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BotAnalyticsTest {
    @TempDir Path folder;
    final ZoneId zone=ZoneId.of("Asia/Novosibirsk");
    long epoch(String day){return LocalDate.parse(day).atStartOfDay(zone).toEpochSecond();}
    BotAnalyticsService.Event event(String id,long at,String user,String role,String feature,String kind){return new BotAnalyticsService.Event(id,at,user,role,feature,"test.action",kind,"success","","","unknown","manual",0,12);}
    @Test void dedupRestartSessionsThirtyMinutesAndIndexedReports() throws Exception {
        long start=epoch("2026-08-01"),now=epoch("2026-10-18")+3600;String key;
        try(var repo=new BotAnalyticsRepository(folder,zone,start)) {
            key=repo.hash("actor:42");
            var e=event("a",start+60,key,"player","search","button_click");
            repo.batch(List.of(e,e,event("b",start+120,key,"player","search","screen_view"),event("c",start+1920,key,"player","premium","button_click")),start+2000,0,0);
            var queries=new BotAnalyticsQueries(repo,new BotAnalyticsCatalog());
            var q=BotAnalyticsQuery.parse(Map.of("period","all"),zone,now,start);var report=queries.report(q,now);
            var summary=(Map<?,?>)report.get("current");assertEquals(2L,((Number)summary.get("actions")).longValue());assertEquals(2L,((Number)summary.get("sessions")).longValue());assertEquals(1L,((Number)summary.get("users")).longValue());
            try(var db=repo.read();var s=db.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM ba_events")){assertTrue(r.next());assertEquals(3,r.getInt(1));}
        }
        try(var repo=new BotAnalyticsRepository(folder,zone,now)) {
            assertEquals(key,repo.hash("actor:42"));assertEquals(start,repo.started);
            repo.batch(List.of(event("a",now,key,"player","search","button_click")),now,0,0);
            try(var db=repo.read();var s=db.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM ba_events")){r.next();assertEquals(3,r.getInt(1));}
        }
    }
    @Test void rolesFeaturesFiltersRetentionCompleteCohortsAndLongPeriods() throws Exception {
        long start=epoch("2026-08-01"),now=epoch("2026-10-18")+3600;
        try(var repo=new BotAnalyticsRepository(folder,zone,start)) {
            var batch=new ArrayList<BotAnalyticsService.Event>();int id=0;
            for(int day:List.of(0,1,7,30))batch.add(event("e"+id++,start+day*86400+60,"player","player","search","button_click"));
            batch.add(event("root",start+60,"root","root","root","button_click"));
            batch.add(event("incomplete",epoch("2026-10-18"),"late","player","search","button_click"));repo.batch(batch,now,0,0);
            var queries=new BotAnalyticsQueries(repo,new BotAnalyticsCatalog());
            var q=BotAnalyticsQuery.parse(Map.of("period","custom","from","2026-08-01","to","2026-10-18","role","player","feature","search"),zone,now,start);
            var report=queries.report(q,now);var summary=(Map<?,?>)report.get("current");assertEquals(2L,((Number)summary.get("users")).longValue());
            for(var r:(List<Map<String,Object>>)report.get("retention")){assertEquals(1L,((Number)r.get("eligible")).longValue());assertEquals(1L,((Number)r.get("returned")).longValue());}
            assertThrows(IllegalArgumentException.class,()->BotAnalyticsQuery.parse(Map.of("role","hacker"),zone,now,start));
            assertThrows(IllegalArgumentException.class,()->BotAnalyticsQuery.parse(Map.of("period","custom","from","2027-01-01","to","2028-01-01"),zone,now,start));
        }
    }
    @Test void boundedAsyncCapturePrivacyAndDuplicateUpdates() throws Exception {
        var time=new AtomicLong(epoch("2026-10-18"));var catalog=new BotAnalyticsCatalog();
        var service=new BotAnalyticsService(folder,new BotAnalyticsConfig(true,-1001,"https://fixture.example/bot-analytics/",zone),catalog,time::get);
        var context=new BotAnalytics.Context(service,42,"update:1","player",System.nanoTime(),"manual");
        service.capture(context,"search","search.result","form_submit","success","item","DIAMOND",2,"ru");
        service.capture(context,"search","search.result","form_submit","success","item","DIAMOND",2,"ru");
        service.capture(new BotAnalytics.Context(service,42,"update:2","player",System.nanoTime(),"manual"),"search","search.result","form_submit","empty","player","private message or token",0,"en");
        service.close();
        try(var repo=new BotAnalyticsRepository(folder,zone,time.get())) {
            try(var db=repo.read();var s=db.createStatement();var r=s.executeQuery("SELECT actor,query FROM ba_events ORDER BY at,query")){
                int count=0;while(r.next()){count++;assertEquals(repo.hash("actor:42"),r.getString(1));assertTrue(Set.of("","DIAMOND").contains(r.getString(2)));}assertEquals(2,count);
            }
        }
    }
    @Test void viewsAreNotPurchasesAndRoleDenialsAreCaptured()throws Exception {
        var config=new BotAnalyticsConfig(true,-1001,"https://fixture.example/bot-analytics/",zone);
        try(var guard=new GuardService(folder.resolve("guard"),new GuardConfig(true,120000,500),java.util.logging.Logger.getAnonymousLogger());var service=new BotAnalyticsService(folder,config,new BotAnalyticsCatalog())) {
            var registry=new TelegramActionRegistry(guard);registry.scope("CabinetMenu").on("premiumCardTest",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.ROOT,false,(u,a,d)->new TelegramCommands.View("Only a view"));
            String allowed=registry.button(500,"Open","premiumCardTest","",0,0).data();
            try(var context=BotAnalytics.begin(service,guard,new TelegramApi.Incoming(1,500,0,500,1,"",null,null,true))){registry.dispatch(500,allowed);}
            String denied=registry.button(42,"Open","premiumCardTest","",0,0).data();
            try(var context=BotAnalytics.begin(service,guard,new TelegramApi.Incoming(2,42,0,42,2,"",null,null,true))){assertThrows(SecurityException.class,()->registry.dispatch(42,denied));}
            service.close();
            try(var repository=new BotAnalyticsRepository(folder,zone,System.currentTimeMillis()/1000);var db=repository.read();var s=db.createStatement()) {
                try(var r=s.executeQuery("SELECT COUNT(*) FROM ba_events WHERE kind='operation_success'")){r.next();assertEquals(0,r.getInt(1));}
                try(var r=s.executeQuery("SELECT role,kind FROM ba_events WHERE kind='operation_denied'")){assertTrue(r.next());assertEquals("player",r.getString(1));}
            }
        }
    }
    @Test void funnelRequiresAStartAndKeepsIndependentActualRepeats()throws Exception {
        long start=epoch("2026-10-01"),now=epoch("2026-10-18");
        try(var repo=new BotAnalyticsRepository(folder,zone,start)) {
            repo.batch(List.of(event("1",start+1,"u","player","premium","flow_start"),event("2",start+2,"u","player","premium","form_submit"),event("3",start+3,"u","player","premium","flow_complete"),event("4",start+2000,"u","player","premium","flow_start"),event("5",start+2001,"u","player","premium","flow_complete"),event("orphan",start+1,"other","player","link","flow_complete")),now,0,0);
            var report=new BotAnalyticsQueries(repo,new BotAnalyticsCatalog()).report(new BotAnalyticsQuery(LocalDate.of(2026,10,1),LocalDate.of(2026,10,19),"all","all"),now);
            var flows=(List<Map<String,Object>>)report.get("flowConversions");assertEquals(1,flows.size());assertEquals(2L,((Number)flows.getFirst().get("completed")).longValue());
        }
    }
    @Test void aggregatesHandleLargerHistoryAndHealthCountsDoNotOverwriteSameSecond()throws Exception {
        long start=epoch("2024-01-01"),now=epoch("2026-10-18");
        try(var repo=new BotAnalyticsRepository(folder,zone,start)) {
            var events=new ArrayList<BotAnalyticsService.Event>();for(int i=0;i<1000;i++)events.add(event("load"+i,start+i*86400L,"u"+(i%10),i%10==0?"root":"player","search","button_click"));
            repo.batch(events,now,2,1,30);repo.batch(List.of(),now,3,2,20);
            var q=BotAnalyticsQuery.parse(Map.of("period","custom","from","2024-01-01","to","2026-10-18"),zone,now,start);var report=new BotAnalyticsQueries(repo,new BotAnalyticsCatalog()).report(q,now);
            var current=(Map<?,?>)report.get("current");assertEquals(1000L,((Number)current.get("actions")).longValue());
            var health=(Map<?,?>)report.get("health");assertEquals(5L,((Number)health.get("dropped")).longValue());assertEquals(50L,((Number)health.get("gapseconds")).longValue());
            var missing=new BotAnalyticsQueries(repo,new BotAnalyticsCatalog()).report(new BotAnalyticsQuery(LocalDate.of(2020,1,1),LocalDate.of(2021,1,1),"all","all"),now);assertEquals(false,missing.get("coverageAvailable"));
        }
    }
}

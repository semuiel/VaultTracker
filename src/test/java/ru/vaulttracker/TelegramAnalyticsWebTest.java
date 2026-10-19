package ru.vaulttracker;

import com.google.gson.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramAnalyticsWebTest {
    @TempDir Path folder;
    final AtomicLong clock=new AtomicLong(1_800_000_000_000L);
    final String botToken="123456:fixture-only-local";
    final HttpClient client=HttpClient.newHttpClient();
    final Set<Long> roots=new HashSet<>(Set.of(42L)),admins=new HashSet<>(Set.of(42L,43L));
    TelegramWebRuntime runtime;TelegramAnalyticsService service;TelegramAnalyticsWeb stats;CoreProtectWebServer cp;
    TelegramAnalyticsConfig config(){return new TelegramAnalyticsConfig(true,Set.of(-1001L),ZoneId.of("UTC"),-1001,"https://admin.example/telegram-stats/",60);}
    @BeforeEach void start()throws Exception{
        service=new TelegramAnalyticsService(folder,config(),()->clock.get()/1000);runtime=new TelegramWebRuntime("127.0.0.1",0);
        stats=new TelegramAnalyticsWeb(runtime,config(),service,botToken,roots::contains,(c,u)->admins.contains(u),clock::get);
        cp=new CoreProtectWebServer(CoreProtectWebTest.config(1,2,100),botToken,new CoreProtectLookupSource(){
            public Capabilities capabilities(){return new Capabilities(List.of(),List.of(),false);}
            public Page lookup(CoreProtectQuery query){return new Page(List.of(),false);}
        },admins::contains,(c,u)->admins.contains(u),(u,a,t)->{},clock::get,runtime);runtime.start();
    }
    @AfterEach void close(){if(stats!=null)stats.close();if(cp!=null)cp.close();if(runtime!=null)runtime.close();if(service!=null)service.close();}
    HttpResponse<String> get(String path,String auth)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+runtime.port()+path)).timeout(Duration.ofSeconds(10));if(auth!=null)b.header("Authorization",auth);return client.send(b.GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    String session(String route,long user)throws Exception{var r=get(route+"api/session","Telegram "+CoreProtectWebTest.signed(user,clock.get()/1000,botToken));assertEquals(200,r.statusCode(),r.body());return "Bearer "+JsonParser.parseString(r.body()).getAsJsonObject().get("token").getAsString();}
    @Test void independentPoliciesOnTheSameListenerAndAssetsDoNotConflict()throws Exception{
        assertEquals(cp.port(),runtime.port());assertEquals(200,get("/coreprotect/",null).statusCode());assertEquals(200,get("/telegram-stats/",null).statusCode());
        assertNotEquals(get("/coreprotect/app.js",null).body(),get("/telegram-stats/app.js",null).body());
        String root=session("/telegram-stats/",42),admin=session("/coreprotect/",43);
        assertEquals(403,get("/telegram-stats/api/dashboard",admin).statusCode());assertEquals(403,get("/coreprotect/api/capabilities",root).statusCode());
        assertEquals(200,get("/coreprotect/api/capabilities",admin).statusCode());assertEquals(200,get("/telegram-stats/api/options",root).statusCode());
        assertEquals(200,get("/telegram-stats/api/dashboard?period=all",root).statusCode());
        assertEquals(403,get("/telegram-stats/api/session","Telegram "+CoreProtectWebTest.signed(43,clock.get()/1000,botToken)).statusCode());
        assertEquals(403,get("/telegram-stats/api/dashboard",null).statusCode());assertEquals(403,get("/telegram-stats/api/options?user=42",null).statusCode());
    }
    @Test void forgedExpiredRevokedAndCrossOriginAccessDeniedAndNoWriteEndpoints()throws Exception{
        assertEquals(403,get("/telegram-stats/api/session","Telegram forged").statusCode());
        assertEquals(403,get("/telegram-stats/api/session","Telegram "+CoreProtectWebTest.signed(42,clock.get()/1000-301,botToken)).statusCode());
        String auth=session("/telegram-stats/",42);assertEquals(400,get("/telegram-stats/api/dashboard?user=42",auth).statusCode());
        for(String route:List.of("delete","purge","sql","backfill","edit"))assertEquals(404,get("/telegram-stats/api/"+route,auth).statusCode());
        var post=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+runtime.port()+"/telegram-stats/api/dashboard")).header("Authorization",auth).POST(HttpRequest.BodyPublishers.noBody()).build();assertEquals(405,client.send(post,HttpResponse.BodyHandlers.ofString()).statusCode());
        var cross=HttpRequest.newBuilder(post.uri()).header("Authorization",auth).header("Origin","https://other.example").GET().build();assertEquals(403,client.send(cross,HttpResponse.BodyHandlers.ofString()).statusCode());
        admins.remove(42L);clock.addAndGet(31_000);assertEquals(403,get("/telegram-stats/api/options",auth).statusCode());
        admins.add(42L);String next=session("/telegram-stats/",42);roots.clear();assertEquals(403,get("/telegram-stats/api/options",next).statusCode());
    }
    @Test void cachedReportsDoNotBypassAclAndExpireAfterThirtySeconds()throws Exception{
        String auth=session("/telegram-stats/",42);String first=get("/telegram-stats/api/dashboard?period=all",auth).body();
        service.submit(r->r.message(new TelegramAnalyticsEvent(-1001,1,2,clock.get()/1000,0,r.pseudonym(99),"text",false,false,false,5,"Fixture","supergroup","")));assertTrue(service.awaitIdle(5000));
        assertEquals(first,get("/telegram-stats/api/dashboard?period=all",auth).body());clock.addAndGet(31_000);
        var report=JsonParser.parseString(get("/telegram-stats/api/dashboard?period=all",auth).body()).getAsJsonObject();assertEquals(1,report.getAsJsonObject("summary").get("messages").getAsLong());
        assertFalse(report.toString().contains(service.repository.pseudonym(99)));roots.clear();assertEquals(403,get("/telegram-stats/api/dashboard?period=all",auth).statusCode());
    }
    @Test void rootOnlyButtonUsesActualGuardRoleAndTelegramRequirement()throws Exception{
        try(var guard=new GuardService(folder.resolve("guard"),new GuardConfig(true,120000,42),java.util.logging.Logger.getAnonymousLogger(),clock::get)){
            guard.configure(new GuardConfig(true,120000,42),Set.of(43L));guard.changeSuper(42,77,true).get();
            var menu=new TelegramGuardMenu(guard,new TelegramCommands(new Catalogue(v->{}),mock(StorageEngine.class),20),mock(TelegramModeration.class));menu.analyticsWeb=stats;
            var analytics=menu.callback(42,menu.superAdmin.rootHome(42).buttons().stream().filter(b->b.text().contains("Аналитика")).findFirst().orElseThrow().data()).view();
            assertTrue(analytics.buttons().stream().anyMatch(b->b.text().equals("📊 Telegram-статистика")));
            assertFalse(menu.superAdmin.superHome(77).buttons().stream().anyMatch(b->b.text().contains("Telegram-статистика")));
            assertFalse(menu.admin.adminHome(43).buttons().stream().anyMatch(b->b.text().contains("Telegram-статистика")));
            assertNull(stats.button(7,0));admins.remove(42L);clock.addAndGet(31_000);assertNull(stats.button(42,0));
        }
    }
    @Test void concurrentReportRequestsAreBoundedAndCannotStarveCoreProtect()throws Exception{
        String auth=session("/telegram-stats/",42);var calls=new ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<String>>>();
        for(int i=0;i<12;i++){var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+runtime.port()+"/telegram-stats/api/dashboard?period=all&offset="+i)).header("Authorization",auth).timeout(Duration.ofSeconds(10)).GET().build();calls.add(client.sendAsync(request,HttpResponse.BodyHandlers.ofString()));}
        for(var call:calls){int code=call.get(12,java.util.concurrent.TimeUnit.SECONDS).statusCode();assertTrue(code==200||code==429,"Unexpected response: "+code);}
        assertEquals(200,get("/coreprotect/",null).statusCode());
    }
}

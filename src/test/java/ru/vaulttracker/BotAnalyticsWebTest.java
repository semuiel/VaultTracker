package ru.vaulttracker;

import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BotAnalyticsWebTest {
    @TempDir Path folder;
    final String token="123456:only-local-synthetic-fixture";
    final AtomicLong clock=new AtomicLong(1_800_000_000_000L);
    final AtomicBoolean root=new AtomicBoolean(true),admin=new AtomicBoolean(true);
    final HttpClient client=HttpClient.newHttpClient();
    BotAnalyticsService service;BotAnalyticsWeb web;TelegramWebRuntime runtime;
    @BeforeEach void setup()throws Exception{
        var config=new BotAnalyticsConfig(true,-1001,"https://fixture.example/bot-analytics/",ZoneId.of("Asia/Novosibirsk"));
        service=new BotAnalyticsService(folder,config,new BotAnalyticsCatalog(),()->clock.get()/1000);runtime=new TelegramWebRuntime("127.0.0.1",0);
        web=new BotAnalyticsWeb(runtime,config,service,token,id->id==500&&root.get(),(c,id)->id==500&&admin.get(),clock::get);runtime.start();
    }
    @AfterEach void close(){if(web!=null)web.close();if(runtime!=null)runtime.close();if(service!=null)service.close();}
    HttpResponse<String> get(String path,String auth)throws Exception{
        var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+runtime.port()+"/bot-analytics/"+path));if(auth!=null)r.header("Authorization",auth);return client.send(r.build(),HttpResponse.BodyHandlers.ofString());
    }
    String session()throws Exception{var r=get("api/session","Telegram "+CoreProtectWebTest.signed(500,clock.get()/1000,token));assertEquals(200,r.statusCode());return "Bearer "+com.google.gson.JsonParser.parseString(r.body()).getAsJsonObject().get("token").getAsString();}
    @Test void signedRootAndTelegramAdminRequiredRevocationAndNoMutationRoutes()throws Exception{
        assertNull(web.button(42,0));assertNull(web.button(99,0));assertNotNull(web.button(500,0));
        assertEquals(403,get("api/dashboard",null).statusCode());
        assertEquals(403,get("api/session","Telegram "+CoreProtectWebTest.signed(42,clock.get()/1000,token)).statusCode());
        assertEquals(403,get("api/session","Telegram "+CoreProtectWebTest.signed(500,clock.get()/1000,"forged-token")).statusCode());
        assertEquals(403,get("api/session?user=500","Telegram "+CoreProtectWebTest.signed(500,clock.get()/1000,token)).statusCode());
        String auth=session();assertEquals(200,get("api/options",auth).statusCode());assertEquals(200,get("api/dashboard?period=all",auth).statusCode());
        assertEquals(400,get("api/dashboard?user=500",auth).statusCode());assertEquals(404,get("api/delete",auth).statusCode());
        var post=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+runtime.port()+"/bot-analytics/api/dashboard")).header("Authorization",auth).POST(HttpRequest.BodyPublishers.ofString("{}")).build();assertEquals(405,client.send(post,HttpResponse.BodyHandlers.ofString()).statusCode());
        root.set(false);assertEquals(403,get("api/options",auth).statusCode());
    }
    @Test void telegramRoleCacheRevalidatesAndHeadersRemainPrivate()throws Exception{
        String auth=session();admin.set(false);clock.addAndGet(CoreProtectWebAccess.ACL_TTL+1);assertEquals(403,get("api/dashboard?period=30",auth).statusCode());
        var asset=get("",null);assertEquals(200,asset.statusCode());assertEquals("no-store",asset.headers().firstValue("cache-control").orElseThrow());assertTrue(asset.headers().firstValue("content-security-policy").orElseThrow().contains("frame-ancestors"));
    }
    @Test void rootRequestsAreRateLimited()throws Exception{
        String auth=session();int limited=0;for(int i=0;i<35;i++){int status=get("api/options",auth).statusCode();assertTrue(status==200||status==429);if(status==429)limited++;}assertTrue(limited>0);
    }
}

package ru.vaulttracker;

import com.google.gson.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoreProtectWebTest {
    @TempDir Path folder;
    final AtomicLong clock=new AtomicLong(1_800_000_000_000L);
    final String token="123456:local-fixture-not-a-real-bot-token";
    CoreProtectWebServer web;
    final HttpClient client=HttpClient.newHttpClient();
    static CoreProtectWebConfig config(int concurrency,int timeout,int rate) {
        return new CoreProtectWebConfig(true,-100123L,"https://admin.example/coreprotect/","127.0.0.1",0,50,100,2000,30,32,
                concurrency,timeout,rate,"127.0.0.1",3306,"coreprotect","co_","viewer","local-fixture-only");
    }
    static String signed(long user,long date,String token) throws Exception {
        var map=new TreeMap<String,String>();map.put("auth_date",Long.toString(date));map.put("query_id","local-test");map.put("user","{\"id\":"+user+",\"first_name\":\"Fixture\"}");
        String check=String.join("\n",map.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).toList());
        String hash=HexFormat.of().formatHex(TelegramMiniAppAuth.hmac(TelegramMiniAppAuth.hmac("WebAppData".getBytes(StandardCharsets.UTF_8),token),check));
        return String.join("&",map.entrySet().stream().map(e->e.getKey()+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).toList())+"&hash="+hash;
    }
    @AfterEach void close() {if(web!=null) web.close();}
    CoreProtectLookupSource source() {
        return new CoreProtectLookupSource() {
            public Capabilities capabilities() {return new Capabilities(List.of(new Category("block","Блоки","🧱",true,false)),List.of("world"),true);}
            public Page lookup(CoreProtectQuery query) {return new Page(List.of(new Event("1",1,"Fixture","","block","place","STONE",1,"world",1,2,3,false,null)),false);}
            public Players players(String search,int offset,int limit){return new Players(List.of(new Player("OfflineFixture","00000000-0000-0000-0000-000000000001")),false);}
        };
    }
    void start(CoreProtectLookupSource source,CoreProtectWebConfig config) throws Exception {
        web=new CoreProtectWebServer(config,token,source,id->id==42,(chat,id)->id==42,(u,a,t)->{},clock::get);web.start();
    }
    HttpResponse<String> get(String route,String auth) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+web.port()+"/coreprotect/"+route)).timeout(java.time.Duration.ofSeconds(4));
        if(auth!=null) request.header("Authorization",auth);
        return client.send(request.GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    String session() throws Exception {
        var response=get("api/session","Telegram "+signed(42,clock.get()/1000,token));assertEquals(200,response.statusCode(),response.body());
        return "Bearer "+JsonParser.parseString(response.body()).getAsJsonObject().get("token").getAsString();
    }
    @Test void signatureTamperExpiryAndDuplicateFields() throws Exception {
        String raw=signed(42,clock.get()/1000,token);
        assertEquals(42,TelegramMiniAppAuth.verify(raw,token,clock.get()/1000));
        assertThrows(SecurityException.class,()->TelegramMiniAppAuth.verify(raw.replace("42","43"),token,clock.get()/1000));
        assertThrows(SecurityException.class,()->TelegramMiniAppAuth.verify(raw,token,clock.get()/1000+301));
        assertThrows(SecurityException.class,()->TelegramMiniAppAuth.verify(signed(42,clock.get()/1000+31,token),token,clock.get()/1000));
        assertThrows(SecurityException.class,()->TelegramMiniAppAuth.verify(raw+"&user=x",token,clock.get()/1000));
        assertThrows(SecurityException.class,()->TelegramMiniAppAuth.verify(null,token,clock.get()/1000));
        assertThrows(SecurityException.class,()->TelegramMiniAppAuth.verify(raw,"different-token",clock.get()/1000));
    }
    @Test void dualAclEveryRoleRevocationAndFailure() {
        var roles=new HashSet<Long>(Set.of(10L,11L,12L)); // Admin / Super / Root
        var telegram=new HashSet<Long>(Set.of(10L,11L,12L,13L));
        var access=new CoreProtectWebAccess(config(2,2,20),roles::contains,(chat,id)->telegram.contains(id),clock::get);
        for(long id:roles) assertTrue(access.allowed(id,true));
        assertFalse(access.allowed(13,true));assertFalse(access.allowed(14,true));
        String session=access.open(10);telegram.remove(10L);assertEquals(10,access.require(session));
        assertThrows(SecurityException.class,()->access.open(10)); // each new session always refreshes
        clock.addAndGet(CoreProtectWebAccess.ACL_TTL+1);
        assertThrows(SecurityException.class,()->access.require(session));
        roles.remove(11L);assertFalse(access.allowed(11,false));
        var failure=new CoreProtectWebAccess(config(2,2,20),id->true,(chat,id)->{throw new java.io.IOException();},clock::get);
        assertFalse(failure.allowed(1,true));
    }
    @Test void sessionExpiresAndRateBounded() {
        var access=new CoreProtectWebAccess(config(2,2,2),id->true,(c,u)->true,clock::get);
        String session=access.open(1);assertTrue(access.rate(1));assertTrue(access.rate(1));assertFalse(access.rate(1));
        clock.addAndGet(60_000);assertTrue(access.rate(1));clock.addAndGet(CoreProtectWebAccess.SESSION_TTL);
        assertThrows(SecurityException.class,()->access.require(session));assertThrows(SecurityException.class,()->access.require("old-link"));
    }
    @Test void realCheckerReusesOfftopStatuses() throws Exception {
        TelegramApi api=mock(TelegramApi.class);doCallRealMethod().when(api).chatAdministrator(anyLong(),anyLong());
        for(String status:List.of("member","restricted","left","kicked")) {when(api.memberStatus(-1,42)).thenReturn(status);assertFalse(api.chatAdministrator(-1,42));}
        for(String status:List.of("administrator","creator")) {when(api.memberStatus(-1,42)).thenReturn(status);assertTrue(api.chatAdministrator(-1,42));}
    }
    @Test void privateButtonAndKeyboardSerialization() throws Exception {
        start(source(),config(2,2,20));assertNull(web.button(5,0));assertNotNull(web.button(42,0));
        var view=new TelegramCommands.View("test",List.of(web.button(42,0)));
        var button=TelegramApi.messageBody(42,view).getAsJsonObject("reply_markup").getAsJsonArray("inline_keyboard").get(0).getAsJsonArray().get(0).getAsJsonObject();
        assertEquals("https://admin.example/coreprotect/",button.getAsJsonObject("web_app").get("url").getAsString());assertFalse(button.has("callback_data"));
    }
    @Test void actualAdminSuperRootMenusUseTheSameDualAcl() throws Exception {
        try(var guard=new GuardService(folder.resolve("guard"),new GuardConfig(true,120000,500),java.util.logging.Logger.getAnonymousLogger(),clock::get)) {
            guard.configure(new GuardConfig(true,120000,500),Set.of(99L));guard.changeSuper(500,77,true).get();
            var menu=new TelegramGuardMenu(guard,new TelegramCommands(new Catalogue(v->{}),mock(StorageEngine.class),20),mock(TelegramModeration.class));
            var telegram=new HashSet<>(Set.of(500L,77L,99L,42L));
            web=new CoreProtectWebServer(config(2,2,20),token,source(),guard::admin,(c,u)->telegram.contains(u),(u,a,t)->{},clock::get);web.start();menu.coreprotectWeb=web;
            assertTrue(menu.admin.adminHome(99).buttons().stream().anyMatch(b->b.webAppUrl()!=null));
            assertTrue(menu.superAdmin.superHome(77).buttons().stream().anyMatch(b->b.webAppUrl()!=null));
            var analytics=menu.callback(500,menu.superAdmin.rootHome(500).buttons().stream().filter(b->b.text().contains("Аналитика")).findFirst().orElseThrow().data()).view();
            assertTrue(analytics.buttons().stream().anyMatch(b->b.webAppUrl()!=null));
            assertNull(web.button(42,0));telegram.remove(99L);clock.addAndGet(CoreProtectWebAccess.ACL_TTL+1);
            assertFalse(menu.admin.adminHome(99).buttons().stream().anyMatch(b->b.webAppUrl()!=null));
            assertThrows(SecurityException.class,()->web.access.open(99));
        }
    }
    @Test void routesCannotBeUsedWithoutAuthOrWithQueryUserAndNeverMutate() throws Exception {
        start(source(),config(2,2,40));
        assertEquals(200,get("",null).statusCode());
        assertEquals(403,get("api/lookup?player=Fixture",null).statusCode());assertEquals(403,get("api/capabilities",null).statusCode());
        assertEquals(403,get("api/session?user=42","Telegram "+signed(42,clock.get()/1000,token)).statusCode());
        String auth=session();
        assertEquals(400,get("api/lookup?player=Fixture&user=1",auth).statusCode());
        assertEquals(200,get("api/lookup?player=Fixture",auth).statusCode());
        for(String name:List.of("rollback","restore","purge","delete","edit","sql","command")) assertEquals(404,get("api/"+name,auth).statusCode());
        var post=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+web.port()+"/coreprotect/api/lookup")).header("Authorization",auth).POST(HttpRequest.BodyPublishers.ofString("anything")).build();
        assertEquals(405,client.send(post,HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(403,get("api/players?search=Fixture",null).statusCode());
        var players=get("api/players?search=fixture",auth);assertEquals(200,players.statusCode());assertTrue(players.body().contains("OfflineFixture"));
        assertEquals(400,get("api/players?limit=1000",auth).statusCode());assertEquals(400,get("api/players?search=x&user=1",auth).statusCode());
        assertEquals(Set.of("capabilities","lookup","players","close"),new HashSet<>(Arrays.stream(CoreProtectLookupSource.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName).toList()));
    }
    @Test void securityHeadersAndCrossOriginFailClosed() throws Exception {
        start(source(),config(2,2,20));var response=get("",null);
        assertTrue(response.headers().firstValue("content-security-policy").orElseThrow().contains("frame-ancestors"));
        assertEquals("nosniff",response.headers().firstValue("x-content-type-options").orElseThrow());assertEquals("no-store",response.headers().firstValue("cache-control").orElseThrow());
        assertTrue(response.headers().firstValue("access-control-allow-origin").isEmpty());
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+web.port()+"/coreprotect/api/session")).header("Origin","https://attacker.example").header("Authorization","Telegram "+signed(42,clock.get()/1000,token)).build();
        assertEquals(403,client.send(req,HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    @Test void commandHelperIsOnlyAStaticAssetAndContainerQueryNeedsNoCoordinates() throws Exception {
        start(source(),config(2,2,20));
        var asset=get("rollback.js",null);assertEquals(200,asset.statusCode());assertTrue(asset.headers().firstValue("content-type").orElseThrow().startsWith("text/javascript"));
        assertEquals(200,get("api/lookup?player=Fixture&category=container",session()).statusCode());
        assertEquals(404,get("api/rollback",session()).statusCode());
    }
    @Test void materialCatalogUsesBundledRussianNamesRequiresAclAndDoesNotQueryDatabase() throws Exception {
        var unavailable=new CoreProtectLookupSource() {
            public Capabilities capabilities() {throw new AssertionError("Catalog must not query the database");}
            public Page lookup(CoreProtectQuery query) {throw new AssertionError("Catalog must not query the database");}
        };
        start(unavailable,config(1,2,20));
        assertEquals(200,get("materials.js",null).statusCode());
        assertEquals(403,get("api/materials",null).statusCode());
        String auth=session();var response=get("api/materials",auth);assertEquals(200,response.statusCode(),response.body());
        var rows=JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("materials");
        var names=new HashMap<String,String>();
        for(var row:rows) {var entry=row.getAsJsonObject();names.put(entry.get("id").getAsString(),entry.get("label").getAsString());}
        assertTrue(names.size()>1000);assertEquals("Алмазная руда",names.get("DIAMOND_ORE"));assertEquals("Алмаз",names.get("DIAMOND"));
        assertTrue(names.keySet().stream().noneMatch(name->name.startsWith("LEGACY_")));
        assertEquals(400,get("api/materials?player=Fixture",auth).statusCode());
        assertEquals(404,get("api/rollback",auth).statusCode());
    }
    @Test void boundsAndFilters() {
        var c=config(2,2,20);long now=clock.get()/1000;
        var params=new HashMap<>(Map.of("player","Fixture","world","world","x","1","y","64","z","2","radius","8","include","diamond, minecraft:DIAMOND_ORE","exclude","STONE","offset","50","limit","100"));
        params.put("from",Long.toString(now-7200));params.put("to",Long.toString(now));
        var q=CoreProtectQuery.parse(params,c,now);
        assertEquals(List.of("minecraft:diamond","minecraft:diamond_ore"),q.include());assertEquals(50,q.offset());assertEquals(8,q.radius());assertEquals(now-7200,q.from());
        for(var p:List.of(Map.<String,String>of(),Map.of("player","Fixture","limit","101"),Map.of("player","Fixture","offset","2001"),Map.of("player","Fixture","radius","33"),Map.of("player","Fixture","x","0"),Map.of("player","Fixture","from","0"),Map.of("player","Fixture","include","not_a_material"),Map.of("player","Fixture","text","secret")))
            assertThrows(IllegalArgumentException.class,()->CoreProtectQuery.parse(p,c,now));
        assertThrows(IllegalArgumentException.class,()->CoreProtectWebServer.parameters("player=a&player=b"));
        var sensitive=CoreProtectQuery.parse(Map.of("player","Fixture","category","command","text","secret-content"),c,now);assertFalse(sensitive.auditSummary().contains("secret-content"));
    }
    @Test void unavailableEmptyOversizedTimeoutAndConcurrency() throws Exception {
        AtomicInteger active=new AtomicInteger(),max=new AtomicInteger();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        var slow=new CoreProtectLookupSource() {
            public Capabilities capabilities() {return new Capabilities(List.of(),List.of(),false);}
            public Page lookup(CoreProtectQuery q) throws Exception {int n=active.incrementAndGet();max.accumulateAndGet(n,Math::max);entered.countDown();try {while(!release.await(50,TimeUnit.MILLISECONDS)) {}}catch(InterruptedException ignored) {release.await();}finally {active.decrementAndGet();}return new Page(List.of(),false);}
        };
        start(slow,config(1,1,40));String auth=session();
        var first=CompletableFuture.supplyAsync(()->{try{return get("api/lookup?player=Fixture",auth);}catch(Exception e){throw new RuntimeException(e);}});
        assertTrue(entered.await(2,TimeUnit.SECONDS));assertEquals(429,get("api/lookup?player=Fixture",auth).statusCode());
        assertEquals(504,first.get(3,TimeUnit.SECONDS).statusCode());assertEquals(429,get("api/lookup?player=Fixture",auth).statusCode());
        release.countDown();assertEquals(1,max.get());
    }
    @Test void configIsOffByDefaultAndRejectsUnsafePublicSettings() throws Exception {
        Files.writeString(folder.resolve("telegram.yml"),"{}");assertFalse(CoreProtectWebConfig.load(folder).enabled());
        Files.writeString(folder.resolve("telegram.yml"),"coreprotectWeb:\n  enabled: true\n  accessChatId: -1001\n  publicUrl: 'http://insecure.test'\n  database:\n    user: viewer\n    password: fixture\n");
        assertThrows(IllegalArgumentException.class,()->CoreProtectWebConfig.load(folder));
        String settings=Files.readString(folder.resolve("telegram.yml"));
        for(String url:List.of("https://admin.example/unsafe%20path/","https://admin.example:0/coreprotect/","https://admin.example/coreprotect/?user=1")) {
            Files.writeString(folder.resolve("telegram.yml"),settings.replace("http://insecure.test",url));
            assertThrows(IllegalArgumentException.class,()->CoreProtectWebConfig.load(folder));
        }
        Files.writeString(folder.resolve("telegram.yml"),settings.replace("http://insecure.test","https://admin.example/coreprotect/"));
        assertTrue(CoreProtectWebConfig.load(folder).buttonEnabled());
        Files.writeString(folder.resolve("telegram.yml"),settings.replace("http://insecure.test",""));
        assertFalse(CoreProtectWebConfig.load(folder).buttonEnabled());
        assertFalse(config(2,2,20).toString().contains("local-fixture-only"));
    }
    @Test void emptyUnavailableAndAdapterOverflowHaveGenericResponses() throws Exception {
        var fake=mock(CoreProtectLookupSource.class);
        when(fake.lookup(any())).thenReturn(new CoreProtectLookupSource.Page(List.of(),false));
        start(fake,config(2,2,20));String auth=session();
        var diagnostics=new ArrayList<String>();web.diagnostics(diagnostics::add);
        assertEquals(200,get("api/lookup?player=Fixture",auth).statusCode());
        when(fake.lookup(any())).thenThrow(new java.sql.SQLException("secret-database-password"));
        var unavailable=get("api/lookup?player=Fixture",auth);assertEquals(503,unavailable.statusCode());assertFalse(unavailable.body().contains("secret-database-password"));
        doReturn(new CoreProtectLookupSource.Page(Collections.nCopies(101,new CoreProtectLookupSource.Event("1",0,"Fixture","","block","place","STONE",1,"world",1,2,3,false,null)),true)).when(fake).lookup(any());
        assertEquals(503,get("api/lookup?player=Fixture",auth).statusCode());
        assertEquals(1,diagnostics.size());assertFalse(diagnostics.toString().contains("secret-database-password"));
    }
}

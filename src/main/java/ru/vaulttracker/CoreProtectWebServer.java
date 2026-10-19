package ru.vaulttracker;

import com.google.gson.Gson;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Small private listener. The entire HTTP route allowlist is below; every investigation route requires ACL. */
final class CoreProtectWebServer implements AutoCloseable {
    interface Audit {void record(long user,String action,String target);}
    private final CoreProtectWebConfig config;
    private final String botToken;
    private final CoreProtectLookupSource source;
    final CoreProtectWebAccess access;
    private final Audit audit;
    private final LongSupplier clock;
    private final Gson gson=new Gson();
    private final TelegramWebRuntime runtime;
    private final boolean ownsRuntime;
    private final ExecutorService lookups;
    private Consumer<String> diagnostic=ignored->{};
    private volatile BotAnalyticsService productAnalytics;
    private java.util.function.LongFunction<String> productRole=ignored->"admin";
    void analytics(BotAnalyticsService service,java.util.function.LongFunction<String> role){productAnalytics=service;productRole=role;}
    private void analytics(long user,String action,String kind,String outcome,int results){var capture=productAnalytics;if(capture!=null)capture.capture(new BotAnalytics.Context(capture,user,"web:"+UUID.randomUUID(),productRole.apply(user),System.nanoTime(),"manual"),"coreprotect",action,kind,outcome,"","",results,"unknown");}
    private final java.util.concurrent.atomic.AtomicLong nextDiagnostic=new java.util.concurrent.atomic.AtomicLong();
    void diagnostics(Consumer<String> sink) {diagnostic=sink;}
    CoreProtectWebServer(CoreProtectWebConfig config,String botToken,CoreProtectLookupSource source,
                         LongPredicate role,CoreProtectWebAccess.ChatAdmin checker,Audit audit) throws IOException {
        this(config,botToken,source,role,checker,audit,System::currentTimeMillis);
    }
    CoreProtectWebServer(CoreProtectWebConfig config,String botToken,CoreProtectLookupSource source,
                         LongPredicate role,CoreProtectWebAccess.ChatAdmin checker,Audit audit,LongSupplier clock) throws IOException {
        this(config,botToken,source,role,checker,audit,clock,null);
    }
    CoreProtectWebServer(CoreProtectWebConfig config,String botToken,CoreProtectLookupSource source,
                         LongPredicate role,CoreProtectWebAccess.ChatAdmin checker,Audit audit,LongSupplier clock,TelegramWebRuntime shared) throws IOException {
        this.config=config;this.botToken=botToken;this.source=source;this.audit=audit;this.clock=clock;
        access=new CoreProtectWebAccess(config,role,checker,clock);
        lookups=new ThreadPoolExecutor(config.concurrency(),config.concurrency(),0,TimeUnit.SECONDS,new SynchronousQueue<>(),Thread.ofPlatform().daemon().name("VaultTracker-cp-read-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
        ownsRuntime=shared==null;
        try {runtime=ownsRuntime?new TelegramWebRuntime(config.bindHost(),config.bindPort()):shared;runtime.mount(config.basePath(),this::handle);}
        catch(IOException | RuntimeException failure) {lookups.shutdownNow();throw failure;}
    }
    void start() {runtime.start();}
    int port() {return runtime.port();}
    TelegramCommands.Button button(long user,int row) {
        return config.buttonEnabled()&&access.allowed(user,false)?TelegramCommands.Button.webApp("🔎 CoreProtect",config.publicUrl().endsWith("/")?config.publicUrl():config.publicUrl()+"/",row):null;
    }
    private void handle(HttpExchange request) throws IOException {
        try {
            headers(request);
            if(!request.getRequestMethod().equals("GET")) {json(request,405,Map.of("error","Разрешено только чтение"));return;}
            String origin=request.getRequestHeaders().getFirst("Origin");
            if(origin!=null&&!origin.equals(config.origin())) throw new SecurityException("Нет доступа");
            if(!config.enabled()) {json(request,503,Map.of("error","CoreProtect сейчас недоступен."));return;}
            String path=request.getRequestURI().getRawPath();String base=config.basePath();
            String route=path.startsWith(base)?path.substring(base.length()):"missing";
            if(route.equals("")||route.equals("index.html")||route.equals("app.js")||route.equals("rollback.js")||route.equals("materials.js")||route.equals("players.js")||route.equals("style.css")) {
                if(request.getRequestURI().getRawQuery()!=null) throw new IllegalArgumentException("Откройте панель через бота");
                asset(request,route.isEmpty()?"index.html":route);return;
            }
            // No dynamic dispatch, SQL/console/action endpoints or URL-provided user identities.
            if(!Set.of("api/session","api/capabilities","api/materials","api/players","api/lookup").contains(route)) {json(request,404,Map.of("error","Страница не найдена"));return;}
            String auth=request.getRequestHeaders().getFirst("Authorization");
            if(route.equals("api/session")) {
                if(request.getRequestURI().getRawQuery()!=null||auth==null||!auth.startsWith("Telegram ")) throw new SecurityException("Откройте CoreProtect через Telegram-бот FLEXITY");
                long user=TelegramMiniAppAuth.verify(auth.substring(9),botToken,clock.getAsLong()/1000);
                if(!access.rate(user)) {json(request,429,Map.of("error","Слишком много запросов. Подождите минуту"));return;}
                String session=access.open(user);
                audit.record(user,"coreprotect.open","Mini App");
                analytics(user,"coreprotect.open","screen_view","",0);
                json(request,200,Map.of("token",session,"expiresIn",CoreProtectWebAccess.SESSION_TTL/1000));return;
            }
            long user=access.require(auth!=null&&auth.startsWith("Bearer ")?auth.substring(7):null);
            if(!access.rate(user)) {json(request,429,Map.of("error","Слишком много запросов. Подождите минуту"));return;}
            Map<String,String> params=parameters(request.getRequestURI().getRawQuery());
            if(route.equals("api/players")) {
                if(!Set.of("search","offset","limit").containsAll(params.keySet()))throw new IllegalArgumentException("Неизвестный фильтр");
                String search=params.getOrDefault("search","").trim();int offset=Integer.parseInt(params.getOrDefault("offset","0")),limit=Integer.parseInt(params.getOrDefault("limit","30"));
                if(search.length()>36||!search.matches("[A-Za-z0-9_\\-]*")||offset<0||offset>1000||limit<1||limit>30)throw new IllegalArgumentException("Проверьте ник, UUID и страницу списка");
                var result=read(()->source.players(search,offset,limit));
                if(result.players().size()>limit)throw new IOException("Adapter exceeded player limit");
                json(request,200,result);return;
            }
            if(route.equals("api/materials")) {
                if(!params.isEmpty()) throw new IllegalArgumentException("Неизвестный фильтр");
                json(request,200,Map.of("materials",MaterialCatalog.VALUES));return;
            }
            if(route.equals("api/capabilities")) {
                if(!params.isEmpty()) throw new IllegalArgumentException("Неизвестный фильтр");
                var caps=read(source::capabilities);
                json(request,200,Map.of("capabilities",caps,"limits",Map.of("pageSize",config.pageSize(),"maxPageSize",config.maxPageSize(),"maxDays",config.maxDays(),"maxRadius",config.maxRadius(),"maxOffset",config.maxOffset())));return;
            }
            var query=CoreProtectQuery.parse(params,config,clock.getAsLong()/1000);
            audit.record(user,"coreprotect.lookup",query.auditSummary());
            var result=read(()->source.lookup(query));
            analytics(user,"coreprotect.lookup","form_submit",result.events().isEmpty()?"empty":"success",result.events().size());
            // Defend even against a faulty optional adapter: never send more than the requested page.
            if(result.events().size()>query.limit()) throw new IOException("Adapter exceeded page limit");
            json(request,200,Map.of("page",result,"offset",query.offset(),"limit",query.limit(),"serverTime",Instant.ofEpochMilli(clock.getAsLong()).toString()));
        } catch(SecurityException denied) {json(request,403,Map.of("error",denied.getMessage()));}
        catch(IllegalArgumentException invalid) {json(request,400,Map.of("error",invalid.getMessage()==null?"Проверьте фильтры":invalid.getMessage()));}
        catch(RejectedExecutionException busy) {json(request,429,Map.of("error","Поиск занят. Повторите через несколько секунд"));}
        catch(TimeoutException timeout) {json(request,504,Map.of("error","Поиск занял слишком много времени. Сузьте период или область"));}
        catch(Exception | LinkageError unavailable) {
            long now=clock.getAsLong(),next=nextDiagnostic.get();
            if(now>=next&&nextDiagnostic.compareAndSet(next,now+60_000))
                diagnostic.accept("CoreProtect viewer: read backend unavailable; check SELECT-only grants, connection and 24.1 schema.");
            json(request,503,Map.of("error","CoreProtect сейчас недоступен."));
        }
        finally {request.close();}
    }
    // Enum constants and bundled translations only: no Bukkit world access or database query.
    private static final class MaterialCatalog {
        static final List<Map<String,String>> VALUES=Arrays.stream(org.bukkit.Material.values())
                .filter(material->!material.name().startsWith("LEGACY_"))
                .map(material->Map.of("id",material.name(),"label",RussianItems.name(material.name())))
                .toList();
    }
    private <T> T read(Callable<T> action) throws Exception {
        Future<T> result=lookups.submit(action);
        try {return result.get(config.timeoutSeconds(),TimeUnit.SECONDS);}
        catch(TimeoutException timeout) {result.cancel(true);throw timeout;}
        catch(ExecutionException failure) {
            if(failure.getCause() instanceof IllegalArgumentException invalid) throw invalid;
            if(failure.getCause() instanceof java.sql.SQLTimeoutException) throw new TimeoutException();
            throw new IOException("Lookup unavailable");
        }
        catch(InterruptedException interrupted) {result.cancel(true);Thread.currentThread().interrupt();throw interrupted;}
        // A driver ignoring interrupts continues occupying its bounded worker; no replacement worker is created.
    }
    static Map<String,String> parameters(String raw) {
        if(raw==null||raw.isBlank()) return Map.of();
        if(raw.length()>4096) throw new IllegalArgumentException("Слишком длинный запрос");
        Map<String,String> params=new LinkedHashMap<>();
        for(String pair:raw.split("&",-1)) {
            String[] parts=pair.split("=",2);
            if(parts.length!=2) throw new IllegalArgumentException("Некорректный фильтр");
            String key=URLDecoder.decode(parts[0],StandardCharsets.UTF_8),value=URLDecoder.decode(parts[1],StandardCharsets.UTF_8);
            if(params.putIfAbsent(key,value)!=null) throw new IllegalArgumentException("Повторяющийся фильтр");
        }
        return params;
    }
    static void headers(HttpExchange r) {
        var h=r.getResponseHeaders();h.set("Cache-Control","no-store");h.set("X-Content-Type-Options","nosniff");h.set("Referrer-Policy","no-referrer");
        h.set("Permissions-Policy","camera=(), microphone=(), geolocation=()");
        h.set("Content-Security-Policy","default-src 'none'; script-src 'self' https://telegram.org; style-src 'self'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'self' https://web.telegram.org https://*.web.telegram.org https://telegram.org https://*.telegram.org https://t.me");
    }
    private void json(HttpExchange r,int status,Object body) throws IOException {send(r,status,"application/json; charset=utf-8",gson.toJson(body).getBytes(StandardCharsets.UTF_8));}
    private static void asset(HttpExchange r,String name) throws IOException {
        try(InputStream in=CoreProtectWebServer.class.getResourceAsStream("/coreprotect-web/"+name)) {
            if(in==null) {send(r,404,"text/plain; charset=utf-8",new byte[0]);return;}
            String type=name.endsWith(".js")?"text/javascript":name.endsWith(".css")?"text/css":"text/html";
            send(r,200,type+"; charset=utf-8",in.readAllBytes());
        }
    }
    static void send(HttpExchange r,int status,String type,byte[] body) throws IOException {
        r.getResponseHeaders().set("Content-Type",type);r.sendResponseHeaders(status,body.length);r.getResponseBody().write(body);
    }
    @Override public void close() {runtime.unmount(config.basePath());access.clear();lookups.shutdownNow();source.close();if(ownsRuntime)runtime.close();}
}

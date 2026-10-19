package ru.vaulttracker;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Separate route and Root policy on the common listener. No data-changing HTTP endpoints. */
final class TelegramAnalyticsWeb implements AutoCloseable {
    private record Cached(long expires,String json) {}
    private final TelegramWebRuntime runtime;
    private final TelegramAnalyticsConfig config;
    private final TelegramAnalyticsService service;
    private final String botToken;
    final CoreProtectWebAccess access;
    private final LongSupplier millis;
    private final Gson gson=new Gson().newBuilder().serializeNulls().create();
    private final ExecutorService reports=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new SynchronousQueue<>(),Thread.ofPlatform().daemon().name("VaultTracker-analytics-report-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    private final Map<String,Cached> cache=new LinkedHashMap<>();
    TelegramAnalyticsWeb(TelegramWebRuntime runtime,TelegramAnalyticsConfig c,TelegramAnalyticsService service,String botToken,LongPredicate root,CoreProtectWebAccess.ChatAdmin admin,LongSupplier millis) {
        this.runtime=runtime;config=c;this.service=service;this.botToken=botToken;this.millis=millis;
        access=new CoreProtectWebAccess(c.enabled(),c.accessChatId(),30,root,admin,millis);
        runtime.mount(c.basePath(),this::handle);
    }
    TelegramCommands.Button button(long user,int row){return config.enabled()&&access.allowed(user,false)?TelegramCommands.Button.webApp("📊 Telegram-статистика",config.publicUrl(),row):null;}
    private void json(HttpExchange r,int status,Object body)throws IOException {raw(r,status,gson.toJson(body));}
    private void raw(HttpExchange r,int status,String text)throws IOException{CoreProtectWebServer.send(r,status,"application/json; charset=utf-8",text.getBytes(StandardCharsets.UTF_8));}
    private void handle(HttpExchange r)throws IOException {
        try {
            CoreProtectWebServer.headers(r);
            if(!r.getRequestMethod().equals("GET")){json(r,405,Map.of("error","Разрешён только просмотр"));return;}
            if(r.getRequestHeaders().getFirst("Origin")!=null&&!config.origin().equals(r.getRequestHeaders().getFirst("Origin")))throw new SecurityException("Нет доступа");
            String path=r.getRequestURI().getRawPath();String route=path.startsWith(config.basePath())?path.substring(config.basePath().length()):"missing";
            if(Set.of("","index.html","app.js","style.css").contains(route)) {
                if(r.getRequestURI().getRawQuery()!=null)throw new IllegalArgumentException("Откройте панель через бота");
                String asset=route.isEmpty()?"index.html":route;
                try(var in=getClass().getResourceAsStream("/telegram-stats/"+asset)) {
                    if(in==null){json(r,404,Map.of("error","Не найдено"));return;}
                    CoreProtectWebServer.send(r,200,(asset.endsWith(".js")?"text/javascript":asset.endsWith(".css")?"text/css":"text/html")+"; charset=utf-8",in.readAllBytes());return;
                }
            }
            if(!Set.of("api/session","api/options","api/dashboard").contains(route)){json(r,404,Map.of("error","Не найдено"));return;}
            String auth=r.getRequestHeaders().getFirst("Authorization");
            if(route.equals("api/session")) {
                if(r.getRequestURI().getRawQuery()!=null||auth==null||!auth.startsWith("Telegram "))throw new SecurityException("Откройте панель через Telegram-бот");
                long user=TelegramMiniAppAuth.verify(auth.substring(9),botToken,millis.getAsLong()/1000);
                if(!access.rate(user)){json(r,429,Map.of("error","Повторите через минуту"));return;}
                json(r,200,Map.of("token",access.open(user)));return;
            }
            long user=access.require(auth!=null&&auth.startsWith("Bearer ")?auth.substring(7):null);
            if(!access.rate(user)){json(r,429,Map.of("error","Повторите через минуту"));return;}
            Map<String,String> p=CoreProtectWebServer.parameters(r.getRequestURI().getRawQuery());
            String key=route+gson.toJson(new TreeMap<>(p))+config.zone()+new TreeSet<>(config.chats());long now=millis.getAsLong();
            synchronized(cache){var cached=cache.get(key);if(cached!=null&&cached.expires()>now){raw(r,200,cached.json());return;}}
            Future<String> task=reports.submit(()->{
                Object body;
                if(route.equals("api/options")){if(!p.isEmpty())throw new IllegalArgumentException("Неизвестный фильтр");body=service.queries.options();}
                else {var query=TelegramAnalyticsQuery.parse(p,config,Instant.ofEpochMilli(now).atZone(config.zone()).toLocalDate(),service.queries.earliest());var report=service.queries.report(query,now/1000);report.put("captureFailures",service.failures());body=report;}
                return gson.toJson(body);
            });
            try {String body=task.get(8,TimeUnit.SECONDS);synchronized(cache){cache.entrySet().removeIf(e->e.getValue().expires()<=now);if(cache.size()>=64)cache.remove(cache.keySet().iterator().next());cache.put(key,new Cached(now+30_000,body));}raw(r,200,body);}
            catch(TimeoutException timeout){task.cancel(true);json(r,504,Map.of("error","Отчёт занят. Выберите один чат или повторите позже"));}
            catch(ExecutionException failure){if(failure.getCause() instanceof IllegalArgumentException invalid)throw invalid;throw new IOException("Analytics report unavailable");}
        }catch(SecurityException denied){json(r,403,Map.of("error",denied.getMessage()));}
        catch(IllegalArgumentException invalid){json(r,400,Map.of("error",invalid.getMessage()==null?"Проверьте фильтры":invalid.getMessage()));}
        catch(RejectedExecutionException busy){json(r,429,Map.of("error","Отчёт формируется. Повторите через несколько секунд"));}
        catch(Exception unavailable){json(r,503,Map.of("error","Telegram-аналитика временно недоступна"));}
        finally {r.close();}
    }
    public void close(){runtime.unmount(config.basePath());access.clear();reports.shutdownNow();synchronized(cache){cache.clear();}}
}

package ru.vaulttracker;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Root AND current Telegram administrator; independent sessions on the existing shared listener. */
final class BotAnalyticsWeb implements AutoCloseable {
    private record Cached(long expires,String value) {}
    private final TelegramWebRuntime runtime;
    private final BotAnalyticsConfig config;
    private final BotAnalyticsService service;
    private final BotAnalyticsQueries queries;
    private final String token;
    private final LongSupplier clock;
    final CoreProtectWebAccess access;
    private final Gson gson=new Gson().newBuilder().serializeNulls().create();
    private final Map<String,Cached> cache=new LinkedHashMap<>();
    private final ExecutorService reports=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new SynchronousQueue<>(),Thread.ofPlatform().daemon().name("VaultTracker-bot-report-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    BotAnalyticsWeb(TelegramWebRuntime runtime,BotAnalyticsConfig config,BotAnalyticsService service,String token,LongPredicate root,CoreProtectWebAccess.ChatAdmin admin,LongSupplier clock) {
        this.runtime=runtime;this.config=config;this.service=service;this.token=token;this.clock=clock;queries=new BotAnalyticsQueries(service.repository,service.catalog);
        access=new CoreProtectWebAccess(config.enabled(),config.accessChatId(),30,root,admin,clock);runtime.mount(config.basePath(),this::handle);
    }
    TelegramCommands.Button button(long user,int row){return access.allowed(user,false)?TelegramCommands.Button.webApp("🤖 Аналитика бота",config.publicUrl(),row):null;}
    private void json(HttpExchange r,int status,Object value)throws IOException{raw(r,status,gson.toJson(value));}
    private void raw(HttpExchange r,int status,String value)throws IOException{CoreProtectWebServer.send(r,status,"application/json; charset=utf-8",value.getBytes(StandardCharsets.UTF_8));}
    private void handle(HttpExchange r)throws IOException {
        try {
            CoreProtectWebServer.headers(r);
            if(!r.getRequestMethod().equals("GET")){json(r,405,Map.of("error","Разрешён только просмотр"));return;}
            String origin=r.getRequestHeaders().getFirst("Origin");if(origin!=null&&!origin.equals(config.origin()))throw new SecurityException("Нет доступа");
            String path=r.getRequestURI().getRawPath(),route=path.startsWith(config.basePath())?path.substring(config.basePath().length()):"missing";
            if(Set.of("","index.html","app.js","style.css").contains(route)) {
                if(r.getRequestURI().getRawQuery()!=null)throw new IllegalArgumentException("Откройте приложение через бота");
                String name=route.isEmpty()?"index.html":route;
                try(var in=getClass().getResourceAsStream("/bot-analytics/"+name)) {
                    if(in==null){json(r,404,Map.of("error","Не найдено"));return;}
                    CoreProtectWebServer.send(r,200,(name.endsWith(".js")?"text/javascript":name.endsWith(".css")?"text/css":"text/html")+"; charset=utf-8",in.readAllBytes());return;
                }
            }
            if(!Set.of("api/session","api/options","api/dashboard").contains(route)){json(r,404,Map.of("error","Не найдено"));return;}
            String auth=r.getRequestHeaders().getFirst("Authorization");
            if(route.equals("api/session")) {
                if(r.getRequestURI().getRawQuery()!=null||auth==null||!auth.startsWith("Telegram "))throw new SecurityException("Откройте приложение через Telegram");
                long user=TelegramMiniAppAuth.verify(auth.substring(9),token,clock.getAsLong()/1000);
                if(!access.rate(user)){json(r,429,Map.of("error","Повторите через минуту"));return;}
                String session=access.open(user);
                var context=new BotAnalytics.Context(service,user,"web:"+UUID.randomUUID(),"root",System.nanoTime(),"manual");service.capture(context,"bot_analytics","botAnalytics.open","screen_view","","","",0,"unknown");
                json(r,200,Map.of("token",session));return;
            }
            long user=access.require(auth!=null&&auth.startsWith("Bearer ")?auth.substring(7):null);
            if(!access.rate(user)){json(r,429,Map.of("error","Повторите через минуту"));return;}
            var p=CoreProtectWebServer.parameters(r.getRequestURI().getRawQuery());
            if(route.equals("api/options")){if(!p.isEmpty())throw new IllegalArgumentException("Неизвестный фильтр");json(r,200,queries.options());return;}
            long now=clock.getAsLong();var query=BotAnalyticsQuery.parse(p,config.zone(),now/1000,service.repository.started);String key=query.toString();
            synchronized(cache){var hit=cache.get(key);if(hit!=null&&hit.expires()>now){raw(r,200,hit.value());return;}}
            var task=reports.submit(()->gson.toJson(queries.report(query,now/1000)));
            try{String value=task.get(8,TimeUnit.SECONDS);synchronized(cache){cache.entrySet().removeIf(e->e.getValue().expires()<=now);if(cache.size()>=64)cache.remove(cache.keySet().iterator().next());cache.put(key,new Cached(now+30000,value));}raw(r,200,value);}
            catch(TimeoutException timeout){task.cancel(true);json(r,504,Map.of("error","Отчёт занят. Сузьте период или повторите позже"));}
            catch(ExecutionException failed){throw new IOException("Bot report unavailable",failed);}
        }catch(SecurityException denied){json(r,403,Map.of("error",denied.getMessage()));}
        catch(IllegalArgumentException invalid){json(r,400,Map.of("error",invalid.getMessage()==null?"Проверьте фильтры":invalid.getMessage()));}
        catch(RejectedExecutionException busy){json(r,429,Map.of("error","Другой отчёт формируется. Повторите через несколько секунд"));}
        catch(Exception unavailable){json(r,503,Map.of("error","Аналитика бота временно недоступна"));}
        finally{r.close();}
    }
    public void close(){runtime.unmount(config.basePath());access.clear();reports.shutdownNow();synchronized(cache){cache.clear();}}
}

package ru.vaulttracker;

import java.util.*;

/** Per-update context. Explicit outcome hooks are harmless when capture is disabled. */
final class BotAnalytics {
    record Context(BotAnalyticsService service,long user,String correlation,String role,long started,String source) {}
    private static final ThreadLocal<Context> CURRENT=new ThreadLocal<>();
    private static final ThreadLocal<String> LANGUAGE=ThreadLocal.withInitial(()->"unknown");
    private static final ThreadLocal<Boolean> FAILED=ThreadLocal.withInitial(()->false);
    static AutoCloseable begin(BotAnalyticsService service,GuardService guard,TelegramApi.Incoming update) {
        Context old=CURRENT.get();
        if(service!=null&&update.userId()>0&&!update.senderBot()&&(update.privateChat()||update.callback()||update.text()!=null&&update.text().startsWith("/")))
            CURRENT.set(new Context(service,update.userId(),"update:"+update.updateId(),role(guard,update.userId()),System.nanoTime(),"manual"));
        else CURRENT.remove();
        FAILED.set(false);return ()->{if(old==null)CURRENT.remove();else CURRENT.set(old);LANGUAGE.remove();FAILED.remove();};
    }
    static String role(GuardService guard,long user){return guard==null?"player":guard.rootAdmin(user)?"root":guard.superAdmin(user)?"super":guard.admin(user)?"admin":"player";}
    static void source(String value){var c=CURRENT.get();if(c!=null)CURRENT.set(new Context(c.service,c.user,c.correlation,c.role,c.started,value));}
    static void inputLanguage(String value){LANGUAGE.set(language(value));}
    static String inputLanguage(){return LANGUAGE.get();}
    static void event(String feature,String action,String kind){event(feature,action,kind,"","","",0);}
    static void event(String feature,String action,String kind,String outcome,String mode,String query,int results){
        var c=CURRENT.get();if(c!=null)c.service.capture(c,feature,action,kind,outcome,mode,query,results,"unknown");
    }
    static void search(String action,String mode,String query,int results,String language){
        var c=CURRENT.get();if(c!=null)c.service.capture(c,"search",c.source().equals("pagination")?"search.page":action,c.source().equals("pagination")?"button_click":"form_submit",results>0?"success":"empty",mode,query,results,language);
    }
    static void success(String feature,String action){event(feature,action,"operation_success","success","","",0);}
    static void complete(String feature,String action){event(feature,action,"flow_complete","success","","",0);}
    static void committed(String feature,String action,String key){var c=CURRENT.get();if(c!=null)c.service.committed(c.user,c.role,"operation:"+feature+":"+key,feature,action);}
    static void failure(String feature,String action,Throwable error){if(FAILED.get())return;FAILED.set(true);event(feature,action,error instanceof SecurityException?"operation_denied":"operation_failure",error instanceof SecurityException?"denied":error instanceof IllegalArgumentException?"validation":"unavailable","","",0);}
    static String language(String value){return value!=null&&value.matches(".*[А-Яа-яЁё].*")?"ru":"en";}
}

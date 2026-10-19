package ru.vaulttracker;

import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Dashboards read aggregates, never re-scan the forever event log. No per-user query histories leave the server. */
final class BotAnalyticsQueries {
    private final BotAnalyticsRepository repository;
    private final BotAnalyticsCatalog catalog;
    private final ThreadLocal<Long> deadline=new ThreadLocal<>();
    BotAnalyticsQueries(BotAnalyticsRepository repository,BotAnalyticsCatalog catalog){this.repository=repository;this.catalog=catalog;}
    Map<String,Object> options(){return Map.of("features",BotAnalyticsCatalog.FEATURES,"actions",catalog.entries(),"started",repository.started,"timezone",repository.zone.getId());}
    private String where(BotAnalyticsQuery q,String column){return column+">=? AND "+column+"<?"+(q.role().equals("all")?"":" AND role=?")+(q.feature().equals("all")?"":" AND feature=?");}
    private List<Object> args(BotAnalyticsQuery q){var args=new ArrayList<Object>(List.of(q.start(repository.zone),q.end(repository.zone)));if(!q.role().equals("all"))args.add(q.role());if(!q.feature().equals("all"))args.add(q.feature());return args;}
    private List<Map<String,Object>> rows(Connection c,String sql,List<?> args)throws SQLException {
        if(Thread.currentThread().isInterrupted()||deadline.get()!=null&&System.nanoTime()>deadline.get())throw new SQLTimeoutException("Analytics deadline exceeded");
        try(var s=c.prepareStatement(sql)){s.setQueryTimeout(6);s.setMaxRows(5000);BotAnalyticsRepository.bind(s,args.toArray());try(var r=s.executeQuery()){
            var rows=new ArrayList<Map<String,Object>>();while(r.next()){var row=new LinkedHashMap<String,Object>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.put(r.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT),r.getObject(i));rows.add(row);}return rows;
        }}
    }
    private Map<String,Object> first(Connection c,String sql,List<?> args)throws SQLException {var rows=rows(c,sql,args);return rows.isEmpty()?Map.of():rows.getFirst();}
    private Map<String,Object> summary(Connection c,BotAnalyticsQuery q)throws SQLException {
        String w=where(q,"bucket");var a=args(q);var result=new LinkedHashMap<String,Object>(first(c,
            "SELECT COALESCE(SUM(CASE WHEN kind IN ('button_click','form_submit') THEN n ELSE 0 END),0) AS actions,COALESCE(SUM(CASE WHEN kind='screen_view' THEN n ELSE 0 END),0) AS views,COALESCE(SUM(CASE WHEN kind='operation_success' THEN n ELSE 0 END),0) AS successes,COALESCE(SUM(CASE WHEN kind IN ('operation_failure','operation_denied') THEN n ELSE 0 END),0) AS errors FROM ba_hours WHERE "+w,a));
        var ua=new ArrayList<Object>(List.of(q.start(repository.zone)));ua.addAll(a);
        result.putAll(first(c,"SELECT COUNT(*) AS users,COALESCE(SUM(CASE WHEN first_at>=? THEN 1 ELSE 0 END),0) AS newusers,COALESCE(SUM(CASE WHEN features>1 THEN 1 ELSE 0 END),0) AS multifeatureusers FROM (SELECT u.actor,MIN(u.first_at) AS first_at,COUNT(DISTINCT h.feature) AS features FROM ba_user_hours h JOIN ba_users u ON u.actor=h.actor WHERE "+w.replace("bucket","h.bucket")+" GROUP BY u.actor)",ua));
        result.putAll(first(c,"SELECT COUNT(*) AS sessions,COALESCE(AVG(actions),0) AS actionspersession,COALESCE(AVG(seconds),0) AS sessionseconds FROM (SELECT session,SUM(actions) AS actions,MAX(last_at)-MIN(started) AS seconds FROM ba_session_features WHERE "+where(q,"started")+" GROUP BY session)",a));
        var repeated=first(c,"SELECT COALESCE(SUM(n-1),0) AS repeatsearches FROM (SELECT actor,mode,query,SUM(n) AS n FROM ba_queries WHERE "+w+" GROUP BY actor,mode,query)",a);result.putAll(repeated);
        long users=((Number)result.get("users")).longValue(),newUsers=((Number)result.get("newusers")).longValue();result.put("returningusers",users-newUsers);
        result.put("sessionsperuser",users==0?0:((Number)result.get("sessions")).doubleValue()/users);
        double errors=((Number)result.get("errors")).doubleValue(),successes=((Number)result.get("successes")).doubleValue();result.put("errorpercent",errors+successes==0?0:100*errors/(errors+successes));
        result.putAll(first(c,"SELECT COUNT(*) AS onetimesessionusers FROM (SELECT s.actor,COUNT(DISTINCT f.session) AS n FROM ba_session_features f JOIN ba_sessions s ON s.id=f.session WHERE "+where(q,"f.started").replace("role","f.role").replace("feature","f.feature")+" GROUP BY s.actor HAVING COUNT(DISTINCT f.session)=1)",a));
        return result;
    }
    Map<String,Object> report(BotAnalyticsQuery q,long now)throws Exception {
        deadline.set(System.nanoTime()+7_000_000_000L);
        try(var c=repository.read()) {
            var result=new LinkedHashMap<String,Object>();var a=args(q);String w=where(q,"bucket");
            result.put("from",q.from().toString());result.put("to",q.until().minusDays(1).toString());result.put("started",repository.started);result.put("timezone",repository.zone.getId());
            result.put("current",summary(c,q));result.put("previous",summary(c,q.previous()));
            result.put("previousCovered",q.previous().start(repository.zone)>=repository.started);result.put("partial",q.start(repository.zone)<repository.started||q.end(repository.zone)>now);
            result.put("coverageAvailable",q.end(repository.zone)>repository.started&&q.start(repository.zone)<=now);
            result.put("features",rows(c,"SELECT feature,SUM(n) AS events,SUM(CASE WHEN kind='screen_view' THEN n ELSE 0 END) AS opens,SUM(CASE WHEN kind IN ('button_click','form_submit') THEN n ELSE 0 END) AS actions,SUM(CASE WHEN kind='operation_success' THEN n ELSE 0 END) AS successes,SUM(CASE WHEN kind IN ('operation_failure','operation_denied') THEN n ELSE 0 END) AS errors FROM ba_hours WHERE "+w+" GROUP BY feature ORDER BY actions DESC",a));
            result.put("featureUsers",rows(c,"SELECT feature,COUNT(DISTINCT actor) AS users FROM ba_user_hours WHERE "+w+" GROUP BY feature",a));
            result.put("featurePrevious",rows(c,"SELECT feature,SUM(CASE WHEN kind IN ('button_click','form_submit') THEN n ELSE 0 END) AS actions FROM ba_hours WHERE "+where(q.previous(),"bucket")+" GROUP BY feature",args(q.previous())));
            result.put("searchUsers",rows(c,"SELECT mode,COUNT(DISTINCT actor) AS users,SUM(n) AS queries FROM ba_search_users WHERE "+w+" GROUP BY mode",a));
            result.put("search",rows(c,"SELECT action,mode,source,language,outcome,SUM(n) AS n,SUM(duration)/NULLIF(SUM(n),0) AS avgms FROM ba_hours WHERE "+w+" AND feature='search' AND kind IN ('form_submit','button_click') GROUP BY action,mode,source,language,outcome ORDER BY n DESC",a));
            result.put("queries",rows(c,"SELECT mode,query,SUM(n) AS n,COUNT(DISTINCT actor) AS users FROM ba_queries WHERE "+w+" GROUP BY mode,query ORDER BY n DESC LIMIT 30",a));
            result.put("errors",rows(c,"SELECT feature,action,outcome,SUM(n) AS n FROM ba_hours WHERE "+w+" AND kind IN ('operation_failure','operation_denied') GROUP BY feature,action,outcome ORDER BY n DESC LIMIT 50",a));
            result.put("funnels",rows(c,"SELECT feature,kind,action,SUM(n) AS n FROM ba_hours WHERE "+w+" AND feature IN ('link','premium','support') AND kind IN ('flow_start','form_submit','operation_success','flow_complete') GROUP BY feature,kind,action ORDER BY feature,kind",a));
            var flowArgs=new ArrayList<Object>(List.of(q.end(repository.zone)));flowArgs.addAll(a);
            result.put("flowConversions",rows(c,"SELECT feature,COUNT(*) AS starts,COUNT(DISTINCT actor) AS users,SUM(CASE WHEN submitted THEN 1 ELSE 0 END) AS submits,SUM(CASE WHEN success AND last_at<? THEN 1 ELSE 0 END) AS completed FROM ba_flows WHERE "+where(q,"started")+" GROUP BY feature",flowArgs));
            result.put("roles",rows(c,"SELECT role,COUNT(DISTINCT actor) AS users,SUM(actions) AS actions FROM ba_user_hours WHERE "+w+" GROUP BY role",a));
            result.put("admin",rows(c,"SELECT role,feature,action,kind,SUM(n) AS n FROM ba_hours WHERE "+w+" AND role<>'player' GROUP BY role,feature,action,kind ORDER BY n DESC LIMIT 100",a));
            result.put("heatmap",rows(c,"SELECT dow,hour_of_day,SUM(n) AS n FROM ba_hours WHERE "+w+" AND kind IN ('button_click','form_submit') GROUP BY dow,hour_of_day",a));
            long days=ChronoUnit.DAYS.between(q.from(),q.until()),step=days<=2?3600:days<=90?86400:days<=730?7*86400:30*86400;
            long anchor=q.start(repository.zone);String bucket="FLOOR((bucket-"+anchor+")/"+step+")";
            result.put("timelineStepSeconds",step);
            String month="FORMATDATETIME(DATEADD('SECOND',bucket,TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00Z'),'yyyy-MM','en','"+repository.zone.getId()+"')";
            result.put("months",rows(c,"SELECT "+month+" AS month_key,SUM(CASE WHEN kind IN ('button_click','form_submit') THEN n ELSE 0 END) AS actions,SUM(CASE WHEN kind='form_submit' AND feature='search' THEN n ELSE 0 END) AS searches FROM ba_hours WHERE "+w+" GROUP BY "+month+" ORDER BY month_key",a));
            result.put("timeline",rows(c,"SELECT "+bucket+" AS point,SUM(CASE WHEN kind IN ('button_click','form_submit') THEN n ELSE 0 END) AS actions,SUM(CASE WHEN feature='search' AND mode='item' AND kind='form_submit' AND action='search.result' THEN n ELSE 0 END) AS items,SUM(CASE WHEN feature='search' AND mode='player' AND kind='form_submit' AND action='search.result' THEN n ELSE 0 END) AS players,SUM(CASE WHEN kind IN ('operation_failure','operation_denied') THEN n ELSE 0 END) AS errors FROM ba_hours WHERE "+w+" GROUP BY "+bucket+" ORDER BY point",a));
            result.put("featureTimeline",rows(c,"SELECT "+bucket+" AS point,feature,SUM(n) AS actions FROM ba_hours WHERE "+w+" AND kind IN ('button_click','form_submit') GROUP BY "+bucket+",feature ORDER BY point",a));
            result.put("audience",rows(c,"SELECT "+bucket+" AS point,COUNT(DISTINCT actor) AS users,COUNT(DISTINCT CASE WHEN first_at>=bucket AND first_at<bucket+3600 THEN actor END) AS newusers FROM (SELECT h.bucket,h.actor,u.first_at FROM ba_user_hours h JOIN ba_users u ON u.actor=h.actor WHERE "+w.replace("bucket","h.bucket")+") GROUP BY "+bucket+" ORDER BY point",a));
            result.put("retention",retention(c,q,now));
            var windows=new LinkedHashMap<String,Object>();
            for(int daysWindow:new int[]{1,7,30}) {
                var window=new BotAnalyticsQuery(q.until().minusDays(daysWindow),q.until(),q.role(),q.feature());
                windows.put(daysWindow==1?"dau":daysWindow==7?"wau":"mau",first(c,"SELECT COUNT(DISTINCT actor) AS n FROM ba_user_hours WHERE "+where(window,"bucket"),args(window)).get("n"));
            }
            result.put("activeUsers",windows);
            var latest=first(c,"SELECT COALESCE(SUM(dropped),0) AS dropped,COALESCE(SUM(failures),0) AS failures,COALESCE(SUM(gap_seconds),0) AS gapseconds FROM ba_health WHERE at>=? AND at<?",List.of(q.start(repository.zone),q.end(repository.zone)));result.put("health",latest);
            result.put("collectionNote","Учитываются только действия после включения. История из сообщений и консоли не восстанавливается. Сессия завершается после 30 минут бездействия. Числа воронок — события этапов, а не обещание конверсии одной заявки.");
            return result;
        }finally{deadline.remove();}
    }
    private List<Map<String,Object>> retention(Connection c,BotAnalyticsQuery q,long now)throws SQLException {
        var result=new ArrayList<Map<String,Object>>();LocalDate today=Instant.ofEpochSecond(now).atZone(repository.zone).toLocalDate();
        for(int delay:List.of(1,7,30)) {
            LocalDate cutoff=today.minusDays(delay);long cutoffEpoch=cutoff.atStartOfDay(repository.zone).toEpochSecond();
            // Incomplete cohorts (including today's return window) are excluded from numerator and denominator.
            String dimensions=(q.role().equals("all")?"":" AND d.role=?")+(q.feature().equals("all")?"":" AND d.feature=?");
            var params=new ArrayList<Object>(List.of(q.from(),q.until(),cutoff));if(!q.role().equals("all"))params.add(q.role());if(!q.feature().equals("all"))params.add(q.feature());
            var counts=first(c,"SELECT COUNT(*) AS eligible,COALESCE(SUM(CASE WHEN EXISTS(SELECT 1 FROM ba_user_days d WHERE d.actor=u.actor AND d.day_at=DATEADD('DAY',"+delay+",u.first_day)"+dimensions+") THEN 1 ELSE 0 END),0) AS returned FROM ba_users u WHERE u.first_day>=? AND u.first_day<? AND u.first_day<? AND EXISTS(SELECT 1 FROM ba_user_days d WHERE d.actor=u.actor AND d.day_at=u.first_day"+dimensions+")",retentionArgs(params,q));
            long eligible=((Number)counts.get("eligible")).longValue(),returned=((Number)counts.get("returned")).longValue();
            result.add(Map.of("day",delay,"eligible",eligible,"returned",returned,"percent",eligible==0?0:100.0*returned/eligible));
        }
        return result;
    }
    private List<Object> retentionArgs(List<Object> params,BotAnalyticsQuery q) {
        var args=new ArrayList<Object>();if(!q.role().equals("all"))args.add(q.role());if(!q.feature().equals("all"))args.add(q.feature());args.addAll(params);return args;
    }
}

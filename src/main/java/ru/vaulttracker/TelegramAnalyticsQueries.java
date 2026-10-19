package ru.vaulttracker;

import java.sql.*;
import java.time.*;
import java.time.temporal.*;
import java.math.*;
import java.nio.file.Files;
import java.util.*;

/** Read-only reports over daily/hourly/user aggregates. Never SELECTs message bodies or raw message rows. */
final class TelegramAnalyticsQueries {
    private final TelegramAnalyticsRepository repo;
    private final TelegramAnalyticsConfig config;
    TelegramAnalyticsQueries(TelegramAnalyticsRepository repo,TelegramAnalyticsConfig config){this.repo=repo;this.config=config;}
    private String scope(TelegramAnalyticsQuery q,boolean authors) {
        String chats=q.chat()==null?String.join(",",config.chats().stream().sorted().map(String::valueOf).toList()):q.chat().toString();
        return "chat IN ("+chats+")"+(q.topic()!=null?" AND topic="+q.topic():authors?" AND topic=-1":""); // validated numeric IDs only
    }
    static List<Map<String,Object>> rows(Connection db,String sql,Object... args) throws SQLException {
        try(var s=db.prepareStatement(sql)){s.setQueryTimeout(5);TelegramAnalyticsRepository.bind(s,args);try(var r=s.executeQuery()){
            List<Map<String,Object>> rows=new ArrayList<>();var meta=r.getMetaData();while(r.next()){Map<String,Object> row=new LinkedHashMap<>();for(int i=1;i<=meta.getColumnCount();i++){Object value=r.getObject(i);if(value instanceof java.sql.Date date)value=date.toLocalDate().toString();row.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT),value);}rows.add(row);}return rows;
        }}
    }
    static long n(Map<String,Object> row,String key){Object value=row.get(key);return value instanceof Number number?number.longValue():0;}
    static Long nullableLong(Map<String,Object> row,String key){return row.get(key)==null?null:n(row,key);}
    static Map<String,Object> one(Connection db,String sql,Object... args)throws SQLException{return rows(db,sql,args).getFirst();}
    static BigDecimal divide(long a,long b){return b==0?null:BigDecimal.valueOf(a).divide(BigDecimal.valueOf(b),2,RoundingMode.HALF_UP);}
    static BigDecimal percent(long a,long b){return divide(a*100,b);}
    Map<String,Object> options() throws Exception {
        try(var db=repo.read()) {
            String allow=String.join(",",config.chats().stream().sorted().map(String::valueOf).toList());
            return Map.of("timezone",config.zone().getId(),"chats",rows(db,"SELECT chat,title,kind,started,last_event FROM ta_chats WHERE chat IN ("+allow+") ORDER BY chat"),
                    "topics",rows(db,"SELECT DISTINCT d.chat,d.topic,t.title FROM ta_daily d LEFT JOIN ta_topics t ON t.chat=d.chat AND t.topic=d.topic WHERE d.chat IN ("+allow+") ORDER BY d.chat,d.topic LIMIT 10001"));
        }
    }
    LocalDate earliest() throws Exception {
        try(var db=repo.read()){Long at=nullableLong(one(db,"SELECT MIN(started) a FROM ta_chats WHERE "+scope(new TelegramAnalyticsQuery(null,null,null,null,true,"month","active",0),false)),"a");return at==null?LocalDate.now(config.zone()):Instant.ofEpochSecond(at).atZone(config.zone()).toLocalDate();}
    }
    private Map<String,Object> counts(Connection db,TelegramAnalyticsQuery q,LocalDate from,LocalDate to) throws Exception {
        var count=one(db,"SELECT COALESCE(SUM(n),0) messages,COALESCE(SUM(replies),0) replies,COALESCE(SUM(forwards),0) forwards,COALESCE(SUM(media),0) media FROM ta_daily WHERE "+scope(q,false)+" AND kind<>'service' AND day>=? AND day<?",from.toString(),to.toString());
        String users=scope(q,true);long active=n(one(db,q.all()&&from.equals(q.from())?"SELECT COUNT(DISTINCT author) n FROM ta_authors WHERE "+users:"SELECT COUNT(DISTINCT author) n FROM ta_user_days WHERE "+users+" AND day>='"+from+"' AND day<'"+to+"'"),"n");
        count.put("authors",active);count.put("messagesPerAuthor",divide(n(count,"messages"),active));count.put("mediaShare",percent(n(count,"media"),n(count,"messages")));count.put("replyRate",percent(n(count,"replies"),n(count,"messages")));count.put("forwardShare",percent(n(count,"forwards"),n(count,"messages")));return count;
    }
    private List<Map<String,Object>> coverage(Connection db,TelegramAnalyticsQuery q) throws Exception {
        var chats=rows(db,"SELECT chat,title,kind,started,last_event,heartbeat FROM ta_chats WHERE "+scope(new TelegramAnalyticsQuery(q.chat(),null,null,null,false,"day","active",0),false)+" ORDER BY chat");
        for(var chat:chats) {chat.put("collectionStartedAt",chat.get("started"));chat.put("earliestReliableAt",chat.get("started"));chat.put("gaps",rows(db,"SELECT a,b,reason FROM ta_gaps WHERE chat=? ORDER BY a DESC LIMIT 1000",chat.get("chat")));chat.put("gapCount",n(one(db,"SELECT COUNT(*) n FROM ta_gaps WHERE chat=?",chat.get("chat")),"n"));chat.put("snapshotCoverage",one(db,"SELECT MIN(at) first,MAX(at) last,COUNT(*) points FROM ta_snapshots WHERE chat=?",chat.get("chat")));}
        return chats;
    }
    @SuppressWarnings("unchecked") private boolean complete(List<Map<String,Object>> coverage,LocalDate from,LocalDate to,long now) {
        long a=from.atStartOfDay(config.zone()).toEpochSecond(),b=to.atStartOfDay(config.zone()).toEpochSecond();
        if(b>now||coverage.isEmpty())return false;
        for(var c:coverage){if(n(c,"started")>a||n(c,"heartbeat")<b||n(c,"gapCount")>((List<?>)c.get("gaps")).size())return false;for(var gap:(List<Map<String,Object>>)c.get("gaps"))if(n(gap,"a")<b&&n(gap,"b")>a)return false;}
        return true;
    }
    Map<String,Object> report(TelegramAnalyticsQuery q,long now) throws Exception {
        try(var db=repo.read()) {
            var result=new LinkedHashMap<String,Object>();var coverage=coverage(db,q);result.put("coverage",coverage);result.put("timezone",config.zone().getId());
            result.put("from",q.from().toString());result.put("to",q.to().minusDays(1).toString());result.put("grain",q.grain());result.put("generatedAt",now);
            long started=coverage.stream().mapToLong(c->n(c,"started")).min().orElse(now);
            boolean hasCoverage=q.to().atStartOfDay(config.zone()).toEpochSecond()>started;
            result.put("hasCoverage",hasCoverage);result.put("partial",!complete(coverage,q.from(),q.to(),now));result.put("started",started);
            var summary=counts(db,q,q.from(),q.to());
            LocalDate observedFrom=q.from().isBefore(Instant.ofEpochSecond(started).atZone(config.zone()).toLocalDate())?Instant.ofEpochSecond(started).atZone(config.zone()).toLocalDate():q.from();
            long days=Math.max(1,ChronoUnit.DAYS.between(observedFrom,q.to()));summary.put("messagesPerDay",divide(n(summary,"messages"),days));summary.put("observedDays",days);
            String users=scope(q,true),range=" AND day>='"+q.from()+"' AND day<'"+q.to()+"'";
            String chatAuthors=scope(new TelegramAnalyticsQuery(q.chat(),null,null,null,false,"day","active",0),true);
            long newAuthors=n(one(db,"WITH firsts AS(SELECT author,MIN(first_day) first_day FROM ta_authors WHERE "+chatAuthors+" GROUP BY author) SELECT COUNT(DISTINCT d.author) n FROM ta_user_days d JOIN firsts f ON f.author=d.author WHERE d."+users.replace(" AND "," AND d.")+" AND d.day>=? AND d.day<? AND f.first_day>=?",q.from().toString(),q.to().toString(),q.from().toString()),"n");
            summary.put("newAuthors",newAuthors);summary.put("returningAuthors",Math.max(0,n(summary,"authors")-newAuthors));summary.put("returningShare",percent(Math.max(0,n(summary,"authors")-newAuthors),n(summary,"authors")));
            summary.put("multiDayAuthors",n(one(db,"SELECT COUNT(*) n FROM(SELECT author FROM ta_user_days WHERE "+users+range+" GROUP BY author HAVING COUNT(DISTINCT day)>1)"),"n"));
            LocalDate end=q.to().minusDays(1);for(int window:new int[]{1,7,30})summary.put(window==1?"dau":window==7?"wau":"mau",n(one(db,"SELECT COUNT(DISTINCT author) n FROM ta_user_days WHERE "+users+" AND day>=? AND day<?",end.minusDays(window-1).toString(),q.to().toString()),"n"));
            summary.put("activeTopics",n(one(db,"SELECT COUNT(*) n FROM(SELECT chat,topic FROM ta_daily WHERE "+scope(q,false)+" AND kind<>'service'"+range+" GROUP BY chat,topic HAVING SUM(n)>0)"),"n"));
            result.put("summary",summary);
            long span=ChronoUnit.DAYS.between(q.from(),q.to());LocalDate previousFrom=q.from().minusDays(span);
            Map<String,Object> previous=!q.all()&&complete(coverage,previousFrom,q.from(),now)&&complete(coverage,q.from(),q.to(),now)?counts(db,q,previousFrom,q.from()):null;
            result.put("previous",previous);result.put("comparisonFrom",previousFrom.toString());result.put("comparisonTo",q.from().minusDays(1).toString());
            Map<String,Object> changes=new LinkedHashMap<>();for(String metric:List.of("messages","authors"))changes.put(metric,previous!=null&&n(previous,metric)>0?percent(n(summary,metric)-n(previous,metric),n(previous,metric)):null);result.put("changes",changes);
            summary.put("averageActivePerDay",divide(n(one(db,"SELECT COUNT(*) n FROM(SELECT day,author FROM ta_user_days WHERE "+users+range+" GROUP BY day,author)"),"n"),days));
            result.put("bestMonth",rows(db,"SELECT SUBSTRING(day,1,7) month_key,SUM(n) messages FROM ta_daily WHERE "+scope(q,false)+" AND kind<>'service'"+range+" GROUP BY SUBSTRING(day,1,7) ORDER BY messages DESC LIMIT 1"));
            result.put("timeline",timeline(db,q));result.put("heatmap",heatmap(db,q));var topics=topics(db,q,previousFrom,previous!=null);result.put("topics",topics);
            result.put("adTopics",q.sort().equals("active")&&q.offset()==0?topics.get("rows"):topics(db,new TelegramAnalyticsQuery(q.chat(),q.topic(),q.from(),q.to(),q.all(),q.grain(),"active",0),previousFrom,previous!=null).get("rows"));
            result.put("content",rows(db,"SELECT kind,SUM(n) n FROM ta_daily WHERE "+scope(q,false)+range+" GROUP BY kind ORDER BY n DESC"));
            result.put("audience",audience(db,q,previousFrom,previous!=null,now));result.put("retention",retention(db,q,coverage,now));
            var diagnostic=one(db,"SELECT COUNT(*) metadataRows FROM ta_messages"); // H2 exact table count uses its row counter, not a scan.
            diagnostic.put("bytes",Files.exists(repo.file)?Files.size(repo.file):0L);diagnostic.put("schema",1);diagnostic.put("retention","forever");
            diagnostic.putAll(one(db,"SELECT MIN(at) earliest,MAX(at) latest FROM ta_messages"));result.put("diagnostics",diagnostic);
            return result;
        }
    }
    private String bucket(TelegramAnalyticsQuery q){return q.grain().equals("month")?"SUBSTRING(day,1,7)":q.grain().equals("week")?"CAST(DATE_TRUNC('WEEK',CAST(day AS DATE)) AS DATE)":"day";}
    private List<Map<String,Object>> timeline(Connection db,TelegramAnalyticsQuery q) throws Exception {
        String range=" AND day>=? AND day<?",bucket=bucket(q);
        var counts=rows(db,"SELECT "+bucket+" bucket,SUM(n) messages FROM ta_daily WHERE "+scope(q,false)+" AND kind<>'service'"+range+" GROUP BY "+bucket+" ORDER BY bucket",q.from().toString(),q.to().toString());
        var users=rows(db,"SELECT "+bucket+" bucket,COUNT(DISTINCT author) authors FROM ta_user_days WHERE "+scope(q,true)+range+" GROUP BY "+bucket,q.from().toString(),q.to().toString());
        Map<String,Long> authors=new HashMap<>();for(var row:users)authors.put(row.get("bucket").toString(),n(row,"authors"));
        for(var row:counts)row.put("authors",authors.getOrDefault(row.get("bucket").toString(),0L));return counts;
    }
    private List<Map<String,Object>> heatmap(Connection db,TelegramAnalyticsQuery q) throws Exception {
        return rows(db,"SELECT dow,hour,SUM(n) n FROM "+(q.all()?"ta_heatmap":"ta_hours")+" WHERE "+scope(q,false)+(q.all()?"":" AND day>='"+q.from()+"' AND day<'"+q.to()+"'")+" GROUP BY dow,hour ORDER BY dow,hour");
    }
    private Map<String,Object> topics(Connection db,TelegramAnalyticsQuery q,LocalDate previous,boolean comparable) throws Exception {
        String where=scope(q,false),from=q.from().toString(),to=q.to().toString();
        String order=switch(comparable?q.sort():q.sort().equals("name")?"name":"active"){case "growth"->"(COALESCE(c.n,0)-COALESCE(p.n,0)) DESC";case "fall"->"(COALESCE(c.n,0)-COALESCE(p.n,0)) ASC";case "name"->"COALESCE(t.title,'') ASC";default->"COALESCE(c.n,0) DESC";};
        var rows=rows(db,"WITH c AS(SELECT chat,topic,SUM(n) n FROM ta_daily WHERE "+where+" AND kind<>'service' AND day>=? AND day<? GROUP BY chat,topic),p AS(SELECT chat,topic,SUM(n) n FROM ta_daily WHERE "+where+" AND kind<>'service' AND day>=? AND day<? GROUP BY chat,topic),keys AS(SELECT chat,topic FROM c"+(comparable?" UNION SELECT chat,topic FROM p":"")+") SELECT k.chat,k.topic,COALESCE(c.n,0) messages,p.n previous,t.title FROM keys k LEFT JOIN c ON c.chat=k.chat AND c.topic=k.topic LEFT JOIN p ON k.chat=p.chat AND k.topic=p.topic LEFT JOIN ta_topics t ON t.chat=k.chat AND t.topic=k.topic ORDER BY "+order+",k.chat,k.topic LIMIT 51 OFFSET ?",from,to,previous.toString(),from,q.offset());
        boolean more=rows.size()>50;if(more)rows.removeLast();
        long total=n(one(db,"SELECT COALESCE(SUM(n),0) n FROM ta_daily WHERE "+where+" AND kind<>'service' AND day>=? AND day<?",from,to),"n");
        for(var row:rows){row.put("authors",n(one(db,"SELECT COUNT(DISTINCT author) n FROM ta_user_days WHERE chat=? AND topic=? AND day>=? AND day<?",row.get("chat"),row.get("topic"),from,to),"n"));row.put("share",percent(n(row,"messages"),total));row.put("messagesPerDay",divide(n(row,"messages"),Math.max(1,ChronoUnit.DAYS.between(q.from(),q.to()))));row.put("change",comparable&&n(row,"previous")>0?percent(n(row,"messages")-n(row,"previous"),n(row,"previous")):null);if(!comparable)row.put("previous",null);}
        return Map.of("rows",rows,"hasMore",more,"offset",q.offset(),"growthAvailable",comparable);
    }
    private Map<String,Object> audience(Connection db,TelegramAnalyticsQuery q,LocalDate previous,boolean comparable,long now) throws Exception {
        var out=new LinkedHashMap<String,Object>();var series=new ArrayList<Map<String,Object>>();long current=0,delta=0,baseline=0;boolean hasCurrent=true,hasDelta=true,allExact=true;long joins=0,leaves=0;
        String chatScope=scope(new TelegramAnalyticsQuery(q.chat(),null,null,null,false,"day","active",0),false);
        long a=q.from().atStartOfDay(config.zone()).toEpochSecond(),b=q.to().atStartOfDay(config.zone()).toEpochSecond();
        Long previousDelta=0L;
        for(var chat:rows(db,"SELECT chat,title,kind FROM ta_chats WHERE "+chatScope)) {
            long id=n(chat,"chat");var latest=rows(db,"SELECT at,n FROM ta_snapshots WHERE chat=? ORDER BY at DESC LIMIT 1",id);
            boolean fresh=!latest.isEmpty()&&now-n(latest.getFirst(),"at")<=config.snapshotMinutes()*120L;
            if(fresh)current+=n(latest.getFirst(),"n");else hasCurrent=false;
            String bucket=bucket(q);
            var points=rows(db,"SELECT "+bucket+" bucket,MAX(at) at FROM ta_snapshots WHERE chat=? AND at>=? AND at<? GROUP BY "+bucket+" ORDER BY bucket",id,a,b);
            for(var point:points){var row=one(db,"SELECT chat,at,n FROM ta_snapshots WHERE chat=? AND at=?",id,point.get("at"));series.add(row);}
            var change=snapshotDelta(db,id,a,b);if(change==null)hasDelta=false;else {delta+=change;baseline+=n(rows(db,"SELECT n FROM ta_snapshots WHERE chat=? AND at>=? AND at<? ORDER BY at LIMIT 1",id,a,b).getFirst(),"n");}
            if(comparable){Long old=snapshotDelta(db,id,previous.atStartOfDay(config.zone()).toEpochSecond(),a);if(old==null)previousDelta=null;else if(previousDelta!=null)previousDelta+=old;}
            long observed=n(one(db,"SELECT COUNT(*) n FROM ta_members WHERE chat=? AND at>=? AND at<?",id,a,b),"n");
            if(chat.get("kind").equals("channel")||observed==0)allExact=false;
            var counts=one(db,"SELECT COALESCE(SUM(joins),0) joins,COALESCE(SUM(leaves),0) leaves FROM ta_member_days WHERE chat=? AND day>=? AND day<?",id,q.from().toString(),q.to().toString());joins+=n(counts,"joins");leaves+=n(counts,"leaves");
        }
        out.put("current",hasCurrent?current:null);out.put("delta",hasDelta?delta:null);out.put("previousDelta",comparable?previousDelta:null);out.put("series",series);out.put("observedJoins",allExact?joins:null);out.put("observedLeaves",allExact?leaves:null);out.put("topicIndependent",true);
        out.put("observedNet",allExact?joins-leaves:null);out.put("growthPercent",hasDelta&&baseline>0?percent(delta,baseline):null);return out;
    }
    private Long snapshotDelta(Connection db,long chat,long a,long b) throws Exception {
        var first=rows(db,"SELECT at,n FROM ta_snapshots WHERE chat=? AND at>=? AND at<? ORDER BY at LIMIT 1",chat,a,b);
        var last=rows(db,"SELECT at,n FROM ta_snapshots WHERE chat=? AND at>=? AND at<? ORDER BY at DESC LIMIT 1",chat,a,b);
        if(first.isEmpty()||last.isEmpty()||n(first.getFirst(),"at")==n(last.getFirst(),"at"))return null;
        return n(last.getFirst(),"n")-n(first.getFirst(),"n");
    }
    private Map<String,Object> retention(Connection db,TelegramAnalyticsQuery q,List<Map<String,Object>> coverage,long now) throws Exception {
        String authors=scope(q,true);
        String chatAuthors=scope(new TelegramAnalyticsQuery(q.chat(),null,null,null,false,"day","active",0),true);
        var cohorts=rows(db,"WITH firsts AS(SELECT author,MIN(first_day) first_day FROM ta_authors WHERE "+chatAuthors+" GROUP BY author),returns AS(SELECT f.author,f.first_day,MIN(d.day) next_day FROM firsts f LEFT JOIN ta_user_days d ON d.author=f.author AND d."+authors.replace(" AND "," AND d.")+" AND d.day>f.first_day WHERE f.first_day>=? AND f.first_day<? AND EXISTS(SELECT 1 FROM ta_user_days a WHERE a.author=f.author AND a."+authors.replace(" AND "," AND a.")+" AND a.day>=? AND a.day<?) GROUP BY f.author,f.first_day) SELECT first_day,COUNT(*) n,SUM(CASE WHEN next_day IS NOT NULL AND CAST(next_day AS DATE)<=DATEADD('DAY',7,CAST(first_day AS DATE)) THEN 1 ELSE 0 END) returned FROM returns GROUP BY first_day",q.from().toString(),q.to().toString(),q.from().toString(),q.to().toString());
        long eligible=0,returned=0,pending=0;
        for(var row:cohorts){LocalDate first=LocalDate.parse(row.get("first_day").toString());if(complete(coverage,first,first.plusDays(8),now)){eligible+=n(row,"n");returned+=n(row,"returned");}else pending+=n(row,"n");}
        Map<String,Object> out=new LinkedHashMap<>();out.put("eligible",eligible);out.put("returned",returned);out.put("incomplete",pending);out.put("rate",percent(returned,eligible));return out;
    }
}

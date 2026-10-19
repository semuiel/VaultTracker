package ru.vaulttracker;

import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Independent H2 metadata store. No purge/expiry and no dependency on guard-data transactions. */
final class TelegramAnalyticsRepository implements AutoCloseable {
    interface Write {void apply(TelegramAnalyticsRepository repository) throws Exception;}
    final Path file;
    final ZoneId zone;
    private final String url;
    private final Connection writer;
    private final byte[] secret;
    TelegramAnalyticsRepository(Path folder,ZoneId zone) throws Exception {
        Files.createDirectories(folder);this.zone=zone;file=folder.resolve("telegram-analytics.mv.db");
        // Calendar dimension names are intentionally identifiers, not SQL DAY/HOUR functions.
        url="jdbc:h2:"+folder.resolve("telegram-analytics").toAbsolutePath()+";DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=2000;NON_KEYWORDS=DAY,HOUR";
        writer=DriverManager.getConnection(url,"sa","");
        try {
            exec(writer,"CREATE TABLE IF NOT EXISTS ta_settings(k VARCHAR(40) PRIMARY KEY,v VARCHAR(512) NOT NULL)");
            String version=setting("version");if(version!=null&&!version.equals("1"))throw new SQLException("Unsupported analytics schema");
            for(String sql:List.of(
                "CREATE TABLE IF NOT EXISTS ta_chats(chat BIGINT PRIMARY KEY,title VARCHAR(200) NOT NULL,kind VARCHAR(20) NOT NULL,started BIGINT NOT NULL,last_event BIGINT NOT NULL,heartbeat BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS ta_topics(chat BIGINT,topic INT,title VARCHAR(200),PRIMARY KEY(chat,topic))",
                "CREATE TABLE IF NOT EXISTS ta_gaps(chat BIGINT,a BIGINT,b BIGINT,reason VARCHAR(40),PRIMARY KEY(chat,a,reason))",
                "CREATE TABLE IF NOT EXISTS ta_messages(chat BIGINT,msg INT,topic INT NOT NULL,at BIGINT NOT NULL,revision BIGINT NOT NULL,author VARCHAR(64),kind VARCHAR(24) NOT NULL,reply BOOLEAN NOT NULL,forwarded BOOLEAN NOT NULL,media BOOLEAN NOT NULL,text_length INT NOT NULL,PRIMARY KEY(chat,msg))",
                "CREATE INDEX IF NOT EXISTS ta_msg_time ON ta_messages(chat,at)",
                "CREATE INDEX IF NOT EXISTS ta_msg_topic_time ON ta_messages(chat,topic,at)",
                "CREATE INDEX IF NOT EXISTS ta_msg_user_time ON ta_messages(author,at)",
                "CREATE INDEX IF NOT EXISTS ta_msg_kind ON ta_messages(kind,at)",
                "CREATE INDEX IF NOT EXISTS ta_msg_at ON ta_messages(at)",
                "CREATE TABLE IF NOT EXISTS ta_daily(chat BIGINT,topic INT,day VARCHAR(10),kind VARCHAR(24),n BIGINT NOT NULL,replies BIGINT NOT NULL,forwards BIGINT NOT NULL,media BIGINT NOT NULL,PRIMARY KEY(chat,topic,day,kind))",
                "CREATE INDEX IF NOT EXISTS ta_daily_date ON ta_daily(day,chat,topic)",
                "CREATE TABLE IF NOT EXISTS ta_hours(chat BIGINT,topic INT,day VARCHAR(10),dow INT,hour INT,n BIGINT NOT NULL,PRIMARY KEY(chat,topic,day,dow,hour))",
                "CREATE INDEX IF NOT EXISTS ta_hours_date ON ta_hours(day,chat,topic)",
                "CREATE TABLE IF NOT EXISTS ta_heatmap(chat BIGINT,topic INT,dow INT,hour INT,n BIGINT NOT NULL,PRIMARY KEY(chat,topic,dow,hour))",
                "CREATE TABLE IF NOT EXISTS ta_user_days(chat BIGINT,topic INT,day VARCHAR(10),author VARCHAR(64),n BIGINT NOT NULL,PRIMARY KEY(chat,topic,day,author))",
                "CREATE INDEX IF NOT EXISTS ta_user_history ON ta_user_days(chat,topic,author,day)",
                "CREATE TABLE IF NOT EXISTS ta_authors(chat BIGINT,topic INT,author VARCHAR(64),first_day VARCHAR(10),last_day VARCHAR(10),days BIGINT,PRIMARY KEY(chat,topic,author))",
                "CREATE INDEX IF NOT EXISTS ta_first_authors ON ta_authors(chat,topic,first_day)",
                "CREATE TABLE IF NOT EXISTS ta_snapshots(chat BIGINT,bucket BIGINT,at BIGINT,n BIGINT NOT NULL,day VARCHAR(10) NOT NULL,PRIMARY KEY(chat,bucket))",
                "CREATE INDEX IF NOT EXISTS ta_snapshot_time ON ta_snapshots(chat,at)",
                "CREATE TABLE IF NOT EXISTS ta_members(chat BIGINT,at BIGINT,author VARCHAR(64),joined BOOLEAN,PRIMARY KEY(chat,at,author,joined))",
                "CREATE TABLE IF NOT EXISTS ta_member_days(chat BIGINT,day VARCHAR(10),joins BIGINT NOT NULL,leaves BIGINT NOT NULL,PRIMARY KEY(chat,day))"
            ))exec(writer,sql);
            String oldZone=setting("timezone");if(oldZone!=null&&!oldZone.equals(zone.getId()))throw new SQLException("Analytics timezone differs from stored aggregates");
            put("timezone",zone.getId());put("version","1");
            String key=setting("pseudonym_key");if(key==null){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);key=Base64.getEncoder().encodeToString(bytes);put("pseudonym_key",key);}
            secret=Base64.getDecoder().decode(key);writer.setAutoCommit(false);
        }catch(Exception failure){writer.close();throw failure;}
    }
    private String setting(String key) throws SQLException {try(var s=writer.prepareStatement("SELECT v FROM ta_settings WHERE k=?")){s.setString(1,key);try(var r=s.executeQuery()){return r.next()?r.getString(1):null;}}}
    private void put(String key,String value) throws SQLException {exec(writer,"MERGE INTO ta_settings KEY(k) VALUES(?,?)",key,value);}
    synchronized Map<Long,Long> collectionStarts() throws SQLException {
        Map<Long,Long> starts=new HashMap<>();try(var s=writer.prepareStatement("SELECT chat,started FROM ta_chats");var r=s.executeQuery()){while(r.next())starts.put(r.getLong(1),r.getLong(2));}return Map.copyOf(starts);
    }
    String pseudonym(long id) {
        try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(("telegram-author:"+id).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception failure){throw new IllegalStateException("Analytics identity unavailable");}
    }
    Connection read() throws SQLException {var db=DriverManager.getConnection(url,"sa","");db.setReadOnly(true);db.setAutoCommit(false);db.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);return db;}
    synchronized void batch(List<Write> writes) throws Exception {
        try{for(var write:writes)write.apply(this);writer.commit();}catch(Exception failure){writer.rollback();throw failure;}
    }
    void begin(Set<Long> chats,long now) throws SQLException {
        for(long chat:chats) {
            try(var s=writer.prepareStatement("SELECT heartbeat FROM ta_chats WHERE chat=?")){s.setLong(1,chat);try(var r=s.executeQuery()){if(r.next()){long last=r.getLong(1);if(last<now)gap(chat,last,now,"restart_or_disabled");}else exec(writer,"INSERT INTO ta_chats VALUES(?,?,?, ?,0,?)",chat,"","unknown",now,now);}}
            exec(writer,"UPDATE ta_chats SET heartbeat=? WHERE chat=?",now,chat);
        }
    }
    void heartbeat(Set<Long> chats,long now) throws SQLException {for(long chat:chats)exec(writer,"UPDATE ta_chats SET heartbeat=? WHERE chat=?",now,chat);}
    void gap(long chat,long a,long b,String reason) throws SQLException {exec(writer,"MERGE INTO ta_gaps KEY(chat,a,reason) VALUES(?,?,?,?)",chat,a,Math.max(a+1,b),reason);}
    void topicName(TelegramAnalyticsEvent.TopicName topic) throws SQLException {
        if(topic.title().isBlank())return;
        if(topic.renamed())exec(writer,"MERGE INTO ta_topics KEY(chat,topic) VALUES(?,?,?)",topic.chat(),topic.topic(),topic.title());
        else exec(writer,"INSERT INTO ta_topics(chat,topic,title) SELECT ?,?,? WHERE NOT EXISTS (SELECT 1 FROM ta_topics WHERE chat=? AND topic=?)",topic.chat(),topic.topic(),topic.title(),topic.chat(),topic.topic());
    }
    void message(TelegramAnalyticsEvent e) throws SQLException {
        if(!e.topicTitle().isBlank())topicName(new TelegramAnalyticsEvent.TopicName(e.chat(),e.topic(),e.topicTitle(),false));
        TelegramAnalyticsEvent old=null;
        try(var s=writer.prepareStatement("SELECT topic,at,revision,author,kind,reply,forwarded,media,text_length FROM ta_messages WHERE chat=? AND msg=?")) {
            bind(s,e.chat(),e.message());try(var r=s.executeQuery()){if(r.next())old=new TelegramAnalyticsEvent(e.chat(),e.message(),r.getInt(1),r.getLong(2),r.getLong(3),r.getString(4),r.getString(5),r.getBoolean(6),r.getBoolean(7),r.getBoolean(8),r.getInt(9),"","","");}
        }
        if(old!=null&&(e.revision()<old.revision()||e.revision()==old.revision()&&e.topic()==old.topic()&&Objects.equals(e.author(),old.author())&&e.type().equals(old.type())&&e.reply()==old.reply()&&e.forwarded()==old.forwarded()&&e.media()==old.media()&&e.textLength()==old.textLength()))return;
        if(old!=null)adjust(old,-1);
        exec(writer,"MERGE INTO ta_messages KEY(chat,msg) VALUES(?,?,?,?,?,?,?,?,?,?,?)",e.chat(),e.message(),e.topic(),e.at(),e.revision(),e.author(),e.type(),e.reply(),e.forwarded(),e.media(),e.textLength());
        adjust(e,1);
        exec(writer,"UPDATE ta_chats SET title=CASE WHEN ?='' THEN title ELSE ? END,kind=CASE WHEN ?='' THEN kind ELSE ? END,last_event=GREATEST(last_event,?) WHERE chat=?",e.chatTitle(),e.chatTitle(),e.chatType(),e.chatType(),e.at(),e.chat());
    }
    private void adjust(TelegramAnalyticsEvent e,int sign) throws SQLException {
        var local=Instant.ofEpochSecond(e.at()).atZone(zone);String day=local.toLocalDate().toString();int dow=local.getDayOfWeek().getValue()-1,hour=local.getHour();
        counter("ta_daily",new String[]{"chat","topic","day","kind"},new Object[]{e.chat(),e.topic(),day,e.type()},new String[]{"n","replies","forwards","media"},new long[]{sign,e.reply()?sign:0,e.forwarded()?sign:0,e.media()?sign:0});
        if(e.type().equals("service"))return;
        counter("ta_hours",new String[]{"chat","topic","day","dow","hour"},new Object[]{e.chat(),e.topic(),day,dow,hour},new String[]{"n"},new long[]{sign});
        counter("ta_heatmap",new String[]{"chat","topic","dow","hour"},new Object[]{e.chat(),e.topic(),dow,hour},new String[]{"n"},new long[]{sign});
        if(e.author()!=null)for(int topic:new int[]{e.topic(),-1}) {
            long before=0;
            try(var s=writer.prepareStatement("SELECT n FROM ta_user_days WHERE chat=? AND topic=? AND day=? AND author=?")){bind(s,e.chat(),topic,day,e.author());try(var r=s.executeQuery()){if(r.next())before=r.getLong(1);}}
            counter("ta_user_days",new String[]{"chat","topic","day","author"},new Object[]{e.chat(),topic,day,e.author()},new String[]{"n"},new long[]{sign});
            if(sign>0) {
                if(exec(writer,"UPDATE ta_authors SET first_day=LEAST(first_day,?),last_day=GREATEST(last_day,?),days=days+? WHERE chat=? AND topic=? AND author=?",day,day,before==0?1:0,e.chat(),topic,e.author())==0)
                    exec(writer,"INSERT INTO ta_authors VALUES(?,?,?,?,?,1)",e.chat(),topic,e.author(),day,day);
            }else if(before==1) {
                // Only edits which remove an author's last event that day need to rebuild that small indexed history.
                exec(writer,"MERGE INTO ta_authors(chat,topic,author,first_day,last_day,days) KEY(chat,topic,author) SELECT ?,?,?,MIN(day),MAX(day),COUNT(*) FROM ta_user_days WHERE chat=? AND topic=? AND author=?",e.chat(),topic,e.author(),e.chat(),topic,e.author());
                exec(writer,"DELETE FROM ta_authors WHERE chat=? AND topic=? AND author=? AND days=0",e.chat(),topic,e.author());
            }
        }
    }
    // Identifiers below are fixed internal schema constants, never taken from HTTP or Telegram input.
    private void counter(String table,String[] keys,Object[] values,String[] counts,long[] delta) throws SQLException {
        String where=String.join(" AND ",Arrays.stream(keys).map(k->k+"=?").toList());
        String set=String.join(",",Arrays.stream(counts).map(c->c+"="+c+"+?").toList());
        Object[] update=new Object[delta.length+values.length];for(int i=0;i<delta.length;i++)update[i]=delta[i];System.arraycopy(values,0,update,delta.length,values.length);
        if(exec(writer,"UPDATE "+table+" SET "+set+" WHERE "+where,update)==0) {
            if(delta[0]<0)throw new SQLException("Analytics aggregate missing");
            Object[] insert=new Object[values.length+delta.length];System.arraycopy(values,0,insert,0,values.length);for(int i=0;i<delta.length;i++)insert[values.length+i]=delta[i];
            exec(writer,"INSERT INTO "+table+" VALUES("+String.join(",",Collections.nCopies(insert.length,"?"))+")",insert);
        }
        exec(writer,"DELETE FROM "+table+" WHERE "+where+" AND n=0",values);
    }
    void snapshot(long chat,long at,long count,int minutes,String title,String kind) throws SQLException {
        if(count<0)return;
        exec(writer,"MERGE INTO ta_snapshots KEY(chat,bucket) VALUES(?,?,?,?,?)",chat,at/(minutes*60L),at,count,Instant.ofEpochSecond(at).atZone(zone).toLocalDate().toString());
        exec(writer,"UPDATE ta_chats SET title=CASE WHEN ?='' THEN title ELSE ? END,kind=? WHERE chat=?",title,title,kind,chat);
    }
    void membership(long chat,long at,String author,boolean joined) throws SQLException {
        int exists;try(var s=writer.prepareStatement("SELECT COUNT(*) FROM ta_members WHERE chat=? AND at=? AND author=? AND joined=?")){bind(s,chat,at,author,joined);try(var r=s.executeQuery()){r.next();exists=r.getInt(1);}}
        if(exists>0)return;
        exec(writer,"INSERT INTO ta_members VALUES(?,?,?,?)",chat,at,author,joined);
        String day=Instant.ofEpochSecond(at).atZone(zone).toLocalDate().toString();
        if(exec(writer,"UPDATE ta_member_days SET joins=joins+?,leaves=leaves+? WHERE chat=? AND day=?",joined?1:0,joined?0:1,chat,day)==0)
            exec(writer,"INSERT INTO ta_member_days VALUES(?,?,?,?)",chat,day,joined?1:0,joined?0:1);
    }
    static int exec(Connection db,String sql,Object... args) throws SQLException {try(var s=db.prepareStatement(sql)){s.setQueryTimeout(5);bind(s,args);return s.executeUpdate();}}
    static void bind(PreparedStatement s,Object... args) throws SQLException {for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);}
    public synchronized void close() throws SQLException {writer.close();}
}

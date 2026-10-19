package ru.vaulttracker;

import java.nio.file.Path;
import java.sql.*;

/** Small independent H2 store; no live game state and no long-lived connection. */
final class TelegramModerationRepository {
    record News(boolean poll,String url) {}
    private final String url;
    TelegramModerationRepository(Path folder) throws Exception {
        java.nio.file.Files.createDirectories(folder);Class.forName("org.h2.Driver");
        url="jdbc:h2:"+folder.resolve("telegram-moderation").toAbsolutePath()+";DB_CLOSE_ON_EXIT=FALSE";
        try(var db=open();var s=db.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS latest_news(id INT PRIMARY KEY,poll BOOLEAN NOT NULL,url VARCHAR(512) NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS topic_restrictions(chat BIGINT NOT NULL,topic INT NOT NULL,target BIGINT NOT NULL,expires BIGINT NOT NULL,PRIMARY KEY(chat,topic,target))");
            s.execute("ALTER TABLE topic_restrictions ADD COLUMN IF NOT EXISTS actor BIGINT DEFAULT 0 NOT NULL");
        }
    }
    private Connection open() throws SQLException {return DriverManager.getConnection(url,"sa","");}
    synchronized void news(News news) throws SQLException {try(var db=open();var s=db.prepareStatement("MERGE INTO latest_news KEY(id) VALUES(1,?,?)")){s.setBoolean(1,news.poll());s.setString(2,news.url());s.executeUpdate();}}
    synchronized News news() throws SQLException {try(var db=open();var s=db.createStatement();var r=s.executeQuery("SELECT poll,url FROM latest_news WHERE id=1")){return r.next()?new News(r.getBoolean(1),r.getString(2)):null;}}
    synchronized void restrict(long chat,int topic,long target,long expires,long actor) throws SQLException {try(var db=open();var s=db.prepareStatement("MERGE INTO topic_restrictions KEY(chat,topic,target) VALUES(?,?,?,?,?)")){s.setLong(1,chat);s.setInt(2,topic);s.setLong(3,target);s.setLong(4,expires);s.setLong(5,actor);s.executeUpdate();}}
    synchronized long actor(long chat,int topic,long target) throws SQLException {try(var db=open();var s=db.prepareStatement("SELECT actor FROM topic_restrictions WHERE chat=? AND topic=? AND target=?")){s.setLong(1,chat);s.setInt(2,topic);s.setLong(3,target);try(var r=s.executeQuery()){return r.next()?r.getLong(1):0;}}}
    synchronized long expiry(long chat,int topic,long target) throws SQLException {try(var db=open();var s=db.prepareStatement("SELECT expires FROM topic_restrictions WHERE chat=? AND topic=? AND target=?")){s.setLong(1,chat);s.setInt(2,topic);s.setLong(3,target);try(var r=s.executeQuery()){return r.next()?r.getLong(1):0;}}}
    synchronized int release(long chat,long target,long now) throws SQLException {try(var db=open();var s=db.prepareStatement("DELETE FROM topic_restrictions WHERE chat=? AND target=? AND expires>?")){s.setLong(1,chat);s.setLong(2,target);s.setLong(3,now);return s.executeUpdate();}}
}

package ru.vaulttracker;

import com.google.gson.Gson;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Durable outbox. Only the database worker may access this connection. */
public final class LocalStore implements AutoCloseable {
    private static final Gson JSON = new Gson();
    private final Connection db;
    public LocalStore(Path path) throws SQLException {
        db = DriverManager.getConnection("jdbc:h2:file:" + path.toAbsolutePath().toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0", "sa", "");
        try (Statement s = db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS outbox (vault_id VARCHAR(160) PRIMARY KEY, revision BIGINT NOT NULL, payload CLOB NOT NULL, dirty BOOLEAN NOT NULL)");
        }
    }
    public List<Snapshot> load() throws SQLException { return select(false, 0); }
    public void republishAll() throws SQLException {
        try (Statement s = db.createStatement()) { s.executeUpdate("UPDATE outbox SET dirty=TRUE"); }
    }
    public List<Snapshot> dirty(int limit) throws SQLException { return select(true, limit); }
    private List<Snapshot> select(boolean dirty, int limit) throws SQLException {
        List<Snapshot> result = new ArrayList<>();
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT payload FROM outbox" + (dirty ? " WHERE dirty=TRUE ORDER BY revision LIMIT " + limit : " ORDER BY revision"))) {
            while (r.next()) result.add(JSON.fromJson(r.getString(1), Snapshot.class));
        }
        return result;
    }
    public void save(Collection<Snapshot> batch) throws SQLException {
        db.setAutoCommit(false);
        try (PreparedStatement lookup = db.prepareStatement("SELECT revision FROM outbox WHERE vault_id=?");
             PreparedStatement s = db.prepareStatement("MERGE INTO outbox(vault_id,revision,payload,dirty) KEY(vault_id) VALUES(?,?,?,TRUE)")) {
            for (Snapshot v : batch) {
                lookup.setString(1, v.sign().id());
                try (ResultSet r = lookup.executeQuery()) { if (r.next() && r.getLong(1) >= v.revision()) continue; }
                s.setString(1,v.sign().id()); s.setLong(2,v.revision()); s.setString(3,JSON.toJson(v)); s.executeUpdate();
            }
            db.commit();
        } catch (SQLException e) { db.rollback(); throw e; }
        finally { db.setAutoCommit(true); }
    }
    public void acknowledge(Collection<Snapshot> batch) throws SQLException {
        try (PreparedStatement s = db.prepareStatement("UPDATE outbox SET dirty=FALSE WHERE vault_id=? AND revision=?")) {
            for (Snapshot v : batch) { s.setString(1,v.sign().id()); s.setLong(2,v.revision()); s.addBatch(); }
            s.executeBatch();
        }
    }
    public long dirtyCount() throws SQLException {
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM outbox WHERE dirty=TRUE")) { r.next(); return r.getLong(1); }
    }
    @Override public void close() throws SQLException { db.close(); }
}

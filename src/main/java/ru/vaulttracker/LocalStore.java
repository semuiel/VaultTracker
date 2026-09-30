package ru.vaulttracker;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.function.Function;

/** Durable outbox. Only the database worker may access this connection. */
public final class LocalStore implements AutoCloseable {
    private static final Gson JSON = new Gson();
    private final Connection db;
    private final Function<String,UUID> knownOwner;
    private int unresolved;
    public LocalStore(Path path) throws SQLException {this(path,name->null);}
    public LocalStore(Path path,Function<String,UUID> knownOwner) throws SQLException {
        this.knownOwner=knownOwner;
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
    public boolean hasUnresolved() {return unresolved>0;}
    public int unresolvedCount() {return unresolved;}
    private record Row(String id,String payload,JsonObject json) {}
    private List<Snapshot> select(boolean dirty, int limit) throws SQLException {
        List<Row> rows=new ArrayList<>();Map<String,Set<UUID>> localNames=new HashMap<>();
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT vault_id,payload FROM outbox" + (dirty ? " WHERE dirty=TRUE ORDER BY revision" : " ORDER BY revision"))) {
            while (r.next()) {
                String payload=r.getString(2);JsonObject json=JsonParser.parseString(payload).getAsJsonObject();
                rows.add(new Row(r.getString(1),payload,json));
                if(json.has("owner") && !json.get("owner").isJsonNull() && json.has("playerName")) try {
                    UUID uuid=UUID.fromString(json.get("owner").getAsString());
                    localNames.computeIfAbsent(json.get("playerName").getAsString().toLowerCase(Locale.ROOT),ignored->new HashSet<>()).add(uuid);
                } catch(IllegalArgumentException ignored) {}
            }
        }
        List<Snapshot> result = new ArrayList<>();unresolved=0;
        try(var update=db.prepareStatement("UPDATE outbox SET payload=?,dirty=TRUE WHERE vault_id=?")) {
            for(Row row:rows) {
                JsonObject json=row.json();
                if(!json.has("owner") || json.get("owner").isJsonNull()) {
                    String name=json.has("playerName")?json.get("playerName").getAsString()
                            :json.has("ownerName")?json.get("ownerName").getAsString():null;
                    if(name==null || name.isBlank()) {unresolved++;continue;}
                    Set<UUID> candidates=new HashSet<>(localNames.getOrDefault(name.toLowerCase(Locale.ROOT),Set.of()));
                    UUID known=knownOwner.apply(name);if(known!=null) candidates.add(known);
                    if(candidates.size()!=1) {unresolved++;continue;}
                    if(!json.has("playerName")) json.addProperty("playerName",name);
                    json.addProperty("owner",candidates.iterator().next().toString());
                    update.setString(1,json.toString());update.setString(2,row.id());update.addBatch();
                }
                try {result.add(JSON.fromJson(json,Snapshot.class));}
                catch(RuntimeException invalid) {unresolved++;}
                if(dirty && result.size()>=limit) break;
            }
            update.executeBatch();
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

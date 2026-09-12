package ru.vaulttracker;

import java.sql.*;
import java.util.*;

/** Transactional MariaDB projection for the Telegram bot. Never touches other plugins' tables. */
public final class RemoteStore implements AutoCloseable {
    public record Settings(String host, int port, String database, String user, String password, String serverId) {
        public Settings {
            if (!host.matches("[a-zA-Z0-9.:-]+") || !database.matches("[a-zA-Z0-9_]+")
                    || !serverId.matches("[a-zA-Z0-9_-]{1,48}") || port < 1 || port > 65535)
                throw new IllegalArgumentException("Проверьте database.host, port, name и server-id");
        }
    }
    private final Settings settings;
    private Connection db;
    public RemoteStore(Settings settings) { this.settings = settings; }
    public void connect() throws SQLException {
        if (db != null && !db.isClosed() && db.isValid(2)) return;
        close();
        db = DriverManager.getConnection("jdbc:mariadb://" + settings.host + ":" + settings.port + "/" + settings.database
                        + "?connectTimeout=3000&socketTimeout=5000&tcpKeepAlive=true", settings.user, settings.password);
        initialize(db);
    }
    public static void initialize(Connection db) throws SQLException {
        try (Statement s = db.createStatement()) {
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS vt2_vaults (
                  server_id VARCHAR(48) NOT NULL, vault_id VARCHAR(160) NOT NULL,
                  owner_uuid CHAR(36) NOT NULL, player_name VARCHAR(64) NOT NULL,
                  world_uuid CHAR(36) NOT NULL, sign_x INT NOT NULL, sign_y INT NOT NULL, sign_z INT NOT NULL,
                  active BOOLEAN NOT NULL, checked_at_ms BIGINT NOT NULL, revision BIGINT NOT NULL,
                  PRIMARY KEY(server_id,vault_id), INDEX owner_lookup(owner_uuid)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS vt2_items (
                  server_id VARCHAR(48) NOT NULL, vault_id VARCHAR(160) NOT NULL,
                  material VARCHAR(128) NOT NULL, amount BIGINT NOT NULL,
                  PRIMARY KEY(server_id,vault_id,material), INDEX material_lookup(material)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        }
    }
    public void write(List<Snapshot> batch) throws SQLException { connect(); writeBatch(db, settings.serverId, batch); }
    // Exposed separately to test rollback with a real JDBC database, independent of network setup.
    public static void writeBatch(Connection db, String serverId, List<Snapshot> batch) throws SQLException {
        db.setAutoCommit(false);
        try (PreparedStatement header = db.prepareStatement("""
                INSERT INTO vt2_vaults(server_id,vault_id,owner_uuid,player_name,world_uuid,sign_x,sign_y,sign_z,active,checked_at_ms,revision)
                VALUES(?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE
                owner_uuid=VALUES(owner_uuid),player_name=VALUES(player_name),world_uuid=VALUES(world_uuid),
                sign_x=VALUES(sign_x),sign_y=VALUES(sign_y),sign_z=VALUES(sign_z),active=VALUES(active),
                checked_at_ms=VALUES(checked_at_ms),revision=VALUES(revision)
                """);
             PreparedStatement delete = db.prepareStatement("DELETE FROM vt2_items WHERE server_id=? AND vault_id=?");
             PreparedStatement insert = db.prepareStatement("INSERT INTO vt2_items(server_id,vault_id,material,amount) VALUES(?,?,?,?)")) {
            for (Snapshot v : batch) {
                header.setString(1,serverId); header.setString(2,v.sign().id()); header.setString(3,v.owner().toString());
                header.setString(4,v.playerName()); header.setString(5,v.sign().world().toString());
                header.setInt(6,v.sign().x()); header.setInt(7,v.sign().y()); header.setInt(8,v.sign().z());
                header.setBoolean(9,v.active()); header.setLong(10,v.checkedAt()); header.setLong(11,v.revision()); header.executeUpdate();
                delete.setString(1,serverId); delete.setString(2,v.sign().id()); delete.executeUpdate();
                for (var item : v.items().entrySet()) {
                    insert.setString(1,serverId); insert.setString(2,v.sign().id()); insert.setString(3,item.getKey());
                    insert.setLong(4,item.getValue()); insert.addBatch();
                }
                insert.executeBatch();
                insert.clearBatch();
            }
            db.commit();
        } catch (SQLException e) { db.rollback(); throw e; }
        finally { db.setAutoCommit(true); }
    }
    @Override public void close() throws SQLException { if (db != null) { try { db.close(); } finally { db = null; } } }
}

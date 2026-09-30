package ru.vaulttracker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import com.google.gson.JsonParser;
import static org.junit.jupiter.api.Assertions.*;
import static ru.vaulttracker.CatalogueTest.*;

class StorageTest {
    @TempDir Path temp;
    @Test void pendingChangesSurviveCloseAndReopen() throws Exception {
        Snapshot v=fixture(1,64);
        try (LocalStore db=new LocalStore(temp.resolve("cache"))) { db.save(List.of(v)); }
        try (LocalStore db=new LocalStore(temp.resolve("cache"))) {
            assertEquals(List.of(v),db.load()); assertEquals(1,db.dirtyCount());
        }
    }
    @Test void legacyNameOnlyVaultWaitsForUnambiguousUuidAndMigratesInPlace() throws Exception {
        Path path=temp.resolve("legacy-cache");Snapshot original=fixture(1,42);
        try(LocalStore store=new LocalStore(path)) {store.save(List.of(original));}
        var json=JsonParser.parseString(new com.google.gson.Gson().toJson(original)).getAsJsonObject();json.remove("owner");
        try(Connection db=DriverManager.getConnection("jdbc:h2:file:"+path.toAbsolutePath().toString().replace('\\','/')+";DB_CLOSE_ON_EXIT=FALSE","sa","");
            PreparedStatement update=db.prepareStatement("UPDATE outbox SET payload=?,dirty=FALSE")) {
            update.setString(1,json.toString());update.executeUpdate();
        }
        try(LocalStore unresolved=new LocalStore(path)) {
            assertTrue(unresolved.load().isEmpty());assertTrue(unresolved.hasUnresolved());
        }
        try(LocalStore resolved=new LocalStore(path,name->name.equals("Steve")?original.owner():null)) {
            assertEquals(List.of(original),resolved.load());assertFalse(resolved.hasUnresolved());
            assertEquals(List.of(original),resolved.dirty(10));
        }
        try(LocalStore again=new LocalStore(path)) {assertEquals(List.of(original),again.load());}
    }
    @Test void lateAcknowledgementCannotClearNewerChange() throws Exception {
        try (LocalStore db=new LocalStore(temp.resolve("cache"))) {
            Snapshot old=fixture(1,64), fresh=fixture(2,3);
            db.save(List.of(old)); db.save(List.of(fresh)); db.acknowledge(List.of(old));
            assertEquals(List.of(fresh),db.dirty(10));
            db.acknowledge(List.of(fresh)); assertEquals(0,db.dirtyCount());
        }
    }
    @Test void staleSnapshotCannotOverwriteTombstone() throws Exception {
        try (LocalStore db=new LocalStore(temp.resolve("cache"))) {
            Snapshot old=fixture(1,64);
            Snapshot deleted=new Snapshot(old.sign(),old.generation(),old.owner(),old.playerName(),old.chests(),Map.of(),false,2,2);
            db.save(List.of(deleted)); db.save(List.of(old));
            assertEquals(List.of(deleted),db.dirty(10));
        }
    }
    @Test void networkAcknowledgementAndDiskWriteUseSeparateConnectionsSafely() throws Exception {
        try (LocalStore disk=new LocalStore(temp.resolve("cache")); LocalStore mirror=new LocalStore(temp.resolve("cache"))) {
            disk.save(List.of(fixture(1,64)));
            List<Snapshot> sent=mirror.dirty(10);
            disk.save(List.of(fixture(2,2)));
            mirror.acknowledge(sent);
            assertEquals(1,disk.dirtyCount());
            assertEquals(2,mirror.dirty(10).getFirst().items().get("DIAMOND"));
        }
    }
    @Test void engineFlushesOnShutdownEvenWithoutMariaDb() throws Exception {
        CountDownLatch loaded=new CountDownLatch(1);
        StorageEngine engine=new StorageEngine(temp.resolve("cache"),null,Logger.getAnonymousLogger());
        engine.start(v->loaded.countDown());
        assertTrue(loaded.await(10,TimeUnit.SECONDS));
        engine.accept(fixture(1,42));
        engine.close();
        try (LocalStore db=new LocalStore(temp.resolve("cache"))) { assertEquals(List.of(fixture(1,42)),db.load()); }
    }
    @Test void dirtyReadsOnlyRequestedSqlBatch() throws Exception {
        Path path=temp.resolve("bounded");
        try(LocalStore store=new LocalStore(path)) {
            Snapshot first=fixture(1,10);
            Snapshot second=new Snapshot(p(10),UUID.randomUUID(),OWNER,"Steve",List.of(p(11)),Map.of("STONE",1L),true,2,2);
            store.save(List.of(first,second));
            try(Connection db=DriverManager.getConnection("jdbc:h2:file:"+path.toAbsolutePath().toString().replace('\\','/')+";DB_CLOSE_ON_EXIT=FALSE","sa","");
                var statement=db.prepareStatement("UPDATE outbox SET payload='invalid json' WHERE revision=2")) {statement.executeUpdate();}
            // The later row must not even be parsed while fetching the first batch.
            assertEquals(List.of(first),store.dirty(1));
            assertTrue(store.dirty(0).isEmpty());
        }
    }
    @Test void preResolvedSchemaMigratesWithoutDroppingLegacyRows() throws Exception {
        Path path=temp.resolve("old-schema");Snapshot snapshot=fixture(1,10);
        try(Connection db=DriverManager.getConnection("jdbc:h2:file:"+path.toAbsolutePath().toString().replace('\\','/')+";DB_CLOSE_ON_EXIT=FALSE","sa","");var statement=db.createStatement()) {
            statement.execute("CREATE TABLE outbox(vault_id VARCHAR(160) PRIMARY KEY,revision BIGINT NOT NULL,payload CLOB NOT NULL,dirty BOOLEAN NOT NULL)");
            try(var insert=db.prepareStatement("INSERT INTO outbox VALUES(?,?,?,TRUE)")) {
                insert.setString(1,snapshot.sign().id());insert.setLong(2,1);insert.setString(3,new com.google.gson.Gson().toJson(snapshot));insert.executeUpdate();
            }
        }
        try(LocalStore store=new LocalStore(path)) {
            assertEquals(List.of(snapshot),store.load());assertEquals(List.of(snapshot),store.dirty(1));
        }
    }
    private Connection remote() throws SQLException {
        Connection db=DriverManager.getConnection("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL");
        RemoteStore.initialize(db);
        return db;
    }
    private long total(Connection db) throws SQLException {
        try (Statement s=db.createStatement(); ResultSet r=s.executeQuery("SELECT COALESCE(SUM(i.amount),0) FROM vt2_items i JOIN vt2_vaults v ON i.server_id=v.server_id AND i.vault_id=v.vault_id WHERE v.active=TRUE")) { r.next(); return r.getLong(1); }
    }
    @Test void sqlReplacesCountsAndDeleteRemovesAllItems() throws Exception {
        try (Connection db=remote()) {
            RemoteStore.writeBatch(db,"test",List.of(fixture(1,64)));
            RemoteStore.writeBatch(db,"test",List.of(fixture(2,3)));
            assertEquals(3,total(db));
            Snapshot old=fixture(3,0);
            Snapshot deleted=new Snapshot(old.sign(),old.generation(),old.owner(),old.playerName(),old.chests(),Map.of(),false,4,4);
            RemoteStore.writeBatch(db,"test",List.of(deleted));
            assertEquals(0,total(db));
            try (Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM vt2_items")) { r.next(); assertEquals(0,r.getInt(1)); }
        }
    }
    @Test void sqlFailureRollsBackDeletionAndHeaderUpdateTogether() throws Exception {
        try (Connection db=remote()) {
            Snapshot old=fixture(1,64);
            RemoteStore.writeBatch(db,"test",List.of(old));
            Snapshot invalid=new Snapshot(old.sign(),old.generation(),old.owner(),"ChangedName",old.chests(),Map.of("X".repeat(200),1L),true,2,2);
            assertThrows(SQLException.class,()->RemoteStore.writeBatch(db,"test",List.of(invalid)));
            assertEquals(64,total(db));
            try (Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT player_name FROM vt2_vaults")) { r.next(); assertEquals("Steve",r.getString(1)); }
        }
    }
    @Test void replayAfterLostAcknowledgementIsIdempotentAndServersAreIsolated() throws Exception {
        try (Connection db=remote()) {
            RemoteStore.writeBatch(db,"test",List.of(fixture(1,64)));
            RemoteStore.writeBatch(db,"test",List.of(fixture(1,64)));
            assertEquals(64,total(db));
            RemoteStore.writeBatch(db,"main",List.of(fixture(1,64)));
            assertEquals(128,total(db));
        }
    }
}

package ru.vaulttracker;

import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQL against an isolated schema fixture shaped from the official CoreProtect v24.1 DDL. */
class CoreProtectMariaDbSourceTest {
    String url;
    CoreProtectMariaDbSource source;
    final long now=1_800_000_000L;
    final String uuid="00000000-0000-0000-0000-000000000001";
    Connection keeper;
    Connection open() throws SQLException {var c=DriverManager.getConnection(url);c.setSchema("coreprotect");return c;}
    @BeforeEach void setup() throws Exception {
        url="jdbc:h2:mem:coreprotect;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER;DB_CLOSE_DELAY=0";
        keeper=DriverManager.getConnection(url);
        try(var s=keeper.createStatement()) {
            s.execute("CREATE SCHEMA coreprotect");s.execute("SET SCHEMA coreprotect");
            s.execute("CREATE TABLE co_user(rowid INT PRIMARY KEY, user VARCHAR, uuid VARCHAR)");
            s.execute("CREATE TABLE co_username_log(user VARCHAR, uuid VARCHAR)");
            s.execute("CREATE TABLE co_world(id INT PRIMARY KEY, world VARCHAR)");
            s.execute("CREATE TABLE co_material_map(id INT PRIMARY KEY, material VARCHAR)");
            s.execute("CREATE TABLE co_entity_map(id INT PRIMARY KEY, entity VARCHAR)");
            String shared="rowid BIGINT PRIMARY KEY,time BIGINT,user INT,wid INT,x INT,y INT,z INT";
            s.execute("CREATE TABLE co_block("+shared+",type INT,action INT,rolled_back INT)");
            s.execute("CREATE TABLE co_container("+shared+",type INT,amount INT,action INT,rolled_back INT)");
            s.execute("CREATE TABLE co_item("+shared+",type INT,amount INT,action INT,rolled_back INT)");
            s.execute("CREATE TABLE co_session("+shared+",action INT)");
            s.execute("CREATE TABLE co_chat("+shared+",message VARCHAR)");s.execute("CREATE TABLE co_command("+shared+",message VARCHAR)");
            s.execute("CREATE TABLE co_sign("+shared+",action INT,line_1 VARCHAR,line_2 VARCHAR,line_3 VARCHAR,line_4 VARCHAR,line_5 VARCHAR,line_6 VARCHAR,line_7 VARCHAR,line_8 VARCHAR)");
            s.execute("INSERT INTO co_user VALUES(1,'Fixture','"+uuid+"'),(2,'Other','00000000-0000-0000-0000-000000000002')");
            s.execute("INSERT INTO co_username_log VALUES('OldFixture','"+uuid+"')");
            s.execute("INSERT INTO co_world VALUES(1,'world'),(2,'nether')");
            s.execute("INSERT INTO co_material_map VALUES(1,'minecraft:diamond'),(2,'minecraft:stone')");
            s.execute("INSERT INTO co_entity_map VALUES(3,'minecraft:zombie')");
            s.execute("INSERT INTO co_block VALUES(1,"+(now-30)+",1,1,10,64,20,1,0,0),(2,"+(now-20)+",1,1,10,64,20,2,1,1),(3,"+(now-10)+",2,2,10,64,20,1,0,0),(4,"+(now-15)+",1,1,11,64,20,3,3,0)");
            s.execute("INSERT INTO co_container VALUES(1,"+(now-25)+",1,1,10,64,20,1,32,0,0),(2,"+(now-24)+",1,1,10,65,20,1,12,1,0)");
            s.execute("INSERT INTO co_item VALUES(1,"+(now-18)+",1,1,10,64,20,1,5,4,0)");
            s.execute("INSERT INTO co_session VALUES(1,"+(now-60)+",1,1,10,64,20,1)");
            s.execute("INSERT INTO co_chat VALUES(1,"+(now-17)+",1,1,10,64,20,'hello 100% _ world')");
            s.execute("INSERT INTO co_command VALUES(1,"+(now-16)+",1,1,10,64,20,'/fixture secret')");
            s.execute("INSERT INTO co_sign VALUES(1,"+(now-14)+",1,1,10,64,20,0,'one','two','','','','','','')");
        }
        source=new CoreProtectMariaDbSource(CoreProtectWebTest.config(2,5,40),this::open,()->true,false);
    }
    @AfterEach void close() throws Exception {keeper.close();}
    CoreProtectQuery query(Map<String,String> filters) {var params=new HashMap<>(Map.of("player","Fixture","from",Long.toString(now-100),"to",Long.toString(now)));params.putAll(filters);return CoreProtectQuery.parse(params,CoreProtectWebTest.config(2,5,40),now);}
    @Test void playerCatalogueIncludesOfflineHistorySubstringUuidAndBoundedPages() throws Exception {
        var page=source.players("fIx",0,30);assertEquals(List.of("Fixture","OldFixture"),page.players().stream().map(CoreProtectLookupSource.Player::name).toList());
        assertEquals(uuid,page.players().getFirst().uuid());assertFalse(page.hasMore());
        assertEquals(source.lookup(query(Map.of())).events(),source.lookup(query(Map.of("player","fIxTuRe"))).events());
        assertEquals(2,source.players(uuid,0,30).players().size());
        assertTrue(source.players("missing",0,30).players().isEmpty());
        var first=source.players("",0,1);assertTrue(first.hasMore());
        assertNotEquals(first.players(),source.players("",1,1).players());
        try(var s=keeper.createStatement()){s.execute("INSERT INTO co_user VALUES(3,'A_B','id3'),(4,'AxB','id4')");}
        assertEquals(List.of("A_B"),source.players("a_b",0,30).players().stream().map(CoreProtectLookupSource.Player::name).toList());
        for(String invalid:List.of("x%' OR 1=1","a".repeat(37)))assertThrows(IllegalArgumentException.class,()->source.players(invalid,0,30));
        assertThrows(IllegalArgumentException.class,()->source.players("",1001,30));
        assertThrows(IllegalArgumentException.class,()->source.players("",0,31));
    }
    @Test void capabilitiesMatchActualSchemaAndUnsupportedSpawnHidden() throws Exception {
        var c=source.capabilities();assertEquals(List.of("world","nether"),c.worlds());assertTrue(c.usernameHistory());
        assertEquals(Set.of("block","container","item","kill","session","chat","command","sign","inventory"),new HashSet<>(c.categories().stream().map(CoreProtectLookupSource.Category::id).toList()));
        try(var s=keeper.createStatement()) {s.execute("DROP TABLE co_sign");}
        var withoutSign=new CoreProtectMariaDbSource(CoreProtectWebTest.config(2,5,40),this::open,()->true,false);
        assertFalse(withoutSign.capabilities().categories().stream().anyMatch(cg->cg.id().equals("sign")));
    }
    @Test void mergedTimelineNewestFirstWithAmountsRollbackAndPagination() throws Exception {
        var page=source.lookup(query(Map.of("limit","3")));assertEquals(3,page.events().size());assertTrue(page.hasMore());
        assertEquals("sign",page.events().get(0).category());assertEquals("kill",page.events().get(1).category());
        var next=source.lookup(query(Map.of("limit","3","offset","3")));
        assertTrue(next.events().getFirst().timestamp()<=page.events().getLast().timestamp());
        assertTrue(Collections.disjoint(page.events().stream().map(CoreProtectLookupSource.Event::id).toList(),next.events().stream().map(CoreProtectLookupSource.Event::id).toList()));
        var blocks=source.lookup(query(Map.of("category","block"))).events();assertEquals(2,blocks.size());
        assertEquals("STONE",blocks.get(0).material());assertEquals(true,blocks.get(0).rolledBack());assertEquals(uuid,blocks.get(0).uuid());
        assertEquals(32,source.lookup(query(Map.of("category","container","action","container_remove"))).events().getFirst().amount());
    }
    @Test void worldTimeActionMaterialsAndExactContainerIncludingY() throws Exception {
        assertEquals(1,source.lookup(query(Map.of("category","block","include","DIAMOND","exclude","STONE","action","break","world","world"))).events().size());
        assertTrue(source.lookup(query(Map.of("category","block","include","STONE","action","break"))).events().isEmpty());
        assertTrue(source.lookup(query(Map.of("world","nether"))).events().isEmpty());
        assertEquals(1,source.lookup(query(Map.of("category","container","world","world","x","10","y","64","z","20","radius","0"))).events().size());
        assertEquals(2,source.lookup(query(Map.of("category","container","world","world","x","10","y","64","z","20","radius","1"))).events().size());
        assertTrue(source.lookup(query(Map.of("category","block","from",Long.toString(now-19)))).events().isEmpty());
        assertEquals(1,source.lookup(query(Map.of("category","block","to",Long.toString(now-25)))).events().size());
        assertThrows(IllegalArgumentException.class,()->source.lookup(query(Map.of("category","spawn"))));
    }
    @Test void usernamesUuidEmptyAndAmbiguousOldNick() throws Exception {
        var expected=source.lookup(query(Map.of())).events();
        assertEquals(expected,source.lookup(query(Map.of("player","OldFixture"))).events());
        assertEquals(expected,source.lookup(query(Map.of("player",uuid))).events());
        assertTrue(source.lookup(query(Map.of("player","Missing"))).events().isEmpty());
        try(var s=keeper.createStatement()) {s.execute("INSERT INTO co_username_log VALUES('OldFixture','00000000-0000-0000-0000-000000000002')");}
        assertThrows(IllegalArgumentException.class,()->source.lookup(query(Map.of("player","OldFixture"))));
    }
    @Test void literalTextFilterAndInventoryDirections() throws Exception {
        assertEquals(1,source.lookup(query(Map.of("category","chat","text","100% _"))).events().size());
        assertTrue(source.lookup(query(Map.of("category","chat","text","100X A"))).events().isEmpty());
        assertEquals(1,source.lookup(query(Map.of("category","command","text","/fixture","textMode","starts"))).events().size());
        assertTrue(source.lookup(query(Map.of("category","command","text","secret","textMode","starts"))).events().isEmpty());
        var inventory=source.lookup(query(Map.of("category","inventory","action","inventory_add"))).events();
        assertEquals(2,inventory.size());assertTrue(inventory.stream().allMatch(e->e.action().equals("inventory_add")));
        assertEquals("ZOMBIE",source.lookup(query(Map.of("category","kill"))).events().getFirst().material());
    }
    @Test void largeDatasetIsBoundedAndParametersDoNotBecomeSql() throws Exception {
        try(var s=keeper.prepareStatement("INSERT INTO co_container VALUES(?, ?,1,1,10,64,20,1,64,1,0)")) {
            for(int i=3;i<503;i++) {s.setInt(1,i);s.setLong(2,now-2);s.addBatch();}s.executeBatch();
        }
        var page=source.lookup(query(Map.of("category","container","limit","100")));assertEquals(100,page.events().size());assertTrue(page.hasMore());
        assertTrue(source.lookup(query(Map.of("category","command","text","' OR 1=1 --"))).events().isEmpty());
    }
    @Test void onlySelectGrantsAreAcceptedNoRolesOrGlobalOrWritePrivileges() throws Exception {
        CoreProtectMariaDbSource.verifyGrants(List.of("GRANT USAGE ON *.* TO 'viewer'@'localhost'","GRANT SELECT ON `coreprotect`.* TO 'viewer'@'localhost'"),"coreprotect");
        for(String grant:List.of("GRANT ALL PRIVILEGES ON `coreprotect`.* TO 'viewer'@'localhost'","GRANT SELECT, INSERT ON `coreprotect`.* TO 'viewer'@'localhost'","GRANT SELECT ON *.* TO 'viewer'@'localhost'","GRANT `adminrole` TO 'viewer'@'localhost'","GRANT SELECT ON `coreprotect`.* TO 'viewer'@'localhost' WITH GRANT OPTION"))
            assertThrows(SQLException.class,()->CoreProtectMariaDbSource.verifyGrants(List.of(grant),"coreprotect"));
        assertThrows(SQLException.class,()->CoreProtectMariaDbSource.verifyGrants(List.of(),"coreprotect"));
    }
    @Test void unavailableSourceDoesNotOpenDatabase() {
        var absent=new CoreProtectMariaDbSource(CoreProtectWebTest.config(2,5,40),()->{fail("Must not open");return null;},()->false,false);
        assertThrows(SQLException.class,absent::capabilities);assertThrows(SQLException.class,()->absent.lookup(query(Map.of())));
    }
}

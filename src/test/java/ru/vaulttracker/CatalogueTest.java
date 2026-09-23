package ru.vaulttracker;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogueTest {
    static final UUID WORLD=UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID OWNER=UUID.fromString("00000000-0000-0000-0000-000000000002");
    static BlockKey p(int x) { return new BlockKey(WORLD,x,64,0); }
    static Snapshot fixture(long revision, long amount) {
        return new Snapshot(p(0),UUID.fromString("00000000-0000-0000-0000-000000000003"),OWNER,"Steve",List.of(p(1)),
                amount>0 ? Map.of("DIAMOND",amount) : Map.of(),true,1000+revision,revision);
    }
    @Test void sumsSeveralChestsAndUpdatesWithoutAccumulatingOldItems() {
        Catalogue c=new Catalogue(v->{});
        Snapshot a=c.register(p(0),OWNER,"Steve",List.of(p(1)),Map.of("DIAMOND",10L),1,100);
        c.register(p(10),OWNER,"Steve",List.of(p(11)),Map.of("DIAMOND",20L),1,100);
        assertEquals(List.of("Steve — 30"),c.find("DIAMOND",20));
        c.observe(a.sign(),a.generation(),a.chests(),Map.of("DIAMOND",3L),2);
        assertEquals(List.of("Steve — 23"),c.find("DIAMOND",20));
        c.remove(a.sign(),a.generation(),3);
        assertEquals(List.of("Steve — 20"),c.find("DIAMOND",20));
    }
    @Test void doubleChestCannotBeRegisteredTwiceFromEitherHalf() {
        Catalogue c=new Catalogue(v->{});
        c.register(p(0),OWNER,"Steve",List.of(p(1),p(2)),Map.of(),1,100);
        assertThrows(IllegalArgumentException.class,()->c.register(p(3),UUID.randomUUID(),"Alex",List.of(p(2),p(1)),Map.of(),2,100));
        assertEquals(1,c.size());
    }
    @Test void splitDoubleReleasesOnlyRemovedHalf() {
        Catalogue c=new Catalogue(v->{});
        Snapshot a=c.register(p(0),OWNER,"Steve",List.of(p(1),p(2)),Map.of("STONE",128L),1,100);
        c.observe(a.sign(),a.generation(),List.of(p(1)),Map.of("STONE",64L),2);
        assertNull(c.claimedBy(p(2)));
        assertEquals(p(0),c.claimedBy(p(1)));
        assertEquals(List.of("Steve — 64"),c.find("STONE",10));
    }
    @Test void mergingTwoOwnedVaultsRemovesBothInsteadOfTransferringOwnership() {
        List<Snapshot> output=new ArrayList<>();
        Catalogue c=new Catalogue(output::add);
        Snapshot a=c.register(p(0),OWNER,"Steve",List.of(p(1)),Map.of("STONE",64L),1,100);
        c.register(p(3),UUID.randomUUID(),"Alex",List.of(p(2)),Map.of("STONE",64L),1,100);
        assertFalse(c.observe(a.sign(),a.generation(),List.of(p(1),p(2)),Map.of("STONE",128L),2));
        assertEquals(0,c.size());
        assertEquals(2,output.stream().filter(v->!v.active() && v.items().isEmpty()).count());
    }
    @Test void delayedWorkFromOldRegistrationCannotDeleteOrResurrectNewOne() {
        Catalogue c=new Catalogue(v->{});
        Snapshot old=c.register(p(0),OWNER,"Steve",List.of(p(1)),Map.of("STONE",1L),1,100);
        c.remove(old.sign(),old.generation(),2);
        Snapshot fresh=c.register(p(0),UUID.randomUUID(),"Alex",List.of(p(1)),Map.of("DIAMOND",5L),3,100);
        c.remove(old.sign(),old.generation(),4);
        assertFalse(c.observe(old.sign(),old.generation(),old.chests(),old.items(),5));
        assertEquals(fresh,c.get(p(0)));
    }
    @Test void restoreAndRenameKeepUuidAndRevision() {
        Catalogue c=new Catalogue(v->{});
        c.restore(List.of(fixture(90,42)));
        c.rename(OWNER,"NewName");
        assertEquals(List.of("NewName — 42"),c.find("DIAMOND",10));
        assertEquals(OWNER,c.get(p(0)).owner());
        assertTrue(c.get(p(0)).revision()>90);
    }
    @Test void unloadedNegativeChunkIndexRetainsRegistration() {
        Catalogue c=new Catalogue(v->{});
        c.register(p(-1),OWNER,"Steve",List.of(p(-2)),Map.of(),1,100);
        assertEquals(1,c.inChunk(new BlockKey.ChunkKey(WORLD,-1,0)).size());
        assertEquals(1,c.size());
    }
    @Test void parallelRegionsCannotClaimTheSameChest() throws Exception {
        Catalogue c=new Catalogue(v->{});
        try (ExecutorService workers=Executors.newFixedThreadPool(8)) {
            List<Callable<Boolean>> attempts=new ArrayList<>();
            for (int i=0;i<50;i++) { int n=i; attempts.add(()->{
                try { c.register(p(100+n),UUID.randomUUID(),"P"+n,List.of(p(1)),Map.of(),1,100); return true; }
                catch (IllegalArgumentException e) { return false; }
            }); }
            int successful=0;
            for (Future<Boolean> result:workers.invokeAll(attempts)) if (result.get()) successful++;
            assertEquals(1,successful);
        }
    }
    @Test void playerLimitRejectsExtraRegistration() {
        Catalogue c=new Catalogue(v->{});
        c.register(p(0),OWNER,"Steve",List.of(p(1)),Map.of(),1,1);
        assertThrows(IllegalArgumentException.class,()->c.register(p(2),OWNER,"Steve",List.of(p(3)),Map.of(),1,1));
    }
    @Test void playerCatalogueCanBeOrderedByTotalAmountAcrossAllVaults() {
        Catalogue c=new Catalogue(v->{});UUID alex=UUID.randomUUID(),bob=UUID.randomUUID(),empty=UUID.randomUUID();
        c.register(p(10),alex,"Alex",List.of(p(11)),Map.of("STONE",20L,"DIAMOND",5L),1,100);
        c.register(p(12),alex,"Alex",List.of(p(13)),Map.of("STONE",10L),1,100);
        c.register(p(14),bob,"Bob",List.of(p(15)),Map.of("DIAMOND",40L),1,100);
        c.register(p(16),empty,"Empty",List.of(p(17)),Map.of(),1,100);
        assertEquals(List.of("Bob","Alex","Empty"),c.ownerNamesByTotalItems());
    }
}

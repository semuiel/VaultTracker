package ru.vaulttracker;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OwnResourceSearchTest {
    @Test void keepsOwnersPrivateAndSortsSameWorldByDistanceThenOtherWorlds() {
        Catalogue c=new Catalogue(v->{});UUID owner=UUID.randomUUID(),world=UUID.randomUUID(),other=UUID.randomUUID();
        add(c,owner,world,100);add(c,owner,other,1);add(c,owner,world,3);add(c,UUID.randomUUID(),world,1);
        var rows=OwnResourceSearch.find(c,owner,"алмазная руда",new BlockKey(world,0,64,0));
        assertEquals(3,rows.size());assertEquals(3,rows.get(0).chest().x());assertEquals(100,rows.get(1).chest().x());assertEquals(other,rows.get(2).chest().world());
        assertEquals(rows,OwnResourceSearch.find(c,owner,"diamnd_ore",new BlockKey(world,0,64,0)));
        assertEquals(3,OwnResourceSearch.find(c,owner,"diamond",null).size());
    }
    @Test void zeroLimitAllowsMoreThanOneHundredVaultsAndDoubleChestAppearsOnce() {
        Catalogue c=new Catalogue(v->{});UUID owner=UUID.randomUUID(),world=UUID.randomUUID();
        for(int i=0;i<150;i++) add(c,owner,world,i*3);
        assertEquals(150,c.size());assertEquals(150,OwnResourceSearch.find(c,owner,"diamond_ore",null).size());
        assertTrue(OwnResourceSearch.find(c,owner,"gold",null).isEmpty());
    }
    private void add(Catalogue c,UUID owner,UUID world,int x) {
        c.register(new BlockKey(world,x,65,0),owner,"Alex",List.of(new BlockKey(world,x,64,0),new BlockKey(world,x+1,64,0)),Map.of("DIAMOND_ORE",12L),1,0);
    }
}

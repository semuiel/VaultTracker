package ru.vaulttracker;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TopItemTabCompletionTest {
    private static final List<String> ITEMS=List.of("Алмаз","Алмазная руда","diamond","daimond");
    private final Catalogue catalogue=catalogue();

    @Test void suggestsRussianItemsAndAliasesLikeFind() {
        assertTrue(TopItemTabCompletion.complete(catalogue,new String[]{"алм"},ITEMS).contains("Алмаз"));
        assertTrue(TopItemTabCompletion.complete(catalogue,new String[]{"алмазная",""},ITEMS).contains("руда"));
        assertTrue(TopItemTabCompletion.complete(catalogue,new String[]{"DAI"},ITEMS).contains("daimond"));
        assertTrue(TopItemTabCompletion.complete(catalogue,new String[]{"АР"},ITEMS).contains("АР"));
    }
    @Test void retainsPlayerAllAndPageCompletion() {
        var first=TopItemTabCompletion.complete(catalogue,new String[]{""},ITEMS);
        assertTrue(first.containsAll(List.of("Friend","player","all","Алмаз")));
        assertEquals(List.of("Friend"),TopItemTabCompletion.complete(catalogue,new String[]{"player","Fri"},ITEMS));
        assertTrue(TopItemTabCompletion.complete(catalogue,new String[]{"Friend","АЛМ"},ITEMS).contains("Алмаз"));
        assertTrue(TopItemTabCompletion.complete(catalogue,new String[]{"Friend",""},ITEMS).contains("2"));
        assertEquals(List.of("1"),TopItemTabCompletion.complete(catalogue,new String[]{"all",""},ITEMS));
    }
    private static Catalogue catalogue() {
        Catalogue catalogue=new Catalogue(v->{});UUID world=UUID.randomUUID();Map<String,Long> items=new HashMap<>();
        for(int i=0;i<9;i++) items.put("ITEM_"+i,1L);
        catalogue.register(new BlockKey(world,0,64,0),UUID.randomUUID(),"Friend",List.of(new BlockKey(world,0,64,1)),items,1,0);
        return catalogue;
    }
}

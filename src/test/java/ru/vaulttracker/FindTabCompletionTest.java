package ru.vaulttracker;

import org.junit.jupiter.api.Test;
import org.bukkit.Material;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class FindTabCompletionTest {
    private final List<String> items=RussianItems.searchTerms(material->material==Material.DIAMOND||material==Material.DIAMOND_ORE);
    @Test void firstArgumentCombinesPlayersItemsTranslationsAndAliases() {
        Catalogue catalogue=catalogue();
        var all=FindTabCompletion.complete(catalogue,new String[]{""},items);
        assertTrue(all.contains("Friend"));
        assertTrue(all.contains("diamond"));
        assertTrue(all.contains("Алмаз"));
        assertTrue(all.contains("daimond"));
        assertTrue(all.contains("minecraft:diamond"));
        assertTrue(FindTabCompletion.complete(catalogue,new String[]{"FRI"},items).contains("Friend"));
        assertTrue(FindTabCompletion.complete(catalogue,new String[]{"DIAM"},items).contains("diamond"));
        assertTrue(FindTabCompletion.complete(catalogue,new String[]{"АЛМ"},items).contains("Алмаз"));
    }
    @Test void itemSuggestionsContinueAfterNicknameAndAcrossRussianWords() {
        Catalogue catalogue=catalogue();
        assertTrue(FindTabCompletion.complete(catalogue,new String[]{"friend","АЛМ"},items).contains("Алмаз"));
        assertTrue(FindTabCompletion.complete(catalogue,new String[]{"Friend","алмазная",""},items).contains("руда"));
        assertTrue(FindTabCompletion.complete(catalogue,new String[]{"алмазная",""},items).contains("руда"));
        assertTrue(FindTabCompletion.complete(catalogue,new String[]{"Friend","dia"},items).contains("diamond"));
    }
    private Catalogue catalogue() {
        Catalogue catalogue=new Catalogue(v->{});UUID world=UUID.randomUUID();
        catalogue.register(new BlockKey(world,0,64,0),UUID.randomUUID(),"Friend",List.of(new BlockKey(world,0,64,1)),Map.of("DIAMOND",5L),1,0);
        return catalogue;
    }
}

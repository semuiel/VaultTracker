package ru.vaulttracker;

import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ItemSortingTest {
    @TempDir Path folder;

    @Test void preferencesAndSearchCountsSurviveRestartWithoutChangingOldAccounts() throws Exception {
        Path path=folder.resolve("guard");UUID owner=UUID.randomUUID();
        try(var guard=new GuardService(path,new GuardConfig(true,0),Logger.getAnonymousLogger())) {
            assertEquals(ItemSorting.Mode.MINECRAFT,guard.itemSortMode(42));
            String code=guard.generate(42).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH);
            guard.link(owner,"Alex",code).get();
            guard.itemSortMode(42,ItemSorting.Mode.SERVER).get();
            guard.recordItemSearch(ItemSorting.queryItem("алмаз")).get();
            guard.recordItemSearch(ItemSorting.queryItem("daimond")).get();
            assertEquals(2L,guard.itemSearchCounts().get("DIAMOND"));
        }
        try(var reopened=new GuardService(path,new GuardConfig(true,0),Logger.getAnonymousLogger())) {
            assertEquals(ItemSorting.Mode.SERVER,reopened.itemSortMode(42));
            assertEquals(ItemSorting.Mode.MINECRAFT,reopened.itemSortMode(43));
            assertEquals(2L,reopened.itemSearchCounts().get("DIAMOND"));
        }
    }

    @Test void sameSorterUsesGlobalCountsForCatalogueAndScopedCountsForResources() {
        Map<String,Long> global=Map.of("DIAMOND",5L,"STONE",100L,"IRON_INGOT",50L);
        Map<String,Long> own=Map.of("DIAMOND",5L,"STONE",1L,"IRON_INGOT",3L);
        var keys=global.keySet();
        assertEquals("DIAMOND",ItemSorting.sort(keys,ItemSorting.Mode.MINECRAFT,global,Map.of()).getFirst());
        assertEquals("STONE",ItemSorting.sort(keys,ItemSorting.Mode.AMOUNT,global,Map.of()).getFirst());
        assertEquals("DIAMOND",ItemSorting.sort(keys,ItemSorting.Mode.AMOUNT,own,Map.of()).getFirst());
        assertEquals("IRON_INGOT",ItemSorting.sort(keys,ItemSorting.Mode.SERVER,global,Map.of("IRON_INGOT",7L)).getFirst());
        assertEquals(ItemSorting.sort(keys,ItemSorting.Mode.ALPHABETICAL,global,Map.of()),
                ItemSorting.sort(keys,ItemSorting.Mode.ALPHABETICAL,own,Map.of()));
    }

    @Test void personalTopKeepsPlaceFirstAndSortsTiesBySavedMode() {
        var ranks=List.of(new Catalogue.PersonalRank(1,"STONE","Камень",100),
                new Catalogue.PersonalRank(2,"NETHERITE_INGOT","Незеритовый слиток",1000),
                new Catalogue.PersonalRank(1,"DIAMOND","Алмаз",5));
        assertEquals(List.of("DIAMOND","STONE","NETHERITE_INGOT"),
                ItemSorting.personalRanks(ranks,ItemSorting.Mode.MINECRAFT,Map.of()).stream().map(Catalogue.PersonalRank::material).toList());
        assertEquals(List.of("STONE","DIAMOND","NETHERITE_INGOT"),
                ItemSorting.personalRanks(ranks,ItemSorting.Mode.SERVER,Map.of("STONE",7L)).stream().map(Catalogue.PersonalRank::material).toList());
        assertEquals(List.of("DIAMOND","STONE","NETHERITE_INGOT"),
                ItemSorting.personalRanks(ranks,ItemSorting.Mode.ALPHABETICAL,Map.of()).stream().map(Catalogue.PersonalRank::material).toList());
        assertEquals(List.of("STONE","DIAMOND","NETHERITE_INGOT"),
                ItemSorting.personalRanks(ranks,ItemSorting.Mode.AMOUNT,Map.of()).stream().map(Catalogue.PersonalRank::material).toList());
    }

    @Test void renderedCatalogueAndOwnResourcesFollowSameSavedPreferenceWithoutCountingPages() throws Exception {
        var catalogue=new Catalogue(ignored->{});var storage=mock(StorageEngine.class);when(storage.ready()).thenReturn(true);
        UUID world=UUID.randomUUID(),owner=UUID.randomUUID(),other=UUID.randomUUID();
        catalogue.register(new BlockKey(world,1,64,1),owner,"Alex",List.of(new BlockKey(world,1,64,2)),
                Map.of("DIAMOND",5L,"STONE",1L),1,10);
        catalogue.register(new BlockKey(world,2,64,1),other,"Bob",List.of(new BlockKey(world,2,64,2)),
                Map.of("STONE",100L),1,10);
        try(var guard=new GuardService(folder.resolve("render"),new GuardConfig(true,0),Logger.getAnonymousLogger())) {
            String code=guard.generate(42).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH);
            guard.link(owner,"Alex",code).get();guard.itemSortMode(42,ItemSorting.Mode.AMOUNT).get();
            var commands=new TelegramCommands(catalogue,storage,10,id->true);
            var menu=new TelegramPrivateMenu(catalogue,storage,commands,1,id->true,System::currentTimeMillis);menu.guard(guard);
            var search=menu.handle(42,42,0,"/search",false);
            var items=menu.callback(42,42,0,search.buttons().stream().filter(b->b.text().contains("Предмет")).findFirst().orElseThrow().data()).view();
            assertEquals("Камень",items.buttons().get(1).text());
            var own=commands.ownResources(42,owner);
            assertTrue(own.text().indexOf("Алмаз —")<own.text().indexOf("Камень —"));
            assertTrue(guard.itemSearchCounts().isEmpty());
            Material stone=mock(Material.class);when(stone.getMaxStackSize()).thenReturn(64);
            try(var materials=mockStatic(Material.class)) {
                materials.when(()->Material.getMaterial("STONE")).thenReturn(stone);
                var selected=menu.callback(42,42,0,items.buttons().get(1).data()).view();
                assertEquals(1L,guard.itemSearchCounts().get("STONE"));
                if(selected.buttons().stream().anyMatch(b->b.text().contains("Вперёд")))
                    menu.callback(42,42,0,selected.buttons().stream().filter(b->b.text().contains("Вперёд")).findFirst().orElseThrow().data());
                assertEquals(1L,guard.itemSearchCounts().get("STONE"));
            }
        }
    }
}

package ru.vaulttracker;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerItemsTest {
    private final Catalogue catalogue=new Catalogue(v -> {});
    private final UUID world=UUID.randomUUID(), alex=UUID.randomUUID();
    private int position;
    private Snapshot add(UUID owner,String name,Map<String,Long> items) {
        int x=position++;
        return catalogue.register(new BlockKey(world,x,0,0),owner,name,List.of(new BlockKey(world,x,0,1)),items,1,100);
    }
    private List<Component> run(boolean allowed,boolean ready,String... args) {
        CommandSender sender=mock(CommandSender.class); when(sender.hasPermission("vaulttracker.search")).thenReturn(allowed);
        try(var materials=mockStatic(org.bukkit.Material.class)) {
            var material=mock(org.bukkit.Material.class);
            when(material.getMaxStackSize()).thenReturn(64);
            materials.when(() -> org.bukkit.Material.getMaterial(anyString())).thenReturn(material);
            new TopItemCommand(catalogue,() -> ready,id -> Set.of("DIAMOND","STONE","IRON_INGOT").contains(id)).execute(sender,args);
        }
        var capture=ArgumentCaptor.forClass(Component.class); verify(sender,atLeastOnce()).sendMessage(capture.capture());
        return capture.getAllValues();
    }
    private String text(List<Component> components) {
        return String.join("\n",components.stream().map(PlainTextComponentSerializer.plainText()::serialize).toList());
    }
    private List<String> clicks(Component component) {
        List<String> values=new ArrayList<>();
        if (component.clickEvent()!=null) {
            assertEquals(ClickEvent.Action.RUN_COMMAND,component.clickEvent().action());
            values.add(component.clickEvent().value());
        }
        component.children().forEach(child -> values.addAll(clicks(child))); return values;
    }
    @Test void aggregatesOnlyRequestedOwnerAcrossContainersAndIncludesOfflineNames() {
        add(alex,"Alex",Map.of("DIAMOND",64L)); add(alex,"Alex",Map.of("DIAMOND",10L,"STONE",3L));
        add(UUID.randomUUID(),"Steve",Map.of("DIAMOND",999L));
        assertTrue(text(run(true,true,"aLeX","diamon")).contains("Alex — Алмаз: 74 шт."));
        assertTrue(text(run(true,true,"Alex","iron_ingot")).contains("Железный слиток: 0 шт."));
        assertFalse(text(run(true,true,"Alex")).contains("999"));
    }
    @Test void allTopMatchesBotTotalsAndPaginatesTwentyOwners() {
        add(alex,"Alex",Map.of("DIAMOND",864L,"STONE",864L));add(alex,"Alex",Map.of("IRON_INGOT",1728L));
        for(int i=0;i<20;i++) add(UUID.randomUUID(),"Player"+i,Map.of("STONE",18L+i));
        var first=run(true,true,"all");assertEquals(22,first.size());assertTrue(text(first).contains("1. Alex — 2 шалк."));assertFalse(text(first).contains("шт."));
        assertEquals(List.of("/topitem all 2"),clicks(first.getLast()));assertEquals(3,run(true,true,"all","2").size());
        assertTrue(text(run(true,true,"all","0")).contains("от 1 до 2"));
        add(UUID.randomUUID(),"all",Map.of("STONE",3L));assertTrue(text(run(true,true,"player","all")).contains("ресурсы хранилищ"));
    }
    @Test void everyPageHasEightRowsWithCorrectBoundaryButtonsAndNoMissingItems() {
        Map<String,Long> items=new HashMap<>(); for(int i=1;i<=17;i++) items.put("TEST_"+i,(long)i);
        add(alex,"Alex",items);
        var first=run(true,true,"Alex"); var middle=run(true,true,"player","Alex","2"); var last=run(true,true,"Alex","3");
        assertEquals(10,first.size()); assertEquals(10,middle.size()); assertEquals(3,last.size());
        assertEquals(List.of("/topitem player Alex 2"),clicks(first.getLast()));
        assertEquals(List.of("/topitem player Alex 1","/topitem player Alex 3"),clicks(middle.getLast()));
        assertEquals(List.of("/topitem player Alex 2"),clicks(last.getLast()));
        assertTrue(text(first).contains("1. TEST_17 — 17 шт."));
        assertTrue(text(last).contains("17. TEST_1 — 1 шт."));
    }
    @Test void itemPriorityAndExplicitPlayerResolveMatchingNickname() {
        add(alex,"Stone",Map.of("STONE",7L,"DIAMOND",9L));
        assertTrue(text(run(true,true,"stone")).contains("топ владельцев"));
        assertTrue(text(run(true,true,"player","Stone")).contains("ресурсы хранилищ"));
        assertTrue(text(run(true,true,"Stone","diamond")).contains("Алмаз: 9 шт."));
    }
    @Test void validatesPagesUnknownNamesUnknownItemsPermissionsAndReadiness() {
        add(alex,"Alex",Map.of("DIAMOND",1L));
        for(String page:List.of("0","-1","2")) assertTrue(text(run(true,true,"Alex",page)).contains("от 1 до 1"));
        assertTrue(text(run(true,true,"Alex","9999999999999")).contains("слишком большой"));
        assertTrue(text(run(true,true,"Missing")).contains("не найден"));
        assertTrue(text(run(true,true,"Alex","nonsense")).contains("Неизвестный предмет"));
        assertTrue(text(run(false,true,"Alex")).contains("Нет права"));
        assertTrue(text(run(true,false,"Alex")).contains("загружается"));
    }
    @Test void emptyOwnerRenamesAndRemovedContainersDoNotKeepOldTotals() {
        Snapshot first=add(alex,"Alex",Map.of("DIAMOND",4L)); add(alex,"Alex",Map.of());
        catalogue.remove(first.sign(),first.generation(),2);
        assertTrue(text(run(true,true,"Alex")).contains("пока нет предметов"));
        catalogue.rename(alex,"NewName");
        assertTrue(text(run(true,true,"Alex")).contains("не найден"));
        assertTrue(text(run(true,true,"NewName")).contains("пока нет предметов"));
    }
    @Test void duplicateNicknamesDoNotMergeDifferentPlayers() {
        add(alex,"Alex",Map.of("DIAMOND",1L)); add(UUID.randomUUID(),"Alex",Map.of("DIAMOND",2L));
        assertTrue(text(run(true,true,"Alex")).contains("несколько UUID"));
    }
    @Test void bundledRussianNamesCoverCommonItemsAndContainers() {
        assertEquals("Алмаз",RussianItems.name("DIAMOND"));
        assertEquals("Железный слиток",RussianItems.name("IRON_INGOT"));
        assertEquals("Бочка",RussianItems.name("BARREL"));
        assertNotEquals("RED_SHULKER_BOX",RussianItems.name("RED_SHULKER_BOX"));
    }
}

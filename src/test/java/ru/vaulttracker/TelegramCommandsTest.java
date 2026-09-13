package ru.vaulttracker;

import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramCommandsTest {
    private final Catalogue catalogue=new Catalogue(v -> {});
    private final StorageEngine storage=mock(StorageEngine.class);
    private final TelegramCommands commands=new TelegramCommands(catalogue,storage,1,
            id->Set.of("DIAMOND","IRON_INGOT").contains(id));

    @BeforeEach void prepare() {
        when(storage.ready()).thenReturn(true);
        UUID world=UUID.randomUUID();
        catalogue.register(new BlockKey(world,1,2,3),UUID.randomUUID(),"Alex",List.of(new BlockKey(world,1,2,4)),
                Map.of("DIAMOND",2000L,"IRON_INGOT",50L),1,100);
        catalogue.register(new BlockKey(world,5,2,3),UUID.randomUUID(),"Bob",List.of(new BlockKey(world,5,2,4)),
                Map.of("DIAMOND",10L),1,100);
    }

    @Test void topAndPlayerListsUsePrivateInteractivePages() {
        Material diamond=mock(Material.class); when(diamond.getMaxStackSize()).thenReturn(64);
        Material iron=mock(Material.class); when(iron.getMaxStackSize()).thenReturn(64);
        try(var materials=mockStatic(Material.class)) {
            materials.when(()->Material.getMaterial("DIAMOND")).thenReturn(diamond);
            materials.when(()->Material.getMaterial("IRON_INGOT")).thenReturn(iron);
            var top=commands.handle(42,10,20,"/topitem@VaultBot daimond",false);
            assertTrue(top.text().contains("Алмаз")); assertTrue(top.text().contains("Alex — 2000 шт. (1 шалкер + 272 шт.)"));
            assertEquals(1,top.buttons().size());
            var denied=commands.callback(99,top.buttons().getFirst().data());
            assertTrue(denied.alert()); assertNull(denied.view());
            var second=commands.callback(42,top.buttons().getFirst().data());
            assertTrue(second.view().text().contains("Bob — 10 шт."));

            var player=commands.handle(42,10,20,"/topitem Alex",false);
            assertTrue(player.text().contains("Ресурсы Alex")); assertFalse(player.buttons().isEmpty());
            var quantity=commands.handle(42,10,20,"/topitem Alex diamond",false);
            assertEquals("Alex — Алмаз: 2000 шт. (1 шалкер + 272 шт.)",quantity.text());
            assertTrue(commands.handle(42,10,20,"/item алмаз",false).text().contains("Alex — 2000 шт."));
            assertTrue(commands.handle(42,10,20,"/item железный слиток",false).text().contains("Alex — 50 шт."));
            assertEquals("Alex — Алмаз: 2000 шт. (1 шалкер + 272 шт.)",
                    commands.handle(42,10,20,"/item Alex алмаз",false).text());
        }
    }

    @Test void restrictsAdministrativeCommandsAndShowsUserId() {
        when(storage.status()).thenReturn("Локальный каталог: работает");
        assertTrue(commands.handle(77,42,12345,"/status",false).text().contains("только администратору"));
        assertTrue(commands.handle(77,42,12345,"/status",true).text().contains("Локальный каталог: работает"));
        assertEquals("ID пользователя: 77\nID этого чата: 42\nID этой темы: 12345",
                commands.handle(77,42,12345,"/id",false).text());
        assertTrue(commands.handle(77,42,12345,"/unknown",false).text().contains("/topitem"));
    }

    @Test void splitsTelegramMessagesWithoutLosingText() {
        String input="line one\n"+"x".repeat(80)+"\nlast";
        var parts=TelegramApi.split(input,40);
        assertTrue(parts.stream().allMatch(part->part.length()<=40));
        assertEquals(input.replace("\n",""),String.join("",parts).replace("\n",""));
    }

    @Test void keepsItemQueriesButMarksServiceCommandsForDeletion() {
        assertTrue(TelegramBotService.keepCommand("/item алмаз"));
        assertTrue(TelegramBotService.keepCommand("/topitem Alex"));
        assertTrue(TelegramBotService.keepCommand("/itemtop AR"));
        assertFalse(TelegramBotService.keepCommand("/status"));
        assertFalse(TelegramBotService.keepCommand("/id"));
    }
}

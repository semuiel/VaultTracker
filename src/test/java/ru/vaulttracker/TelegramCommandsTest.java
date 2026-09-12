package ru.vaulttracker;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.bukkit.Material;

class TelegramCommandsTest {
    private final Catalogue catalogue=new Catalogue(v -> {});
    private final StorageEngine storage=mock(StorageEngine.class);
    private final TelegramCommands commands=new TelegramCommands(catalogue,storage,20);
    @Test void searchesInMemoryCatalogueWithAliasesAndRussianNames() {
        UUID world=UUID.randomUUID();
        catalogue.register(new BlockKey(world,1,2,3),UUID.randomUUID(),"Alex",List.of(new BlockKey(world,1,2,4)),Map.of("DIAMOND",2000L),1,100);
        Material diamond=mock(Material.class); when(diamond.getMaxStackSize()).thenReturn(64);
        try(var materials=mockStatic(Material.class)) {
            materials.when(()->Material.getMaterial("DIAMOND")).thenReturn(diamond);
            String answer=commands.handle(42,"/topitem@VaultBot daimond");
            assertTrue(answer.contains("Алмаз")); assertTrue(answer.contains("Alex — 2000 шт. (1 шалкер + 272 шт.)"));
            assertEquals(answer,commands.handle(42,"/find diamond"));
            assertTrue(commands.handle(42,"/items").contains("Алмаз — 2000"));
        }
    }
    @Test void supportsStatusIdHelpAndUnknownCommands() {
        when(storage.status()).thenReturn("Локальный каталог: работает");
        assertEquals("ID этого чата: 42",commands.handle(42,"/id"));
        assertTrue(commands.handle(42,"/status").contains("Локальный каталог: работает"));
        assertTrue(commands.handle(42,"/start").contains("/topitem"));
        assertTrue(commands.handle(42,"/unknown").contains("Неизвестная команда"));
    }
    @Test void splitsTelegramMessagesWithoutLosingText() {
        String input="line one\n"+"x".repeat(80)+"\nlast";
        var parts=TelegramApi.split(input,40);
        assertTrue(parts.stream().allMatch(part->part.length()<=40));
        assertEquals(input.replace("\n",""),String.join("",parts).replace("\n",""));
    }
}

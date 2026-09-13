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
            id->Set.of("DIAMOND","IRON_INGOT","DIAMOND_ORE","DEEPSLATE_DIAMOND_ORE",
                    "EMERALD_ORE","DEEPSLATE_EMERALD_ORE").contains(id));

    @BeforeEach void prepare() {
        when(storage.ready()).thenReturn(true);
        UUID world=UUID.randomUUID();
        catalogue.register(new BlockKey(world,1,2,3),UUID.randomUUID(),"Alex",List.of(new BlockKey(world,1,2,4)),
                Map.of("DIAMOND",2000L,"IRON_INGOT",50L,"DIAMOND_ORE",4L,"DEEPSLATE_DIAMOND_ORE",6L,
                        "EMERALD_ORE",2L,"DEEPSLATE_EMERALD_ORE",3L),1,100);
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
            assertTrue(commands.handle(42,10,20,"/itemtop АР",false).text().contains("Alex — 10 шт."));
            assertEquals("Alex — Изумрудная руда (обычная + глубинная): 5 шт.",
                    commands.handle(42,10,20,"/itemtop Alex ИР",false).text());
        }
    }

    @Test void restrictsAdministrativeCommands() {
        when(storage.status()).thenReturn("Локальный каталог: работает");
        assertTrue(commands.handle(77,42,12345,"/status",false).text().contains("только администратору"));
        assertTrue(commands.handle(77,42,12345,"/status",true).text().contains("Локальный каталог: работает"));
        assertTrue(commands.handle(77,42,12345,"/unknown",false).text().contains("/topitem"));
    }

    @Test void splitsTelegramMessagesWithoutLosingText() {
        String input="line one\n"+"x".repeat(80)+"\nlast";
        var parts=TelegramApi.split(input,40);
        assertTrue(parts.stream().allMatch(part->part.length()<=40));
        assertEquals(input.replace("\n",""),String.join("",parts).replace("\n",""));
    }

    @Test void idLogsCopyableConfigurationWithoutReplyInAllowedAndBlockedTopics() throws Exception {
        TelegramConfig config=mock(TelegramConfig.class);
        var logger=mock(java.util.logging.Logger.class);
        var process=TelegramBotService.class.getDeclaredMethod("process",TelegramApi.Incoming.class);
        process.setAccessible(true);
        try(var apis=mockConstruction(TelegramApi.class);
            var service=new TelegramBotService(config,catalogue,storage,logger)) {
            var api=apis.constructed().getFirst();
            for(boolean allowed:List.of(true,false)) {
                for(int topic:List.of(20,0)) {
                    when(config.allowed(-1001234567890L,topic)).thenReturn(allowed);
                    for(String text:List.of("/id", "/id@VaultBot")) {
                        process.invoke(service,new TelegramApi.Incoming(1,-1001234567890L,topic,42,100,text,null,null));
                        var logged=org.mockito.ArgumentCaptor.forClass(String.class);
                        verify(logger).info(logged.capture());
                        String message=logged.getValue();
                        assertTrue(message.contains("chats:\n  - isDefault: true\n    chatId: -1001234567890\n    topicId: "+topic));
                        assertTrue(message.contains("adminUserIds:\n  - 42"));
                        verify(api).delete(-1001234567890L,100);
                        verifyNoMoreInteractions(api);
                        clearInvocations(api,logger);
                    }
                }
            }
            doThrow(new java.io.IOException("No deletion permission")).when(api).delete(-1001234567890L,100);
            process.invoke(service,new TelegramApi.Incoming(1,-1001234567890L,20,42,100,"/id",null,null));
            verify(logger).info(contains("chatId: -1001234567890"));
            verify(api,never()).send(anyLong(),anyInt(),any());
        }
    }

    @Test void ignoresConversationButStillHandlesButtonsWithoutMessageText() throws Exception {
        TelegramConfig config=mock(TelegramConfig.class);
        var process=TelegramBotService.class.getDeclaredMethod("process",TelegramApi.Incoming.class);
        process.setAccessible(true);
        try(var apis=mockConstruction(TelegramApi.class);
            var service=new TelegramBotService(config,catalogue,storage,java.util.logging.Logger.getAnonymousLogger())) {
            var api=apis.constructed().getFirst();
            for(boolean allowed:List.of(true,false)) {
                when(config.allowed(10,20)).thenReturn(allowed);
                for(String text:Arrays.asList(".", "ы", "а", "просто пишу", "Пример /item алмаз", "", "   ", null)) {
                    process.invoke(service,new TelegramApi.Incoming(1,10,20,42,800,text,null,null));
                }
                verifyNoInteractions(api);
            }
            when(config.allowed(10,20)).thenReturn(true);
            process.invoke(service,new TelegramApi.Incoming(1,10,20,42,900,null,"callback-1","vt:expired:2"));
            verify(api).answerCallback(eq("callback-1"),anyString(),eq(true));
            verifyNoMoreInteractions(api);
        }
    }

    @Test void deletesCommandMessagesWithArgumentsAfterSendingReply() throws Exception {
        TelegramConfig config=mock(TelegramConfig.class);
        when(config.allowed(10,20)).thenReturn(true);
        when(config.pageSize()).thenReturn(8);
        when(config.messageLifetimeSeconds()).thenReturn(300);
        when(storage.ready()).thenReturn(false);
        var process=TelegramBotService.class.getDeclaredMethod("process",TelegramApi.Incoming.class);
        process.setAccessible(true);
        try(var apis=mockConstruction(TelegramApi.class);
            var service=new TelegramBotService(config,catalogue,storage,java.util.logging.Logger.getAnonymousLogger())) {
            var api=apis.constructed().getFirst();
            when(api.send(eq(10L),eq(20),any())).thenReturn(900);
            int messageId=100;
            for(String text:List.of("/item", "/item алмаз", "/item Alex алмаз", "/item глубинная алмазная руда",
                    "/topitem Alex", "/itemtop АР", "/item@VaultBot алмаз", "/status")) {
                process.invoke(service,new TelegramApi.Incoming(1,10,20,42,messageId,text,null,null));
                var order=inOrder(api);
                order.verify(api).send(eq(10L),eq(20),any());
                order.verify(api).delete(10,messageId);
                verify(api,never()).delete(10,900);
                clearInvocations(api);
                messageId++;
            }
            process.invoke(service,new TelegramApi.Incoming(1,10,20,42,800,"обычное сообщение",null,null));
            verifyNoInteractions(api);
            when(config.allowed(10,20)).thenReturn(false);
            clearInvocations(api);
            process.invoke(service,new TelegramApi.Incoming(1,10,20,42,801,"/item алмаз",null,null));
            verify(api).delete(10,801);
            verify(api,never()).send(anyLong(),anyInt(),any());
        }
    }
}

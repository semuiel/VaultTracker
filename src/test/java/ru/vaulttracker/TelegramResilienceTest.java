package ru.vaulttracker;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramResilienceTest {
    @TempDir Path path;
    @Test void expiredCallbacksDoNotStopPollingAndOffsetsAdvanceBeforeProcessing() throws Exception {
        var config=mock(TelegramConfig.class);when(config.pageSize()).thenReturn(8);when(config.offsetFile()).thenReturn(path.resolve("offset"));
        when(config.retry()).thenReturn(new TelegramConfig.Retry(10,1,2));when(config.chats()).thenReturn(List.of());when(config.allowedChatIds()).thenReturn(Set.of());
        var storage=mock(StorageEngine.class);var catalogue=new Catalogue(v->{});
        try(var apis=mockConstruction(TelegramApi.class);var service=new TelegramBotService(config,catalogue,storage,Logger.getAnonymousLogger())) {
            var api=apis.constructed().getFirst();when(api.verify()).thenReturn("TestBot");
            AtomicInteger rounds=new AtomicInteger();
            when(api.updates(anyLong())).thenAnswer(call-> {
                int n=rounds.incrementAndGet();assertEquals((long)n-1,(long)call.getArgument(0,Long.class));
                if(n<=12) return List.of(new TelegramApi.Incoming(n-1,42,0,42,1,null,"old-"+n,"vm:expired:0",true));
                if(n==13) return List.of(new TelegramApi.Incoming(12,42,0,42,2,"/start",null,null,true));
                var field=TelegramBotService.class.getDeclaredField("stopping");field.setAccessible(true);((AtomicBoolean)field.get(service)).set(true);return List.of();
            });
            doAnswer(call-> {
                assertEquals(rounds.get(),Long.parseLong(Files.readString(path.resolve("offset"))));
                throw new IOException("Telegram API: Bad Request: query is too old and response timeout expired or query ID is invalid");
            }).when(api).answerCallback(anyString(),anyString(),anyBoolean());
            var run=TelegramBotService.class.getDeclaredMethod("run");run.setAccessible(true);run.invoke(service);
            verify(api,times(12)).answerCallback(anyString(),anyString(),anyBoolean());verify(api).send(eq(42L),eq(0),any());
            assertEquals("13",Files.readString(path.resolve("offset")));assertEquals(14,rounds.get());
        }
    }
    @Test void unchangedMessageAndOtherReplyFailuresDoNotPoisonNextUpdate() throws Exception {
        var config=mock(TelegramConfig.class);when(config.pageSize()).thenReturn(8);
        try(var apis=mockConstruction(TelegramApi.class);var service=new TelegramBotService(config,new Catalogue(v->{}),mock(StorageEngine.class),Logger.getAnonymousLogger())) {
            var api=apis.constructed().getFirst();
            when(api.send(eq(42L),eq(0),any())).thenThrow(new IOException("message is not modified")).thenReturn(10);
            service.consume(new TelegramApi.Incoming(1,42,0,42,1,"/start",null,null,true));
            service.consume(new TelegramApi.Incoming(2,42,0,42,2,"/start",null,null,true));verify(api,times(2)).send(eq(42L),eq(0),any());
        }
        assertTrue(TelegramApi.benignCallbackError(new IOException("message is not modified")));
        assertFalse(TelegramApi.benignCallbackError(new IOException("Unauthorized")));
    }
}

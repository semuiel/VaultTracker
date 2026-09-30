package ru.vaulttracker;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupportDeliveryTest {
    @TempDir Path folder;
    final AtomicLong clock=new AtomicLong(System.currentTimeMillis());
    GuardService guard;TelegramApi api;SupportService service;
    @BeforeEach void setup() throws Exception {
        open();api=mock(TelegramApi.class);service=new SupportService(guard,api,new SupportTopics(-100,11,12,13));
        String code=guard.generate(42).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH);
        guard.link(UUID.randomUUID(),"Author",code).get();
        when(api.sendDocument(eq(-100L),eq(13),anyString(),anyString())).thenReturn(90);
        when(api.sendHtml(eq(-100L),eq(13),anyString(),eq(false),anyList())).thenReturn(100);
    }
    void open() throws Exception {guard=new GuardService(folder.resolve("guard"),new GuardConfig(true,0),Logger.getAnonymousLogger(),clock::get);}
    @AfterEach void close() {guard.close();}
    @Test void failedAttachmentResumesAfterRestartWithoutRepeatingSavedFiles() throws Exception {
        when(api.copyMessage(42,7,-100,13)).thenReturn(91);
        when(api.copyMessage(42,8,-100,13)).thenThrow(new IOException("offline")).thenReturn(92);
        var queued=service.submit(SupportTicket.Type.BUG,42,guard.account(42).get(),null,null,"Bug",List.of(7,8));
        assertEquals("DELIVERY_PENDING",queued.status());
        assertEquals(List.of(91),guard.support(queued.id()).get().adminMedia());
        verify(api,never()).delete(anyLong(),anyInt());
        guard.close();open();service=new SupportService(guard,api,new SupportTopics(-100,11,12,13));
        clock.addAndGet(60_001);service.retryDeliveries();
        var delivered=guard.support(queued.id()).get();
        assertEquals("OPEN",delivered.status());assertEquals(List.of(91,92),delivered.adminMedia());
        assertEquals(100,delivered.adminMessage());assertTrue(guard.pendingSupport().get().isEmpty());
        verify(api,times(1)).sendDocument(anyLong(),anyInt(),anyString(),anyString());
        verify(api,times(1)).copyMessage(42,7,-100,13);
        service.retryDeliveries();verify(api,times(1)).sendHtml(anyLong(),anyInt(),anyString(),anyBoolean(),anyList());
    }
    @Test void requestIsDurableBeforeFirstTelegramCallAndCardFailureKeepsMaterials() throws Exception {
        when(api.sendDocument(anyLong(),anyInt(),anyString(),anyString())).thenAnswer(call->{
            assertEquals(1,guard.pendingSupport().get().size());return 90;
        });
        when(api.sendHtml(anyLong(),anyInt(),anyString(),anyBoolean(),anyList())).thenThrow(new IOException("offline")).thenReturn(100);
        var queued=service.submit(SupportTicket.Type.BUG,42,guard.account(42).get(),null,null,"Bug",List.of());
        assertEquals("DELIVERY_PENDING",queued.status());
        clock.addAndGet(60_001);service.retryDeliveries();
        assertEquals("OPEN",guard.support(queued.id()).get().status());
        verify(api,times(1)).sendDocument(anyLong(),anyInt(),anyString(),anyString());
    }
}

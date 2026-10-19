package ru.vaulttracker;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ComplaintNotificationsTest {
    static final long CHAT=-1002748891862L;
    @TempDir Path directory;
    final AtomicLong clock=new AtomicLong(System.currentTimeMillis());
    GuardService guard;
    TelegramApi api=mock(TelegramApi.class);
    AdminMenu admin=mock(AdminMenu.class);
    SupportService service;
    @BeforeEach void setup() throws Exception {open();guard.rememberTelegram(99,"Admin @admin");guard.rememberTelegram(88,"Root @root");}
    void open() throws Exception {
        guard=new GuardService(directory.resolve("guard"),new GuardConfig(true,0,88),Logger.getAnonymousLogger(),clock::get);
        guard.configure(new GuardConfig(true,0,88),Set.of(99L));
        service=new SupportService(guard,api,new SupportTopics(CHAT,11,12,13));
    }
    @AfterEach void close() {guard.close();}
    SupportTicket ticket(SupportTicket.Type type) throws Exception {
        var ticket=new SupportTicket(UUID.randomUUID().toString().replace("-",""),type,42,UUID.randomUUID(),"Author",
                UUID.randomUUID(),"Target","Описание жалобы",clock.get(),CHAT,type==SupportTicket.Type.COMPLAINT?11:type==SupportTicket.Type.BUG?13:12,100,List.of(),"OPEN");
        guard.saveSupport(ticket).get();return ticket;
    }
    void click(SupportTicket ticket,long actor,String operation) throws Exception {
        service.callback(new TelegramApi.Incoming(1,CHAT,ticket.adminTopic(),actor,100,null,"cb","vs:"+ticket.id()+":"+operation,false),admin);
    }
    List<String> sent(int count) throws Exception {
        var messages=ArgumentCaptor.forClass(TelegramCommands.View.class);
        verify(api,times(count)).send(eq(42L),eq(0),messages.capture());
        return messages.getAllValues().stream().map(TelegramCommands.View::text).toList();
    }
    @Test void takeAndCloseNotifyOnlyAuthorWithLinkedGameNameAndClosingAdmin() throws Exception {
        String code=guard.generate(99).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH);
        guard.link(UUID.randomUUID(),"GameAdmin",code).get();
        var ticket=ticket(SupportTicket.Type.COMPLAINT);
        click(ticket,99,"take");click(ticket,99,"take");click(ticket,88,"close");click(ticket,88,"close");
        var messages=sent(2);
        assertTrue(messages.get(0).contains("взялся администратор GameAdmin"));
        assertFalse(messages.get(0).contains("Admin @admin"));
        assertTrue(messages.get(0).contains("в работе"));assertTrue(messages.get(0).contains("Target"));
        assertTrue(messages.get(0).contains(ticket.id().substring(0,8)));
        assertTrue(messages.get(1).contains("Root @root"));assertTrue(messages.get(1).contains("рассмотрена, закрыта"));
        assertFalse(messages.get(1).contains("Меры приняты"));
        assertTrue(guard.pendingComplaintNotifications().get().isEmpty());
        verify(api,never()).send(eq(99L),anyInt(),any());
        verify(api,never()).send(eq(88L),anyInt(),any());
    }
    @Test void firstSuccessfulModerationActionNotifiesAssignmentOnlyOnce() throws Exception {
        var ticket=ticket(SupportTicket.Type.COMPLAINT);
        click(ticket,99,"kick");click(ticket,99,"ban5");
        var messages=sent(1);
        assertTrue(messages.getFirst().contains("взялся администратор Admin @admin"));
        assertFalse(messages.getFirst().contains("Меры приняты"));
        assertEquals(99,guard.support(ticket.id()).get().takenActor());
        verify(admin).perform(99,"kick",ticket.targetUuid().toString());
        verify(admin).perform(99,"ban",ticket.targetUuid().toString());
        click(ticket,88,"close");assertEquals(2,sent(2).size());
    }
    @Test void unauthorizedOrWrongTopicCallbacksNeverNotify() throws Exception {
        var ticket=ticket(SupportTicket.Type.COMPLAINT);
        click(ticket,42,"take");click(ticket,42,"close");
        service.callback(new TelegramApi.Incoming(1,CHAT,12,99,100,null,"cb","vs:"+ticket.id()+":take",false),admin);
        assertEquals("OPEN",guard.support(ticket.id()).get().status());
        assertTrue(guard.pendingComplaintNotifications().get().isEmpty());
        verify(api,never()).send(anyLong(),anyInt(),any());verifyNoInteractions(admin);
    }
    @Test void failedModerationDoesNotClaimAssignmentOrMeasures() throws Exception {
        var ticket=ticket(SupportTicket.Type.COMPLAINT);
        doThrow(new IOException("offline")).when(admin).perform(99,"kick",ticket.targetUuid().toString());
        click(ticket,99,"kick");
        assertEquals("FAILED_KICK",guard.support(ticket.id()).get().status());
        verify(api,never()).send(anyLong(),anyInt(),any());
        click(ticket,88,"close");
        assertTrue(sent(1).getFirst().contains("рассмотрена, закрыта"));
        assertFalse(sent(1).getFirst().contains("Меры приняты"));
    }
    @Test void cardEditFailureDoesNotLoseCommittedAuthorNotification() throws Exception {
        var ticket=ticket(SupportTicket.Type.COMPLAINT);
        doThrow(new IOException("edit offline")).when(api).editHtml(anyLong(),anyInt(),anyString(),anyList());
        click(ticket,99,"take");
        assertEquals("IN_PROGRESS",guard.support(ticket.id()).get().status());
        assertTrue(sent(1).getFirst().contains("в работе"));
        click(ticket,99,"take");service.retryDeliveries();sent(1);
    }
    @Test void failedDeliverySurvivesRestartAndPreservesStatusOrder() throws Exception {
        var ticket=ticket(SupportTicket.Type.COMPLAINT);
        when(api.send(eq(42L),eq(0),any())).thenThrow(new IOException("offline"));
        click(ticket,99,"take");click(ticket,88,"close");
        assertEquals("CLOSED",guard.support(ticket.id()).get().status());
        sent(1); // Closing notification waits behind the failed assignment message.
        guard.close();open();
        when(api.send(eq(42L),eq(0),any())).thenReturn(200);
        clock.addAndGet(60_001);service.retryDeliveries();service.retryDeliveries();service.retryDeliveries();
        var messages=sent(3);
        assertTrue(messages.get(1).contains("Admin @admin"));
        assertTrue(messages.get(1).contains("в работе"));assertTrue(messages.get(2).contains("рассмотрена, закрыта"));
        assertTrue(guard.pendingComplaintNotifications().get().isEmpty());
    }
    @Test void reopenInformsAuthorAndAllowsAnotherAssignment() throws Exception {
        var ticket=ticket(SupportTicket.Type.COMPLAINT);
        click(ticket,99,"close");click(ticket,88,"reopen");click(ticket,99,"take");
        var messages=sent(3);
        assertTrue(messages.get(1).contains("открыл вашу жалобу снова"));
        assertTrue(messages.get(2).contains("взялся администратор"));
    }
    @Test void bugAndSuggestionNotificationsRemainUnchanged() throws Exception {
        for(var type:List.of(SupportTicket.Type.BUG,SupportTicket.Type.SUGGESTION)) {
            var ticket=ticket(type);click(ticket,88,"take");click(ticket,88,"close");click(ticket,88,"reopen");
            assertEquals("OPEN",guard.support(ticket.id()).get().status());
        }
        verify(api,never()).send(anyLong(),anyInt(),any());
        assertTrue(guard.pendingComplaintNotifications().get().isEmpty());
    }
    @Test void staleStatusCannotQueueNotificationAndOtherTypesAreRejected() throws Exception {
        var ticket=ticket(SupportTicket.Type.COMPLAINT);
        assertFalse(guard.supportChange(ticket.withStatus("CLOSED"),"IN_PROGRESS","Не отправлять").get());
        assertEquals("OPEN",guard.support(ticket.id()).get().status());
        assertTrue(guard.pendingComplaintNotifications().get().isEmpty());
        var bug=ticket(SupportTicket.Type.BUG);
        assertThrows(java.util.concurrent.ExecutionException.class,()->guard.supportChange(bug.withStatus("CLOSED"),"OPEN","Не отправлять").get());
        assertEquals("OPEN",guard.support(bug.id()).get().status());
    }
}

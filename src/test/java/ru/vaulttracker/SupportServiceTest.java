package ru.vaulttracker;

import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SupportServiceTest {
    private static final long CHAT=-1002748891862L;
    @TempDir Path folder;
    GuardService guard;TelegramApi api;SupportService service;UUID author;
    @BeforeEach void setup() throws Exception {
        guard=new GuardService(folder.resolve("guard"),new GuardConfig(true,0,88),Logger.getAnonymousLogger());
        guard.configure(new GuardConfig(true,0,88),Set.of(99L));
        author=UUID.randomUUID();String code=guard.generate(42).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH);
        guard.link(author,"Author",code).get();
        api=mock(TelegramApi.class);service=new SupportService(guard,api,new SupportTopics(CHAT,11,12,13));
    }
    @AfterEach void close() {guard.close();}
    private SupportTicket ticket(SupportTicket.Type type,int topic,List<Integer> media) {
        return new SupportTicket(UUID.randomUUID().toString().replace("-",""),type,42,author,"Author",
                type==SupportTicket.Type.COMPLAINT?UUID.randomUUID():null,type==SupportTicket.Type.COMPLAINT?"Target":null,
                "Текст обращения",System.currentTimeMillis(),CHAT,topic,100,media,"OPEN");
    }
    private TelegramApi.Incoming callback(long user,SupportTicket ticket,String action) {
        return new TelegramApi.Incoming(1,CHAT,ticket.adminTopic(),user,100,null,"cb","vs:"+ticket.id()+":"+action,false);
    }
    @Test void copiesSeveralMediaToTopicAndPersistsAllIdsAcrossRestart() throws Exception {
        when(api.sendDocument(eq(CHAT),eq(11),anyString(),anyString())).thenReturn(90);
        when(api.sendHtml(eq(CHAT),eq(11),anyString(),eq(false),anyList())).thenReturn(100);
        when(api.copyMessage(eq(42L),anyInt(),eq(CHAT),eq(11))).thenReturn(101,102);
        var sent=service.submit(SupportTicket.Type.COMPLAINT,42,guard.account(42).get(),UUID.randomUUID(),"Target","Жалоба",List.of(7,8));
        assertEquals(List.of(101,102),sent.adminMedia());
        assertEquals(List.of(90),sent.forms());
        var order=inOrder(api);
        order.verify(api).sendDocument(eq(CHAT),eq(11),anyString(),contains("Жалоба"));
        order.verify(api).copyMessage(42,7,CHAT,11);order.verify(api).copyMessage(42,8,CHAT,11);
        order.verify(api).sendHtml(eq(CHAT),eq(11),contains("https://t.me/c/"),eq(false),anyList());
        assertEquals(sent,guard.support(sent.id()).get());
        guard.close();guard=new GuardService(folder.resolve("guard"),new GuardConfig(true,0,88),Logger.getAnonymousLogger());
        assertEquals(sent,guard.support(sent.id()).get());
    }
    @Test void unconfiguredTopicDoesNotSendOrPersist() throws Exception {
        var unavailable=new SupportService(guard,api,new SupportTopics(CHAT,0,0,0));
        assertThrows(IllegalStateException.class,()->unavailable.submit(SupportTicket.Type.BUG,42,guard.account(42).get(),null,null,"Баг",List.of()));
        verifyNoInteractions(api);
    }
    @Test void adminStatusIsExclusiveAndUnauthorizedCallbackCannotChangeIt() throws Exception {
        var request=ticket(SupportTicket.Type.SUGGESTION,12,List.of());guard.saveSupport(request).get();
        var admin=mock(AdminMenu.class);
        service.callback(callback(99,request,"doing"),admin);
        assertEquals("OPEN",guard.support(request.id()).get().status());
        service.callback(callback(99,request,"ignore"),admin);
        assertEquals("OPEN",guard.support(request.id()).get().status());
        service.callback(callback(88,request,"doing"),admin);
        assertEquals("DOING",guard.support(request.id()).get().status());
        service.callback(callback(88,request,"vote"),admin);
        assertEquals("VOTE",guard.support(request.id()).get().status());
        verify(api,times(2)).editHtml(eq(CHAT),eq(100),anyString(),anyList());
    }
    @Test void ignoreDeletesOnlyAdminMessageAndEveryCopiedMedia() throws Exception {
        var request=ticket(SupportTicket.Type.BUG,13,List.of(101,102));guard.saveSupport(request).get();
        service.callback(callback(99,request,"ignore"),mock(AdminMenu.class));
        assertEquals("OPEN",guard.support(request.id()).get().status());
        verify(api,never()).delete(anyLong(),anyInt());
        service.callback(callback(88,request,"ignore"),mock(AdminMenu.class));
        assertEquals("IGNORED",guard.support(request.id()).get().status());
        verify(api).delete(CHAT,101);verify(api).delete(CHAT,102);verify(api).delete(CHAT,100);
        verify(api,never()).delete(eq(42L),anyInt());
    }
    @Test void closeKeepsCardAndMediaAndOffersReopenForEveryRequestType() throws Exception {
        for(var type:List.of(SupportTicket.Type.COMPLAINT,SupportTicket.Type.SUGGESTION,SupportTicket.Type.BUG)) {
            int topic=type==SupportTicket.Type.COMPLAINT?11:type==SupportTicket.Type.SUGGESTION?12:13;
            long moderator=type==SupportTicket.Type.COMPLAINT?99:88;
            var request=ticket(type,topic,List.of(101,102));guard.saveSupport(request).get();
            clearInvocations(api);
            service.callback(callback(moderator,request,"close"),mock(AdminMenu.class));
            assertEquals("CLOSED",guard.support(request.id()).get().status());
            verify(api).editHtml(eq(CHAT),eq(100),contains("Статус: Закрыто"),argThat(buttons->buttons.size()==1 &&
                    buttons.get(0).data().endsWith(":reopen")));
            verify(api,never()).delete(anyLong(),anyInt());
            service.callback(callback(moderator,request,"close"),mock(AdminMenu.class));
            verify(api,times(1)).editHtml(eq(CHAT),eq(100),anyString(),anyList());
        }
    }
    @Test void submittedRequestUsesCloseButton() throws Exception {
        when(api.sendDocument(eq(CHAT),eq(13),anyString(),anyString())).thenReturn(90);
        when(api.sendHtml(eq(CHAT),eq(13),anyString(),eq(false),anyList())).thenReturn(100);
        service.submit(SupportTicket.Type.BUG,42,guard.account(42).get(),null,null,"Баг",List.of());
        verify(api).sendHtml(eq(CHAT),eq(13),anyString(),eq(false),argThat(buttons->buttons.stream()
                .anyMatch(button->button.text().equals("✅ Закрыть") && button.data().endsWith(":close"))));
    }
    @Test void takenAndClosedNamesPersistAndReopenResetsCurrentAssignment() throws Exception {
        guard.rememberTelegram(88,"Sema @sema");
        var request=ticket(SupportTicket.Type.BUG,13,List.of());guard.saveSupport(request).get();
        var admin=mock(AdminMenu.class);
        service.callback(callback(88,request,"take"),admin);
        var taken=guard.support(request.id()).get();
        assertEquals("IN_PROGRESS",taken.status());assertEquals(88,taken.takenActor());
        verify(api).editHtml(eq(CHAT),eq(100),contains("Взялся: Sema @sema"),anyList());
        String code=guard.generate(88).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH);
        guard.link(UUID.randomUUID(),"GameAdmin",code).get();
        service.callback(callback(88,request,"close"),admin);
        var closed=guard.support(request.id()).get();
        assertEquals(88,closed.takenActor());assertEquals(88,closed.closedActor());
        verify(api).editHtml(eq(CHAT),eq(100),argThat(text->text.contains("Взялся: GameAdmin") &&
                text.contains("Закрыл: GameAdmin")),anyList());
        guard.close();guard=new GuardService(folder.resolve("guard"),new GuardConfig(true,0,88),Logger.getAnonymousLogger());
        assertEquals(88,guard.support(request.id()).get().closedActor());
        service=new SupportService(guard,api,new SupportTopics(CHAT,11,12,13));
        service.callback(callback(88,request,"reopen"),admin);
        var reopened=guard.support(request.id()).get();
        assertEquals("OPEN",reopened.status());assertEquals(0,reopened.takenActor());assertEquals(0,reopened.closedActor());
    }
    @Test void bugStatusChangesAndWrongTopicCallbackIsRejected() throws Exception {
        var request=ticket(SupportTicket.Type.BUG,13,List.of());guard.saveSupport(request).get();
        var admin=mock(AdminMenu.class);
        service.callback(new TelegramApi.Incoming(1,CHAT,12,88,100,null,"cb","vs:"+request.id()+":urgent",false),admin);
        assertEquals("OPEN",guard.support(request.id()).get().status());
        service.callback(callback(99,request,"urgent"),admin);
        assertEquals("OPEN",guard.support(request.id()).get().status());
        service.callback(callback(88,request,"urgent"),admin);
        assertEquals("URGENT",guard.support(request.id()).get().status());
        assertEquals(88,guard.support(request.id()).get().takenActor());
        service.callback(callback(88,request,"later"),admin);
        assertEquals("LATER",guard.support(request.id()).get().status());
    }
    @Test void complaintActionsUseExistingAdminHandlerAndPermissionCheck() throws Exception {
        var request=ticket(SupportTicket.Type.COMPLAINT,11,List.of());guard.saveSupport(request).get();
        var admin=mock(AdminMenu.class);doThrow(new SecurityException()).when(admin).requireOperation(99,"permanentBan");
        service.callback(callback(99,request,"ban"),admin);
        assertEquals("OPEN",guard.support(request.id()).get().status());
        verify(admin,never()).perform(anyLong(),anyString(),anyString());
        service.callback(callback(99,request,"kick"),admin);
        verify(admin).requireOperation(99,"kick");
        verify(admin).perform(99,"kick",request.targetUuid().toString());
        assertEquals("ACTION_KICK",guard.support(request.id()).get().status());
    }
}

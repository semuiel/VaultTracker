package ru.vaulttracker;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UnbanSupportTest {
    private static final long CHAT=-1002748891862L;
    @TempDir Path folder;
    GuardService guard;TelegramApi api;TelegramModeration moderation;SupportService service;
    UUID player;
    @BeforeEach void setup() throws Exception {
        guard=new GuardService(folder.resolve("guard"),new GuardConfig(true,0,88),Logger.getAnonymousLogger());
        guard.configure(new GuardConfig(true,0,88),Set.of(99L));
        player=UUID.randomUUID();
        String code=guard.generate(42).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH);
        guard.link(player,"Player",code).get();
        api=mock(TelegramApi.class);moderation=mock(TelegramModeration.class);
        when(moderation.unbanFacts(player)).thenReturn(CompletableFuture.completedFuture(facts()));
        when(moderation.act(anyLong(),eq("unban"),eq(player))).thenReturn(CompletableFuture.completedFuture("Бан снят."));
        when(moderation.liftForConversation(anyLong(),eq(player),any())).thenReturn(CompletableFuture.completedFuture(null));
        when(moderation.restoreAfterConversation(eq(player),any(),anyLong(),anyBoolean())).thenReturn(CompletableFuture.completedFuture(null));
        when(moderation.freezeAvailable()).thenReturn(true);
        when(api.sendDocument(eq(CHAT),eq(14),anyString(),anyString())).thenReturn(90);
        when(api.sendHtml(eq(CHAT),eq(14),anyString(),eq(false),anyList())).thenReturn(100,101);
        when(api.send(eq(88L),eq(0),any())).thenReturn(201);
        when(api.send(eq(42L),eq(0),any())).thenReturn(301);
        when(api.copyMessage(eq(42L),eq(7),eq(CHAT),eq(14))).thenReturn(150);
        service=new SupportService(guard,api,new SupportTopics(CHAT,11,12,13,14),Logger.getAnonymousLogger(),moderation);
    }
    @AfterEach void close() {guard.close();}
    private TelegramModeration.UnbanFacts facts() {
        return new TelegramModeration.UnbanFacts("Player",player,
                new TelegramModeration.BanSnapshot("Нарушение правил",-1,"FLEXITY"),1000,2000,3000,3600L);
    }
    private SupportTicket submit(long tg,GuardService.Account account) throws Exception {
        return service.submitUnban(tg,account,player,"Player","1. Почему?\nОтвет\n\n11. Дополнительно?\nнет",List.of(7),moderation);
    }
    @Test void questionnaireDocumentPrecedesActionCard() throws Exception {
        var ticket=submit(42,guard.account(42).get());
        assertEquals(List.of(90),ticket.forms());
        var order=inOrder(api);
        order.verify(api).sendDocument(eq(CHAT),eq(14),eq("anketa-"+player+".md"),
                argThat(text->text.contains("1. Почему?") && text.contains("11. Дополнительно?")));
        order.verify(api).copyMessage(42,7,CHAT,14);
        order.verify(api).sendHtml(eq(CHAT),eq(14),argThat(text->text.contains("Анкета: файл выше") &&
                !text.contains("1. Почему?")),eq(false),argThat(buttons->buttons.size()==3));
    }
    @Test void closedCardShowsTelegramNameWhenAdminIsNotLinked() throws Exception {
        guard.rememberTelegram(88,"Sema @sema");
        var ticket=submit(42,guard.account(42).get());
        service.callback(callback("vs",ticket,"close",88,100,false),mock(AdminMenu.class));
        service.callback(callback("vc",ticket,"close",88,201,true),mock(AdminMenu.class));
        verify(api).editHtml(eq(CHAT),eq(100),argThat(text->text.contains("закрыл: Sema @sema") &&
                !text.contains("администратор TG 88")),anyList());
    }
    @Test void closedCardShowsGameNameWhenAdminIsLinked() throws Exception {
        String code=guard.generate(88).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH);
        guard.link(UUID.randomUUID(),"GameAdmin",code).get();
        var ticket=submit(42,guard.account(42).get());
        service.callback(callback("vs",ticket,"close",88,100,false),mock(AdminMenu.class));
        service.callback(callback("vc",ticket,"close",88,201,true),mock(AdminMenu.class));
        verify(api).editHtml(eq(CHAT),eq(100),argThat(text->text.contains("закрыл: GameAdmin") &&
                !text.contains("администратор TG 88")),anyList());
    }
    @Test void queuedUnbanSurvivesRestartAndReservesOnlyOneAttempt() throws Exception {
        when(api.sendDocument(anyLong(),anyInt(),anyString(),anyString())).thenThrow(new java.io.IOException("offline"));
        var ticket=submit(42,guard.account(42).get());
        assertEquals("DELIVERY_PENDING",ticket.status());
        assertEquals(1,guard.unbanState(ticket.id()).get().attempt());
        guard.close();guard=new GuardService(folder.resolve("guard"),new GuardConfig(true,0,88),Logger.getAnonymousLogger());
        assertEquals("DELIVERY_PENDING",guard.support(ticket.id()).get().status());
        assertThrows(Exception.class,()->guard.unbanAttempt(42,player,true).get());
        verify(api,never()).delete(anyLong(),anyInt());
    }
    @Test void missingBanExplainsWhyRequestWasNotSent() throws Exception {
        when(moderation.unbanFacts(player)).thenReturn(CompletableFuture.failedFuture(
                new IllegalArgumentException("У этого персонажа сейчас нет действующего бана.")));
        var failure=assertThrows(IllegalArgumentException.class,()->submit(42,guard.account(42).get()));
        assertEquals("У этого персонажа сейчас нет действующего бана.",failure.getMessage());
        verify(api,never()).sendHtml(anyLong(),anyInt(),anyString(),anyBoolean(),anyList());
    }
    private TelegramApi.Incoming callback(String prefix,SupportTicket ticket,String action,long user,int message,boolean privateChat) {
        return new TelegramApi.Incoming(1,privateChat?user:CHAT,privateChat?0:14,user,message,null,"callback",
                prefix+":"+ticket.id()+":"+action,privateChat);
    }
    private void confirm(SupportTicket ticket,String action) throws Exception {
        service.callback(callback("vs",ticket,action,88,ticket.adminMessage(),false),mock(AdminMenu.class));
        assertEquals("OPEN",guard.support(ticket.id()).get().status());
        service.callback(callback("vc",ticket,action,88,201,true),mock(AdminMenu.class));
    }
    @Test void linkedAttemptIsPersistentAndLimitedToThreeByUuid() throws Exception {
        for(int i=1;i<=3;i++) {
            var ticket=submit(42,guard.account(42).get());assertEquals(i,ticket.attempt());
            guard.supportStatus(ticket.id(),"OPEN","CLOSED").get();
        }
        assertThrows(Exception.class,()->submit(42,guard.account(42).get()));
        guard.close();guard=new GuardService(folder.resolve("guard"),new GuardConfig(true,0,88),Logger.getAnonymousLogger());
        assertThrows(Exception.class,()->guard.unbanAttempt(42,player,true).get());
    }
    @Test void unlinkedAttemptIsLimitedToTwoByTelegramIdEvenWithDifferentCharacter() throws Exception {
        UUID second=UUID.randomUUID();
        when(moderation.unbanFacts(second)).thenReturn(CompletableFuture.completedFuture(
                new TelegramModeration.UnbanFacts("Other",second,facts().ban(),0,0,0,null)));
        var first=submit(77,null);guard.supportStatus(first.id(),"OPEN","CLOSED").get();
        var next=service.submitUnban(77,null,second,"Other","1. A\nнет\n\n11. B\nнет",List.of(),moderation);
        assertEquals(2,next.attempt());guard.supportStatus(next.id(),"OPEN","CLOSED").get();
        assertThrows(Exception.class,()->service.submitUnban(77,null,second,"Other","1. A\nнет\n\n11. B\nнет",List.of(),moderation));
    }
    @Test void missingTopicAndUnauthorizedOrUnconfirmedActionDoNotChangeBan() throws Exception {
        var missing=new SupportService(guard,api,new SupportTopics(CHAT,11,12,13,0),Logger.getAnonymousLogger(),moderation);
        assertThrows(IllegalStateException.class,()->missing.submitUnban(42,guard.account(42).get(),player,"Player","11. A",List.of(),moderation));
        verifyNoInteractions(api);
        var ticket=submit(42,guard.account(42).get());
        assertEquals(List.of(150),ticket.adminMedia());
        verify(api).copyMessage(42,7,CHAT,14);
        clearInvocations(api);
        service.callback(callback("vs",ticket,"unban",77,100,false),mock(AdminMenu.class));
        verify(api,never()).send(eq(77L),anyInt(),any());
        service.callback(callback("vs",ticket,"unban",88,100,false),mock(AdminMenu.class));
        verify(moderation,never()).act(anyLong(),eq("unban"),any());
        service.callback(callback("vc",ticket,"unban",88,201,true),mock(AdminMenu.class));
        assertEquals("UNBANNED",guard.support(ticket.id()).get().status());
        verify(moderation,times(1)).act(88,"unban",player);
        service.callback(callback("vc",ticket,"unban",88,201,true),mock(AdminMenu.class));
        verify(moderation,times(1)).act(88,"unban",player);
        verify(api,never()).delete(eq(CHAT),anyInt());
    }
    @Test void revokedAdminPermissionAndAnotherUserCannotUseConfirmation() throws Exception {
        guard.capability(88,"unban",true).get();
        when(api.send(eq(99L),eq(0),any())).thenReturn(202);
        var ticket=submit(42,guard.account(42).get());
        service.callback(callback("vs",ticket,"unban",99,ticket.adminMessage(),false),mock(AdminMenu.class));
        service.callback(callback("vc",ticket,"unban",77,202,true),mock(AdminMenu.class));
        guard.capability(88,"unban",false).get();
        service.callback(callback("vc",ticket,"unban",99,202,true),mock(AdminMenu.class));
        assertEquals("OPEN",guard.support(ticket.id()).get().status());
        verify(moderation,never()).act(99,"unban",player);
    }
    @Test void closeRequiresConfirmationAndRetainsCardAndMedia() throws Exception {
        var ticket=submit(42,guard.account(42).get());
        service.callback(callback("vs",ticket,"close",88,100,false),mock(AdminMenu.class));
        assertEquals("OPEN",guard.support(ticket.id()).get().status());
        service.callback(callback("vc",ticket,"close",88,201,true),mock(AdminMenu.class));
        assertEquals("CLOSED",guard.support(ticket.id()).get().status());
        verify(api,never()).delete(eq(CHAT),anyInt());
        verify(api).editHtml(eq(CHAT),eq(100),contains("Статус: Закрыто"),eq(List.of()));
    }
    @Test void invitationAcceptOpensTemporaryWindowThenRestoresOriginalBan() throws Exception {
        var ticket=submit(42,guard.account(42).get());confirm(ticket,"talk");
        assertEquals("INVITED",guard.support(ticket.id()).get().status());
        service.callback(callback("vu",ticket,"accept",42,301,true),mock(AdminMenu.class));
        assertEquals("ACCESS",guard.support(ticket.id()).get().status());
        verify(moderation).liftForConversation(eq(88L),eq(player),any());
        var state=guard.unbanState(ticket.id()).get();
        guard.unbanAccess(ticket.id(),System.currentTimeMillis()-1,state.banReason(),state.banExpiry(),state.banSource()).get();
        service.expirePending();
        assertEquals("CLOSED",guard.support(ticket.id()).get().status());
        verify(moderation).restoreAfterConversation(eq(player),any(),eq(88L),eq(true));
        service.callback(callback("vu",ticket,"accept",42,301,true),mock(AdminMenu.class));
        verify(moderation,times(1)).liftForConversation(eq(88L),eq(player),any());
    }
    @Test void temporaryAccessDeadlineSurvivesGuardRestart() throws Exception {
        var ticket=submit(42,guard.account(42).get());confirm(ticket,"talk");
        service.callback(callback("vu",ticket,"accept",42,301,true),mock(AdminMenu.class));
        var state=guard.unbanState(ticket.id()).get();
        guard.unbanAccess(ticket.id(),System.currentTimeMillis()-1,state.banReason(),state.banExpiry(),state.banSource()).get();
        guard.close();guard=new GuardService(folder.resolve("guard"),new GuardConfig(true,0,88),Logger.getAnonymousLogger());
        service=new SupportService(guard,api,new SupportTopics(CHAT,11,12,13,14),Logger.getAnonymousLogger(),moderation);
        service.expirePending();
        assertEquals("CLOSED",guard.support(ticket.id()).get().status());
        verify(moderation).restoreAfterConversation(eq(player),any(),eq(88L),eq(true));
    }
    @Test void rejectAndExpiredInvitationNeverLiftBan() throws Exception {
        var ticket=submit(42,guard.account(42).get());confirm(ticket,"talk");
        service.callback(callback("vu",ticket,"reject",42,301,true),mock(AdminMenu.class));
        assertEquals("REJECTED",guard.support(ticket.id()).get().status());
        verify(moderation,never()).liftForConversation(anyLong(),any(),any());
        var another=submit(42,guard.account(42).get());confirm(another,"talk");
        guard.unbanInvite(another.id(),System.currentTimeMillis()-1,301,88).get();
        service.expirePending();assertEquals("EXPIRED",guard.support(another.id()).get().status());
        service.callback(callback("vu",another,"accept",42,301,true),mock(AdminMenu.class));
        verify(moderation,never()).liftForConversation(anyLong(),any(),any());
    }
    @Test void confirmedUnbanPendingAfterRestartCompletesWithCurrentPermission() throws Exception {
        var ticket=submit(42,guard.account(42).get());
        guard.supportStatus(ticket.id(),"OPEN","UNBAN_PENDING").get();guard.supportActor(ticket.id(),88).get();
        guard.close();guard=new GuardService(folder.resolve("guard"),new GuardConfig(true,0,88),Logger.getAnonymousLogger());
        service=new SupportService(guard,api,new SupportTopics(CHAT,11,12,13,14),Logger.getAnonymousLogger(),moderation);
        service.expirePending();
        assertEquals("UNBANNED",guard.support(ticket.id()).get().status());
        verify(moderation).act(88,"unban",player);
    }
    @Test void revokedUnbanPermissionDuringTemporaryAccessRestoresBan() throws Exception {
        var ticket=submit(42,guard.account(42).get());confirm(ticket,"talk");
        service.callback(callback("vu",ticket,"accept",42,301,true),mock(AdminMenu.class));
        guard.supportStatus(ticket.id(),"ACCESS","UNBAN_PENDING").get();guard.supportActor(ticket.id(),99).get();
        service.expirePending();
        assertEquals("CLOSED",guard.support(ticket.id()).get().status());
        verify(moderation,never()).act(99,"unban",player);
        verify(moderation).restoreAfterConversation(eq(player),any(),eq(88L),eq(true));
    }
}

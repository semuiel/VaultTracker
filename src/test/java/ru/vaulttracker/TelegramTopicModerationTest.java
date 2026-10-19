package ru.vaulttracker;

import java.nio.file.Path;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramTopicModerationTest {
    @TempDir Path folder;
    TelegramApi api=mock(TelegramApi.class);
    TelegramConfig config=mock(TelegramConfig.class);
    TelegramListExpiry expiry=mock(TelegramListExpiry.class);
    TelegramModerationRepository repository;
    TelegramTopicModeration moderation;
    AtomicLong now=new AtomicLong(1_000_000);
    static final long CHAT=-1002669159447L;
    @BeforeEach void setup() throws Exception {
        repository=new TelegramModerationRepository(folder);
        when(config.chats()).thenReturn(java.util.List.of());when(config.allowedChatIds()).thenReturn(Set.of(CHAT,CHAT-1));
        when(config.moderation()).thenReturn(new TelegramConfig.Topic(CHAT,400));
        doCallRealMethod().when(api).chatAdministrator(anyLong(),anyLong());
        when(api.memberStatus(CHAT,10)).thenReturn("administrator");when(api.canDeleteMessages(CHAT)).thenReturn(true);
        when(api.sendHtml(anyLong(),anyInt(),anyString(),anyBoolean())).thenReturn(99);
        moderation=new TelegramTopicModeration(api,config,repository,expiry,now::get);
    }
    TelegramApi.Incoming message(long chat,int topic,long actor,String text,TelegramApi.Reply reply,boolean anonymous) {
        return new TelegramApi.Incoming(1,chat,topic,actor,text.startsWith("/")?21:20,text,null,null,false,"@player",false,false,"Player",reply,anonymous);
    }
    TelegramApi.Reply target(){return new TelegramApi.Reply(2,42,"Player","text",false);}
    boolean command(int topic,String text) throws Exception {return moderation.consume(message(CHAT,topic,10,text,target(),false),"Bot");}
    @Test void restriction_is_topic_local_replaced_and_persistent() throws Exception {
        assertTrue(command(100,"/offtop 5"));assertEquals(now.get()+300000,repository.expiry(CHAT,100,42));
        assertFalse(moderation.consume(message(CHAT,101,42,"text",null,false),"Bot"));
        assertFalse(moderation.consume(message(CHAT-1,100,42,"text",null,false),"Bot"));
        now.addAndGet(1000);command(100,"/offtop@Bot 2");assertEquals(now.get()+120000,repository.expiry(CHAT,100,42));
        var restarted=new TelegramTopicModeration(api,config,new TelegramModerationRepository(folder),expiry,now::get);
        assertTrue(restarted.consume(message(CHAT,100,42,"text",null,false),"Bot"));verify(api).delete(CHAT,20);
        verify(api,times(2)).sendHtml(eq(CHAT),eq(100),contains("https://t.me/c/2669159447/400"),eq(true));
        verify(expiry,atLeastOnce()).track(CHAT,99,now.get()+60000);
        now.addAndGet(120000);assertFalse(restarted.consume(message(CHAT,100,42,"text",null,false),"Bot"));
    }
    @Test void real_admin_rechecked_every_command_and_anonymous_denied() throws Exception {
        command(100,"/offtop 5");when(api.memberStatus(CHAT,10)).thenReturn("member");
        command(100,"/unofftop");assertTrue(repository.expiry(CHAT,100,42)>now.get());
        verify(api,times(2)).memberStatus(CHAT,10);
        moderation.consume(message(CHAT,100,10,"/offtop 5",target(),true),"Bot");
        verify(api,times(2)).memberStatus(CHAT,10);
        assertTrue(moderation.consume(message(CHAT,100,12,"/offtop 5",target(),false),"Bot"));
        assertEquals(0,repository.expiry(CHAT,100,12));
    }
    @Test void unofftop_any_topic_releases_same_chat_only_and_expiry_reports_no_active() throws Exception {
        command(100,"/offtop 5");command(101,"/offtop 5");repository.restrict(CHAT-1,100,42,now.get()+300000,10);
        command(999,"/unofftop");assertEquals(0,repository.expiry(CHAT,100,42));assertEquals(0,repository.expiry(CHAT,101,42));
        assertTrue(repository.expiry(CHAT-1,100,42)>now.get());
        command(100,"/offtop 1");now.addAndGet(60000);command(200,"/unofftop");
        verify(api).sendHtml(eq(CHAT),eq(200),contains("нет активных"),eq(true));
    }
    @Test void invalid_minutes_reply_bot_topic_and_missing_rights_do_not_store() throws Exception {
        for(String text:java.util.List.of("/offtop","/offtop -1","/offtop 0","/offtop 10081","/offtop abc","/offtop 2 extra"))command(100,text);
        moderation.consume(message(CHAT,100,10,"/offtop 5",null,false),"Bot");
        moderation.consume(message(CHAT,100,10,"/offtop 5",new TelegramApi.Reply(2,42,"bot","x",true),false),"Bot");
        command(0,"/offtop 5");assertEquals(0,repository.expiry(CHAT,100,42));
        when(api.canDeleteMessages(CHAT)).thenReturn(false);command(100,"/offtop 5");assertEquals(0,repository.expiry(CHAT,100,42));
        verify(api).sendHtml(eq(CHAT),eq(100),contains("Ограничение не включено"),eq(true));
    }
    @Test void command_deleted_confirmation_expires_and_enforcement_is_silent() throws Exception {
        when(config.moderation()).thenReturn(new TelegramConfig.Topic(0,0));command(100,"/offtop 5");clearInvocations(api,expiry);
        for(int i=0;i<5;i++)assertTrue(moderation.consume(message(CHAT,100,42,"spam",null,false),"Bot"));
        verify(api,times(5)).delete(CHAT,20);verify(api,never()).sendHtml(anyLong(),anyInt(),anyString(),anyBoolean());verifyNoInteractions(expiry);
        now.addAndGet(60000);moderation.consume(message(CHAT,100,42,"spam",null,false),"Bot");verify(api,never()).sendHtml(anyLong(),anyInt(),anyString(),anyBoolean());
        command(100,"/unofftop");verify(api).delete(CHAT,21);verify(expiry).track(CHAT,99,now.get()+60000);
    }
    @Test void initial_confirmation_is_only_notice_and_command_deletion_retries_on_failure() throws Exception {
        doThrow(new IOException("temporary failure")).when(api).delete(CHAT,21);
        command(100,"/offtop 5");verify(api).delete(CHAT,21);
        verify(expiry).track(CHAT,21,now.get());verify(expiry).track(CHAT,99,now.get()+60000);
        verify(api).sendHtml(eq(CHAT),eq(100),argThat(s->s.contains("Оффтоп ограничен")&&s.contains("https://t.me/c/2669159447/400")),eq(true));
        clearInvocations(api,expiry);moderation.consume(message(CHAT,100,42,"spam",null,false),"Bot");
        verify(api).delete(CHAT,20);verifyNoMoreInteractions(api);verifyNoInteractions(expiry);
    }
    @Test void lost_delete_rights_report_to_admin_without_success_claim() throws Exception {
        command(100,"/offtop 5");clearInvocations(api);doThrow(new IOException("rights lost")).when(api).delete(CHAT,20);
        moderation.consume(message(CHAT,100,42,"spam",null,false),"Bot");
        verify(api).send(eq(10L),eq(0),argThat(view->view.text().contains("Не удалось удалить")));
        verify(api).sendHtml(eq(CHAT),eq(100),contains("не смог удалить"),eq(true));
        assertTrue(repository.expiry(CHAT,100,42)>now.get());
    }
}

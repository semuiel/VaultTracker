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

class SupportMenuTest {
    @TempDir Path folder;
    GuardService guard;TelegramGuardMenu menu;SupportService support;TelegramModeration moderation;
    @BeforeEach void setup() throws Exception {
        guard=new GuardService(folder.resolve("guard"),new GuardConfig(true,0),Logger.getAnonymousLogger());
        String code=guard.generate(42).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH);
        guard.link(UUID.randomUUID(),"Author",code).get();
        support=mock(SupportService.class);moderation=mock(TelegramModeration.class);
        var storage=mock(StorageEngine.class);when(storage.ready()).thenReturn(true);
        menu=new TelegramGuardMenu(guard,new TelegramCommands(new Catalogue(v->{}),storage,10,id->true),moderation,null,null,support);
    }
    @AfterEach void close() {guard.close();}
    static String button(TelegramCommands.View view,String part) {return view.buttons().stream().filter(b->b.text().contains(part)).findFirst().orElseThrow().data();}
    TelegramCommands.View click(TelegramCommands.View view,String part) throws Exception {return menu.callback(42,button(view,part)).view();}
    TelegramCommands.View home() throws Exception {return click(menu.mainMenu(42),"Поддержка");}

    @Test void supportStartsInMainMenuAndReturnsThere() throws Exception {
        var main=menu.mainMenu(42);
        assertEquals(List.of("🔎 Поиск","👤 Личный кабинет","🆘 Поддержка"),
                main.buttons().stream().map(TelegramCommands.Button::text).toList());
        assertFalse(click(menu.home(42),"Настройки").buttons().stream()
                .anyMatch(button->button.text().contains("Поддержка")));
        var supportHome=home();
        assertFalse(supportHome.buttons().stream().anyMatch(button->button.text().contains("Назад")));
        assertEquals(1,supportHome.buttons().stream().filter(button->button.text().equals("⌂ Меню")).count());
        assertTrue(click(supportHome,"Меню").text().contains("торговый бот FLEXITY"));
    }

    @Test void unlinkedUserCanOpenUnbanWithoutLinkingAndOtherTypesStillNeedLink() throws Exception {
        long user=77;
        var main=menu.mainMenu(user);
        var supportScreen=menu.callback(user,button(main,"Поддержка")).view();
        assertNotNull(button(supportScreen,"Заявка на разбан"));
        for(String type:List.of("Жалоба","Предложение","Баг")) {
            var gate=menu.callback(user,button(supportScreen,type)).view();
            assertTrue(gate.text().contains("свяжите Telegram"));
            assertNotNull(button(gate,"Привязать персонажа"));
            assertFalse(menu.support.active(user));
            assertFalse(menu.waiting.containsKey(user));
            var link=menu.callback(user,button(gate,"Привязать персонажа")).view();
            assertTrue(link.text().contains("/vtrack link "));
        }
        verifyNoInteractions(support,moderation);
    }

    @Test void suggestionAndBugHaveTextOptionalMediaReviewAndSend() throws Exception {
        when(support.configured(any())).thenReturn(true);
        for(String type:List.of("Предложение","Баг")) {
            var instruction=click(home(),type);
            assertTrue(instruction.text().contains(type));
            var media=menu.input(42,"Подробное описание и шаги");
            assertTrue(media.text().contains("Материалы: 0"));
            if(type.equals("Предложение")) {
                media=menu.supportMedia(42,new TelegramApi.Incoming(1,42,0,42,77,"[Фото]",null,null,true,"",false,true));
                media=menu.supportMedia(42,new TelegramApi.Incoming(2,42,0,42,78,"[Видео]",null,null,true,"",false,true));
                assertTrue(media.text().contains("Материалы: 2"));
            }
            var review=click(media,"К проверке");
            assertTrue(review.text().contains("Подробное описание"));
            assertTrue(click(review,"Отправить").text().contains("отправлено"));
        }
        verify(support).submit(eq(SupportTicket.Type.SUGGESTION),eq(42L),any(),isNull(),isNull(),anyString(),eq(List.of(77,78)));
        verify(support).submit(eq(SupportTicket.Type.BUG),eq(42L),any(),isNull(),isNull(),anyString(),eq(List.of()));
    }
    @Test void complaintSearchDescriptionAndSendWithOptionalMedia() throws Exception {
        when(support.configured(any())).thenReturn(true);
        UUID target=UUID.randomUUID();
        when(moderation.supportPlayers("Off",false)).thenReturn(CompletableFuture.completedFuture(List.of(new GuardService.OnlineLinked(target,"Offline"))));
        when(moderation.supportPlayer(target)).thenReturn(CompletableFuture.completedFuture(new GuardService.OnlineLinked(target,"Offline")));
        for(boolean attach:List.of(false,true)) {
            var search=click(home(),"Жалоба");assertTrue(search.text().contains("1/3"));
            verify(moderation,never()).supportPlayers("",true);
            var found=menu.input(42,"Off");
            var prompt=click(found,"Offline");assertTrue(prompt.text().contains("2/3"));
            var review=menu.input(42,"Нарушение вчера вечером");
            assertTrue(review.text().contains("3/3"));
            assertTrue(review.text().contains("Offline"));
            if(attach) {
                var photo=new TelegramApi.Incoming(1,42,0,42,77,"[Фото]",null,null,true,"",false,true);
                review=menu.supportMedia(42,photo);
                review=menu.supportMedia(42,photo);
                assertTrue(review.text().contains("Материалы: 1"));
                click(review,"Изменить текст");
                review=menu.input(42,"Уточнённое описание");
                assertTrue(review.text().contains("Материалы: 1"));
            }
            assertTrue(click(review,"Отправить").text().contains("отправлено"));
            assertFalse(menu.support.active(42));
        }
        verify(support).submit(eq(SupportTicket.Type.COMPLAINT),eq(42L),any(),eq(target),eq("Offline"),eq("Нарушение вчера вечером"),eq(List.of()));
        verify(support).submit(eq(SupportTicket.Type.COMPLAINT),eq(42L),any(),eq(target),eq("Offline"),eq("Уточнённое описание"),eq(List.of(77)));
    }
    @Test void missingTopicKeepsDraftAndDoesNotClaimSubmission() throws Exception {
        assertNull(menu.input(42,"Текст идеи")); // no active flow: existing input remains unaffected
        click(home(),"Предложение");
        var review=click(menu.input(42,"Текст идеи"),"К проверке");
        assertTrue(click(review,"Отправить").text().contains("не отправлено"));
        assertTrue(menu.support.active(42));
        verify(support,never()).submit(any(),anyLong(),any(),any(),any(),any(),any());
    }
    @Test void cancelCommandClearsDraftAndPendingText() throws Exception {
        click(home(),"Баг");
        assertTrue(menu.support.active(42));assertTrue(menu.waiting.containsKey(42L));
        menu.cancelSupport(42);
        assertFalse(menu.support.active(42));assertFalse(menu.waiting.containsKey(42L));
    }
    @Test void linkedUnbanSkipsSearchAndRequiresAllAnswersBeforeOptionalMedia() throws Exception {
        when(support.configured(SupportTicket.Type.UNBAN)).thenReturn(true);
        var intro=click(home(),"Заявка на разбан");
        assertTrue(intro.text().contains("Author"));
        assertFalse(intro.buttons().stream().anyMatch(b->b.text().contains("Найти офлайн")));
        var question=click(intro,"Начать анкету");
        for(int i=1;i<=11;i++) {
            assertTrue(question.text().contains("вопрос "+i+"/11"));
            if(i==1) {
                assertTrue(menu.input(42," ").text().contains("Ответьте на вопрос"));
                assertTrue(menu.input(42,"x".repeat(501)).text().contains("до 500"));
            }
            question=menu.input(42,i==4?"нет":"Ответ "+i);
        }
        assertTrue(question.text().contains("Материалы: 0"));
        question=menu.supportMedia(42,new TelegramApi.Incoming(1,42,0,42,77,"[Фото]",null,null,true,"",false,true));
        assertTrue(question.text().contains("Материалы: 1"));
        var review=click(question,"К проверке");
        assertTrue(review.text().contains("Ответ 1"));
        click(review,"Отправить");
        verify(support).submitUnban(eq(42L),any(),any(),eq("Author"),contains("11. "),eq(List.of(77)),eq(moderation));
    }
    @Test void unlinkedUnbanUsesPartialOfflineSearchAndCancelDoesNotSubmit() throws Exception {
        long user=77;UUID uuid=UUID.randomUUID();
        when(moderation.supportPlayers("Semu",false)).thenReturn(CompletableFuture.completedFuture(List.of(new GuardService.OnlineLinked(uuid,"Semuiel"))));
        when(moderation.supportPlayer(uuid)).thenReturn(CompletableFuture.completedFuture(new GuardService.OnlineLinked(uuid,"Semuiel")));
        var supportHome=menu.callback(user,button(menu.mainMenu(user),"Поддержка")).view();
        var search=menu.callback(user,button(supportHome,"Заявка на разбан")).view();
        assertTrue(search.text().contains("Введите часть игрового ника"));
        verify(moderation,never()).supportPlayers("",false);
        var found=menu.input(user,"Semu");assertNotNull(button(found,"Semuiel"));
        var intro=menu.callback(user,button(found,"Semuiel")).view();
        assertTrue(intro.text().contains("Semuiel"));
        menu.callback(user,button(intro,"Отмена"));
        assertFalse(menu.support.active(user));
        verify(support,never()).submitUnban(anyLong(),any(),any(),any(),any(),any(),any());
    }
    @Test void unbanWithoutTopicKeepsCompletedDraft() throws Exception {
        var intro=click(home(),"Заявка на разбан");var question=click(intro,"Начать анкету");
        for(int i=0;i<11;i++) question=menu.input(42,"нет");
        var review=click(question,"К проверке");
        assertTrue(click(review,"Отправить").text().contains("не отправлено"));
        assertTrue(menu.support.active(42));
    }
}

package ru.vaulttracker;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RootConsoleMenuTest {
    @TempDir Path directory;
    GuardService guard;
    TelegramModeration moderation=mock(TelegramModeration.class);
    TelegramApi api=mock(TelegramApi.class);
    TelegramGuardMenu menu;

    @BeforeEach void setup() throws Exception {
        guard=new GuardService(directory.resolve("guard"),new GuardConfig(true,120000,500),Logger.getAnonymousLogger());
        guard.configure(new GuardConfig(true,120000,500),Set.of(99L));
        guard.changeSuper(500,501,true).get();
        guard.changeAdmin(500,77,true).get();
        menu=new TelegramGuardMenu(guard,new TelegramCommands(new Catalogue(v->{}),mock(StorageEngine.class),8),moderation,null,api);
        when(moderation.consoleCommand(eq(500L),anyString())).thenReturn(CompletableFuture.completedFuture(true));
    }
    @AfterEach void close() {guard.close();}
    private String button(TelegramCommands.View view,String label) {
        return view.buttons().stream().filter(b->b.text().contains(label)).findFirst().orElseThrow().data();
    }
    private TelegramCommands.View click(long user,TelegramCommands.View view,String label) throws Exception {
        return menu.callback(user,button(view,label)).view();
    }
    private TelegramCommands.View root() {return menu.rootHome(500);}

    @Test void donationAdminLivesInRootAndOldSuperCallbackCannotOpenIt() throws Exception {
        var superHome=menu.superHome(501);
        assertFalse(superHome.buttons().stream().anyMatch(b->b.text().contains("Пожертвования")));
        var oldSuperButton=menu.routes.button(501,"💝 Пожертвования","donationAdmin","",0,0);
        assertThrows(SecurityException.class,()->menu.callback(501,oldSuperButton.data()));
        assertThrows(Exception.class,()->guard.donationsVisible(501,true).get());
        var root=root();
        assertNotNull(button(root,"Управление пожертвованиями"));
        var donations=click(500,root,"Управление пожертвованиями");
        assertTrue(donations.text().contains("Кнопка у связанных игроков"));
        assertTrue(click(500,donations,"Root").text().equals("🔐 Root"));
        String oldRootCallback=button(root,"Управление пожертвованиями");
        guard.configure(new GuardConfig(true,120000,0),Set.of(99L));
        assertThrows(SecurityException.class,()->menu.callback(500,oldRootCallback));
    }

    @Test void rootOnlyCallbacksAndInputRecheckPermission() throws Exception {
        var root=root();
        assertNotNull(button(root,"📄 Логи"));assertNotNull(button(root,"🖥 Консоль"));
        String console=button(root,"Консоль"),logs=button(root,"Логи");
        assertTrue(menu.callback(501,console).alert());
        assertTrue(menu.callback(77,logs).alert());
        assertTrue(menu.callback(501,"vg:rootConsole").alert());
        assertTrue(menu.callback(500,"vg:rootUnknown").alert());
        var input=click(500,click(500,root,"Консоль"),"Выполнить команду");
        assertTrue(input.text().contains("/cancel"));
        guard.configure(new GuardConfig(true,120000,0),Set.of(99L));
        assertThrows(SecurityException.class,()->menu.input(500,"list"));
        assertThrows(SecurityException.class,()->menu.callback(500,console));
        verify(moderation,never()).consoleCommand(anyLong(),anyString());
    }

    @Test void rootGrantIsConfirmedPersistedAndHistoryBelongsToEachRoot() throws Exception {
        var roots=click(500,root(),"Список Root");
        assertTrue(roots.buttons().stream().anyMatch(b->b.text().contains("500 · основной Root")));
        assertFalse(click(500,roots,"500 · основной Root").buttons().stream().anyMatch(b->b.text().contains("Удалить Root")));
        var input=click(500,roots,"Добавить Root");
        assertTrue(input.text().contains("Telegram ID"));
        var confirmation=menu.input(500,"777");
        assertFalse(guard.rootAdmin(777));
        click(500,confirmation,"Подтвердить");
        assertTrue(guard.rootAdmin(777));
        guard.rememberRootCommand(500,"say owner").get();
        guard.rememberRootCommand(777,"say second").get();
        assertEquals("say owner",guard.rootCommands(500).get().getFirst().command());
        assertEquals("say second",guard.rootCommands(777).get().getFirst().command());
        assertNull(guard.rootCommand(777,guard.rootCommands(500).get().getFirst().id()).get());
        guard.clearRootCommands(777).get();
        assertEquals(1,guard.rootCommands(500).get().size());
        guard.close();
        guard=new GuardService(directory.resolve("guard"),new GuardConfig(true,120000,500),Logger.getAnonymousLogger());
        assertTrue(guard.rootAdmin(777));
    }

    @Test void extraRootCanBeRemovedOnlyByAnotherRootAndOldCallbacksLoseAccess() throws Exception {
        guard.addRoot(500,777).get();
        var roots=click(500,root(),"Список Root");
        var details=click(500,roots,"777");
        var confirm=click(500,details,"Удалить Root");
        assertTrue(guard.rootAdmin(777));
        assertFalse(menu.rootHome(777).buttons().stream().anyMatch(b->b.text().contains("Список Root")));
        assertThrows(SecurityException.class,()->menu.superAdmin.rootList(777,0));
        assertThrows(java.util.concurrent.ExecutionException.class,()->guard.addRoot(777,888).get());
        String oldConsole=button(menu.rootHome(777),"Консоль");
        var result=click(500,confirm,"Удалить");
        assertTrue(result.text().contains("Root 777 удалён"));
        assertFalse(guard.rootAdmin(777));
        assertThrows(SecurityException.class,()->menu.callback(777,oldConsole));
        assertThrows(SecurityException.class,()->menu.rootCommand(777,"/cmd list"));
        assertThrows(java.util.concurrent.ExecutionException.class,()->guard.removeRoot(500,500).get());
        guard.close();
        guard=new GuardService(directory.resolve("guard"),new GuardConfig(true,120000,500),Logger.getAnonymousLogger());
        assertFalse(guard.rootAdmin(777));
    }

    @Test void directCommandsReuseConsoleAndLogHandlers() throws Exception {
        assertTrue(menu.rootCommand(500,"/cmd").text().contains("Использование"));
        assertTrue(menu.rootCommand(500,"/log md nope").text().contains("Использование"));
        assertThrows(SecurityException.class,()->menu.rootCommand(501,"/cmd list"));
        menu.rootCommand(500,"/cmd /time set day");
        verify(moderation).consoleCommand(500,"time set day");
        assertEquals("time set day",guard.rootCommands(500).get().getFirst().command());
        Path latest=directory.resolve("latest.log");Files.writeString(latest,"one\ntwo\n");
        when(moderation.latestLogPath()).thenReturn(latest);
        assertTrue(menu.rootCommand(500,"/log md 1").text().contains("отправлен"));
        verify(api).sendDocument(eq(500L),any(Path.class),eq("latest-1.md"));
    }

    @Test void inputNormalizesSlashHistoryRepeatsWithoutDuplicatesAndCancelWorks() throws Exception {
        var console=click(500,root(),"Консоль");
        click(500,console,"Выполнить команду");
        assertTrue(menu.rootConsolePending(500));
        var result=menu.input(500,"/time set day");
        assertTrue(result.text().contains("✅ Выполнено: time set day"));
        verify(moderation).consoleCommand(500,"time set day");
        assertFalse(menu.rootConsolePending(500));
        var history=click(500,result,"История команд");
        assertTrue(history.buttons().stream().anyMatch(b->b.text().equals("time set day")));
        var repeat=click(500,history,"time set day");
        verify(moderation,times(2)).consoleCommand(500,"time set day");
        assertEquals(1,guard.rootCommands(500).get().size());
        click(500,repeat,"Выполнить команду");
        menu.cancelInput(500); // /cancel uses this same cancellation path
        assertNull(menu.input(500,"ban Someone"));
        verify(moderation,never()).consoleCommand(500,"ban Someone");
    }

    @Test void historyIsBoundedAndOldEntriesCannotBeReplayed() throws Exception {
        for(int index=0;index<21;index++) guard.rememberRootCommand(500,"say "+index).get();
        var rows=guard.rootCommands(500).get();
        assertEquals(20,rows.size());assertEquals("say 20",rows.getFirst().command());
        assertEquals("say 1",rows.getLast().command());
        assertNull(guard.rootCommand(500,1).get());
        var history=click(500,click(500,root(),"Консоль"),"История команд");
        String stale=button(history,"say 20");
        click(500,history,"Очистить историю");
        assertTrue(menu.callback(500,stale).view().text().contains("больше не найдена"));
        assertTrue(guard.rootCommands(500).get().isEmpty());
        verify(moderation,never()).consoleCommand(anyLong(),anyString());
    }

    @Test void historySurvivesRestartAndLongButtonKeepsFullCommandById() throws Exception {
        String longCommand="say "+"x".repeat(100);
        guard.rememberRootCommand(500,longCommand).get();
        var history=click(500,click(500,root(),"Консоль"),"История команд");
        var commandButton=history.buttons().stream().filter(b->b.text().startsWith("say ")).findFirst().orElseThrow();
        assertTrue(commandButton.text().endsWith("…"));
        assertFalse(commandButton.data().contains(longCommand));
        click(500,history,"say ");
        verify(moderation).consoleCommand(500,longCommand);
        guard.close();
        guard=new GuardService(directory.resolve("guard"),new GuardConfig(true,120000,500),Logger.getAnonymousLogger());
        guard.configure(new GuardConfig(true,120000,500),Set.of(99L));
        assertEquals(longCommand,guard.rootCommands(500).get().getFirst().command());
    }

    @Test void sendsActualLogWithoutModifyingItAndDeniesOtherRoles() throws Exception {
        Path latest=directory.resolve("latest.log");Files.writeString(latest,"[12:00:00] server started\n");
        byte[] before=Files.readAllBytes(latest);
        when(moderation.latestLogPath()).thenReturn(latest);
        var preview=new java.util.concurrent.atomic.AtomicReference<Path>();
        doAnswer(call->{
            Path sent=call.getArgument(1);preview.set(sent);
            assertTrue(Files.readString(sent).contains("    [12:00:00] server started"));
            assertArrayEquals(before,Files.readAllBytes(latest));return null;
        }).when(api).sendDocument(eq(500L),any(Path.class),eq("latest-100.md"));
        String logs=button(root(),"Логи");
        assertTrue(menu.callback(501,logs).alert());
        assertTrue(menu.callback(77,logs).alert());
        var logMenu=menu.callback(500,logs).view();
        var formats=click(500,logMenu,"Последние строки");
        var format=click(500,formats,"100 строк");
        assertTrue(click(500,format,".md").text().contains("отправлен"));
        verify(api).sendDocument(eq(500L),any(Path.class),eq("latest-100.md"));
        assertFalse(Files.exists(preview.get()));
        assertArrayEquals(before,Files.readAllBytes(latest));
    }
}

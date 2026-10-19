package ru.vaulttracker;

import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminNavigationTest {
    @TempDir Path directory;
    GuardService guard;
    TelegramGuardMenu menu;
    TelegramModeration moderation=mock(TelegramModeration.class);

    @BeforeEach void setup() throws Exception {
        guard=new GuardService(directory.resolve("guard"),new GuardConfig(true,120000,500),Logger.getAnonymousLogger());
        guard.configure(new GuardConfig(true,120000,500),Set.of(99L));
        guard.changeSuper(500,501,true).get();
        menu=new TelegramGuardMenu(guard,new TelegramCommands(new Catalogue(v->{}),mock(StorageEngine.class),8),moderation);
    }
    @AfterEach void close() {guard.close();}
    private List<String> labels(TelegramCommands.View view) {return view.buttons().stream().map(TelegramCommands.Button::text).toList();}
    private TelegramCommands.View click(long user,TelegramCommands.View view,String label) throws Exception {
        return menu.callback(user,view.buttons().stream().filter(b->b.text().contains(label)).findFirst().orElseThrow().data()).view();
    }
    private String action(long user,String operation) {return menu.routes.button(user,operation,operation,"",0,0).data();}

    @Test void compactRootGroupsPreserveFunctionsAndReturnToTheirParent() throws Exception {
        var root=menu.rootHome(500);
        assertEquals(List.of("👥 Команда и роли","📊 Аналитика","💰 Экономика","⚙️ Техническое управление","↩ Личный кабинет","⌂ Меню"),labels(root));
        var team=click(500,root,"Команда и роли");
        assertEquals(List.of("👮 Администраторы бота","👑 Суперадминистраторы","👥 Список Root","🧒 Детские аккаунты","↩ Root","⌂ Меню"),labels(team));
        for(String entry:List.of("Администраторы бота","Суперадминистраторы","Список Root","Детские аккаунты")) {
            var leaf=click(500,team,entry);
            assertEquals(team.text(),click(500,leaf,"Команда и роли").text());
        }
        var economy=click(500,root,"Экономика");
        assertEquals(List.of("💝 Пожертвования и премиум","🗳 Голосования и награды","↩ Root","⌂ Меню"),labels(economy));
        var donations=click(500,economy,"Пожертвования и премиум");
        assertEquals(economy.text(),click(500,donations,"Экономика").text());
        var server=click(500,root,"Техническое управление");
        assertEquals(List.of("📄 Логи","🖥 Консоль","♻️ Перезагрузить VaultTracker","↩ Root","⌂ Меню"),labels(server));
        for(String entry:List.of("Логи","Консоль"))
            assertEquals(server.text(),click(500,click(500,server,entry),"Техническое управление").text());
        var analytics=click(500,root,"Аналитика");
        assertTrue(analytics.text().contains("нет доступных приложений"));
        for(var section:List.of(team,economy,server,analytics))
            assertEquals(root.text(),click(500,section,"↩ Root").text());
        verifyNoInteractions(moderation);
    }
    @Test void superPreferencesKeepOnlyNotificationSettingsWithShortLabels() throws Exception {
        var home=menu.superHome(501);
        assertEquals(List.of("👥 Список игроков","👮 Администраторы бота","⚙️ Личные настройки","↩ Назад","⌂ Меню"),labels(home));
        var preferences=click(501,home,"Личные настройки");
        assertEquals(List.of("🔔 Срок уведомлений","↩ Суперадминистратор","⌂ Меню"),labels(preferences));
        var notifications=click(501,preferences,"Срок уведомлений");
        assertEquals(preferences.text(),click(501,notifications,"Личные настройки").text());
        assertEquals(home.text(),click(501,preferences,"Суперадминистратор").text());
        assertEquals(home.text(),click(501,click(501,home,"Администраторы бота"),"Суперадминистратор").text());
        UUID uuid=UUID.randomUUID();
        String code=guard.generate(501).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH);
        guard.link(uuid,"SuperNick",code).get();
        assertFalse(labels(click(501,home,"Личные настройки")).stream().anyMatch(t->t.contains("персонаж")));
        verifyNoInteractions(moderation);
    }
    @Test void rootAndSuperActionsRejectLowerRolesEvenWithTheirOwnCallbackTokens() {
        // Own tokens reach the authorization check; copying another user's button is separately rejected by the registry.
        for(long user:List.of(42L,99L,501L)) {
            for(String operation:List.of("rootHome","rootTeam","rootAnalytics","rootEconomy","rootServer",
                    "supers","children","rootLogs","rootConsole","donationAdmin","votingAdmin","reloadPlugin","rootList","selfActions")) {
                String callback=action(user,operation);
                assertThrows(SecurityException.class,()->menu.callback(user,callback),user+": "+operation);
            }
        }
        for(long user:List.of(42L,99L)) {
            for(String operation:List.of("superHome","superPreferences","admins","selfActions","rootSettings","capabilities")) {
                String callback=action(user,operation);
                assertThrows(SecurityException.class,()->menu.callback(user,callback),user+": "+operation);
            }
        }
        verifyNoInteractions(moderation);
    }
    @Test void primaryRootAloneManagesRootsAndRevokedRootButtonsCannotBeReused() throws Exception {
        guard.changeSuper(500,600,true).get();guard.addRoot(500,600).get();
        var home=menu.rootHome(600);
        var team=click(600,home,"Команда и роли");
        assertFalse(labels(team).stream().anyMatch(t->t.contains("Список Root")));
        assertThrows(SecurityException.class,()->menu.callback(600,action(600,"rootList")));
        var callbacks=new ArrayList<String>();
        for(String operation:List.of("rootHome","rootTeam","rootAnalytics","rootEconomy","rootServer","rootLogs","rootConsole","children","supers","donationAdmin","votingAdmin","reloadPlugin","selfActions"))
            callbacks.add(action(600,operation));
        guard.removeRoot(500,600).get();
        assertTrue(guard.superAdmin(600));
        for(String callback:callbacks) assertThrows(SecurityException.class,()->menu.callback(600,callback));
        assertFalse(labels(menu.superHome(600)).contains("🔐 Root"));
        verifyNoInteractions(moderation);
    }
    @Test void cabinetAloneShowsRootAndOwnCharacterForMainAndAdditionalRoots() throws Exception {
        guard.addRoot(500,600).get();
        for(long user:List.of(500L,600L)) {
            var cabinet=menu.home(user);
            assertTrue(labels(cabinet).contains("🔐 Root"));
            assertTrue(labels(cabinet).contains("👤 Мой персонаж"));
            assertEquals(cabinet.text(),click(user,click(user,cabinet,"🔐 Root"),"Личный кабинет").text());
            assertEquals(cabinet.text(),click(user,click(user,cabinet,"Мой персонаж"),"Личный кабинет").text());
            assertFalse(labels(menu.superHome(user)).contains("🔐 Root"));
            assertFalse(labels(menu.superAdmin.superPreferences(user)).stream().anyMatch(t->t.contains("персонаж")));
        }
        for(long user:List.of(42L,99L,501L)) {
            assertFalse(labels(menu.home(user)).stream().anyMatch(t->t.contains("Root")||t.contains("Мой персонаж")));
        }
        verifyNoInteractions(moderation);
    }
    @Test void revokedSuperCannotReusePreferencesOrNotificationButtons() throws Exception {
        String preferences=action(501,"superPreferences"),notifications=action(501,"rootSettings"),admins=action(501,"admins");
        guard.changeSuper(500,501,false).get();
        for(String callback:List.of(preferences,notifications,admins))
            assertThrows(SecurityException.class,()->menu.callback(501,callback));
        assertFalse(labels(menu.mainMenu(42)).stream().anyMatch(t->t.contains("Root")||t.contains("Админ")));
        verifyNoInteractions(moderation);
    }
    @Test void copiedOrUnknownCallbacksCannotOpenSections() throws Exception {
        String rootTeam=action(500,"rootTeam"),preferences=action(501,"superPreferences");
        for(long user:List.of(42L,99L,501L)) assertTrue(menu.callback(user,rootTeam).alert());
        assertTrue(menu.callback(42,preferences).alert());
        for(String callback:List.of("vg:rootTeam","vg:rootAnalytics","vg:rootEconomy","vg:rootServer","vg:superPreferences"))
            assertTrue(menu.callback(42,callback).alert());
        verifyNoInteractions(moderation);
    }
}

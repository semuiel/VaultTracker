package ru.vaulttracker;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CabinetRolesTest {
    @TempDir Path path;
    GuardService guard;
    AtomicLong clock=new AtomicLong(2_000_000);
    UUID owner=UUID.randomUUID(),other=UUID.randomUUID(),world=UUID.randomUUID(),generation=UUID.randomUUID();
    TelegramModeration moderation=mock(TelegramModeration.class);
    TelegramGuardMenu menu;
    @BeforeEach void setup() throws Exception {
        guard=new GuardService(path.resolve("guard"),new GuardConfig(true,120000,500),Logger.getAnonymousLogger(),clock::get);
        guard.configure(new GuardConfig(true,120000,500),Set.of(99L));
        link(42,owner);link(43,other);
        menu=new TelegramGuardMenu(guard,new TelegramCommands(new Catalogue(v->{}),mock(StorageEngine.class),8),moderation);
    }
    void link(long tg,UUID uuid) throws Exception {String code=guard.generate(tg).get().split("/vtrack link ")[1].substring(0,32);guard.link(uuid,"Player"+tg,code).get();}
    @AfterEach void close() {guard.close();}
    String button(TelegramCommands.View view,String text) {return view.buttons().stream().filter(b->b.text().contains(text)).findFirst().orElseThrow().data();}
    TelegramCommands.View click(long user,TelegramCommands.View view,String text) throws Exception {return menu.callback(user,button(view,text)).view();}
    Snapshot snap(UUID uuid,long amount,long revision) {return new Snapshot(new BlockKey(world,uuid.equals(owner) ? 1 : 2,64,0),generation,uuid,"SameName",List.of(new BlockKey(world,uuid.equals(owner) ? 1 : 2,64,1)),Map.of("DIAMOND",amount),true,clock.get(),revision);}
    @Test void cabinetRowsAndSettingsAreSeparatedByRole() throws Exception {
        var home=menu.home(42);assertEquals(List.of("📦 Мои ресурсы","📜 Мои события · 2 дня","⚙️ Настройки","🔎 Поиск"),home.buttons().stream().map(TelegramCommands.Button::text).toList());
        assertFalse(menu.home(99).buttons().stream().anyMatch(b->b.text().contains("Супер")));
        assertTrue(menu.home(500).buttons().stream().anyMatch(b->b.row()==5 && b.text().contains("Супер")));
        var settings=click(42,home,"Настройки");assertNotNull(button(settings,"Отключить мои"));assertNotNull(button(settings,"В том числе в игре"));assertNotNull(button(settings,"2 дн."));
        assertNotNull(button(settings,"Применить тег"));assertNotNull(button(settings,"Сбросить тег"));
    }
    @Test void personalHistoryUsesUuidAnd48HoursAndNeverExposesActor() throws Exception {
        guard.restore(List.of(snap(owner,10,1),snap(other,10,1)));clock.addAndGet(120001);
        guard.attribute(snap(owner,10,1).sign(),"Actor");guard.accept(snap(owner,9,2));guard.accept(snap(other,8,2));
        var own=guard.ownHistory(42,0,10).get();assertEquals(1,own.size());assertNull(own.getFirst().actor());
        assertNull(guard.ownEvent(43,own.getFirst().id()).get());
        var view=click(42,click(42,menu.home(42),"Мои события"),"#");assertFalse(view.text().contains("Actor"));assertFalse(view.text().contains("Кто изменил"));
        clock.addAndGet(2L*86400_000+1);assertTrue(guard.ownHistory(42,0,10).get().isEmpty());assertEquals(2,guard.history(99,0,10).get().size());
    }
    @Test void onlineNotificationsAndSuperDelayAreIndependentOfAdminDelay() throws Exception {
        guard.offlineSeconds(42,-1L).get();guard.superDelay(500,-1).get();guard.presence(owner,"Player42",true);guard.restore(List.of(snap(owner,10,1)));guard.accept(snap(owner,9,2));
        assertEquals(Set.of(42L,500L),new HashSet<>(guard.deliveries().get().stream().map(GuardService.Delivery::recipient).toList()));
        assertThrows(ExecutionException.class,()->guard.superDelay(99,0).get());
    }
    @Test void rootAdminChangesRequireConfirmationAndRevocationIsImmediate() throws Exception {
        var root=click(500,menu.home(500),"Супер");click(500,root,"Добавить");var confirm=menu.input(500,"77");
        assertFalse(guard.admin(77));String yes=button(confirm,"Подтвердить");assertTrue(menu.callback(99,yes).alert());menu.callback(500,yes);assertTrue(guard.admin(77));assertTrue(menu.callback(500,yes).alert());
        var adminScreen=click(77,menu.home(77),"Настройки администратора");String stale=button(adminScreen,"Отключить");
        guard.changeAdmin(500,77,false).get();assertThrows(Exception.class,()->menu.callback(77,stale));
        assertThrows(ExecutionException.class,()->guard.changeAdmin(99,77,true).get());assertThrows(ExecutionException.class,()->guard.changeAdmin(500,500,false).get());
    }
    @Test void confirmedBanUsesChosenUuidAndOnlyExecutesOnce() throws Exception {
        var person=new TelegramModeration.Person(other,"Target",true,"UUID: "+other);
        when(moderation.players(99,"all")).thenReturn(CompletableFuture.completedFuture(List.of(person)));
        when(moderation.info(99,other)).thenReturn(CompletableFuture.completedFuture(person));
        when(moderation.act(99,"ban",other)).thenReturn(CompletableFuture.completedFuture("Бан на 5 минут"));
        var players=click(99,click(99,menu.home(99),"Настройки администратора"),"Временный бан");var confirm=click(99,players,"Target");
        verify(moderation,never()).act(anyLong(),anyString(),any());String yes=button(confirm,"Подтвердить");
        assertTrue(menu.callback(42,yes).alert());menu.callback(99,yes);menu.callback(99,yes);verify(moderation,times(1)).act(99,"ban",other);
    }
    @Test void cancelledOrRevokedConfirmationCannotModerate() throws Exception {
        var person=new TelegramModeration.Person(other,"Target",true,"");
        when(moderation.players(99,"online")).thenReturn(CompletableFuture.completedFuture(List.of(person)));when(moderation.info(99,other)).thenReturn(CompletableFuture.completedFuture(person));
        var players=click(99,click(99,menu.home(99),"Настройки администратора"),"Кикнуть");var confirm=click(99,players,"Target");String yes=button(confirm,"Подтвердить");
        menu.home(99);assertTrue(menu.callback(99,yes).alert());
        confirm=click(99,players,"Target");String revoked=button(confirm,"Подтвердить");guard.changeAdmin(500,99,false).get();
        assertThrows(Exception.class,()->menu.callback(99,revoked));verify(moderation,never()).act(anyLong(),anyString(),any());
    }
    @Test void chatTextIsPreviewedAndNotTreatedAsAConsoleCommand() throws Exception {
        when(moderation.broadcast(500,"/op Player42")).thenReturn(CompletableFuture.completedFuture("Отправлено"));
        click(500,click(500,menu.home(500),"Супер"),"Сообщение");var preview=menu.input(500,"/op Player42");
        verify(moderation,never()).broadcast(anyLong(),anyString());click(500,preview,"Подтвердить");verify(moderation).broadcast(500,"/op Player42");
    }
    @Test void roleOverridesTagAndSuperTimePersistAcrossRestart() throws Exception {
        guard.changeAdmin(500,77,true).get();guard.changeAdmin(500,99,false).get();guard.tag(42,true).get();guard.superDelay(500,172800).get();guard.close();
        guard=new GuardService(path.resolve("guard"),new GuardConfig(true,120000,500),Logger.getAnonymousLogger(),clock::get);guard.configure(new GuardConfig(true,120000,500),Set.of(99L));
        assertTrue(guard.admin(77));assertFalse(guard.admin(99));assertTrue(guard.tag(42,false).get());assertEquals(172800,guard.superDelay(500));
    }
}

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
        var home=menu.home(42);assertEquals(List.of("📦 Мои ресурсы","📜 Мои события · 2 дня","⚙️ Настройки игрока","⌂ Меню"),home.buttons().stream().map(TelegramCommands.Button::text).toList());
        assertFalse(menu.home(99).buttons().stream().anyMatch(b->b.text().contains("Супер")));
        assertTrue(menu.home(500).buttons().stream().anyMatch(b->b.row()==5 && b.text().contains("Супер")));
        var settings=click(42,home,"Настройки");assertNotNull(button(settings,"Отключить мои"));assertNotNull(button(settings,"Всегда"));assertNotNull(button(settings,"2 дня"));
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
        guard.history(500,0,1).get();clock.addAndGet(10000);
        assertEquals(Set.of(42L,500L),new HashSet<>(guard.deliveries().get().stream().map(GuardService.Delivery::recipient).toList()));
        assertThrows(ExecutionException.class,()->guard.superDelay(99,0).get());
    }
    @Test void rootAdminChangesRequireConfirmationAndRevocationIsImmediate() throws Exception {
        var root=click(500,click(500,menu.home(500),"Супер"),"Администраторы бота");click(500,root,"Добавить администратора");var confirm=menu.input(500,"77");
        assertFalse(guard.admin(77));String yes=button(confirm,"Подтвердить");assertTrue(menu.callback(99,yes).alert());menu.callback(500,yes);assertTrue(guard.admin(77));assertTrue(menu.callback(500,yes).alert());
        var adminScreen=click(77,menu.home(77),"Функции администратора");String stale=button(adminScreen,"Отключить");
        guard.changeAdmin(500,77,false).get();assertThrows(Exception.class,()->menu.callback(77,stale));
        assertThrows(ExecutionException.class,()->guard.changeAdmin(99,77,true).get());assertThrows(ExecutionException.class,()->guard.changeAdmin(500,500,false).get());
    }
    @Test void confirmedBanUsesChosenUuidAndOnlyExecutesOnce() throws Exception {
        guard.capability(500,"ban",true).get();
        var person=new TelegramModeration.Person(other,"Target",true,"UUID: "+other);
        when(moderation.players(99,"online")).thenReturn(CompletableFuture.completedFuture(List.of(person)));
        when(moderation.info(99,other)).thenReturn(CompletableFuture.completedFuture(person));
        when(moderation.act(99,"ban",other)).thenReturn(CompletableFuture.completedFuture("Бан на 5 минут"));
        var choice=click(99,click(99,menu.home(99),"Функции администратора"),"Список игроков");
        assertNotNull(button(choice,"Игроки офлайн"));
        var players=choice;var confirm=click(99,click(99,players,"Target"),"Бан на 5 минут");
        verify(moderation,never()).act(anyLong(),anyString(),any());String yes=button(confirm,"Подтвердить");
        assertTrue(menu.callback(42,yes).alert());menu.callback(99,yes);menu.callback(99,yes);verify(moderation,times(1)).act(99,"ban",other);
    }
    @Test void regularAdminCanChooseOnlinePlayersForTemporaryBan() throws Exception {
        guard.capability(500,"ban",true).get();
        var person=new TelegramModeration.Person(other,"OnlineTarget",true,"");
        when(moderation.players(99,"online")).thenReturn(CompletableFuture.completedFuture(List.of(person)));
        var choice=click(99,click(99,menu.home(99),"Функции администратора"),"Список игроков");
        var players=choice;assertNotNull(button(players,"OnlineTarget"));
        verify(moderation).players(99,"online");
    }
    @Test void superAdminCanFilterPlayerListByPartialName() throws Exception {
        var first=new TelegramModeration.Person(other,"Giga_TapoChek_",false,"");
        var second=new TelegramModeration.Person(UUID.randomUUID(),"Semui",false,"");
        when(moderation.players(500,"online")).thenReturn(CompletableFuture.completedFuture(List.of(first,second)));
        when(moderation.adminGroup(500,other)).thenReturn(CompletableFuture.completedFuture(false));
        var list=click(500,click(500,menu.home(500),"Супер"),"Список игроков");

        var prompt=click(500,list,"Поиск");
        var filtered=menu.input(500,"tapo");
        assertTrue(filtered.text().contains("поиск «tapo»"));
        assertTrue(filtered.buttons().stream().anyMatch(b->b.text().contains("Giga_TapoChek_")));
        assertFalse(filtered.buttons().stream().anyMatch(b->b.text().contains("Semui")));
        assertTrue(prompt.text().contains("часть ника"));
    }
    @Test void superAdminPlayerCardOffersSpeedMenusWithDefaultsAndConfirmation() throws Exception {
        var person=new TelegramModeration.Person(other,"Target",true,"");
        when(moderation.players(500,"online")).thenReturn(CompletableFuture.completedFuture(List.of(person)));
        when(moderation.info(500,other)).thenReturn(CompletableFuture.completedFuture(person));
        when(moderation.adminGroup(500,other)).thenReturn(CompletableFuture.completedFuture(false));
        when(moderation.speed(500,other,"fly",20)).thenReturn(CompletableFuture.completedFuture("Скорость полёта установлена"));
        var root=click(500,menu.home(500),"Супер");var list=click(500,root,"Список игроков");var card=click(500,list,"Target");
        var actions=card;
        var fly=click(500,actions,"Скорость полёта");
        assertTrue(fly.text().contains("1.0 — значение по умолчанию"));
        assertTrue(fly.buttons().stream().anyMatch(b->b.text().endsWith("20.0")), fly.buttons().toString());
        var confirm=click(500,fly,"20.0");
        assertTrue(confirm.text().contains("скорость полёта 20.0x"));
        click(500,confirm,"Подтвердить");verify(moderation).speed(500,other,"fly",20);
    }
    TelegramCommands.View rootActions() throws Exception {
        var target=new TelegramModeration.Person(other,"Target",true,"");
        when(moderation.players(500,"online")).thenReturn(CompletableFuture.completedFuture(List.of(target)));
        when(moderation.info(500,other)).thenReturn(CompletableFuture.completedFuture(target));
        when(moderation.adminGroup(500,other)).thenReturn(CompletableFuture.completedFuture(false));
        return click(500,click(500,click(500,menu.home(500),"Супер"),"Список игроков"),"Target");
    }
    @Test void customNumbersValidateConfirmAndCancel() throws Exception {
        var actions=rootActions();
        for(String title:List.of("Размер","Скорость полёта","Скорость передвижения")) {
            click(500,click(500,actions,title),"Своё значение");
            assertTrue(menu.input(500,"NaN").text().contains("Введите число"));
            assertTrue(menu.input(500,"21").text().contains("Введите число"));
            var confirm=menu.input(500,"1,25");assertTrue(confirm.text().contains("1.25"));
            assertTrue(menu.callback(99,button(confirm,"Подтвердить")).alert());
            menu.home(500);assertTrue(menu.callback(500,button(confirm,"Подтвердить")).alert());
        }
        click(500,click(500,actions,"Размер"),"Своё значение");menu.home(500);assertNull(menu.input(500,"2"));
        verify(moderation,never()).scale(anyLong(),any(),anyDouble());verify(moderation,never()).speed(anyLong(),any(),anyString(),anyDouble());
        when(moderation.scale(500,other,1.25)).thenReturn(CompletableFuture.completedFuture("Готово"));
        click(500,click(500,actions,"Размер"),"Своё значение");click(500,menu.input(500,"1.25"),"Подтвердить");verify(moderation).scale(500,other,1.25);
    }
    @Test void teleportMenusPreviewExactSourceDestinationAndCoordinates() throws Exception {
        var actions=rootActions();
        when(moderation.teleport(500,other,null,"223 200 1004")).thenReturn(CompletableFuture.completedFuture("Готово"));
        click(500,actions,"на координаты");assertTrue(menu.input(500,"223 200").text().contains("x y z"));
        var preview=menu.input(500,"223 200 1004");assertTrue(preview.text().contains("Target"));assertTrue(preview.text().contains("223 200 1004"));
        verify(moderation,never()).teleport(anyLong(),any(),any(),any());
        String yes=button(preview,"Подтвердить");menu.callback(500,yes);menu.callback(500,yes);verify(moderation).teleport(500,other,null,"223 200 1004");
        var destination=new TelegramModeration.Person(owner,"Destination",true,"");
        when(moderation.players(500,"online")).thenReturn(CompletableFuture.completedFuture(List.of(destination)));
        when(moderation.info(500,owner)).thenReturn(CompletableFuture.completedFuture(destination));
        when(moderation.teleport(500,other,owner,null)).thenReturn(CompletableFuture.completedFuture("Готово"));
        preview=click(500,click(500,actions,"к игроку"),"Destination");assertTrue(preview.text().contains("Destination"));click(500,preview,"Подтвердить");verify(moderation).teleport(500,other,owner,null);
    }
    @Test void adminEventSearchIsAvailableAndRetainsFilterAcrossPages() throws Exception {
        guard.restore(List.of(snap(owner,20,1)));clock.addAndGet(120001);
        for(int i=1;i<=8;i++) {guard.attribute(snap(owner,20,1).sign(),"Investigator");guard.accept(snap(owner,20-i,i+1));}
        var history=click(99,click(99,menu.home(99),"Функции администратора"),"События");click(99,history,"Поиск событий");
        var filtered=menu.input(99,"invest");assertTrue(filtered.text().contains("invest"));
        assertTrue(click(99,filtered,"Вперёд").text().contains("invest"));
        assertFalse(menu.home(42).buttons().stream().anyMatch(b->b.text().contains("События администраторов")));
    }
    @Test void cancelledOrRevokedConfirmationCannotModerate() throws Exception {
        guard.capability(500,"kick",true).get();
        var person=new TelegramModeration.Person(other,"Target",true,"");
        when(moderation.players(99,"online")).thenReturn(CompletableFuture.completedFuture(List.of(person)));when(moderation.info(99,other)).thenReturn(CompletableFuture.completedFuture(person));
        var players=click(99,click(99,menu.home(99),"Функции администратора"),"Список игроков");var confirm=click(99,click(99,players,"Target"),"Кик");String yes=button(confirm,"Подтвердить");
        menu.home(99);assertTrue(menu.callback(99,yes).alert());
        confirm=click(99,click(99,players,"Target"),"Кик");String revoked=button(confirm,"Подтвердить");guard.changeAdmin(500,99,false).get();
        assertThrows(Exception.class,()->menu.callback(99,revoked));verify(moderation,never()).act(anyLong(),anyString(),any());
    }
    @Test void chatTextIsPreviewedAndNotTreatedAsAConsoleCommand() throws Exception {
        when(moderation.broadcast(500,"/op Player42")).thenReturn(CompletableFuture.completedFuture("Отправлено"));
        click(500,rootActions(),"сообщение");var preview=menu.input(500,"/op Player42");
        verify(moderation,never()).broadcast(anyLong(),anyString());click(500,preview,"Подтвердить");verify(moderation).broadcast(500,"/op Player42");
    }
    @Test void roleOverridesTagAndSuperTimePersistAcrossRestart() throws Exception {
        guard.changeAdmin(500,77,true).get();guard.changeAdmin(500,99,false).get();guard.tag(42,true).get();guard.superDelay(500,172800).get();guard.close();
        guard=new GuardService(path.resolve("guard"),new GuardConfig(true,120000,500),Logger.getAnonymousLogger(),clock::get);guard.configure(new GuardConfig(true,120000,500),Set.of(99L));
        assertTrue(guard.admin(77));assertFalse(guard.admin(99));assertTrue(guard.tag(42,false).get());assertEquals(172800,guard.superDelay(500));
    }
    @Test void mainMenuAlwaysOffersSearchAndCabinetEvenBeforeBinding() throws Exception {
        for(long user:List.of(42L,500L,12345L)) {
            var main=menu.mainMenu(user);assertEquals(List.of("🔎 Поиск","👤 Личный кабинет"),main.buttons().stream().map(TelegramCommands.Button::text).toList());
        }
        assertNotNull(button(click(12345,menu.mainMenu(12345),"Личный кабинет"),"Привязать"));
    }
    @Test void removesSuperByTypedIdOnlyAfterConfirmation() throws Exception {
        long target=123456789L;guard.changeSuper(500,target,true).get();
        var admins=click(500,click(500,menu.home(500),"Супер"),"Администраторы бота");
        String remove=button(admins,"Удалить супер администратора");
        assertTrue(menu.callback(99,remove).alert());menu.callback(500,remove);
        assertTrue(menu.input(500,"abc").text().contains("Telegram ID"));
        assertTrue(menu.input(500,"123").text().contains("не найден"));
        assertTrue(menu.input(500,"500").text().contains("удалить нельзя"));
        var confirm=menu.input(500,Long.toString(target));assertTrue(guard.superAdmin(target));
        String yes=button(confirm,"Подтвердить");assertTrue(menu.callback(99,yes).alert());
        menu.callback(500,yes);assertFalse(guard.admin(target));assertTrue(menu.callback(500,yes).alert());
    }
    @Test void playerAndInventoryPagesContainTwentyEntriesWithoutSkipping() throws Exception {
        var people=java.util.stream.IntStream.range(0,21).mapToObj(i->new TelegramModeration.Person(UUID.randomUUID(),"Player_"+i,true,"")).toList();
        when(moderation.players(500,"online")).thenReturn(CompletableFuture.completedFuture(people));
        var first=click(500,click(500,menu.home(500),"Супер"),"Список игроков");
        assertEquals(20,first.buttons().stream().filter(b->b.text().contains("Player_")).count());
        var next=click(500,first,"Вперёд");assertEquals(1,next.buttons().stream().filter(b->b.text().contains("Player_")).count());assertNotNull(button(next,"Player_20"));
        assertEquals(20,click(500,next,"◀ Назад").buttons().stream().filter(b->b.text().contains("Player_")).count());
        var actions=rootActions();var items=java.util.stream.IntStream.range(0,21).mapToObj(i->new TelegramModeration.ItemView("Item_"+i,1,List.of())).toList();
        when(moderation.inventory(500,other,false)).thenReturn(CompletableFuture.completedFuture(items));
        var inventory=click(500,actions,"Инвентарь");assertEquals(20,inventory.text().lines().filter(line->line.startsWith("Item_")).count());
        assertEquals(1,click(500,inventory,"Вперёд").text().lines().filter(line->line.startsWith("Item_")).count());
    }
    @Test void independentCapabilitiesHideButtonsAndRejectStaleConfirmations() throws Exception {
        var person=new TelegramModeration.Person(other,"Target",true,"");
        when(moderation.players(99,"online")).thenReturn(CompletableFuture.completedFuture(List.of(person)));
        when(moderation.info(99,other)).thenReturn(CompletableFuture.completedFuture(person));
        guard.capability(500,"op",true).get();guard.capability(500,"inventory",true).get();
        var card=click(99,click(99,click(99,menu.home(99),"Функции администратора"),"Список игроков"),"Target");
        assertNotNull(button(card,"Выдать OP"));assertNotNull(button(card,"Инвентарь"));
        assertFalse(card.buttons().stream().anyMatch(b->b.text().contains("Забрать OP") || b.text().contains("Эндер")));
        var confirmation=click(99,card,"Выдать OP");guard.capability(500,"op",false).get();
        assertThrows(SecurityException.class,()->click(99,confirmation,"Подтвердить"));verify(moderation,never()).act(anyLong(),anyString(),any());
        var superCard=rootActions();assertNotNull(button(superCard,"Выдать OP"));assertNotNull(button(superCard,"Забрать OP"));
    }
    @Test void addedSuperRolesAndTelegramLabelsPersistAndHaveIndependentAlerts() throws Exception {
        assertThrows(ExecutionException.class,()->guard.changeSuper(99,77,true).get());
        guard.changeSuper(500,77,true).get();guard.superDelay(77,-1).get();guard.rememberTelegram(77,"Name @tag");
        guard.presence(owner,"Player42",true);guard.restore(List.of(snap(owner,10,1)));guard.accept(snap(owner,9,2));guard.history(500,0,1).get();clock.addAndGet(10000);
        assertTrue(guard.deliveries().get().stream().anyMatch(d->d.recipient()==77));
        guard.close();guard=new GuardService(path.resolve("guard"),new GuardConfig(true,120000,500),Logger.getAnonymousLogger(),clock::get);
        assertTrue(guard.superAdmin(77));assertEquals("Name @tag",guard.telegramProfile(77));assertEquals(-1,guard.superDelay(77));
        assertThrows(ExecutionException.class,()->guard.changeSuper(77,500,false).get());
        guard.changeSuper(500,77,false).get();assertFalse(guard.admin(77));
    }
}

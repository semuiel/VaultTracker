package ru.vaulttracker;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
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
    void link(long tg,UUID uuid) throws Exception {String code=guard.generate(tg).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH);guard.link(uuid,"Player"+tg,code).get();}
    @AfterEach void close() {guard.close();}
    String button(TelegramCommands.View view,String text) {return view.buttons().stream().filter(b->b.text().contains(text)).findFirst().orElseThrow().data();}
    TelegramCommands.View click(long user,TelegramCommands.View view,String text) throws Exception {
        String label=switch(text) {case "Бан на 5 минут" -> "Бан 5 мин";case "Скорость полёта" -> "Полёт";
            case "Скорость передвижения" -> "Ходьба";case "на координаты" -> "На координаты";
            case "к игроку" -> "К игроку";case "Отправить сообщение в игровой чат" -> "В игровой чат";default -> text;};
        for(int i=0;i<10;i++) {
            var match=view.buttons().stream().filter(b->b.text().contains(label)).findFirst();
            if(match.isPresent()) return menu.callback(user,match.get().data()).view();
            var next=view.buttons().stream().filter(b->b.text().contains("Вперёд")).findFirst();
            if(next.isEmpty()) break;
            view=menu.callback(user,next.get().data()).view();
        }
        throw new NoSuchElementException("Кнопка: "+text);
    }
    Snapshot snap(UUID uuid,long amount,long revision) {return new Snapshot(new BlockKey(world,uuid.equals(owner) ? 1 : 2,64,0),generation,uuid,"SameName",List.of(new BlockKey(world,uuid.equals(owner) ? 1 : 2,64,1)),Map.of("DIAMOND",amount),true,clock.get(),revision);}
    @Test void rootUnblockRequiresConfirmationAndIsDeniedToAdmin() throws Exception {
        var person=new TelegramModeration.Person(owner,"Player42",true,"",false);
        when(moderation.players(500,"online")).thenReturn(CompletableFuture.completedFuture(List.of(person)));
        when(moderation.info(500,owner)).thenReturn(CompletableFuture.completedFuture(person));
        var result=new com.google.gson.JsonObject();result.addProperty("balanceMinor",0);
        when(moderation.donationChange(eq(500L),eq(owner),anyString(),anyString(),eq("unblock"),eq(0),eq(0L))).thenReturn(result);
        var screen=menu.cabinet.donationAdmin(500);
        String select=screen.buttons().stream().filter(b->b.text().contains("Разблокировать")).findFirst().orElseThrow().data();
        var players=menu.callback(500,select).view();
        var confirm=menu.callback(500,players.buttons().stream().filter(b->b.text().equals("Player42")).findFirst().orElseThrow().data()).view();
        verify(moderation,never()).donationChange(anyLong(),any(),anyString(),anyString(),anyString(),anyInt(),anyLong());
        String apply=confirm.buttons().stream().filter(b->b.text().equals("Подтвердить")).findFirst().orElseThrow().data();
        menu.callback(99,apply);
        verify(moderation,never()).donationChange(anyLong(),any(),anyString(),anyString(),anyString(),anyInt(),anyLong());
        menu.callback(500,apply);
        verify(moderation).donationChange(eq(500L),eq(owner),eq("Player42"),anyString(),eq("unblock"),eq(0),eq(0L));
    }
    @Test void cabinetRowsAndSettingsAreSeparatedByRole() throws Exception {
        var home=menu.home(42);assertEquals(List.of("📦 Мои ресурсы","📊 Статистика","⚙️ Настройки","💬 Сообщения","⌂ Меню"),home.buttons().stream().map(TelegramCommands.Button::text).toList());
        assertFalse(menu.home(99).buttons().stream().anyMatch(b->b.text().contains("Супер")));
        assertTrue(menu.home(500).buttons().stream().anyMatch(b->b.text().equals("🛠 Админ")));
        var settings=click(42,home,"Настройки");assertNotNull(button(settings,"🔔 Уведомления"));assertNotNull(button(settings,"🏷 Тег"));
        assertFalse(settings.buttons().stream().anyMatch(b->b.text().contains("Всегда")||b.text().contains("Применить тег")));
        var notifications=click(42,settings,"Уведомления");var own=click(42,notifications,"Личные уведомления");
        assertNotNull(button(own,"Всегда"));assertNotNull(button(own,"2 дня"));
        var tag=click(42,settings,"Тег");assertNotNull(button(tag,"Применить тег"));assertNotNull(button(tag,"Сбросить тег"));
    }
    @Test void itemSortingMenuPersistsChoiceAndReturnsToSettings() throws Exception {
        var settings=click(42,menu.home(42),"Настройки");
        var sorting=click(42,settings,"Сортировка предметов");
        assertTrue(sorting.buttons().stream().anyMatch(b->b.text().equals("✅ 🌍 Популярное в Minecraft")));
        sorting=click(42,sorting,"По количеству");
        assertTrue(sorting.buttons().stream().anyMatch(b->b.text().equals("✅ 📊 По количеству")));
        assertEquals(ItemSorting.Mode.AMOUNT,guard.itemSortMode(42));
        assertTrue(click(42,sorting,"Назад").buttons().stream().anyMatch(b->b.text().contains("Сортировка предметов")));
    }
    @Test void superAdminPreviewUsesRegularAdminActionsAndResourcesLeadToEvents() throws Exception {
        guard.changeAdmin(500,77,true).get();
        var superCabinet=menu.home(500);
        assertFalse(superCabinet.buttons().stream().anyMatch(b->b.text().contains("Суперадминистратор")));
        var adminMenu=click(500,superCabinet,"🛠 Админ");
        assertEquals(List.of("🛠 Функции администратора","👑 Суперадминистратор","↩️ Назад","⌂ Меню"),
                adminMenu.buttons().stream().map(TelegramCommands.Button::text).toList());
        var normal=click(77,menu.home(77),"Функции администратора");
        var preview=click(500,adminMenu,"Функции администратора");
        assertEquals(normal.buttons().stream().map(TelegramCommands.Button::text).toList(),
                preview.buttons().stream().map(TelegramCommands.Button::text).toList());
        var person=new TelegramModeration.Person(other,"Target",true,"");
        when(moderation.players(500,"online")).thenReturn(CompletableFuture.completedFuture(List.of(person)));
        when(moderation.info(500,other)).thenReturn(CompletableFuture.completedFuture(person));
        var card=click(500,click(500,preview,"Список игроков"),"Target");
        assertFalse(card.buttons().stream().anyMatch(b->b.text().contains("Забанить")));
        guard.capability(500,"permanentBan",true).get();
        card=click(500,click(500,card,"К игрокам"),"Target");
        assertTrue(card.buttons().stream().anyMatch(b->b.text().contains("Забанить")));
        var resources=click(42,menu.home(42),"Мои ресурсы");
        var events=click(42,resources,"События");
        assertTrue(click(42,events,"Назад").buttons().stream().anyMatch(b->b.text().contains("События")));
    }
    @Test void rootButtonBelongsOnlyToConfiguredMainSuperAdmin() throws Exception {
        guard.changeSuper(500,501,true).get();
        var main=click(500,click(500,menu.home(500),"🛠 Админ"),"Суперадминистратор");
        var extra=click(501,click(501,menu.home(501),"🛠 Админ"),"Суперадминистратор");
        String rootButton=button(menu.home(500),"Root");
        assertFalse(main.buttons().stream().anyMatch(b->b.text().contains("Root")));
        assertFalse(extra.buttons().stream().anyMatch(b->b.text().contains("Root")));
        assertTrue(menu.callback(501,rootButton).alert());
        var root=menu.callback(500,rootButton).view();
        var team=click(500,root,"Команда и роли");
        var supers=click(500,team,"Суперадминистраторы");
        assertTrue(supers.buttons().stream().anyMatch(b->b.text().contains("ID 501")));
        assertFalse(supers.buttons().stream().anyMatch(b->b.text().contains("ID 99")));
        String reload=button(click(500,root,"Техническое управление"),"Перезагрузить"),children=button(team,"Детские аккаунты");
        click(500,supers,"Добавить суперадминистратора");
        var confirmation=menu.input(500,"502");
        String grant=button(confirmation,"Подтвердить");
        assertThrows(ExecutionException.class,()->guard.changeSuper(501,502,true).get());
        assertThrows(ExecutionException.class,()->guard.child(501,owner,"Player",true).get());
        assertThrows(SecurityException.class,()->guard.children(501));
        var ordinary=click(501,extra,"Администраторы бота");
        assertFalse(ordinary.buttons().stream().anyMatch(b->b.text().contains("суперадминистратора") || b.text().contains("ID 500") || b.text().contains("ID 501")));
        assertFalse(extra.buttons().stream().anyMatch(b->b.text().contains("Дети") || b.text().contains("Перезагрузить")));
        guard.configure(new GuardConfig(true,120000,0),Set.of(99L));
        assertThrows(SecurityException.class,()->menu.callback(500,rootButton));
        assertThrows(SecurityException.class,()->menu.callback(500,reload));
        assertThrows(SecurityException.class,()->menu.callback(500,children));
        assertThrows(SecurityException.class,()->menu.callback(500,grant));
        assertFalse(guard.superAdmin(502));
        verify(moderation,never()).reloadPlugin(500);
    }
    @Test void linkedCabinetShowsHeartsAndCurrentCoordinates() throws Exception {
        when(moderation.cabinetProfile(42,owner)).thenReturn(CompletableFuture.completedFuture(
                new TelegramModeration.CabinetProfile(true,17.0,20.0,13,"world",223,64,1004,java.time.Instant.parse("2026-02-09T13:57:00Z").toEpochMilli(),90061L)));
        var home=menu.home(42);
        assertTrue(home.text().contains("Здоровье: ♥♥♥♥♥♥♥♥♡♤"),home.text());
        assertTrue(home.text().contains("\n\nГолод: ◆◆◆◆◆◆◇○○○"),home.text());
        var rendered=TelegramEmojiMarkup.html(home.text());assertTrue(rendered.custom());assertTrue(rendered.text().startsWith("<b>"));assertTrue(rendered.text().endsWith("</b>"));
        assertFalse(home.text().contains("17/20"),home.text());
        assertTrue(TelegramEmojiMarkup.plain(home.text()).contains("📍 world · 223 64 1004"),home.text());
        assertTrue(TelegramEmojiMarkup.plain(home.text()).contains("⏱ 1 дн. 1 ч. 1 мин."),home.text());
        assertTrue(TelegramEmojiMarkup.html(home.text()).text().contains("world · </b><code>223 64 1004</code>"),TelegramEmojiMarkup.html(home.text()).text());
    }
    @Test void cabinetOmitsUnknownFirstJoinInsteadOfShowingNoData() throws Exception {
        when(moderation.cabinetProfile(42,owner)).thenReturn(CompletableFuture.completedFuture(
                new TelegramModeration.CabinetProfile(false,20.0,20.0,20,"world",1,64,2,0,3600L)));
        assertFalse(TelegramEmojiMarkup.plain(menu.home(42).text()).contains("Первый вход:"));
    }
    @Test void personalHistoryUsesUuidAnd48HoursAndShowsActorName() throws Exception {
        guard.restore(List.of(snap(owner,10,1),snap(other,10,1)));clock.addAndGet(120001);
        guard.attribute(snap(owner,10,1).sign(),"Actor");guard.accept(snap(owner,9,2));guard.accept(snap(other,8,2));
        var own=guard.ownHistory(42,0,10).get();assertEquals(1,own.size());assertEquals("Actor",own.getFirst().actor());
        assertNull(guard.ownEvent(43,own.getFirst().id()).get());
        var events=click(42,click(42,menu.home(42),"Мои ресурсы"),"События");var view=click(42,click(42,events,"Список событий"),"#");assertTrue(view.text().contains("Кто изменил: Actor"));
        clock.addAndGet(2L*86400_000+1);assertTrue(guard.ownHistory(42,0,10).get().isEmpty());assertEquals(2,guard.history(99,0,10).get().size());
    }
    @Test void onlineNotificationsAndSuperDelayAreIndependentOfAdminDelay() throws Exception {
        guard.offlineSeconds(42,-1L).get();guard.superDelay(500,-1).get();guard.presence(owner,"Player42",true);guard.restore(List.of(snap(owner,10,1)));guard.accept(snap(owner,9,2));
        guard.history(500,0,1).get();clock.addAndGet(10000);
        assertEquals(Set.of(42L,500L),new HashSet<>(guard.deliveries().get().stream().map(GuardService.Delivery::recipient).toList()));
        assertThrows(ExecutionException.class,()->guard.superDelay(99,0).get());
    }
    @Test void rootAdminChangesRequireConfirmationAndRevocationIsImmediate() throws Exception {
        var root=click(500,click(500,click(500,menu.home(500),"🛠 Админ"),"Суперадминистратор"),"Администраторы бота");click(500,root,"Добавить администратора");var confirm=menu.input(500,"77");
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
    @Test void permanentBanIsAlwaysAvailableToSuperAndCanBeToggledForAdmin() throws Exception {
        var person=new TelegramModeration.Person(other,"Target",true,"");
        when(moderation.players(anyLong(),eq("online"))).thenReturn(CompletableFuture.completedFuture(List.of(person)));
        when(moderation.info(anyLong(),eq(other))).thenReturn(CompletableFuture.completedFuture(person));
        var superCard=click(500,click(500,click(500,click(500,menu.home(500),"🛠 Админ"),"Суперадминистратор"),"Список игроков"),"Target");
        assertNotNull(button(superCard,"Забанить"));
        var adminCard=click(99,click(99,click(99,menu.home(99),"Функции администратора"),"Список игроков"),"Target");
        assertFalse(adminCard.buttons().stream().anyMatch(b->b.text().contains("Забанить")));
        guard.capability(500,"permanentBan",true).get();
        adminCard=click(99,click(99,click(99,menu.home(99),"Функции администратора"),"Список игроков"),"Target");
        assertNotNull(button(adminCard,"Забанить"));
        var capabilities=click(500,click(500,click(500,click(500,menu.home(500),"🛠 Админ"),"Суперадминистратор"),"Администраторы бота"),"Возможности администратора");
        assertTrue(capabilities.buttons().stream().anyMatch(b->b.text().contains("Бессрочный бан")));
    }
    @Test void regularAdminCanChooseOnlinePlayersForTemporaryBan() throws Exception {
        guard.capability(500,"ban",true).get();
        var person=new TelegramModeration.Person(other,"OnlineTarget",true,"");
        when(moderation.players(99,"online")).thenReturn(CompletableFuture.completedFuture(List.of(person)));
        var choice=click(99,click(99,menu.home(99),"Функции администратора"),"Список игроков");
        var players=choice;assertNotNull(button(players,"OnlineTarget"));
        verify(moderation).players(99,"online");
    }
    @Test void vanishedPlayerHasGlassesInAdminAndSuperAdminPlayerLists() throws Exception {
        var hidden=new TelegramModeration.Person(other,"HiddenAdmin",true,"",true);
        when(moderation.players(99,"online")).thenReturn(CompletableFuture.completedFuture(List.of(hidden)));
        when(moderation.players(500,"online")).thenReturn(CompletableFuture.completedFuture(List.of(hidden)));
        var adminList=click(99,click(99,menu.home(99),"Функции администратора"),"Список игроков");
        var superList=click(500,click(500,click(500,menu.home(500),"🛠 Админ"),"Суперадминистратор"),"Список игроков");
        assertTrue(adminList.buttons().stream().anyMatch(button->button.text().equals("🕶 HiddenAdmin")));
        assertTrue(superList.buttons().stream().anyMatch(button->button.text().equals("🕶 HiddenAdmin")));
    }
    @Test void onlySuperAdminCanScheduleVaultTrackerReload() throws Exception {
        var root=click(500,menu.home(500),"Root");
        root=click(500,root,"Техническое управление");
        assertTrue(root.buttons().stream().anyMatch(b->b.text().contains("Перезагрузить VaultTracker")));
        var result=click(500,root,"Перезагрузить VaultTracker");
        assertTrue(result.text().contains("запланирована"));
        verify(moderation).reloadPlugin(500);
        var admin=click(99,menu.home(99),"Функции администратора");
        assertFalse(admin.buttons().stream().anyMatch(b->b.text().contains("Перезагрузить VaultTracker")));
    }
    @Test void superAdminCanFilterPlayerListByPartialName() throws Exception {
        var first=new TelegramModeration.Person(other,"Giga_TapoChek_",false,"");
        var second=new TelegramModeration.Person(UUID.randomUUID(),"Semui",false,"");
        when(moderation.players(500,"online")).thenReturn(CompletableFuture.completedFuture(List.of(first,second)));
        when(moderation.adminGroup(500,other)).thenReturn(CompletableFuture.completedFuture(false));
        var list=click(500,click(500,click(500,menu.home(500),"🛠 Админ"),"Суперадминистратор"),"Список игроков");

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
        var root=click(500,click(500,menu.home(500),"🛠 Админ"),"Суперадминистратор");var list=click(500,root,"Список игроков");var card=click(500,list,"Target");
        var actions=card;
        var fly=click(500,actions,"Скорость полёта");
        assertTrue(fly.text().contains("1.0 — значение по умолчанию"));
        assertTrue(fly.buttons().stream().anyMatch(b->b.text().endsWith("20.0")), fly.buttons().toString());
        var confirm=click(500,fly,"20.0");
        assertTrue(confirm.text().contains("скорость полёта 20.0x"));
        click(500,confirm,"Подтвердить");verify(moderation).speed(500,other,"fly",20);
    }
    @Test void adminPlayerCardUsesRussianFlagsMoscowDatesStatusBarsAndCopyableCoordinates() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/player-summary",exchange->{
            byte[] bytes=("{\"balanceMinor\":105,\"premium\":true,\"premiumUntil\":1792067400,\"votes\":["+
                    "{\"provider\":\"minecraft_rating\",\"name\":\"MinecraftRating\",\"votes\":1},"+
                    "{\"provider\":\"top_minecrafter\",\"name\":\"Top-Minecrafter\",\"votes\":2},"+
                    "{\"provider\":\"hotmc\",\"name\":\"HotMC\",\"votes\":5}]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        Files.writeString(path.resolve("donations.yml"),"enabled: true\napi-key: '"+"x".repeat(48)+"'\nport: "+server.getAddress().getPort()+"\n");
        long first=java.time.Instant.parse("2026-02-09T13:57:00Z").toEpochMilli(),last=java.time.Instant.parse("2026-09-20T12:54:00Z").toEpochMilli();
        var person=new TelegramModeration.Person(other,"Target",true,"",false,true,false,first,last,last+3600_000);
        when(moderation.players(500,"online")).thenReturn(CompletableFuture.completedFuture(List.of(person)));
        when(moderation.info(500,other)).thenReturn(CompletableFuture.completedFuture(person));
        when(moderation.playerProfile(500,other)).thenReturn(CompletableFuture.completedFuture(new TelegramModeration.CabinetProfile(true,18.0,20.0,15,"world",10,64,-20,first,93784L)));
        when(moderation.adminGroup(500,other)).thenReturn(CompletableFuture.completedFuture(false));
        try {
            var card=click(500,click(500,click(500,click(500,menu.home(500),"🛠 Админ"),"Суперадминистратор"),"Список игроков"),"Target");String plain=TelegramEmojiMarkup.plain(card.text());
            assertTrue(plain.contains("Онлайн: да · OP: да · Бан: нет"),plain);assertTrue(plain.contains("Первый: 09.02.2026 16:57"),plain);
            assertFalse(plain.contains("Последний:"),plain);assertFalse(plain.contains("Выход:"),plain);
            assertTrue(plain.contains("Баланс: 1.05"),plain);assertTrue(plain.contains("Премиум: активен"),plain);
            assertTrue(plain.contains("Окончание премиума: 15.10.2026 15:30 МСК"),plain);
            assertTrue(plain.contains("MinecraftRating: 1 голос"),plain);assertTrue(plain.contains("Top-Minecrafter: 2 голоса"),plain);assertTrue(plain.contains("HotMC: 5 голосов"),plain);
            assertTrue(plain.contains("♥♥♥♥♥♥♥♥♥♤"),plain);assertTrue(plain.contains("◆◆◆◆◆◆◆◇○○"),plain);
            assertTrue(plain.contains("⏱ 1 дн. 2 ч. 3 мин."),plain);String html=TelegramEmojiMarkup.html(card.text()).text();assertTrue(html.contains("world · </b><code>10 64 -20</code>"),html);assertFalse(html.contains("<b><code>"),html);
            while(card.buttons().stream().anyMatch(b->b.text().contains("Вперёд"))) card=click(500,card,"Вперёд");
            var copy=card.buttons().stream().filter(b->b.copyText()!=null).findFirst().orElseThrow();assertEquals(other.toString(),copy.copyText());
        } finally {server.stop(0);}
    }
    TelegramCommands.View rootActions() throws Exception {
        var target=new TelegramModeration.Person(other,"Target",true,"");
        when(moderation.players(500,"online")).thenReturn(CompletableFuture.completedFuture(List.of(target)));
        when(moderation.info(500,other)).thenReturn(CompletableFuture.completedFuture(target));
        when(moderation.adminGroup(500,other)).thenReturn(CompletableFuture.completedFuture(false));
        return click(500,click(500,click(500,click(500,menu.home(500),"🛠 Админ"),"Суперадминистратор"),"Список игроков"),"Target");
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
        click(500,rootActions(),"Отправить сообщение в игровой чат");var preview=menu.input(500,"/op Player42");
        verify(moderation,never()).broadcast(anyLong(),anyString());click(500,preview,"Подтвердить");verify(moderation).broadcast(500,"/op Player42");
    }
    @Test void roleOverridesTagAndSuperTimePersistAcrossRestart() throws Exception {
        guard.changeAdmin(500,77,true).get();guard.changeAdmin(500,99,false).get();guard.tag(42,true).get();guard.superDelay(500,172800).get();guard.close();
        guard=new GuardService(path.resolve("guard"),new GuardConfig(true,120000,500),Logger.getAnonymousLogger(),clock::get);guard.configure(new GuardConfig(true,120000,500),Set.of(99L));
        assertTrue(guard.admin(77));assertFalse(guard.admin(99));assertTrue(guard.tag(42,false).get());assertEquals(172800,guard.superDelay(500));
    }
    @Test void mainMenuAlwaysOffersSearchAndCabinetEvenBeforeBinding() throws Exception {
        for(long user:List.of(42L,500L,12345L)) {
            var main=menu.mainMenu(user);assertEquals(List.of("🔎 Поиск","👤 Личный кабинет","🆘 Поддержка"),main.buttons().stream().map(TelegramCommands.Button::text).toList());assertTrue(main.text().contains("торговый бот FLEXITY"));assertTrue(main.text().contains("не знаете, у кого он есть"));assertTrue(main.text().contains("защитить свои ресурсы"));
        }
        assertNotNull(button(click(12345,menu.mainMenu(12345),"Личный кабинет"),"Привязать"));
    }
    @Test void removesSuperByTypedIdOnlyAfterConfirmation() throws Exception {
        long target=123456789L;guard.changeSuper(500,target,true).get();
        var team=click(500,menu.rootHome(500),"Команда и роли");var admins=click(500,team,"Суперадминистраторы");
        String remove=button(admins,"Удалить суперадминистратора");
        assertTrue(menu.callback(99,remove).alert());menu.callback(500,remove);
        assertTrue(menu.input(500,"abc").text().contains("Telegram ID"));
        assertTrue(menu.input(500,"123").text().contains("не найден"));
        assertTrue(menu.input(500,"500").text().contains("удалить нельзя"));
        var confirm=menu.input(500,Long.toString(target));assertTrue(guard.superAdmin(target));
        String yes=button(confirm,"Подтвердить");assertTrue(menu.callback(99,yes).alert());
        menu.callback(500,yes);assertFalse(guard.admin(target));assertTrue(menu.callback(500,yes).alert());
    }
    @Test void buttonListsUseTenButTextInventoryUsesTwentyEntriesWithoutSkipping() throws Exception {
        var people=java.util.stream.IntStream.range(0,21).mapToObj(i->new TelegramModeration.Person(UUID.randomUUID(),"Player_"+i,true,"")).toList();
        when(moderation.players(500,"online")).thenReturn(CompletableFuture.completedFuture(people));
        var first=click(500,click(500,click(500,menu.home(500),"🛠 Админ"),"Суперадминистратор"),"Список игроков");
        assertEquals(10,first.buttons().stream().filter(b->b.text().contains("Player_")).count());
        var next=click(500,first,"Вперёд");assertEquals(10,next.buttons().stream().filter(b->b.text().contains("Player_")).count());
        var last=click(500,next,"Вперёд");assertEquals(1,last.buttons().stream().filter(b->b.text().contains("Player_")).count());assertNotNull(button(last,"Player_20"));
        assertEquals(10,click(500,last,"◀ Назад").buttons().stream().filter(b->b.text().contains("Player_")).count());
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
        var superCard=rootActions();boolean canGrant=false,canRevoke=false;
        for(int i=0;i<10;i++) {
            canGrant|=superCard.buttons().stream().anyMatch(b->b.text().contains("Выдать OP"));
            canRevoke|=superCard.buttons().stream().anyMatch(b->b.text().contains("Забрать OP"));
            if(superCard.buttons().stream().noneMatch(b->b.text().contains("Вперёд"))) break;
            superCard=click(500,superCard,"Вперёд");
        }
        assertTrue(canGrant);assertTrue(canRevoke);
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

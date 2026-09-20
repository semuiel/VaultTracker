package ru.vaulttracker;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.bukkit.Material;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramGuardMenuTest {
    @TempDir Path folder;
    GuardService guard;
    Catalogue catalogue=new Catalogue(v->{});
    StorageEngine storage=mock(StorageEngine.class);
    TelegramGuardMenu menu;
    AtomicLong clock=new AtomicLong(2_000_000);
    @BeforeEach void prepare() throws Exception {
        guard=new GuardService(folder.resolve("guard"),new GuardConfig(true,0),Logger.getAnonymousLogger(),clock::get);
        guard.configure(new GuardConfig(true,0),Set.of(99L));when(storage.ready()).thenReturn(true);
        menu=new TelegramGuardMenu(guard,new TelegramCommands(catalogue,storage,1,id->true));
    }
    @AfterEach void close() {guard.close();}
    @Test void donationCodeWorksWithoutPromptAndHasNoInputDeadline() throws Exception {
        guard.configure(new GuardConfig(true,0,99),Set.of());UUID owner=UUID.randomUUID();
        guard.link(owner,"Alex",guard.generate(42).get().split("/vtrack link ")[1].substring(0,32)).get();guard.donationsVisible(99,true).get();
        var direct=menu.input(42,"A".repeat(32));assertNotNull(direct);assertTrue(direct.text().contains("Не удалось зачислить код"));
        var method=TelegramGuardMenu.class.getDeclaredMethod("prompt",long.class,String.class,String.class);method.setAccessible(true);
        var prompt=(TelegramCommands.View)method.invoke(menu,42L,"donationCode","Введите код");assertFalse(prompt.text().contains("10 минут"));
        menu.cancelInput(42);assertNotNull(menu.input(42,"B".repeat(32)));
    }
    @Test void donationPlayerPickerHasTwentyRowsOfflineToggleAndSearch() throws Exception {
        guard.configure(new GuardConfig(true,0,99),Set.of());var moderation=mock(TelegramModeration.class);
        var people=new ArrayList<TelegramModeration.Person>();for(int i=0;i<21;i++)people.add(new TelegramModeration.Person(UUID.randomUUID(),"Player"+i,true,""));
        people.add(new TelegramModeration.Person(UUID.randomUUID(),"OfflineAlex",false,""));
        when(moderation.players(99,"online")).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(people.stream().filter(TelegramModeration.Person::online).toList()));
        when(moderation.players(99,"all")).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(people));
        menu=new TelegramGuardMenu(guard,new TelegramCommands(catalogue,storage,20,v->true),moderation);
        var admin=click(99,click(99,menu.home(99),"Супер"),"Пожертвования");var picker=click(99,admin,"Добавить пожертвования");
        assertEquals(20,picker.buttons().stream().filter(b->b.text().startsWith("Player")).count());
        assertTrue(click(99,picker,"Вперёд").text().contains("2/2"));var offline=click(99,picker,"офлайн");assertTrue(offline.buttons().stream().anyMatch(b->b.text().equals("OfflineAlex")));
        click(99,offline,"Поиск");assertTrue(menu.input(99,"alex").buttons().stream().anyMatch(b->b.text().equals("OfflineAlex")));
        assertTrue(menu.callback(42,button(admin,"Добавить пожертвования")).alert());
    }
    @Test void superAdminCanSelectPermanentOrCustomPremiumDuration() throws Exception {
        guard.configure(new GuardConfig(true,0,99),Set.of());var moderation=mock(TelegramModeration.class);UUID player=UUID.randomUUID();
        var person=new TelegramModeration.Person(player,"Player",true,"");
        when(moderation.players(99,"online")).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(List.of(person)));
        when(moderation.info(99,player)).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(person));
        menu=new TelegramGuardMenu(guard,new TelegramCommands(catalogue,storage,20,v->true),moderation);
        var admin=click(99,click(99,menu.home(99),"Супер"),"Пожертвования");var durations=click(99,admin,"Добавить премиум");
        assertTrue(durations.buttons().stream().anyMatch(b->b.text().equals("Навсегда")));assertTrue(durations.buttons().stream().anyMatch(b->b.text().equals("Свой срок")));
        var permanent=click(99,click(99,durations,"Навсегда"),"Player");assertTrue(permanent.text().contains("бессрочный премиум"));
        var customPlayer=click(99,click(99,durations,"Свой срок"),"Player");assertTrue(customPlayer.text().contains("от 1 до 36500"));
        assertTrue(menu.input(99,"0").text().contains("от 1 до 36500"));var confirm=menu.input(99,"45");assertTrue(confirm.text().contains("на 45 дней"));
    }
    @Test void donationsRequireLinkedAccountAndPersistSuperVisibility() throws Exception {
        guard.configure(new GuardConfig(true,0,99),Set.of());UUID id=UUID.randomUUID();
        guard.link(id,"Alex",guard.generate(42).get().split("/vtrack link ")[1].substring(0,32)).get();
        assertFalse(menu.home(42).buttons().stream().anyMatch(b->b.text().contains("Пожертвования")));
        assertThrows(Exception.class,()->guard.donationsVisible(42,true).get());
        var admin=click(99,click(99,menu.home(99),"Супер"),"Пожертвования");
        click(99,admin,"Включить");
        assertFalse(menu.home(43).buttons().stream().anyMatch(b->b.text().contains("Пожертвования")));
        var donations=click(42,menu.home(42),"Пожертвования");
        assertTrue(donations.text().contains("Баланс: временно недоступен"));assertTrue(donations.text().contains("добровольны"));
        assertTrue(menu.callback(43,button(donations,"Премиум")).alert());
        assertTrue(click(42,donations,"Пожертвовать").text().contains("недоступна"));
        guard.close();guard=new GuardService(folder.resolve("guard"),new GuardConfig(true,0,99),Logger.getAnonymousLogger(),clock::get);
        assertTrue(guard.donationsVisible().get());
        menu=new TelegramGuardMenu(guard,new TelegramCommands(catalogue,storage,20,v->true));
        var fresh=click(42,menu.home(42),"Пожертвования");guard.donationsVisible(99,false).get();
        assertThrows(Exception.class,()->click(42,fresh,"Премиум"));
    }
    @Test void childMenuIsLinkedOwnerOnlyAndSuperManagementIsProtected() throws Exception {
        guard.configure(new GuardConfig(true,0,99),Set.of());UUID id=UUID.randomUUID();
        guard.link(id,"Alex",guard.generate(42).get().split("/vtrack link ")[1].substring(0,32)).get();
        var moderation=new TelegramModeration(mock(org.bukkit.plugin.java.JavaPlugin.class),guard);
        menu=new TelegramGuardMenu(guard,new TelegramCommands(catalogue,storage,20,v->true),moderation);
        assertFalse(menu.home(42).buttons().stream().anyMatch(b->b.text().contains("Размер")));
        var root=click(99,menu.home(99),"Супер");var kids=click(99,root,"Дети");assertTrue(kids.buttons().stream().anyMatch(b->b.text().contains("Добавить ребёнка")));
        assertTrue(menu.callback(42,button(root,"Дети")).alert());
        guard.child(99,id,"Alex",true).get();var sizes=click(42,menu.home(42),"Размер");assertEquals(3,sizes.buttons().stream().filter(b->b.text().contains("Гном")||b.text().contains("Карлик")||b.text().contains("нормальный")).count());
        assertTrue(menu.callback(43,button(sizes,"Гном")).alert());click(42,sizes,"Гном");assertEquals(0.6,guard.child(id).size());
        guard.child(99,id,"Alex",false).get();assertThrows(Exception.class,()->click(42,sizes,"Карлик"));
    }
    @Test void ownChestSearchPaginatesAndDoesNotExposeOtherOwners() throws Exception {
        UUID owner=UUID.randomUUID(),world=UUID.randomUUID();
        guard.link(owner,"Alex",guard.generate(42).get().split("/vtrack link ")[1].substring(0,32)).get();
        for(int i=0;i<21;i++) catalogue.register(new BlockKey(world,i,65,0),owner,"Alex",List.of(new BlockKey(world,i,64,0)),Map.of("DIAMOND_ORE",1L),1,0);
        catalogue.register(new BlockKey(world,999,65,0),UUID.randomUUID(),"Alex",List.of(new BlockKey(world,999,64,0)),Map.of("DIAMOND_ORE",999L),1,0);
        menu.callback(42,"vg:resourceSearch");var page=menu.input(42,"алмазная руда");
        assertTrue(page.text().contains("1/2"));assertFalse(page.text().contains("999"));assertTrue(page.text().contains("офлайн"));
        String next=button(page,"Вперёд");assertTrue(menu.callback(43,next).alert());
        assertTrue(menu.callback(42,next).view().text().contains("2/2"));
    }
    @Test void eventsMenuAddsPagesAndRemovesFriendsByExactNickname() throws Exception {
        UUID owner=UUID.randomUUID(),friendId=UUID.randomUUID();guard.link(owner,"Alex",guard.generate(42).get().split("/vtrack link ")[1].substring(0,32)).get();
        var moderation=mock(TelegramModeration.class);when(moderation.playerByName("Trusted"))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(new TelegramModeration.Person(friendId,"Trusted",false,"")));
        menu=new TelegramGuardMenu(guard,new TelegramCommands(catalogue,storage,20,v->true),moderation);
        var events=click(42,menu.home(42),"События");assertTrue(events.buttons().stream().anyMatch(b->b.text().contains("Список событий за 2 дня")));
        var friends=click(42,events,"Друзья");assertTrue(friends.text().contains("Список пуст"));
        click(42,friends,"Добавить друга");friends=menu.input(42,"Trusted");assertEquals("Trusted",guard.friends(42).get().getFirst().name());
        for(int i=0;i<20;i++) guard.addFriend(42,UUID.randomUUID(),"Friend"+String.format("%02d",i)).get();
        friends=click(42,events,"Друзья");assertEquals(20,friends.buttons().stream().filter(b->b.text().startsWith("❌ ")).count());
        friends=click(42,friends,"Вперёд");assertTrue(friends.text().contains("2/2"));
        var confirm=click(42,friends,"Trusted");assertTrue(confirm.text().contains("Удалить Trusted"));
        click(42,confirm,"Да, удалить");assertFalse(guard.friends(42).get().stream().anyMatch(f->f.uuid().equals(friendId)));
    }
    String button(TelegramCommands.View view,String contains) {return view.buttons().stream().filter(b->b.text().contains(contains)).findFirst().orElseThrow().data();}
    TelegramCommands.View click(long user,TelegramCommands.View view,String contains) throws Exception {return menu.callback(user,button(view,contains)).view();}
    @Test void personalSettingsOfferOnlyThreePresetsAndAreOwnerBound() throws Exception {
        guard.link(UUID.randomUUID(),"Alex",guard.generate(42).get().split("/vtrack link ")[1].substring(0,32)).get();
        var settings=click(42,menu.home(42),"Настройки");String day=button(settings,"2 дня");
        assertTrue(menu.callback(43,day).alert());assertNull(guard.offlineSeconds(42).get());
        settings=menu.callback(42,day).view();assertEquals(172800L,guard.offlineSeconds(42).get());
        assertFalse(settings.buttons().stream().anyMatch(b->b.text().contains("Своё время") || b.text().contains("1 дн.")));
        settings=click(42,settings,"Всегда");assertEquals(-1L,guard.offlineSeconds(42).get());
        click(42,settings,"После выхода");assertEquals(0L,guard.offlineSeconds(42).get());
    }
    @Test void customTimeInputOnlyCapturesPersonalChatAndCancelEndsIt() throws Exception {
        guard.link(UUID.randomUUID(),"Alex",guard.generate(42).get().split("/vtrack link ")[1].substring(0,32)).get();
        TelegramConfig config=mock(TelegramConfig.class);when(config.pageSize()).thenReturn(8);
        var process=TelegramBotService.class.getDeclaredMethod("process",TelegramApi.Incoming.class);process.setAccessible(true);
        try(var apis=mockConstruction(TelegramApi.class);
            var service=new TelegramBotService(config,catalogue,storage,Logger.getAnonymousLogger(),guard)) {
            var field=TelegramBotService.class.getDeclaredField("guardMenu");field.setAccessible(true);
            var actual=(TelegramGuardMenu)field.get(service);var api=apis.constructed().getFirst();
            guard.configure(new GuardConfig(true,0,42),Set.of(99L));
            var root=actual.callback(42,button(actual.home(42),"Супер")).view();
            var settings=actual.callback(42,button(root,"Личный срок")).view();
            String custom=button(settings,"Своё время");
            process.invoke(service,new TelegramApi.Incoming(1,42,0,42,1,null,"cb",custom,true));
            clearInvocations(api);
            process.invoke(service,new TelegramApi.Incoming(2,-100,20,42,2,"86400",null,null,false));verifyNoInteractions(api);
            process.invoke(service,new TelegramApi.Incoming(3,42,0,42,3,"86400",null,null,true));
            verify(api).send(eq(42L),eq(0),any());assertEquals(86400L,guard.superDelay(42));
            process.invoke(service,new TelegramApi.Incoming(4,42,0,42,1,null,"cb2",custom,true));
            process.invoke(service,new TelegramApi.Incoming(5,42,0,42,4,"/cancel",null,null,true));
            clearInvocations(api);
            process.invoke(service,new TelegramApi.Incoming(6,42,0,42,5,"900",null,null,true));
            verifyNoInteractions(api);assertEquals(86400L,guard.superDelay(42));
        }
    }
    @Test void bindingAndAccountButtonsWorkAndCannotBeUsedByAnotherUser() throws Exception {
        var home=menu.home(42);assertFalse(home.text().contains("Уведомления администратора"));
        String bind=button(home,"Привязать");assertTrue(menu.callback(43,bind).alert());
        var challenge=menu.callback(42,bind).view();String code=challenge.text().split("/vtrack link ")[1].substring(0,32);
        UUID uuid=UUID.randomUUID();guard.link(uuid,"Alex",code).get();
        var account=menu.callback(42,"vg:home").view();assertTrue(account.text().contains("Персонаж: Alex"));
        String toggle=button(click(42,account,"Настройки"),"Отключить мои");assertTrue(menu.callback(43,toggle).alert());assertTrue(guard.account(42).get().notifications());
        assertTrue(menu.callback(42,toggle).view().text().contains("Ваши уведомления: выключены"));
        assertTrue(menu.callback(42,toggle).alert());
    }
    @Test void settingsHaveSeparateApplyAndResetTagButtons() throws Exception {
        guard.link(UUID.randomUUID(),"Alex",guard.generate(42).get().split("/vtrack link ")[1].substring(0,32)).get();
        TelegramTagManager tags=mock(TelegramTagManager.class);
        when(tags.apply(42,"Alex")).thenReturn(new TelegramTagManager.Result("applied",true,true));
        when(tags.reset(42)).thenReturn(new TelegramTagManager.Result("reset",true,true));
        menu=new TelegramGuardMenu(guard,new TelegramCommands(catalogue,storage,1,id->true),null,tags);
        var settings=click(42,menu.home(42),"Настройки");
        var result=click(42,settings,"Применить тег");assertEquals("applied",result.text());verify(tags).apply(42,"Alex");assertTrue(guard.tag(42,false).get());
        settings=click(42,result,"Назад в настройки");result=click(42,settings,"Сбросить тег");assertEquals("reset",result.text());verify(tags).reset(42);assertFalse(guard.tag(42,false).get());
    }
    @Test void resourcesUseUuidWhenNamesCollideAndPagesRemainBoundToRequester() throws Exception {
        UUID owner=UUID.randomUUID(),world=UUID.randomUUID();
        guard.link(owner,"Alex",guard.generate(42).get().split("/vtrack link ")[1].substring(0,32)).get();
        catalogue.register(new BlockKey(world,1,1,1),owner,"Alex",List.of(new BlockKey(world,1,1,2)),Map.of("DIAMOND",5L,"IRON_INGOT",2L),1,100);
        catalogue.register(new BlockKey(world,2,1,1),UUID.randomUUID(),"Alex",List.of(new BlockKey(world,2,1,2)),Map.of("DIAMOND",999L),1,100);
        Material material=mock(Material.class);when(material.getMaxStackSize()).thenReturn(64);
        try(var materials=mockStatic(Material.class)) {
            materials.when(()->Material.getMaterial(anyString())).thenReturn(material);
            var result=click(42,menu.home(42),"Мои ресурсы");assertTrue(result.text().contains("5 шт."));assertFalse(result.text().contains("999"));
            assertTrue(result.buttons().stream().anyMatch(b->b.data().startsWith("vt:")));
        }
    }
    @Test void adminWithoutCharacterCanToggleAndReadPaginatedHistory() throws Exception {
        var home=click(99,menu.home(99),"Функции администратора");assertTrue(home.text().contains("Уведомления администратора: включены"));
        assertTrue(click(99,home,"Отключить уведомления администратора").text().contains("Уведомления администратора: выключены"));
        var snapshot=CatalogueTest.fixture(1,20);guard.restore(List.of(snapshot));clock.incrementAndGet();
        for(int i=1;i<=9;i++) guard.accept(CatalogueTest.fixture(i+1,20-i));
        var history=click(99,click(99,menu.home(99),"Функции администратора"),"События");assertTrue(history.buttons().stream().anyMatch(b->b.text().contains("Вперёд")));
        var detail=click(99,history,"#9");assertTrue(detail.text().contains("-1 шт."));
        guard.configure(new GuardConfig(true,0),Set.of());
        assertThrows(Exception.class,()->click(99,detail,"История"));
    }
    @Test void notificationsSendPrivatelyAndFailuresRemainQueued() throws Exception {
        TelegramConfig config=mock(TelegramConfig.class);when(config.pageSize()).thenReturn(8);
        var snapshot=CatalogueTest.fixture(1,20);guard.restore(List.of(snapshot));guard.accept(CatalogueTest.fixture(2,19));
        guard.history(99,0,1).get();clock.addAndGet(10000);
        var notify=TelegramBotService.class.getDeclaredMethod("notifyGuard");notify.setAccessible(true);
        try(var apis=mockConstruction(TelegramApi.class);
            var service=new TelegramBotService(config,catalogue,storage,Logger.getAnonymousLogger(),guard)) {
            var api=apis.constructed().getFirst();
            when(api.send(eq(99L),eq(0),any())).thenThrow(new java.io.IOException("unavailable"));
            notify.invoke(service);verify(api).send(eq(99L),eq(0),any());assertTrue(guard.deliveries().get().isEmpty());
            clock.addAndGet(300_000);when(api.send(eq(99L),eq(0),any())).thenReturn(123);
            notify.invoke(service);verify(api,times(2)).send(eq(99L),eq(0),any());assertTrue(guard.deliveries().get().isEmpty());
            assertEquals(1,guard.history(99,0,10).get().size());
        }
    }
    @Test void privateGuardMenuRoutesWithoutCapturingGroupOrNormalText() throws Exception {
        TelegramConfig config=mock(TelegramConfig.class);when(config.pageSize()).thenReturn(8);
        var process=TelegramBotService.class.getDeclaredMethod("process",TelegramApi.Incoming.class);process.setAccessible(true);
        try(var apis=mockConstruction(TelegramApi.class);
            var service=new TelegramBotService(config,catalogue,storage,Logger.getAnonymousLogger(),guard)) {
            var api=apis.constructed().getFirst();
            process.invoke(service,new TelegramApi.Incoming(1,42,0,42,1,null,"cb","vg:home",true));
            var view=org.mockito.ArgumentCaptor.forClass(TelegramCommands.View.class);
            verify(api).edit(eq(42L),eq(1),view.capture());assertTrue(view.getValue().text().contains("Личный кабинет"));
            clearInvocations(api);
            process.invoke(service,new TelegramApi.Incoming(2,42,0,42,2,"обычный текст",null,null,true));
            process.invoke(service,new TelegramApi.Incoming(3,-100,20,42,3,"обычный текст",null,null,false));
            process.invoke(service,new TelegramApi.Incoming(4,-100,20,42,4,"/list",null,null,false));
            verifyNoInteractions(api);
            process.invoke(service,new TelegramApi.Incoming(5,-100,20,42,5,null,"cb2","vg:home",false));
            verify(api).answerCallback("cb2","",false);verify(api,never()).edit(anyLong(),anyInt(),any());
        }
    }
}

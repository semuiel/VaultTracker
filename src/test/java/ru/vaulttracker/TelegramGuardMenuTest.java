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
    @Test void existingCabinetButtonsAndDirectDialogRouteStillNavigateAfterOtherMenus() throws Exception {
        UUID owner=UUID.randomUUID(),peer=UUID.randomUUID();
        guard.link(owner,"Alice",guard.generate(42).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH)).get();
        guard.link(peer,"Bob",guard.generate(43).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH)).get();
        var cabinet=menu.home(42);
        String settingsButton=button(cabinet,"Настройки");
        assertTrue(menu.callback(42,"vg:menu").view().text().contains("торговый бот FLEXITY"));
        var settings=menu.callback(42,settingsButton).view();
        var notifications=click(42,settings,"Уведомления");
        assertTrue(click(42,notifications,"Назад").text().contains("Настройки игрока"));
        var dialog=menu.callback(42,"vg:dialog:"+peer).view();
        assertTrue(dialog.text().contains("Bob"));
        assertTrue(click(42,dialog,"Сообщения").text().contains("💬 Сообщения"));
        assertTrue(menu.callback(42,"vg:home").view().text().contains("Личный кабинет"));
        assertTrue(menu.callback(43,settingsButton).alert());
    }
    @Test void messagesHaveOneCabinetButtonAndOfferOnlineLinkedPlayers() throws Exception {
        UUID alice=UUID.randomUUID(),bob=UUID.randomUUID(),carol=UUID.randomUUID();
        guard.link(alice,"Alice",guard.generate(41).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH)).get();
        guard.link(bob,"Bob",guard.generate(42).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH)).get();
        guard.link(carol,"Carol",guard.generate(43).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH)).get();
        guard.presence(bob,"Bob",true);guard.presence(carol,"Carol",false);
        var messages=click(41,menu.home(41),"Сообщения");
        assertEquals(1,messages.buttons().stream().filter(b->b.data().equals("vg:home")).count());
        var players=click(41,messages,"Написать игроку");
        assertNotNull(button(players,"Bob"));
        assertFalse(players.buttons().stream().anyMatch(b->b.text().contains("Carol")));
        var dialog=click(41,players,"Bob");
        assertNotNull(button(dialog,"Написать"));
    }
    @Test void playerSettingsToggleGameMessageNotifications() throws Exception {
        UUID alice=UUID.randomUUID();
        guard.link(alice,"Alice",guard.generate(41).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH)).get();
        var settings=click(41,menu.home(41),"Настройки");
        assertTrue(guard.gameMessageNotifications(41).get());
        settings=click(41,settings,"Уведомления");
        settings=click(41,settings,"Уведомления о сообщениях");
        assertFalse(guard.gameMessageNotifications(41).get());
        assertTrue(settings.buttons().stream().anyMatch(b->b.text().contains("Уведомления о сообщениях: ВЫКЛ")));
    }
    @Test void statisticsNavigateThroughSectionPagedListAndBack() throws Exception {
        UUID alice=UUID.randomUUID();
        guard.link(alice,"Alice",guard.generate(41).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH)).get();
        Map<String,Integer> raw=new HashMap<>();
        raw.put("custom:DEATHS",3);
        for(int i=0;i<12;i++) raw.put("mine:BLOCK_"+i,12-i);
        guard.mergeStatistics(alice,raw).get();
        var overview=click(41,menu.home(41),"Статистика");
        assertTrue(overview.text().contains("Статистика персонажа"));
        assertEquals(List.of("↩ В кабинет","⌂ Меню"),overview.buttons().stream()
                .filter(b->b.data().equals("vg:home")||b.data().equals("vg:menu"))
                .map(TelegramCommands.Button::text).toList());
        assertTrue(click(41,overview,"В кабинет").text().contains("Личный кабинет"));
        var general=click(41,overview,"Общая");
        assertTrue(general.text().contains("Смерти: 3"));
        var blocks=click(41,overview,"Блоки и ресурсы");
        assertTrue(blocks.text().contains("Добыто блоков: 78"));
        var page1=click(41,blocks,"Все добытые блоки");
        assertTrue(page1.text().contains("1/2"));
        var page2=click(41,page1,"Вперёд");
        assertTrue(page2.text().contains("2/2"));
        assertTrue(click(41,page2,"К разделу").text().contains("Блоки и ресурсы"));
    }
    @Test void messagesMenuShowsUnreadConversationAndEditsSameCardAfterSending() throws Exception {
        UUID alice=UUID.randomUUID(),bob=UUID.randomUUID();
        guard.link(alice,"Alice",guard.generate(41).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH)).get();
        guard.link(bob,"Bob",guard.generate(42).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH)).get();
        guard.directMessage(bob,"Bob",alice,"Alice","Привет").get();
        var moderation=mock(TelegramModeration.class);var direct=mock(PlayerDirectMessages.class);
        when(moderation.directMessages()).thenReturn(direct);
        when(direct.fromTelegram(41,bob,"Ответ")).thenReturn(java.util.concurrent.CompletableFuture.completedFuture("Сохранено"));
        menu=new TelegramGuardMenu(guard,new TelegramCommands(catalogue,storage,10,id->true),moderation);
        var home=menu.home(41);var list=click(41,home,"Сообщения");
        assertNotNull(button(list,"Bob · 1"));
        var dialog=click(41,list,"Bob · 1");assertTrue(dialog.text().contains("↙ Bob"));assertTrue(dialog.text().contains("Привет"));
        assertEquals(0,guard.dialogs(41,0,10).get().getFirst().unread());
        menu.rememberDialogCard(41,77);click(41,dialog,"Написать");
        var updated=menu.input(41,"Ответ");verify(direct).fromTelegram(41,bob,"Ответ");
        assertEquals(77,menu.takeDialogEditTarget(41));assertEquals(0,menu.takeDialogEditTarget(41));
        assertTrue(updated.text().contains("Привет"));
    }
    @Test void telegramSendEditsConversationCardInsteadOfSendingNewBotMessage() throws Exception {
        UUID alice=UUID.randomUUID(),bob=UUID.randomUUID();
        guard.link(alice,"Alice",guard.generate(41).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH)).get();
        guard.link(bob,"Bob",guard.generate(42).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH)).get();
        guard.directMessage(bob,"Bob",alice,"Alice","Привет").get();
        TelegramConfig config=mock(TelegramConfig.class);when(config.pageSize()).thenReturn(10);
        var moderation=mock(TelegramModeration.class);var direct=mock(PlayerDirectMessages.class);
        when(moderation.directMessages()).thenReturn(direct);
        when(direct.fromTelegram(41,bob,"Ответ")).thenReturn(java.util.concurrent.CompletableFuture.completedFuture("Сохранено"));
        var process=TelegramBotService.class.getDeclaredMethod("process",TelegramApi.Incoming.class);process.setAccessible(true);
        try(var apis=mockConstruction(TelegramApi.class);var service=new TelegramBotService(config,catalogue,storage,Logger.getAnonymousLogger(),guard,moderation)) {
            var field=TelegramBotService.class.getDeclaredField("guardMenu");field.setAccessible(true);
            var actual=(TelegramGuardMenu)field.get(service);var api=apis.constructed().getFirst();
            var list=actual.callback(41,button(actual.home(41),"Сообщения")).view();
            var dialog=actual.callback(41,button(list,"Bob")).view();
            process.invoke(service,new TelegramApi.Incoming(1,41,0,41,77,null,"cb",button(dialog,"Написать"),true));
            clearInvocations(api);
            process.invoke(service,new TelegramApi.Incoming(2,41,0,41,78,"Ответ",null,null,true));
            verify(api).edit(eq(41L),eq(77),any());
            verify(api,never()).send(eq(41L),eq(0),any());
        }
    }
    @Test void unreadDirectMessagesSendOnePersistentNotification() throws Exception {
        UUID alice=UUID.randomUUID(),bob=UUID.randomUUID();
        guard.link(alice,"Alice",guard.generate(41).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH)).get();
        guard.link(bob,"Bob",guard.generate(42).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH)).get();
        guard.directMessage(bob,"Bob",alice,"Alice","Привет").get();
        guard.directMessage(bob,"Bob",alice,"Alice","Ещё сообщение").get();
        TelegramConfig config=mock(TelegramConfig.class);when(config.pageSize()).thenReturn(10);when(config.offsetFile()).thenReturn(folder.resolve("telegram.offset"));
        var notify=TelegramBotService.class.getDeclaredMethod("notifyDirectMessages");notify.setAccessible(true);
        try(var apis=mockConstruction(TelegramApi.class);var service=new TelegramBotService(config,catalogue,storage,Logger.getAnonymousLogger(),guard)) {
            var api=apis.constructed().getFirst();when(api.send(eq(41L),eq(0),any())).thenReturn(77);
            notify.invoke(service);assertTrue(service.awaitIdle(5000));
            verify(api,never()).createPrivateTopic(anyLong(),anyString());
            verify(api).send(eq(41L),eq(0),argThat(view->view.text().equals("💬 Новых сообщений: 2")&&view.buttons().isEmpty()));
            assertTrue(guard.pendingDirectNotifications(10).get().isEmpty());
            notify.invoke(service);assertTrue(service.awaitIdle(5000));verify(api,times(1)).send(eq(41L),eq(0),any());
            verify(api,never()).delete(41L,77);
        }
    }
    @Test void directNoticeMovesCurrentMenuBelowItWithoutSecondSound() throws Exception {
        UUID alice=UUID.randomUUID(),bob=UUID.randomUUID();
        guard.link(alice,"Alice",guard.generate(41).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH)).get();
        guard.link(bob,"Bob",guard.generate(42).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH)).get();
        guard.directMessage(bob,"Bob",alice,"Alice","Привет").get();
        TelegramConfig config=mock(TelegramConfig.class);when(config.pageSize()).thenReturn(10);when(config.offsetFile()).thenReturn(folder.resolve("telegram.offset"));
        var process=TelegramBotService.class.getDeclaredMethod("process",TelegramApi.Incoming.class);process.setAccessible(true);
        var notify=TelegramBotService.class.getDeclaredMethod("notifyDirectMessages");notify.setAccessible(true);
        try(var apis=mockConstruction(TelegramApi.class);var service=new TelegramBotService(config,catalogue,storage,Logger.getAnonymousLogger(),guard)) {
            var api=apis.constructed().getFirst();
            when(api.send(eq(41L),eq(0),any())).thenReturn(70,77);
            when(api.send(eq(41L),eq(0),any(),eq(true))).thenReturn(78);
            process.invoke(service,new TelegramApi.Incoming(1,41,0,41,1,"/menu",null,null,true));
            notify.invoke(service);assertTrue(service.awaitIdle(5000));
            verify(api).send(eq(41L),eq(0),argThat(view->view.text().contains("торговый бот FLEXITY")),eq(true));
            verify(api).delete(41L,70);
            var ordered=inOrder(api);
            ordered.verify(api).send(eq(41L),eq(0),argThat(view->view.text().equals("💬 Новое сообщение от Bob")));
            ordered.verify(api).send(eq(41L),eq(0),any(),eq(true));
        }
    }
    @Test void notificationButtonOpensDialogInGeneralTopic() throws Exception {
        UUID alice=UUID.randomUUID(),bob=UUID.randomUUID();
        guard.link(alice,"Alice",guard.generate(41).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH)).get();
        guard.link(bob,"Bob",guard.generate(42).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH)).get();
        guard.directMessage(bob,"Bob",alice,"Alice","Привет").get();
        TelegramConfig config=mock(TelegramConfig.class);when(config.pageSize()).thenReturn(10);
        var process=TelegramBotService.class.getDeclaredMethod("process",TelegramApi.Incoming.class);process.setAccessible(true);
        try(var apis=mockConstruction(TelegramApi.class);var service=new TelegramBotService(config,catalogue,storage,Logger.getAnonymousLogger(),guard)) {
            var api=apis.constructed().getFirst();when(api.privateTopicsEnabled()).thenReturn(true);
            process.invoke(service,new TelegramApi.Incoming(1,41,314,41,77,null,"cb","vg:dialog:"+bob,true));
            verify(api).send(eq(41L),eq(0),argThat(view->view.text().contains("Привет")));
            verify(api,never()).edit(eq(41L),eq(77),any());
        }
    }
    @Test void menuCommandFromNotificationTopicStaysInGeneralAndCreatesTopic() throws Exception {
        UUID alice=UUID.randomUUID();
        guard.link(alice,"Alice",guard.generate(41).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH)).get();
        TelegramConfig config=mock(TelegramConfig.class);when(config.pageSize()).thenReturn(10);
        var process=TelegramBotService.class.getDeclaredMethod("process",TelegramApi.Incoming.class);process.setAccessible(true);
        try(var apis=mockConstruction(TelegramApi.class);var service=new TelegramBotService(config,catalogue,storage,Logger.getAnonymousLogger(),guard)) {
            var api=apis.constructed().getFirst();when(api.privateTopicsEnabled()).thenReturn(true);
            when(api.createPrivateTopic(41,"Уведомления")).thenReturn(314);
            process.invoke(service,new TelegramApi.Incoming(1,41,555,41,77,"/menu",null,null,true));
            verify(api).send(eq(41L),eq(0),argThat(view->view.text().contains("торговый бот FLEXITY")));
            assertEquals(314,guard.notificationTopic(41).get());
        }
    }
    @Test void bastionButtonsRequireSeparateCapabilityAndConfirmation() throws Exception {
        guard.configure(new GuardConfig(true,0,500),Set.of(99L));
        var moderation=mock(TelegramModeration.class);UUID player=UUID.randomUUID();
        when(moderation.bastionAvailable()).thenReturn(true);
        var person=new TelegramModeration.Person(player,"Alice",true,"");
        when(moderation.info(anyLong(),eq(player))).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(person));
        when(moderation.act(99,"bastionAdd",player)).thenReturn(java.util.concurrent.CompletableFuture.completedFuture("Добавлен"));
        menu=new TelegramGuardMenu(guard,new TelegramCommands(catalogue,storage,20,v->true),moderation);
        // The capability is off for regular admins while the superadmin sees both actions.
        var actions=TelegramGuardMenu.class.getDeclaredMethod("playerActions",long.class,UUID.class);actions.setAccessible(true);
        var root=(TelegramCommands.View)actions.invoke(menu,500L,player);
        var ordinary=(TelegramCommands.View)actions.invoke(menu,99L,player);
        assertNotNull(button(root,"Добавить в Bastion"));
        assertFalse(ordinary.buttons().stream().anyMatch(b->b.text().contains("Bastion")));
        guard.capability(500,"bastionTrust",true).get();ordinary=(TelegramCommands.View)actions.invoke(menu,99L,player);
        String add=button(ordinary,"Добавить в Bastion");
        assertTrue(menu.callback(42,add).alert());
        var confirm=click(99,ordinary,"Добавить в Bastion");
        verify(moderation,never()).act(anyLong(),anyString(),any());
        String approve=button(confirm,"Подтвердить");menu.callback(99,approve);menu.callback(99,approve);
        verify(moderation,times(1)).act(99,"bastionAdd",player);
        assertNotNull(button(ordinary,"Убрать из Bastion"));
        guard.capability(500,"bastionTrust",false).get();assertThrows(Exception.class,()->menu.callback(99,add));
    }
    @Test void notificationActionsArePrivateRoleCheckedAndTargetTheActorUuid() throws Exception {
        guard.configure(new GuardConfig(true,0,500),Set.of(99L));guard.capability(500,"ban",true).get();guard.capability(500,"freeze",true).get();
        var moderation=mock(TelegramModeration.class);when(moderation.freezeAvailable()).thenReturn(true);
        UUID actor=UUID.randomUUID();when(moderation.info(99,actor)).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(new TelegramModeration.Person(actor,"Thief",true,"")));
        when(moderation.act(99,"ban",actor)).thenReturn(java.util.concurrent.CompletableFuture.completedFuture("Бан на 5 минут"));
        UUID adminAccount=UUID.randomUUID();guard.link(adminAccount,"Moderator",guard.generate(99).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH)).get();
        when(moderation.privateMessage(99,actor,"Верните вещи",TelegramModeration.MessageSender.PLAYER)).thenReturn(java.util.concurrent.CompletableFuture.completedFuture("Отправлено"));
        menu=new TelegramGuardMenu(guard,new TelegramCommands(catalogue,storage,20,v->true),moderation);
        var delivery=new GuardService.Delivery(1,99,"Взяли вещи\nКто изменил: Thief",actor,"Thief");
        var alert=menu.notification(delivery);assertNotNull(button(alert,"Заморозить"));
        assertTrue(menu.callback(42,button(alert,"Бан на 5 минут")).alert());
        var confirm=click(99,alert,"Бан на 5 минут");assertTrue(confirm.text().contains("Thief"));
        String token=button(confirm,"Подтвердить");menu.callback(99,token);menu.callback(99,token);verify(moderation,times(1)).act(99,"ban",actor);
        click(99,alert,"Написать игроку");var preview=menu.input(99,"Верните вещи");verify(moderation,never()).privateMessage(anyLong(),any(),anyString(),any());
        assertTrue(preview.text().contains("Moderator"));click(99,preview,"Отправить");verify(moderation).privateMessage(99,actor,"Верните вещи",TelegramModeration.MessageSender.PLAYER);
        guard.capability(500,"freeze",false).get();assertThrows(Exception.class,()->click(99,alert,"Заморозить"));
        var ordinary=menu.notification(new GuardService.Delivery(2,42,"Взяли вещи\nКто изменил: Thief",actor,"Thief"));assertTrue(ordinary.buttons().isEmpty());assertTrue(ordinary.text().contains("Кто изменил: Thief"));
        var ignored=click(99,alert,"Игнорировать");assertTrue(ignored.text().contains("проигнорировано"));assertFalse(ignored.buttons().stream().anyMatch(b->b.text().contains("Бан")));
        guard.changeAdmin(500,99,false).get();assertThrows(Exception.class,()->click(99,alert,"Написать игроку"));
    }
    @Test void superActionsUseThreeColumnsAndNineItemsPerPage() throws Exception {
        guard.configure(new GuardConfig(true,0,99),Set.of());var moderation=mock(TelegramModeration.class);UUID target=UUID.randomUUID();
        when(moderation.info(99,target)).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(new TelegramModeration.Person(target,"Target",true,"")));
        when(moderation.freezeAvailable()).thenReturn(true);
        menu=new TelegramGuardMenu(guard,new TelegramCommands(catalogue,storage,20,v->true),moderation);
        var method=TelegramGuardMenu.class.getDeclaredMethod("playerActions",long.class,UUID.class);method.setAccessible(true);
        var first=(TelegramCommands.View)method.invoke(menu,99L,target);
        assertEquals(List.of("🎒 Инвентарь","🧰 Эндер-сундук","⏳ Бан 5 мин","🚪 Кик","❄ Заморозка","💬 Сообщение",
                        "🔨 Забанить","🔓 Разбан","💚 Вылечить"),
                first.buttons().stream().limit(9).map(TelegramCommands.Button::text).toList());
        assertEquals(List.of(0,0,0,1,1,1,2,2,2),first.buttons().stream().limit(9).map(TelegramCommands.Button::row).toList());
        var freeze=click(99,first,"Заморозка");assertNotNull(button(freeze,"Заморозить"));assertNotNull(button(freeze,"Разморозить"));
        var second=click(99,first,"Вперёд");assertTrue(second.text().contains("Действия · 2/"));
        assertTrue(second.buttons().stream().filter(b->b.row()<3).count()<=9);
        assertTrue(click(99,second,"Назад").text().contains("Действия · 1/"));
        var last=second;
        while(last.buttons().stream().anyMatch(b->b.text().contains("Вперёд"))) last=click(99,last,"Вперёд");
        var copy=last.buttons().stream().filter(b->b.copyText()!=null).findFirst().orElseThrow();
        assertEquals(target.toString(),copy.copyText());assertFalse(first.buttons().stream().anyMatch(b->b.copyText()!=null));
        assertEquals(copy,last.buttons().stream().filter(b->b.row()<3).reduce((a,b)->b).orElseThrow());
    }
    @Test void superChoosesSenderFromNotificationAndOwnActionsSkipConfirmation() throws Exception {
        guard.configure(new GuardConfig(true,0,99),Set.of());UUID own=UUID.randomUUID(),target=UUID.randomUUID();
        guard.link(own,"SuperNick",guard.generate(99).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH)).get();
        var moderation=mock(TelegramModeration.class);
        when(moderation.info(99,own)).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(new TelegramModeration.Person(own,"SuperNick",true,"")));
        when(moderation.act(99,"ban",own)).thenReturn(java.util.concurrent.CompletableFuture.completedFuture("Бан применён"));
        when(moderation.privateMessage(99,target,"Проверка",TelegramModeration.MessageSender.FLEXITY)).thenReturn(java.util.concurrent.CompletableFuture.completedFuture("Отправлено"));
        menu=new TelegramGuardMenu(guard,new TelegramCommands(catalogue,storage,20,v->true),moderation);
        var alert=menu.notification(new GuardService.Delivery(1,99,"Вещи взяты",target,"Target"));
        click(99,alert,"Написать игроку");var sender=menu.input(99,"Проверка");
        assertNotNull(button(sender,"FLEXITY"));assertNotNull(button(sender,"Мой игровой ник"));
        var preview=click(99,sender,"FLEXITY");assertTrue(preview.text().contains("FLEXITY"));
        click(99,preview,"Отправить");verify(moderation).privateMessage(99,target,"Проверка",TelegramModeration.MessageSender.FLEXITY);
        var superHome=click(99,click(99,menu.home(99),"🛠 Админ"),"Суперадминистратор");assertTrue(superHome.buttons().stream().anyMatch(b->b.text().equals("👤 SuperNick")));
        var ownCard=click(99,superHome,"SuperNick");
        assertTrue(ownCard.text().contains("SuperNick"));var result=click(99,ownCard,"Бан 5 мин");
        assertEquals("Бан применён",result.text());verify(moderation).act(99,"ban",own);
    }
    @Test void compassButtonsUseSelectedChestAndPaginationDoesNotResetTarget() throws Exception {
        UUID owner=UUID.randomUUID(),world=UUID.randomUUID();guard.link(owner,"Alex",guard.generate(42).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH)).get();
        for(int i=0;i<12;i++) catalogue.register(new BlockKey(world,i,65,0),owner,"Alex",List.of(new BlockKey(world,i,64,0)),Map.of("DIAMOND",1L),1,0);
        var moderation=mock(TelegramModeration.class);
        when(moderation.ownPosition(42,owner)).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(new BlockKey(world,0,64,0)));
        when(moderation.worldLabels()).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(Map.of(world,"world")));
        BlockKey chosen=new BlockKey(world,9,64,0);when(moderation.selectOwnCompass(42,owner,chosen)).thenReturn(java.util.concurrent.CompletableFuture.completedFuture("Компас направлен"));
        menu=new TelegramGuardMenu(guard,new TelegramCommands(catalogue,storage,20,v->true),moderation);
        menu.callback(42,"vg:resourceSearch");var page=menu.input(42,"алмаз");assertEquals(6,page.buttons().stream().filter(b->b.text().startsWith("🧭")).count());
        assertTrue(page.text().contains("1/2"));
        assertEquals(List.of(0,0,1,1,2,2),page.buttons().stream().filter(b->b.text().startsWith("🧭")).map(TelegramCommands.Button::row).toList());
        assertEquals(page.buttons().stream().filter(b->b.text().contains("Другой поиск")).findFirst().orElseThrow().row(),
                page.buttons().stream().filter(b->b.text().equals("⌂ Меню")).findFirst().orElseThrow().row());
        assertTrue(page.buttons().stream().anyMatch(b->b.text().equals("🧭 Алмаз ×1")));
        var second=click(42,page,"Вперёд");assertEquals(6,second.buttons().stream().filter(b->b.text().startsWith("🧭")).count());
        String token=second.buttons().stream().filter(b->b.text().equals("🧭 Алмаз ×1")).skip(3).findFirst().orElseThrow().data();
        assertTrue(menu.callback(43,token).alert());
        assertNull(menu.callback(42,token).view());verify(moderation).selectOwnCompass(42,owner,chosen);
        click(42,second,"Назад");verify(moderation,times(1)).pointOwnCompass(eq(42L),eq(owner),anyList());
    }
    @Test void donationCodeWorksWithoutPromptAndHasNoInputDeadline() throws Exception {
        guard.configure(new GuardConfig(true,0,99),Set.of());UUID owner=UUID.randomUUID();
        guard.link(owner,"Alex",guard.generate(42).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH)).get();guard.donationsVisible(99,true).get();
        var direct=menu.input(42,"A".repeat(32));assertNotNull(direct);assertTrue(direct.text().contains("Не удалось зачислить код"));
        var method=TelegramGuardMenu.class.getDeclaredMethod("prompt",long.class,String.class,String.class);method.setAccessible(true);
        var prompt=(TelegramCommands.View)method.invoke(menu,42L,"donationCode","Введите код");assertFalse(prompt.text().contains("10 минут"));
        menu.cancelInput(42);assertNotNull(menu.input(42,"B".repeat(32)));
    }
    @Test void donationsUseTheSameRootMenuAfterSearchCancelAndLinking() throws Exception {
        guard.configure(new GuardConfig(true,0,99),Set.of());
        guard.donationsVisible(99,true).get();
        var main=menu.mainMenu(42);
        var labels=main.buttons().stream().map(TelegramCommands.Button::text).toList();
        assertEquals(List.of("🔎 Поиск","👤 Личный кабинет","🆘 Поддержка","💝 Пожертвования"),labels);
        assertFalse(menu.home(42).buttons().stream().anyMatch(b->b.text().contains("Пожертвования")));
        assertEquals(labels,menu.callback(42,"vg:menu").view().buttons().stream().map(TelegramCommands.Button::text).toList());
        var privateMenu=new TelegramPrivateMenu(catalogue,storage,new TelegramCommands(catalogue,storage,10,id->true),10);
        privateMenu.homeRenderer(menu::mainMenu);
        var search=privateMenu.handle(42,42,0,"/search",false);
        String back=button(search,"Меню");
        assertEquals(labels,privateMenu.callback(42,42,0,back).view().buttons().stream().map(TelegramCommands.Button::text).toList());
        assertEquals(labels,privateMenu.handle(42,42,0,"/cancel",false).buttons().stream().map(TelegramCommands.Button::text).toList());
        var instruction=click(43,menu.mainMenu(43),"Пожертвования");
        String code=instruction.text().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH);
        guard.link(UUID.randomUUID(),"NewPlayer",code).get();
        var donations=click(43,instruction,"Пожертвования");
        assertTrue(donations.text().contains("💰 Баланс:"));
        assertTrue(donations.text().contains("💎 Премиум:"));
        assertNotNull(button(donations,"Пополнить"));
        assertFalse(donations.buttons().stream().anyMatch(b->b.text().contains("Назад")));
        assertEquals(labels,click(43,donations,"Меню").buttons().stream().map(TelegramCommands.Button::text).toList());
    }
    @Test void donationPlayerPickerHasTenRowsOfflineToggleAndSearch() throws Exception {
        guard.configure(new GuardConfig(true,0,99),Set.of());var moderation=mock(TelegramModeration.class);
        var people=new ArrayList<TelegramModeration.Person>();for(int i=0;i<21;i++)people.add(new TelegramModeration.Person(UUID.randomUUID(),"Player"+i,true,""));
        people.add(new TelegramModeration.Person(UUID.randomUUID(),"OfflineAlex",false,""));
        when(moderation.players(99,"online")).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(people.stream().filter(TelegramModeration.Person::online).toList()));
        when(moderation.players(99,"all")).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(people));
        menu=new TelegramGuardMenu(guard,new TelegramCommands(catalogue,storage,20,v->true),moderation);
        var admin=click(99,click(99,click(99,click(99,menu.home(99),"🛠 Админ"),"Суперадминистратор"),"Root"),"Управление пожертвованиями");var picker=click(99,admin,"Добавить пожертвования");
        assertEquals(10,picker.buttons().stream().filter(b->b.text().startsWith("Player")).count());
        assertTrue(click(99,picker,"Вперёд").text().contains("2/3"));var offline=click(99,picker,"офлайн");assertTrue(offline.buttons().stream().anyMatch(b->b.text().equals("OfflineAlex")));
        click(99,offline,"Поиск");assertTrue(menu.input(99,"alex").buttons().stream().anyMatch(b->b.text().equals("OfflineAlex")));
        assertTrue(menu.callback(42,button(admin,"Добавить пожертвования")).alert());
    }
    @Test void superAdminCanSelectPermanentOrCustomPremiumDuration() throws Exception {
        guard.configure(new GuardConfig(true,0,99),Set.of());var moderation=mock(TelegramModeration.class);UUID player=UUID.randomUUID();
        var person=new TelegramModeration.Person(player,"Player",true,"");
        when(moderation.players(99,"online")).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(List.of(person)));
        when(moderation.info(99,player)).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(person));
        menu=new TelegramGuardMenu(guard,new TelegramCommands(catalogue,storage,20,v->true),moderation);
        var admin=click(99,click(99,click(99,click(99,menu.home(99),"🛠 Админ"),"Суперадминистратор"),"Root"),"Управление пожертвованиями");var durations=click(99,admin,"Добавить премиум");
        assertTrue(durations.buttons().stream().anyMatch(b->b.text().equals("Навсегда")));assertTrue(durations.buttons().stream().anyMatch(b->b.text().equals("Свой срок")));
        var permanent=click(99,click(99,durations,"Навсегда"),"Player");assertTrue(permanent.text().contains("бессрочный премиум"));
        var customPlayer=click(99,click(99,durations,"Свой срок"),"Player");assertTrue(customPlayer.text().contains("от 1 до 36500"));
        assertTrue(menu.input(99,"0").text().contains("от 1 до 36500"));var confirm=menu.input(99,"45");assertTrue(confirm.text().contains("на 45 дней"));
    }
    @Test void donationsRequireLinkedAccountAndPersistSuperVisibility() throws Exception {
        guard.configure(new GuardConfig(true,0,99),Set.of());UUID id=UUID.randomUUID();
        guard.link(id,"Alex",guard.generate(42).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH)).get();
        assertFalse(menu.home(42).buttons().stream().anyMatch(b->b.text().contains("Пожертвования")));
        assertThrows(Exception.class,()->guard.donationsVisible(42,true).get());
        var admin=click(99,click(99,click(99,click(99,menu.home(99),"🛠 Админ"),"Суперадминистратор"),"Root"),"Управление пожертвованиями");
        click(99,admin,"Включить");
        assertFalse(menu.home(43).buttons().stream().anyMatch(b->b.text().contains("Пожертвования")));
        var donations=click(42,menu.mainMenu(42),"Пожертвования");
        assertTrue(donations.text().contains("Баланс: временно недоступен"));assertTrue(donations.text().contains("добровольны"));
        assertTrue(menu.callback(43,button(donations,"Премиум")).alert());
        assertTrue(click(42,donations,"Пополнить").text().contains("недоступна"));
        guard.close();guard=new GuardService(folder.resolve("guard"),new GuardConfig(true,0,99),Logger.getAnonymousLogger(),clock::get);
        assertTrue(guard.donationsVisible().get());
        menu=new TelegramGuardMenu(guard,new TelegramCommands(catalogue,storage,20,v->true));
        var fresh=click(42,menu.mainMenu(42),"Пожертвования");guard.donationsVisible(99,false).get();
        assertThrows(Exception.class,()->click(42,fresh,"Премиум"));
    }
    @Test void childMenuIsLinkedOwnerOnlyAndSuperManagementIsProtected() throws Exception {
        guard.configure(new GuardConfig(true,0,99),Set.of());UUID id=UUID.randomUUID();
        guard.link(id,"Alex",guard.generate(42).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH)).get();
        var moderation=new TelegramModeration(mock(org.bukkit.plugin.java.JavaPlugin.class),guard);
        menu=new TelegramGuardMenu(guard,new TelegramCommands(catalogue,storage,20,v->true),moderation);
        assertFalse(menu.home(42).buttons().stream().anyMatch(b->b.text().contains("Размер")));
        var root=click(99,click(99,click(99,menu.home(99),"🛠 Админ"),"Суперадминистратор"),"Root");var kids=click(99,root,"Дети");assertTrue(kids.buttons().stream().anyMatch(b->b.text().contains("Добавить ребёнка")));
        assertTrue(menu.callback(42,button(root,"Дети")).alert());
        guard.child(99,id,"Alex",true).get();var sizes=click(42,menu.home(42),"Размер");assertEquals(3,sizes.buttons().stream().filter(b->b.text().contains("Гном")||b.text().contains("Карлик")||b.text().contains("нормальный")).count());
        assertTrue(menu.callback(43,button(sizes,"Гном")).alert());click(42,sizes,"Гном");assertEquals(0.6,guard.child(id).size());
        guard.child(99,id,"Alex",false).get();assertThrows(Exception.class,()->click(42,sizes,"Карлик"));
    }
    @Test void ownChestSearchPaginatesAndDoesNotExposeOtherOwners() throws Exception {
        UUID owner=UUID.randomUUID(),world=UUID.randomUUID();
        guard.link(owner,"Alex",guard.generate(42).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH)).get();
        for(int i=0;i<21;i++) catalogue.register(new BlockKey(world,i,65,0),owner,"Alex",List.of(new BlockKey(world,i,64,0)),Map.of("DIAMOND_ORE",1L),1,0);
        catalogue.register(new BlockKey(world,999,65,0),UUID.randomUUID(),"Alex",List.of(new BlockKey(world,999,64,0)),Map.of("DIAMOND_ORE",999L),1,0);
        menu.callback(42,"vg:resourceSearch");var page=menu.input(42,"алмазная руда");
        assertTrue(page.text().contains("1/2"));assertFalse(page.text().contains("999"));assertTrue(page.text().contains("офлайн"));
        String next=button(page,"Вперёд");assertTrue(menu.callback(43,next).alert());
        assertTrue(menu.callback(42,next).view().text().contains("2/2"));
    }
    @Test void eventsMenuAddsPagesAndRemovesFriendsByExactNickname() throws Exception {
        UUID owner=UUID.randomUUID(),friendId=UUID.randomUUID();guard.link(owner,"Alex",guard.generate(42).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH)).get();
        var moderation=mock(TelegramModeration.class);when(moderation.playerByName("Trusted"))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(new TelegramModeration.Person(friendId,"Trusted",false,"")));
        menu=new TelegramGuardMenu(guard,new TelegramCommands(catalogue,storage,20,v->true),moderation);
        var events=click(42,click(42,menu.home(42),"Мои ресурсы"),"События");assertTrue(events.buttons().stream().anyMatch(b->b.text().contains("Список событий за 2 дня")));assertTrue(events.text().contains("[v] или [vault]"));
        assertFalse(events.buttons().stream().anyMatch(b->b.text().contains("Друзья")));
        var settings=click(42,menu.home(42),"Настройки");
        settings=click(42,settings,"Уведомления");
        assertTrue(settings.buttons().stream().anyMatch(b->b.text().contains("Уведомления из хранилищ друзей")));
        settings=click(42,settings,"Уведомления из хранилищ друзей");
        assertTrue(settings.buttons().stream().anyMatch(b->b.text().contains("Уведомления из хранилищ друзей: ВЫКЛ")));
        assertTrue(guard.account(42).get().notifications());
        settings=click(42,settings,"Назад");
        var friends=click(42,settings,"Друзья");assertTrue(friends.text().contains("Список пуст"));
        assertTrue(click(42,friends,"Настройки").text().contains("⚙️ Настройки игрока"));
        click(42,friends,"Добавить друга");friends=menu.input(42,"Trusted");assertEquals("Trusted",guard.friends(42).get().getFirst().name());
        var friendCard=click(42,friends,"Trusted");
        assertTrue(friendCard.buttons().stream().anyMatch(b->b.text().contains("Отправлять другу уведомления")));
        click(42,friendCard,"Отправлять другу уведомления");assertTrue(guard.friends(42).get().getFirst().notified());
        for(int i=0;i<20;i++) guard.addFriend(42,UUID.randomUUID(),"Friend"+String.format("%02d",i)).get();
        friends=click(42,settings,"Друзья");assertEquals(10,friends.buttons().stream().filter(b->b.text().startsWith("👤 ")).count());
        friends=click(42,friends,"Вперёд");assertTrue(friends.text().contains("2/3"));
        friends=click(42,friends,"Вперёд");assertTrue(friends.text().contains("3/3"));
        var confirm=click(42,click(42,friends,"Trusted"),"Удалить друга");assertTrue(confirm.text().contains("Удалить Trusted"));
        click(42,confirm,"Да, удалить");assertFalse(guard.friends(42).get().stream().anyMatch(f->f.uuid().equals(friendId)));
    }
    String button(TelegramCommands.View view,String contains) {return view.buttons().stream().filter(b->b.text().contains(contains)).findFirst().orElseThrow().data();}
    TelegramCommands.View click(long user,TelegramCommands.View view,String contains) throws Exception {return menu.callback(user,button(view,contains)).view();}
    @Test void personalSettingsOfferOnlyThreePresetsAndAreOwnerBound() throws Exception {
        guard.link(UUID.randomUUID(),"Alex",guard.generate(42).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH)).get();
        var settings=click(42,menu.home(42),"Настройки");
        settings=click(42,click(42,settings,"Уведомления"),"Личные уведомления");
        assertTrue(settings.text().contains("табличку [v] или [vault]"));String day=button(settings,"2 дня");
        assertTrue(menu.callback(43,day).alert());assertNull(guard.offlineSeconds(42).get());
        settings=menu.callback(42,day).view();assertEquals(172800L,guard.offlineSeconds(42).get());
        assertFalse(settings.buttons().stream().anyMatch(b->b.text().contains("Своё время") || b.text().contains("1 дн.")));
        settings=click(42,settings,"Всегда");assertEquals(-1L,guard.offlineSeconds(42).get());
        settings=click(42,settings,"После выхода");assertEquals(0L,guard.offlineSeconds(42).get());
        assertTrue(settings.buttons().stream().anyMatch(b->b.text().equals("✅ После выхода")));
    }
    @Test void customTimeInputOnlyCapturesPersonalChatAndCancelEndsIt() throws Exception {
        guard.link(UUID.randomUUID(),"Alex",guard.generate(42).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH)).get();
        TelegramConfig config=mock(TelegramConfig.class);when(config.pageSize()).thenReturn(8);
        var process=TelegramBotService.class.getDeclaredMethod("process",TelegramApi.Incoming.class);process.setAccessible(true);
        try(var apis=mockConstruction(TelegramApi.class);
            var service=new TelegramBotService(config,catalogue,storage,Logger.getAnonymousLogger(),guard)) {
            var field=TelegramBotService.class.getDeclaredField("guardMenu");field.setAccessible(true);
            var actual=(TelegramGuardMenu)field.get(service);var api=apis.constructed().getFirst();
            guard.configure(new GuardConfig(true,0,42),Set.of(99L));
            var root=actual.callback(42,button(actual.callback(42,button(actual.home(42),"🛠 Админ")).view(),"Суперадминистратор")).view();
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
        var challenge=menu.callback(42,bind).view();String code=challenge.text().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH);
        UUID uuid=UUID.randomUUID();guard.link(uuid,"Alex",code).get();
        var account=menu.callback(42,"vg:home").view();assertTrue(account.text().contains("Персонаж: Alex"));
        var settings=click(42,account,"Настройки");var alerts=click(42,settings,"Уведомления");var own=click(42,alerts,"Личные уведомления");
        String toggle=button(own,"Личные уведомления: ВКЛ");assertTrue(menu.callback(43,toggle).alert());assertTrue(guard.account(42).get().notifications());
        assertTrue(menu.callback(42,toggle).view().text().contains("Состояние: ВЫКЛ"));
        assertTrue(menu.callback(42,toggle).alert());
    }
    @Test void settingsHaveSeparateApplyAndResetTagButtons() throws Exception {
        guard.link(UUID.randomUUID(),"Alex",guard.generate(42).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH)).get();
        TelegramTagManager tags=mock(TelegramTagManager.class);
        when(tags.apply(42,"Alex")).thenReturn(new TelegramTagManager.Result("applied",true,true));
        when(tags.reset(42)).thenReturn(new TelegramTagManager.Result("reset",true,true));
        menu=new TelegramGuardMenu(guard,new TelegramCommands(catalogue,storage,1,id->true),null,tags);
        var settings=click(42,menu.home(42),"Настройки");var tag=click(42,settings,"Тег");
        var result=click(42,tag,"Применить тег");assertEquals("applied",result.text());verify(tags).apply(42,"Alex");assertTrue(guard.tag(42,false).get());
        tag=click(42,result,"Назад к тегу");result=click(42,tag,"Сбросить тег");assertEquals("reset",result.text());verify(tags).reset(42);assertFalse(guard.tag(42,false).get());
    }
    @Test void resourcesUseUuidWhenNamesCollideAndPagesRemainBoundToRequester() throws Exception {
        UUID owner=UUID.randomUUID(),world=UUID.randomUUID();
        guard.link(owner,"Alex",guard.generate(42).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH)).get();
        catalogue.register(new BlockKey(world,1,1,1),owner,"Alex",List.of(new BlockKey(world,1,1,2)),Map.of("DIAMOND",5L,"IRON_INGOT",2L),1,100);
        catalogue.register(new BlockKey(world,2,1,1),UUID.randomUUID(),"Alex",List.of(new BlockKey(world,2,1,2)),Map.of("DIAMOND",999L),1,100);
        Material material=mock(Material.class);when(material.getMaxStackSize()).thenReturn(64);
        try(var materials=mockStatic(Material.class)) {
            materials.when(()->Material.getMaterial(anyString())).thenReturn(material);
            var result=click(42,menu.home(42),"Мои ресурсы");assertTrue(result.text().contains("5 шт."));assertFalse(result.text().contains("999"));
            assertTrue(result.buttons().stream().anyMatch(b->b.data().startsWith("vt:")));
        }
    }
    @Test void personalTopShowsBestRanksInPagesAndReturnsToResources() throws Exception {
        UUID owner=UUID.randomUUID(),world=UUID.randomUUID();
        guard.link(owner,"Alex",guard.generate(42).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH)).get();
        Map<String,Long> items=new HashMap<>();for(int i=0;i<12;i++) items.put("ITEM_"+i,1L);
        catalogue.register(new BlockKey(world,1,64,1),owner,"Alex",List.of(new BlockKey(world,1,64,2)),items,1,100);
        var resources=click(42,menu.home(42),"Мои ресурсы");
        var first=click(42,resources,"Личный топ");
        assertTrue(first.text().contains("1/2"));assertTrue(first.text().contains("#1 ITEM_"));
        assertEquals(10,first.text().lines().filter(line->line.startsWith("#")).count());
        assertTrue(menu.callback(43,button(first,"Вперёд")).alert());
        var second=click(42,first,"Вперёд");
        assertTrue(second.text().contains("2/2"));assertEquals(2,second.text().lines().filter(line->line.startsWith("#")).count());
        var search=click(42,second,"🔎 Поиск");
        assertTrue(search.text().contains("Введите название предмета"));
        var filtered=menu.input(42,"item_11");
        assertTrue(filtered.text().contains("Поиск: «item_11»"));
        assertEquals(1,filtered.text().lines().filter(line->line.startsWith("#")).count());
        assertTrue(filtered.text().contains("ITEM_11"));
        assertTrue(click(42,filtered,"Сбросить поиск").text().contains("1/2"));
        assertTrue(menu.input(42,"неиспользуемый текст")==null);
        search=click(42,filtered,"🔎 Поиск");
        var broad=menu.input(42,"item");
        assertTrue(broad.text().contains("1/2"));
        assertTrue(click(42,broad,"Вперёд").text().contains("Поиск: «item»"));
        search=click(42,filtered,"🔎 Поиск");
        assertTrue(menu.input(42,"нет такого предмета").text().contains("По этому запросу достижений нет"));
        assertTrue(click(42,second,"↩️ Назад").buttons().stream().anyMatch(button->button.text().contains("Личный топ")));
        catalogue.remove(new BlockKey(world,1,64,1),catalogue.get(new BlockKey(world,1,64,1)).generation(),2);
        assertTrue(click(42,resources,"Личный топ").text().contains("Пока нет мест"));
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
            notify.invoke(service);assertTrue(service.awaitIdle(5000));verify(api).send(eq(99L),eq(0),any());assertTrue(guard.deliveries().get().isEmpty());
            clock.addAndGet(300_000);when(api.send(eq(99L),eq(0),any())).thenReturn(123);
            notify.invoke(service);assertTrue(service.awaitIdle(5000));verify(api,times(2)).send(eq(99L),eq(0),any());assertTrue(guard.deliveries().get().isEmpty());
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

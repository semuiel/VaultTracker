package ru.vaulttracker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramPrivateMenuTest {
    private final Catalogue catalogue=new Catalogue(v->{});
    private final StorageEngine storage=mock(StorageEngine.class);
    private final AtomicLong clock=new AtomicLong(1000);
    private final Predicate<String> valid=Set.of("DIAMOND","IRON_INGOT","STONE","DIAMOND_ORE",
            "DEEPSLATE_DIAMOND_ORE","EMERALD_ORE","DEEPSLATE_EMERALD_ORE")::contains;
    private final TelegramCommands commands=new TelegramCommands(catalogue,storage,2,valid);
    private final TelegramPrivateMenu menu=new TelegramPrivateMenu(catalogue,storage,commands,2,valid,clock::get);
    private int nextBlock=1;

    @BeforeEach void prepare() {
        when(storage.ready()).thenReturn(true);
        register("Alex",Map.of("DIAMOND",12L,"IRON_INGOT",8L,"STONE",1L,"DIAMOND_ORE",3L,"DEEPSLATE_DIAMOND_ORE",4L));
        register("Bob",Map.of("DIAMOND",7L,"EMERALD_ORE",2L,"DEEPSLATE_EMERALD_ORE",3L));
        register("Stone",Map.of("DIAMOND",2L));
    }
    private void register(String name,Map<String,Long> items) {
        UUID world=UUID.randomUUID(); int n=nextBlock++;
        catalogue.register(new BlockKey(world,n,1,1),UUID.randomUUID(),name,List.of(new BlockKey(world,n,1,2)),items,1,100);
    }
    private TelegramCommands.View say(String text) { return menu.handle(42,42,0,text,false); }
    private static String data(TelegramCommands.View view,String label) {
        return view.buttons().stream().filter(b->b.text().equals(label)).findFirst().orElseThrow().data();
    }
    private TelegramCommands.View click(TelegramCommands.View view,String label) {
        for(var button:view.buttons()) assertTrue(button.data().getBytes(StandardCharsets.UTF_8).length<=64);
        var result=menu.callback(42,42,0,data(view,label));
        assertFalse(result.alert(),result.notice()); assertNotNull(result.view());
        return result.view();
    }
    private TelegramCommands.View open(String label) {
        return click(click(say("/start"),"🔎 Поиск"),label);
    }

    @Test void browsesPlayersAndTheirResourcePagesWithReturnToList() {
        var list=open("👤 Игрок");
        assertTrue(list.text().contains("1/2"));
        assertTrue(list.buttons().stream().anyMatch(b->b.text().equals("👤 Alex")));
        var second=click(list,"Вперёд ▶");
        assertTrue(second.text().contains("2/2"));
        var stone=click(second,"👤 Stone");
        assertTrue(stone.text().contains("Ресурсы Stone"));
        assertTrue(stone.text().contains("Алмаз — 2 шт."));
        second=click(stone,"◀ К списку");
        assertTrue(second.text().contains("2/2"));
        var alex=click(click(second,"◀ Назад"),"👤 Alex");
        assertTrue(alex.text().contains("1/3"));
        var resourcePage=click(alex,"Вперёд ▶");
        assertTrue(resourcePage.text().contains("Ресурсы Alex • 2/3"));
        assertTrue(click(resourcePage,"◀ К списку").text().contains("Игроки каталога"));
    }

    @Test void acceptsNicknameOnlyDuringPlayerSearchIncludingMaterialName() {
        assertNull(say("Alex"));
        open("👤 Игрок");
        assertTrue(say("несуществующий").text().contains("Игрок не найден"));
        var result=say("stone");
        assertTrue(result.text().contains("Ресурсы Stone"));
        assertNull(say("Alex"));
        click(result,"◀ К списку");
        assertTrue(say("Alex").text().contains("Ресурсы Alex"));
        open("👤 Игрок");
        say("/cancel");
        assertNull(say("Alex"));
    }

    @Test void selectsItemAndPagesTopOrTypesRussianItemAndOreGroup() {
        var list=open("📦 Предмет");
        var ores=click(list,"Алмазная руда (обычная + глубинная) (AR)");
        assertTrue(ores.text().contains("Alex — 7 шт."));
        list=click(ores,"◀ К списку");
        int pages=0;
        while(list.buttons().stream().noneMatch(b->b.text().equals("Алмаз"))) {
            assertTrue(pages++<10); list=click(list,"Вперёд ▶");
        }
        var top=click(list,"Алмаз");
        assertTrue(top.text().contains("Alex — 12 шт."));
        assertTrue(top.text().contains("Bob — 7 шт."));
        assertTrue(click(top,"Вперёд ▶").text().contains("Stone — 2 шт."));
        open("📦 Предмет");
        assertTrue(say("железный слиток").text().contains("Alex — 8 шт."));
        open("📦 Предмет");
        assertTrue(say("ИР").text().contains("Bob — 5 шт."));
        open("📦 Предмет");
        assertTrue(say("Alex").text().contains("Предмет не найден"));
        assertTrue(say("diamond").text().contains("топ владельцев"));
    }

    @Test void buttonsCannotBeUsedByAnotherUserChatTopicOrOldScreen() {
        var home=say("/start"); String button=data(home,"🔎 Поиск");
        assertTrue(menu.callback(99,42,0,button).alert());
        assertTrue(menu.callback(42,99,0,button).alert());
        assertTrue(menu.callback(42,42,10,button).alert());
        assertTrue(menu.callback(42,42,0,"vm:unknown:0").alert());
        assertTrue(menu.callback(42,42,0,button.substring(0,button.lastIndexOf(':')+1)+"-1").alert());
        click(home,"🔎 Поиск");
        assertTrue(menu.callback(42,42,0,button).alert());
    }

    @Test void expirationAndRestartStopTextInputAndInvalidateButtons() {
        var list=open("👤 Игрок");
        clock.addAndGet(30*60*1000L);
        assertNull(say("Alex"));
        assertTrue(menu.callback(42,42,0,data(list,"👤 Alex")).alert());
        list=open("👤 Игрок");
        var restarted=new TelegramPrivateMenu(catalogue,storage,commands,2,valid,clock::get);
        assertNull(restarted.handle(42,42,0,"Alex",false));
        assertTrue(restarted.callback(42,42,0,data(list,"👤 Alex")).alert());
    }

    @Test void emptyLoadingAndChangedCataloguesRemainNavigable() {
        when(storage.ready()).thenReturn(false);
        var search=click(say("/menu"),"🔎 Поиск");
        assertTrue(menu.callback(42,42,0,data(search,"👤 Игрок")).alert());
        when(storage.ready()).thenReturn(true);
        var list=click(search,"👤 Игрок");
        register("Aaron",Map.of("STONE",1L));
        assertTrue(click(list,"👤 Alex").text().contains("Ресурсы Alex"));
        var empty=new Catalogue(v->{});
        var emptyCommands=new TelegramCommands(empty,storage,2,valid);
        var emptyMenu=new TelegramPrivateMenu(empty,storage,emptyCommands,2,valid,clock::get);
        var emptySearch=emptyMenu.handle(42,42,0,"/search",false);
        var emptyPlayers=emptyMenu.callback(42,42,0,data(emptySearch,"👤 Игрок")).view();
        assertTrue(emptyPlayers.text().contains("пока нет"));
        assertTrue(emptyPlayers.buttons().stream().anyMatch(b->b.text().equals("⌂ Меню")));
    }

    @Test void directCommandsRetainPermissionsAndCancelPendingInput() {
        open("👤 Игрок");
        assertTrue(say("/items").text().contains("только администратору"));
        assertNull(say("Alex"));
        when(storage.status()).thenReturn("Работает");
        assertTrue(menu.handle(42,42,0,"/status",true).text().contains("Работает"));
        assertTrue(say("/item Alex diamond").text().contains("Alex — Алмаз: 12 шт."));
    }
}

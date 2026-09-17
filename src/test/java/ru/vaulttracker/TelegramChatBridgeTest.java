package ru.vaulttracker;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.event.player.*;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.threadedregions.scheduler.*;
import io.papermc.paper.advancement.AdvancementDisplay;
import net.kyori.adventure.text.Component;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import java.util.logging.Logger;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class TelegramChatBridgeTest {
    @TempDir Path folder;
    JavaPlugin plugin=mock(JavaPlugin.class);Server server=mock(Server.class);
    Player player=mock(Player.class);World world=mock(World.class);ConsoleCommandSender console=mock(ConsoleCommandSender.class);
    TelegramChatConfig config;TelegramConfig catalogue;
    @BeforeEach void setup() throws Exception {
        config=TelegramChatConfig.parse(TelegramChatConfigTest.SETTINGS+"\nmessages:\n  mergeWindowSeconds: 0\n  requirePrefixInMinecraft: '!'\n");
        catalogue=new TelegramConfig(true,config.token,Set.of(),Set.of(),List.of(),20,5,300,"https://api.telegram.org",new TelegramConfig.Proxy(TelegramConfig.ProxyType.NONE,"",0,"",""),new TelegramConfig.Retry(0,1,10),folder.resolve("telegram.offset"));
        when(plugin.getDataFolder()).thenReturn(folder.toFile());when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));when(server.getConsoleSender()).thenReturn(console);doReturn(List.of(player)).when(server).getOnlinePlayers();
        var global=mock(GlobalRegionScheduler.class);when(server.getGlobalRegionScheduler()).thenReturn(global);
        doAnswer(c->{c.getArgument(1,Consumer.class).accept(mock(ScheduledTask.class));return mock(ScheduledTask.class);}).when(global).run(eq(plugin),any());
        var entity=mock(EntityScheduler.class);when(player.getScheduler()).thenReturn(entity);
        doAnswer(c->{c.getArgument(1,Consumer.class).accept(mock(ScheduledTask.class));return mock(ScheduledTask.class);}).when(entity).run(eq(plugin),any(),any());
        when(player.isOnline()).thenReturn(true);when(player.getPing()).thenReturn(42);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.getName()).thenReturn("Alex");when(player.displayName()).thenReturn(Component.text("DisplayAlex"));when(player.getWorld()).thenReturn(world);when(world.getEnvironment()).thenReturn(World.Environment.NETHER);when(world.getName()).thenReturn("world_nether");
    }
    TelegramApi.Incoming message(long chat,int topic,String text) {return new TelegramApi.Incoming(1,chat,topic,42,1,text,null,null,false,"Sender");}
    @Test void telegramMarkerCanBeDisabledWithLegacyTemplateAndTextIsWhite() throws Exception {
        var settings=TelegramChatConfig.parse(TelegramChatConfigTest.SETTINGS+"\nmessages:\n  showTelegramTag: false\nformats:\n  telegramChat: '<aqua>[TG] {sender}</aqua> {text}'\n");
        try(var apis=mockConstruction(TelegramApi.class);var bridge=new TelegramChatBridge(plugin,settings,catalogue)) {
            var message=bridge.incomingMessage(42,"Gaben","<red>text");
            assertEquals("Gaben: <red>text",TelegramChatBridge.plain(message));
            assertTrue(whiteText(message,"<red>text"));
        }
    }
    private boolean whiteText(Component component,String text) {
        return whiteText(component,text,null);
    }
    private boolean whiteText(Component component,String text,net.kyori.adventure.text.format.TextColor inherited) {
        var color=component.color()==null?inherited:component.color();
        if(component instanceof net.kyori.adventure.text.TextComponent value && value.content().contains(text)) return net.kyori.adventure.text.format.NamedTextColor.WHITE.equals(color);
        return component.children().stream().anyMatch(child->whiteText(child,text,color));
    }
    @Test void linkedAndUnlinkedMarkersAreIndependent() throws Exception {
        GuardService guard=mock(GuardService.class);
        when(guard.account(42)).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(new GuardService.Account(UUID.randomUUID(),"Alex",true)));
        when(guard.account(43)).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        for(boolean linked:List.of(false,true)) {
            var settings=TelegramChatConfig.parse(TelegramChatConfigTest.SETTINGS+"\nmessages:\n  showTelegramTagLinked: "+linked+"\n  showTelegramTagUnlinked: "+(!linked)+"\n");
            try(var apis=mockConstruction(TelegramApi.class);var bridge=new TelegramChatBridge(plugin,settings,catalogue)) {
                bridge.linkedAccounts(guard);
                assertEquals(linked,TelegramChatBridge.plain(bridge.incomingMessage(42,"Sender","hello")).contains("[TG]"));
                assertEquals(!linked,TelegramChatBridge.plain(bridge.incomingMessage(43,"Sender","hello")).contains("[TG]"));
            }
        }
    }
    @Test void linkedIdentityIsResolvedByTelegramIdAndUnlinkedSenderKeepsExistingFormat() throws Exception {
        GuardService guard=mock(GuardService.class);
        when(guard.account(42)).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(new GuardService.Account(UUID.randomUUID(),"Alex",true)));
        when(guard.account(43)).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        try(var apis=mockConstruction(TelegramApi.class);var bridge=new TelegramChatBridge(plugin,config,catalogue)) {
            bridge.linkedAccounts(guard);
            assertEquals("[TG] Alex: hello",TelegramChatBridge.plain(bridge.incomingMessage(42,"FakeAdmin","hello")));
            assertTrue(TelegramChatBridge.plain(bridge.incomingMessage(43,"Sender","hello")).contains("Sender"));
            verify(guard).account(42);verify(guard).account(43);
        }
    }
    @Test void forwardsOnlyConfiguredTopicAndTreatsSenderContentAsLiteral() throws Exception {
        try(var apis=mockConstruction(TelegramApi.class);var bridge=new TelegramChatBridge(plugin,config,catalogue)) {
            bridge.username("ChatBot");bridge.start(true);
            assertFalse(bridge.consume(message(-100123,123,"other topic")));assertFalse(bridge.consume(message(-100999,486,"other group")));assertFalse(bridge.consume(message(-100123,486,"/list@OtherBot")));
            assertFalse(bridge.consume(message(-100123,486,"/op Alex")));
            assertTrue(bridge.consume(message(-100123,486,"<click:run_command:'/op Alex'>hello</click>")));
            var capture=org.mockito.ArgumentCaptor.forClass(Component.class);verify(player).sendMessage(capture.capture());
            assertTrue(TelegramChatBridge.plain(capture.getValue()).contains("<click:run_command:"));assertNull(capture.getValue().clickEvent());
            verify(server,never()).dispatchCommand(any(),anyString());
            verify(apis.constructed().getFirst(),never()).updates(anyLong());
        }
    }
    @Test void listReadsCurrentDimensionWithoutRelyingOnWorldEventsAndHonoursBotSuffix() throws Exception {
        try(var apis=mockConstruction(TelegramApi.class);var bridge=new TelegramChatBridge(plugin,config,catalogue)) {
            bridge.username("ChatBot");bridge.start(true);var api=apis.constructed().getFirst();
            assertTrue(bridge.consume(message(-100123,486,"/list@ChatBot")));
            verify(api,timeout(1500)).sendHtml(eq(-100123L),eq(486),contains("🔥 Alex"),eq(false),eq(List.of()));
            when(world.getEnvironment()).thenReturn(World.Environment.THE_END);
            bridge.consume(message(-100123,486,"/list"));verify(api,timeout(1500)).sendHtml(eq(-100123L),eq(486),contains("🌌 Alex"),eq(false),eq(List.of()));
            when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
            bridge.consume(message(-100123,486,"/list"));verify(api,timeout(1500)).sendHtml(eq(-100123L),eq(486),contains("🌍 Alex"),eq(false),eq(List.of()));
            doReturn(List.of()).when(server).getOnlinePlayers();bridge.consume(message(-100123,486,"/list"));
            verify(api,timeout(1500)).sendHtml(eq(-100123L),eq(486),contains("Никакой конкуренции"),eq(false),eq(List.of()));
        }
    }
    @Test void routesChatAndRussianAdvancementsToDifferentTopics() throws Exception {
        try(var apis=mockConstruction(TelegramApi.class);var bridge=new TelegramChatBridge(plugin,config,catalogue)) {
            bridge.start(true);var api=apis.constructed().getFirst();var chat=mock(AsyncChatEvent.class);when(chat.getPlayer()).thenReturn(player);
            when(chat.message()).thenReturn(Component.text("private"));bridge.chat(chat);verify(api,never()).sendHtml(anyLong(),anyInt(),anyString(),anyBoolean());
            when(chat.message()).thenReturn(Component.text("!Hello <b>"));bridge.chat(chat);verify(api,timeout(1500)).sendHtml(eq(-100123L),eq(486),eq("<b>[DisplayAlex]</b> Hello &lt;b&gt;"),eq(false));
            var event=mock(PlayerAdvancementDoneEvent.class);var advancement=mock(org.bukkit.advancement.Advancement.class);var display=mock(AdvancementDisplay.class);
            when(event.getPlayer()).thenReturn(player);when(event.getAdvancement()).thenReturn(advancement);when(advancement.getDisplay()).thenReturn(display);when(display.doesAnnounceToChat()).thenReturn(true);when(display.frame()).thenReturn(AdvancementDisplay.Frame.CHALLENGE);when(display.title()).thenReturn(Component.translatable("advancements.story.cure_zombie_villager.title"));
            bridge.advancement(event);verify(api,timeout(1500)).sendHtml(eq(-100123L),eq(123),contains("Доктор для зомби"),eq(false));
        }
    }
    @Test void sharedTokenUsesOnlyCataloguePollingAndStillRoutesList() throws Exception {
        try(var apis=mockConstruction(TelegramApi.class);var bridge=new TelegramChatBridge(plugin,config,catalogue);var bot=new TelegramBotService(catalogue,new Catalogue(v->{}),mock(StorageEngine.class),Logger.getAnonymousLogger())) {
            var chatApi=apis.constructed().get(0);var botApi=apis.constructed().get(1);bridge.start(true);bot.chatBridge(bridge);
            when(botApi.verify()).thenReturn("ChatBot");AtomicInteger rounds=new AtomicInteger();
            when(botApi.updates(anyLong())).thenAnswer(c->{if(rounds.incrementAndGet()==1) return List.of(message(-100123,486,"/list@ChatBot"));var field=TelegramBotService.class.getDeclaredField("stopping");field.setAccessible(true);((AtomicBoolean)field.get(bot)).set(true);return List.of();});
            var run=TelegramBotService.class.getDeclaredMethod("run");run.setAccessible(true);run.invoke(bot);
            verify(botApi).registerChatCommands(true);verify(chatApi,never()).updates(anyLong());verify(chatApi,timeout(1500)).sendHtml(eq(-100123L),eq(486),contains("Alex"),eq(false),eq(List.of()));
        }
    }

    @Test void listIsAvailableInAdvancementTopicAndUsesOnePagedMessage() throws Exception {
        try(var apis=mockConstruction(TelegramApi.class);var bridge=new TelegramChatBridge(plugin,config,catalogue)) {
            bridge.username("ChatBot");bridge.start(true);var api=apis.constructed().getFirst();when(api.sendHtml(anyLong(),anyInt(),anyString(),anyBoolean(),anyList())).thenReturn(77);
            var online=new ArrayList<Player>();
            var scheduler=player.getScheduler();
            for(int i=0;i<21;i++) {
                var p=mock(Player.class);when(p.isOnline()).thenReturn(true);when(p.getName()).thenReturn("Player"+String.format("%02d",i));when(p.getWorld()).thenReturn(world);when(p.displayName()).thenReturn(Component.text("Player"+i));when(p.getScheduler()).thenReturn(scheduler);online.add(p);
            }
            doReturn(online).when(server).getOnlinePlayers();
            assertTrue(bridge.consume(message(-100123,123,"/list")));
            @SuppressWarnings("unchecked") var buttons=org.mockito.ArgumentCaptor.forClass((Class<List<TelegramCommands.Button>>)(Class<?>)List.class);
            verify(api,timeout(1500)).sendHtml(eq(-100123L),eq(123),contains("Player"),eq(false),buttons.capture());
            assertEquals("1/2",buttons.getValue().getFirst().text());
            String next=buttons.getValue().getLast().data();
            when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
            var callback=new TelegramApi.Incoming(2,-100123,123,42,77,null,"callback",next,false,"Sender");
            assertTrue(bridge.consume(callback));
            verify(api,timeout(1500)).editHtml(eq(-100123L),eq(77),contains("🌍 Player20"),argThat(value->value.stream().anyMatch(button->button.text().equals("2/2"))));
            verify(api,timeout(1500)).answerCallback("callback","",false);
            var expiryField=TelegramChatBridge.class.getDeclaredField("expiry");expiryField.setAccessible(true);
            var expiry=(TelegramListExpiry)expiryField.get(bridge);
            expiry.deleteDue(System.currentTimeMillis()+299000);verify(api,never()).delete(anyLong(),anyInt());
            expiry.deleteDue(System.currentTimeMillis()+301000);verify(api).delete(-100123L,77);
        }
    }

    @Test void listReadsCurrentPingAndSkipsPlayersWhoDisconnectDuringSnapshot() throws Exception {
        var settings=TelegramChatConfig.parse(TelegramChatConfigTest.SETTINGS+"\nlist:\n  showPing: true\n");
        try(var apis=mockConstruction(TelegramApi.class);var bridge=new TelegramChatBridge(plugin,settings,catalogue)) {
            bridge.start(true);var api=apis.constructed().getFirst();
            bridge.consume(message(-100123,486,"/list"));
            verify(api,timeout(1500)).sendHtml(eq(-100123L),eq(486),contains("🔥 Alex · 42 мс"),eq(false),eq(List.of()));
            when(player.getPing()).thenReturn(123);bridge.consume(message(-100123,486,"/list"));
            verify(api,timeout(1500)).sendHtml(eq(-100123L),eq(486),contains("🔥 Alex · 123 мс"),eq(false),eq(List.of()));
            when(player.isOnline()).thenReturn(false);bridge.consume(message(-100123,486,"/list"));
            verify(api,timeout(1500)).sendHtml(eq(-100123L),eq(486),contains("Никакой конкуренции"),eq(false),eq(List.of()));
        }
    }

    @Test void retiredPlayerCannotHoldUpList() throws Exception {
        var scheduler=player.getScheduler();
        doAnswer(c->{((Runnable)c.getArgument(2)).run();return null;}).when(scheduler).run(eq(plugin),any(),any());
        try(var apis=mockConstruction(TelegramApi.class);var bridge=new TelegramChatBridge(plugin,config,catalogue)) {
            bridge.start(true);bridge.consume(message(-100123,486,"/list"));
            verify(apis.constructed().getFirst(),timeout(1500)).sendHtml(eq(-100123L),eq(486),contains("Никакой конкуренции"),eq(false),eq(List.of()));
        }
    }
}

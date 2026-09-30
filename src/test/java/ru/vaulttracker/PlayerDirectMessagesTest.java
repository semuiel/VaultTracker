package ru.vaulttracker;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import io.papermc.paper.threadedregions.scheduler.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerDirectMessagesTest {
    @TempDir Path folder;
    @Test void privateMessageMatchesFlexityColorsAndPrefillsReply() {
        Component message=PlayerDirectMessages.message("Semuiel","Привет",false);
        var tag=message.children();
        assertEquals(NamedTextColor.DARK_GRAY,tag.get(0).color());
        assertEquals(NamedTextColor.AQUA,tag.get(1).color());
        assertEquals(NamedTextColor.DARK_GRAY,tag.get(2).color());
        assertTrue(TelegramChatBridge.plain(message).startsWith("[TG] [✉] Вы ⬅ Semuiel: "));
        var parts=tag.get(3).children();
        assertEquals(NamedTextColor.GRAY,tag.get(3).color());
        assertEquals(NamedTextColor.BLUE,parts.get(0).color());
        assertEquals(NamedTextColor.GRAY,parts.get(1).color());
        assertEquals(NamedTextColor.BLUE,parts.get(2).color());
        assertEquals(" ⬅ ",TelegramChatBridge.plain(parts.get(2)));
        assertEquals("/tg Semuiel ",parts.get(3).clickEvent().value());
        assertEquals(ClickEvent.Action.SUGGEST_COMMAND,parts.get(3).clickEvent().action());
        assertEquals(NamedTextColor.WHITE,parts.get(5).color());
        assertEquals(" ⮕ ",TelegramChatBridge.plain(PlayerDirectMessages.message("Semuiel","Привет",true).children().get(3).children().get(2)));
    }
    @Test void linkedNamesForTabIncludeOfflinePlayersAndUpdatedNick() throws Exception {
        UUID alice=UUID.randomUUID();
        try(var guard=new GuardService(folder.resolve("guard-names"),new GuardConfig(true,0),Logger.getAnonymousLogger())) {
            guard.link(alice,"Alice",code(guard,41)).get();
            assertTrue(guard.linkedNames().contains("Alice"));
            guard.presence(alice,"AliceNew",true);
            guard.linkedPlayer("AliceNew").get(); // Wait for the disk worker to update the cache.
            assertTrue(guard.linkedNames().contains("AliceNew"));
        }
    }
    @Test void offlineMessageStaysUnreadAndOnlineMessageArrivesInGame() throws Exception {
        UUID alice=UUID.randomUUID(),bob=UUID.randomUUID();
        try(var guard=new GuardService(folder.resolve("guard"),new GuardConfig(true,0),Logger.getAnonymousLogger())) {
            guard.link(alice,"Alice",code(guard,41)).get();guard.link(bob,"Bob",code(guard,42)).get();
            JavaPlugin plugin=mock(JavaPlugin.class);Server server=mock(Server.class);when(plugin.getServer()).thenReturn(server);
            var global=mock(GlobalRegionScheduler.class);when(server.getGlobalRegionScheduler()).thenReturn(global);
            doAnswer(call->{call.getArgument(1,Consumer.class).accept(mock(ScheduledTask.class));return mock(ScheduledTask.class);}).when(global).run(eq(plugin),any());
            var direct=new PlayerDirectMessages(plugin,guard);
            assertTrue(direct.fromGame(alice,"Alice","bob","Привет").get().contains("сохранено"));
            assertEquals(1,guard.dialogs(42,0,10).get().getFirst().unread());
            assertTrue(direct.fromTelegram(42,alice,"Ответ из Telegram").get().contains("сохранено"));
            var history=guard.conversation(41,bob,0,10).get().messages();
            assertEquals(2,history.size());assertEquals("Ответ из Telegram",history.getLast().text());
            Player recipient=mock(Player.class);when(server.getPlayer(bob)).thenReturn(recipient);when(recipient.isOnline()).thenReturn(true);
            var entity=mock(EntityScheduler.class);when(recipient.getScheduler()).thenReturn(entity);
            doAnswer(call->{call.getArgument(1,Consumer.class).accept(mock(ScheduledTask.class));return mock(ScheduledTask.class);}).when(entity).run(eq(plugin),any(),any());
            assertTrue(direct.fromGame(alice,"Alice","Bob","Сейчас").get().contains("доставлено"));
            var sent=org.mockito.ArgumentCaptor.forClass(Component.class);verify(recipient).sendMessage(sent.capture());
            assertTrue(TelegramChatBridge.plain(sent.getValue()).contains("[TG] [✉] Вы ⬅ Alice: Сейчас"));
            assertEquals(1,guard.dialogs(42,0,10).get().getFirst().unread());
        }
    }
    private String code(GuardService guard,long user) throws Exception {
        return guard.generate(user).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH);
    }
}

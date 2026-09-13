package ru.vaulttracker;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.*;
import org.bukkit.ban.ProfileBanList;
import org.bukkit.entity.Player;
import org.bukkit.plugin.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.ConsoleCommandSender;
import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.threadedregions.scheduler.*;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramModerationTest {
    @TempDir Path path;
    GuardService guard;
    JavaPlugin plugin=mock(JavaPlugin.class);
    Server server=mock(Server.class);
    ProfileBanList bans=mock(ProfileBanList.class);
    OfflinePlayer player=mock(OfflinePlayer.class);
    UUID uuid=UUID.randomUUID();
    PlayerProfile profile=mock(PlayerProfile.class);
    TelegramModeration moderation;
    @BeforeEach void setup() throws Exception {
        guard=new GuardService(path.resolve("guard"),new GuardConfig(true,120000,500),Logger.getAnonymousLogger());
        guard.configure(new GuardConfig(true,120000,500),Set.of(99L));
        when(plugin.getServer()).thenReturn(server);when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        var scheduler=mock(GlobalRegionScheduler.class);when(server.getGlobalRegionScheduler()).thenReturn(scheduler);
        doAnswer(call-> {call.getArgument(1,Consumer.class).accept(mock(ScheduledTask.class));return mock(ScheduledTask.class);}).when(scheduler).run(eq(plugin),any());
        doReturn(bans).when(server).getBanList(BanList.Type.PROFILE);
        when(server.getOfflinePlayer(uuid)).thenReturn(player);when(player.getUniqueId()).thenReturn(uuid);when(player.getPlayerProfile()).thenReturn(profile);
        moderation=new TelegramModeration(plugin,guard);
    }
    @AfterEach void close() {guard.close();}
    @Test void temporaryBanUsesFiveMinuteNativeProfileBanAndDoesNotShortenExistingBan() throws Exception {
        assertTrue(moderation.act(99,"ban",uuid).get().contains("5 минут"));
        verify(bans).addBan(eq(profile),eq("Пока идёт расследование"),eq(java.time.Duration.ofMinutes(5)),eq("Telegram 99"));
        when(bans.isBanned(profile)).thenReturn(true);moderation.act(99,"ban",uuid).get();
        verify(bans,times(1)).addBan(eq(profile),anyString(),any(java.time.Duration.class),anyString());
    }
    @Test void onlySuperAdminCanUnbanOrSendLuckPermsRemoval() throws Exception {
        assertThrows(ExecutionException.class,()->moderation.act(99,"unban",uuid).get());verify(bans,never()).pardon(profile);
        moderation.act(500,"unban",uuid).get();verify(bans).pardon(profile);
        var manager=mock(PluginManager.class);when(server.getPluginManager()).thenReturn(manager);when(manager.getPlugin("LuckPerms")).thenReturn(mock(Plugin.class));
        var console=mock(ConsoleCommandSender.class);when(server.getConsoleSender()).thenReturn(console);
        when(server.dispatchCommand(console,"lp user "+uuid+" parent remove admin")).thenReturn(true);
        assertThrows(ExecutionException.class,()->moderation.act(99,"removeLp",uuid).get());
        moderation.act(500,"removeLp",uuid).get();verify(server,times(1)).dispatchCommand(console,"lp user "+uuid+" parent remove admin");
    }
    @Test void chatIsPlainTextAndCannotExecuteCommands() throws Exception {
        var console=mock(ConsoleCommandSender.class);when(server.getConsoleSender()).thenReturn(console);when(server.getOnlinePlayers()).thenReturn(List.of());
        assertThrows(ExecutionException.class,()->moderation.broadcast(99,"hello").get());
        moderation.broadcast(500,"/op Player42").get();verify(console).sendMessage(Component.text("[Консоль] /op Player42"));
        verify(server,never()).dispatchCommand(any(),anyString());
    }
    @Test void kickRechecksPermissionOnPlayerScheduler() throws Exception {
        var online=mock(Player.class);when(player.getPlayer()).thenReturn(online);var scheduler=mock(EntityScheduler.class);when(online.getScheduler()).thenReturn(scheduler);
        AtomicReference<Consumer<ScheduledTask>> deferred=new AtomicReference<>();
        doAnswer(call-> {deferred.set(call.getArgument(1));return mock(ScheduledTask.class);}).when(scheduler).run(eq(plugin),any(),any());
        var result=moderation.act(99,"kick",uuid);guard.changeAdmin(500,99,false).get();deferred.get().accept(mock(ScheduledTask.class));
        assertThrows(ExecutionException.class,result::get);verify(online,never()).kick(any(Component.class));
    }
}

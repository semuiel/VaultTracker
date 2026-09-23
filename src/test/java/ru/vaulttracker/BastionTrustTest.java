package ru.vaulttracker;

import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BastionTrustTest {
    public abstract static class FakeBastion implements Plugin {public abstract Trusted getTrustManager();}
    public static class Trusted {
        final Set<UUID> values=new HashSet<>();
        public boolean isTrusted(UUID uuid) {return values.contains(uuid);}
    }
    @Test void consoleCommandTargetsCurrentOnlineNicknameAndVerifiesBastionState() throws Exception {
        JavaPlugin plugin=mock(JavaPlugin.class);Server server=mock(Server.class);PluginManager plugins=mock(PluginManager.class);
        FakeBastion bastion=mock(FakeBastion.class);Player player=mock(Player.class);Trusted trusted=new Trusted();
        GlobalRegionScheduler scheduler=mock(GlobalRegionScheduler.class);ConsoleCommandSender console=mock(ConsoleCommandSender.class);
        UUID uuid=UUID.randomUUID();
        when(plugin.getServer()).thenReturn(server);when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(server.getPluginManager()).thenReturn(plugins);when(plugins.getPlugin("bastion")).thenReturn(bastion);
        when(bastion.isEnabled()).thenReturn(true);when(bastion.getTrustManager()).thenReturn(trusted);
        when(server.getPlayer(uuid)).thenReturn(player);when(player.isOnline()).thenReturn(true);when(player.getName()).thenReturn("Alice");
        when(server.getGlobalRegionScheduler()).thenReturn(scheduler);when(server.getConsoleSender()).thenReturn(console);
        doAnswer(call->{call.<Consumer<ScheduledTask>>getArgument(1).accept(mock(ScheduledTask.class));return mock(ScheduledTask.class);}).when(scheduler).run(eq(plugin),any());
        when(server.dispatchCommand(console,"bastion trust add Alice")).thenAnswer(call->{trusted.values.add(uuid);return true;});
        when(server.dispatchCommand(console,"bastion trust remove Alice")).thenAnswer(call->{trusted.values.remove(uuid);return true;});
        BastionTrust service=new BastionTrust(plugin);assertTrue(service.available());
        assertTrue(service.change(uuid,true).get().success());assertTrue(trusted.isTrusted(uuid));
        assertTrue(service.change(uuid,true).get().success());verify(server,times(1)).dispatchCommand(console,"bastion trust add Alice");
        assertTrue(service.change(uuid,false).get().success());assertFalse(trusted.isTrusted(uuid));
        when(player.isOnline()).thenReturn(false);assertFalse(service.change(uuid,true).get().success());
        verify(server,times(1)).dispatchCommand(console,"bastion trust add Alice");
    }
    @Test void consoleFailureCannotBeMistakenForSuccessfulTrust() throws Exception {
        JavaPlugin plugin=mock(JavaPlugin.class);Server server=mock(Server.class);PluginManager plugins=mock(PluginManager.class);
        FakeBastion bastion=mock(FakeBastion.class);Player player=mock(Player.class);Trusted trusted=new Trusted();
        GlobalRegionScheduler scheduler=mock(GlobalRegionScheduler.class);UUID uuid=UUID.randomUUID();
        when(plugin.getServer()).thenReturn(server);when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(server.getPluginManager()).thenReturn(plugins);when(plugins.getPlugin("bastion")).thenReturn(bastion);
        when(bastion.isEnabled()).thenReturn(true);when(bastion.getTrustManager()).thenReturn(trusted);
        when(server.getPlayer(uuid)).thenReturn(player);when(player.isOnline()).thenReturn(true);when(player.getName()).thenReturn("Alice");
        when(server.getGlobalRegionScheduler()).thenReturn(scheduler);
        doAnswer(call->{call.<Consumer<ScheduledTask>>getArgument(1).accept(mock(ScheduledTask.class));return mock(ScheduledTask.class);}).when(scheduler).run(eq(plugin),any());
        assertFalse(new BastionTrust(plugin).change(uuid,true).get().success());assertFalse(trusted.isTrusted(uuid));
        verify(server).dispatchCommand(any(),eq("bastion trust add Alice"));
    }
}

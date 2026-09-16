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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.block.ShulkerBox;
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
        guard.capability(500,"ban",true).get();
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
        when(server.dispatchCommand(console,"lp user "+uuid+" parent add admin")).thenReturn(true);
        assertThrows(ExecutionException.class,()->moderation.act(99,"addLp",uuid).get());
        assertTrue(moderation.act(500,"addLp",uuid).get().contains("выдачи"));
        verify(server,times(1)).dispatchCommand(console,"lp user "+uuid+" parent add admin");
    }
    @Test void chatIsPlainTextAndCannotExecuteCommands() throws Exception {
        var console=mock(ConsoleCommandSender.class);when(server.getConsoleSender()).thenReturn(console);when(server.getOnlinePlayers()).thenReturn(List.of());
        assertThrows(ExecutionException.class,()->moderation.broadcast(99,"hello").get());
        moderation.broadcast(500,"/op Player42").get();verify(console).sendMessage(Component.text("[Консоль] /op Player42"));
        verify(server,never()).dispatchCommand(any(),anyString());
    }
    @Test void kickRechecksPermissionOnPlayerScheduler() throws Exception {
        guard.capability(500,"kick",true).get();
        var online=mock(Player.class);when(player.getPlayer()).thenReturn(online);var scheduler=mock(EntityScheduler.class);when(online.getScheduler()).thenReturn(scheduler);
        AtomicReference<Consumer<ScheduledTask>> deferred=new AtomicReference<>();
        doAnswer(call-> {deferred.set(call.getArgument(1));return mock(ScheduledTask.class);}).when(scheduler).run(eq(plugin),any(),any());
        var result=moderation.act(99,"kick",uuid);guard.changeAdmin(500,99,false).get();deferred.get().accept(mock(ScheduledTask.class));
        assertThrows(ExecutionException.class,result::get);verify(online,never()).kick(any(Component.class));
    }
    @Test void coordinatesRejectNonFiniteAndMalformedInput() {
        assertArrayEquals(new double[]{223,200,1004},TelegramModeration.coordinates(" 223  200 1004 "));
        for(String value:List.of("1 2","1 2 3 4","NaN 2 3","Infinity 2 3","30000001 2 3","/tp 1 2")) assertThrows(IllegalArgumentException.class,()->TelegramModeration.coordinates(value));
    }
    @Test void teleportUsesAsyncApiAndRechecksSuperPermissionOnEntityScheduler() throws Exception {
        Player online=mock(Player.class);when(server.getPlayer(uuid)).thenReturn(online);
        World world=mock(World.class);WorldBorder border=mock(WorldBorder.class);
        when(world.getName()).thenReturn("world");when(world.getMinHeight()).thenReturn(-64);when(world.getMaxHeight()).thenReturn(320);
        when(world.getWorldBorder()).thenReturn(border);when(border.isInside(any())).thenReturn(true);when(online.getLocation()).thenReturn(new Location(world,0,70,0));
        EntityScheduler scheduler=mock(EntityScheduler.class);when(online.getScheduler()).thenReturn(scheduler);
        doAnswer(call->{call.getArgument(1,Consumer.class).accept(mock(ScheduledTask.class));return mock(ScheduledTask.class);}).when(scheduler).run(eq(plugin),any(),any());
        when(online.teleportAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(true));
        assertThrows(ExecutionException.class,()->moderation.teleport(99,uuid,null,"223 200 1004").get());
        assertTrue(moderation.teleport(500,uuid,null,"223 200 1004").get().contains("телепортирован"));
        verify(online).teleportAsync(argThat(loc->loc.getX()==223 && loc.getY()==200 && loc.getZ()==1004));
        assertThrows(ExecutionException.class,()->moderation.teleport(500,uuid,null,"1 400 1").get());
        AtomicReference<Consumer<ScheduledTask>> pending=new AtomicReference<>();
        doAnswer(call->{pending.set(call.getArgument(1));return mock(ScheduledTask.class);}).when(scheduler).run(eq(plugin),any(),any());
        var request=moderation.teleport(500,uuid,null,"1 200 1");guard.configure(new GuardConfig(true,120000,600),Set.of(99L));pending.get().accept(mock(ScheduledTask.class));
        assertThrows(ExecutionException.class,request::get);verify(online,times(1)).teleportAsync(any(Location.class));
    }
    @Test void superActionsHaveNoPluginConsoleAuditButAdminActionsStillDo() throws Exception {
        Logger logger=mock(Logger.class);when(plugin.getLogger()).thenReturn(logger);
        moderation.act(500,"unban",uuid).get();verify(logger,never()).info(anyString());
        guard.capability(500,"ban",true).get();
        moderation.act(99,"ban",uuid).get();verify(logger).info(contains("Telegram 99"));
    }
    @Test void inventoryViewKeepsEmptyShulkersOpenable() throws Exception {
        ItemStack boxItem=mock(ItemStack.class);BlockStateMeta meta=mock(BlockStateMeta.class);ShulkerBox box=mock(ShulkerBox.class);Inventory root=mock(Inventory.class);Inventory nested=mock(Inventory.class);
        Material type=mock(Material.class);when(type.isAir()).thenReturn(false);when(type.name()).thenReturn("SHULKER_BOX");
        when(boxItem.getType()).thenReturn(type);when(boxItem.getAmount()).thenReturn(1);when(boxItem.getItemMeta()).thenReturn(meta);
        when(meta.getBlockState()).thenReturn(box);when(box.getSnapshotInventory()).thenReturn(nested);when(root.getContents()).thenReturn(new ItemStack[0]);when(nested.getContents()).thenReturn(new ItemStack[27]);
        var read=TelegramModeration.class.getDeclaredMethod("readItems",Inventory.class);read.setAccessible(true);
        @SuppressWarnings("unchecked") var views=(List<TelegramModeration.ItemView>)read.invoke(null,root);
        assertTrue(views.isEmpty());
        when(root.getContents()).thenReturn(new ItemStack[]{boxItem});
        views=(List<TelegramModeration.ItemView>)read.invoke(null,root);
        assertEquals(1,views.size());assertTrue(views.getFirst().shulker());assertTrue(views.getFirst().contents().isEmpty());
        Material diamond=mock(Material.class);when(diamond.isAir()).thenReturn(false);when(diamond.name()).thenReturn("DIAMOND");
        ItemStack first=mock(ItemStack.class),second=mock(ItemStack.class);when(first.getType()).thenReturn(diamond);when(second.getType()).thenReturn(diamond);
        when(first.getAmount()).thenReturn(64);when(second.getAmount()).thenReturn(12);when(root.getContents()).thenReturn(new ItemStack[]{first,second,boxItem});
        views=(List<TelegramModeration.ItemView>)read.invoke(null,root);
        assertEquals(2,views.size());assertEquals(76,views.getFirst().amount());assertTrue(views.get(1).shulker());
    }
}

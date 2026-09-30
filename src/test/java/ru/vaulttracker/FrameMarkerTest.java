package ru.vaulttracker;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FrameMarkerTest {
    @Test void frameAnchorIsTheBlockBehindTheFrame() {
        ItemFrame frame=mock(ItemFrame.class);Location location=mock(Location.class);
        Block frameBlock=mock(Block.class),chest=mock(Block.class);
        when(frame.getLocation()).thenReturn(location);
        when(location.getBlock()).thenReturn(frameBlock);
        when(frame.getFacing()).thenReturn(BlockFace.NORTH);
        when(frameBlock.getRelative(BlockFace.SOUTH)).thenReturn(chest);
        assertSame(chest,VaultTrackerPlugin.frameAnchor(frame));
    }

    @Test void breakingRegisteredFrameRemovesItsVault() throws Exception {
        UUID worldId=UUID.randomUUID(),owner=UUID.randomUUID();
        BlockKey marker=new BlockKey(worldId,3,64,5),chest=new BlockKey(worldId,3,64,6);
        List<Snapshot> changes=new ArrayList<>();Catalogue catalogue=new Catalogue(changes::add);
        catalogue.register(marker,owner,"Alex",List.of(chest),Map.of("DIAMOND",4L),1,100);
        VaultTrackerPlugin plugin=mock(VaultTrackerPlugin.class,CALLS_REAL_METHODS);
        var field=VaultTrackerPlugin.class.getDeclaredField("catalogue");field.setAccessible(true);field.set(plugin,catalogue);
        World world=mock(World.class);when(world.getUID()).thenReturn(worldId);
        Block block=mock(Block.class);when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(3);when(block.getY()).thenReturn(64);when(block.getZ()).thenReturn(5);
        Location location=mock(Location.class);when(location.getBlock()).thenReturn(block);
        ItemFrame frame=mock(ItemFrame.class);when(frame.getLocation()).thenReturn(location);
        HangingBreakEvent event=mock(HangingBreakEvent.class);when(event.getEntity()).thenReturn(frame);
        plugin.brokenFrame(event);
        assertNull(catalogue.get(marker));
        assertNull(catalogue.claimedBy(chest));
        assertFalse(changes.getLast().active());
    }

    @Test void breakingFrameByOwnerAttributesRemovalBeforePublishing() throws Exception {
        UUID worldId=UUID.randomUUID(),owner=UUID.randomUUID();
        BlockKey marker=new BlockKey(worldId,3,64,5),chest=new BlockKey(worldId,3,64,6);
        GuardService guard=mock(GuardService.class);
        Catalogue catalogue=new Catalogue(snapshot->{if(!snapshot.active()) verify(guard).attribute(marker,owner,"Alex");});
        catalogue.register(marker,owner,"Alex",List.of(chest),Map.of("DIAMOND",4L),1,100);
        VaultTrackerPlugin plugin=mock(VaultTrackerPlugin.class,CALLS_REAL_METHODS);
        var catalogField=VaultTrackerPlugin.class.getDeclaredField("catalogue");catalogField.setAccessible(true);catalogField.set(plugin,catalogue);
        var guardField=VaultTrackerPlugin.class.getDeclaredField("guard");guardField.setAccessible(true);guardField.set(plugin,guard);
        World world=mock(World.class);when(world.getUID()).thenReturn(worldId);
        Block block=mock(Block.class);when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(3);when(block.getY()).thenReturn(64);when(block.getZ()).thenReturn(5);
        Location location=mock(Location.class);when(location.getBlock()).thenReturn(block);
        ItemFrame frame=mock(ItemFrame.class);when(frame.getLocation()).thenReturn(location);
        Player player=mock(Player.class);when(player.getUniqueId()).thenReturn(owner);when(player.getName()).thenReturn("Alex");
        HangingBreakByEntityEvent event=mock(HangingBreakByEntityEvent.class);
        when(event.getEntity()).thenReturn(frame);when(event.getRemover()).thenReturn(player);
        plugin.brokenFrame(event);
        assertNull(catalogue.get(marker));
        verify(player,times(1)).sendMessage(any(Component.class));
    }

    @Test void insertingOrRotatingAnExistingFrameOnClaimedVaultDoesNotRegisterAgain() throws Exception {
        UUID worldId=UUID.randomUUID(),owner=UUID.randomUUID();
        BlockKey sign=new BlockKey(worldId,3,64,4),chest=new BlockKey(worldId,3,64,6);
        Catalogue catalogue=new Catalogue(v->{});
        catalogue.register(sign,owner,"Alex",List.of(chest),Map.of("DIAMOND",4L),1,100);
        VaultTrackerPlugin plugin=mock(VaultTrackerPlugin.class,CALLS_REAL_METHODS);
        var field=VaultTrackerPlugin.class.getDeclaredField("catalogue");field.setAccessible(true);field.set(plugin,catalogue);
        World world=mock(World.class);when(world.getUID()).thenReturn(worldId);
        Block frameBlock=mock(Block.class),anchor=mock(Block.class);
        when(frameBlock.getWorld()).thenReturn(world);when(frameBlock.getX()).thenReturn(3);when(frameBlock.getY()).thenReturn(64);when(frameBlock.getZ()).thenReturn(5);
        when(anchor.getWorld()).thenReturn(world);when(anchor.getX()).thenReturn(3);when(anchor.getY()).thenReturn(64);when(anchor.getZ()).thenReturn(6);
        when(frameBlock.getRelative(BlockFace.SOUTH)).thenReturn(anchor);
        Location location=mock(Location.class);when(location.getBlock()).thenReturn(frameBlock);
        ItemFrame frame=mock(ItemFrame.class);when(frame.getLocation()).thenReturn(location);when(frame.getFacing()).thenReturn(BlockFace.NORTH);
        ItemStack item=mock(ItemStack.class);when(frame.getItem()).thenReturn(item);
        PlayerInteractEntityEvent event=mock(PlayerInteractEntityEvent.class);when(event.getRightClicked()).thenReturn(frame);
        Material paper=mock(Material.class),air=mock(Material.class);
        when(paper.isAir()).thenReturn(false);when(air.isAir()).thenReturn(true);
        when(item.getType()).thenReturn(paper);plugin.interactFrame(event); // rotate
        when(item.getType()).thenReturn(air);plugin.interactFrame(event); // insert into a frame beside an existing vault
        verify(frame,never()).getScheduler();
        assertEquals(1,catalogue.size());
    }
}

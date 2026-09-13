package ru.vaulttracker;

import io.papermc.paper.block.TileStateInventoryHolder;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BlockStorageTest {
    @Test void repeatedAutomatedObservationsDoNotPublishDuplicateSnapshots() {
        List<Snapshot> published = new ArrayList<>();
        Catalogue catalogue = new Catalogue(published::add);
        UUID world = UUID.randomUUID(), owner = UUID.randomUUID();
        BlockKey sign = new BlockKey(world, 1, 64, 1);
        catalogue.register(sign, owner, "Alex", List.of(sign), Map.of("DIAMOND", 3L), 1L, 10);
        assertTrue(catalogue.observe(sign, catalogue.get(sign).generation(), List.of(sign), Map.of("DIAMOND", 3L), 2L));
        assertEquals(1, published.size());
        assertTrue(catalogue.observe(sign, catalogue.get(sign).generation(), List.of(sign), Map.of("DIAMOND", 2L), 3L));
        assertEquals(2, published.size());
    }

    @Test void readsEveryBlockInventoryIncludingBarrelsShulkersAndMachines() throws Exception {
        List<Class<? extends TileStateInventoryHolder>> types = List.of(Barrel.class, ShulkerBox.class,
                Furnace.class, BlastFurnace.class, Smoker.class, Hopper.class, Dispenser.class,
                Dropper.class, BrewingStand.class, Crafter.class, ChiseledBookshelf.class,
                DecoratedPot.class, Jukebox.class, Lectern.class, Shelf.class);
        for (var type : types) {
            var holder = mock(type); Inventory inventory = (Inventory)mock(type.getMethod("getSnapshotInventory").getReturnType());
            ItemStack[] contents = {mock(ItemStack.class)};
            when(holder.getSnapshotInventory()).thenReturn(inventory);
            when(inventory.getContents()).thenReturn(contents);
            assertSame(contents, VaultTrackerPlugin.blockContents(holder), type.getSimpleName());
            verify(holder, never()).getInventory();
        }
    }
    @Test void campfireCountsItsFourSlotsAndEnderChestIsNotBlockStorage() {
        Campfire fire = mock(Campfire.class); when(fire.getSize()).thenReturn(4);
        ItemStack food = mock(ItemStack.class); when(fire.getItem(2)).thenReturn(food);
        assertArrayEquals(new ItemStack[]{null,null,food,null}, VaultTrackerPlugin.blockContents(fire));
        assertNull(VaultTrackerPlugin.blockContents(mock(EnderChest.class)));
        assertNull(VaultTrackerPlugin.blockContents(mock(BlockState.class)));
    }
    @Test void resolvesWallStandingAndCeilingSignsWithoutGuessingWallHangingSupport() {
        Block sign = mock(Block.class), container = mock(Block.class);
        WallSign wall = mock(WallSign.class); when(wall.getFacing()).thenReturn(BlockFace.NORTH);
        when(sign.getBlockData()).thenReturn(wall); when(sign.getRelative(BlockFace.SOUTH)).thenReturn(container);
        assertSame(container,VaultTrackerPlugin.attached(sign));
        when(sign.getBlockData()).thenReturn(mock(org.bukkit.block.data.type.Sign.class));
        when(sign.getRelative(BlockFace.DOWN)).thenReturn(container);
        assertSame(container,VaultTrackerPlugin.attached(sign));
        when(sign.getBlockData()).thenReturn(mock(org.bukkit.block.data.type.HangingSign.class));
        when(sign.getRelative(BlockFace.UP)).thenReturn(container);
        assertSame(container,VaultTrackerPlugin.attached(sign));
        when(sign.getBlockData()).thenReturn(mock(org.bukkit.block.data.type.WallHangingSign.class));
        assertNull(VaultTrackerPlugin.attached(sign));
    }
    @Test void barrelAndShulkerReadThroughTheSamePathUsedForRegistrationAndRefresh() throws Exception {
        var plugin = mock(VaultTrackerPlugin.class, CALLS_REAL_METHODS);
        var method = VaultTrackerPlugin.class.getDeclaredMethod("readChest",Block.class); method.setAccessible(true);
        World world = mock(World.class); when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.isChunkLoaded(0,0)).thenReturn(true);
        Block block = mock(Block.class); when(block.getWorld()).thenReturn(world);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(Location.class))).thenReturn(true);
            for (var type : List.of(Barrel.class,ShulkerBox.class)) {
                var holder = mock(type); Inventory inventory = mock(Inventory.class);
                ItemStack diamonds = mock(ItemStack.class);
                when(diamonds.getType()).thenReturn(Material.DIAMOND); when(diamonds.getAmount()).thenReturn(64);
                when(holder.getSnapshotInventory()).thenReturn(inventory);
                when(inventory.getContents()).thenReturn(new ItemStack[]{diamonds});
                when(block.getState()).thenReturn(holder);
                var view = (VaultTrackerPlugin.ChestView)method.invoke(plugin,block);
                assertEquals(1,view.chests().size()); assertEquals(Map.of("DIAMOND",64L),view.items());
            }
            when(world.isChunkLoaded(0,0)).thenReturn(false);
            assertNull(method.invoke(plugin,block));
        }
    }
}

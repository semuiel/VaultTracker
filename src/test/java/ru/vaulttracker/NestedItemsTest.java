package ru.vaulttracker;

import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NestedItemsTest {
    private ItemStack item(Material type, int amount) {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(type); when(item.getAmount()).thenReturn(amount);
        return item;
    }
    private ItemStack shulker(Material type, int count, ItemStack... contents) {
        ItemStack item = item(type, count);
        BlockStateMeta meta = mock(BlockStateMeta.class); ShulkerBox box = mock(ShulkerBox.class);
        Inventory inventory = mock(Inventory.class);
        when(item.getItemMeta()).thenReturn(meta); when(meta.getBlockState()).thenReturn(box);
        when(box.getSnapshotInventory()).thenReturn(inventory); when(inventory.getContents()).thenReturn(contents);
        return item;
    }
    @Test void sumsLooseItemsAndEveryShulkerColourAcrossBothChestHalves() {
        var first = new ArrayList<ItemStack>(); first.add(item(Material.DIAMOND, 7));
        int boxes = 0;
        for (Material type : Material.values()) {
            if (type.name().startsWith("LEGACY_")) continue;
            if (type.name().equals("SHULKER_BOX") || type.name().endsWith("_SHULKER_BOX")) {
                first.add(shulker(type, 1, item(Material.DIAMOND, 64), null, item(Material.IRON_INGOT, 3)));
                boxes++;
            }
        }
        Map<String,Long> totals = ItemCounter.count(List.of(first.toArray(ItemStack[]::new), new ItemStack[]{item(Material.DIAMOND, 11)}));
        assertEquals(17, boxes);
        assertEquals(18 + 64L * boxes, totals.get("DIAMOND"));
        assertEquals(3L * boxes, totals.get("IRON_INGOT"));
        assertEquals(1L, totals.get("RED_SHULKER_BOX"));
    }
    @Test void countsBundleInsideShulkerAndMultipliesStackedContainers() {
        ItemStack bundle = item(Material.BUNDLE, 1); BundleMeta meta = mock(BundleMeta.class);
        ItemStack diamonds = item(Material.DIAMOND, 5);
        when(bundle.getItemMeta()).thenReturn(meta); when(meta.getItems()).thenReturn(List.of(diamonds));
        ItemStack box = shulker(Material.SHULKER_BOX, 2, bundle, item(Material.DIAMOND, 7));
        assertEquals(Map.of("SHULKER_BOX",2L,"BUNDLE",2L,"DIAMOND",24L), ItemCounter.count(Collections.singletonList(new ItemStack[]{box})));
    }
    @Test void removingShulkerRemovesItsContentsOnNextSnapshot() {
        ItemStack loose = item(Material.DIAMOND, 10);
        ItemStack box = shulker(Material.SHULKER_BOX, 1, item(Material.DIAMOND, 64));
        assertEquals(74L, ItemCounter.count(Collections.singletonList(new ItemStack[]{loose, box})).get("DIAMOND"));
        assertEquals(Map.of("DIAMOND",10L), ItemCounter.count(Collections.singletonList(new ItemStack[]{loose})));
        assertEquals(Map.of("SHULKER_BOX",1L), ItemCounter.count(Collections.singletonList(new ItemStack[]{shulker(Material.SHULKER_BOX,1)})));
    }
}

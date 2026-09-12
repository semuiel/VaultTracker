package ru.vaulttracker;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import java.util.*;
public final class ItemCounter {
    private ItemCounter() {}
    public static Map<String, Long> count(List<ItemStack[]> inventories) {
        Map<String, Long> result = new HashMap<>();
        Deque<Entry> pending = new ArrayDeque<>();
        for (ItemStack[] inventory : inventories) for (ItemStack item : inventory)
            if (item != null) pending.add(new Entry(item, 1, 0));
        int visited = 0;
        while (!pending.isEmpty()) {
            Entry entry = pending.removeFirst();
            if (++visited > 100_000 || entry.depth() > 64)
                throw new IllegalArgumentException("Слишком сложная вложенность предметов; неполный подсчёт не сохраняется");
            ItemStack item = entry.item();
            if (item.getType() == Material.AIR || item.getType() == Material.CAVE_AIR
                    || item.getType() == Material.VOID_AIR || item.getAmount() <= 0) continue;
            long amount = Math.multiplyExact(entry.multiplier(), item.getAmount());
            result.merge(item.getType().name(), amount, Math::addExact);
            // Read only the item's snapshot, without placing blocks or modifying stored items.
            String name = item.getType().name();
            if (name.equals("SHULKER_BOX") || name.endsWith("_SHULKER_BOX")) {
                if (item.getItemMeta() instanceof BlockStateMeta meta && meta.getBlockState() instanceof ShulkerBox box)
                    for (ItemStack child : box.getSnapshotInventory().getContents())
                        if (child != null) pending.add(new Entry(child, amount, entry.depth() + 1));
            } else if (name.equals("BUNDLE") || name.endsWith("_BUNDLE")) {
                if (item.getItemMeta() instanceof BundleMeta meta)
                    for (ItemStack child : meta.getItems())
                        if (child != null) pending.add(new Entry(child, amount, entry.depth() + 1));
            }
        }
        return Map.copyOf(result);
    }
    private record Entry(ItemStack item, long multiplier, int depth) {}
}

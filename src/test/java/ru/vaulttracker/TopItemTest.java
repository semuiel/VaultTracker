package ru.vaulttracker;

import org.bukkit.command.*;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import net.kyori.adventure.text.Component;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class TopItemTest {
    @Test void topitemAndLegacyFindHaveSameResultsAndPermissionChecks() throws Exception {
        VaultTrackerPlugin plugin = mock(VaultTrackerPlugin.class, CALLS_REAL_METHODS);
        Catalogue catalogue = new Catalogue(v -> {});
        UUID world = UUID.randomUUID();
        catalogue.register(new BlockKey(world,0,0,0),UUID.randomUUID(),"Alex",List.of(new BlockKey(world,0,0,1)),Map.of("DIAMOND",74L),1,100);
        StorageEngine storage = mock(StorageEngine.class); when(storage.ready()).thenReturn(true);
        for (var pair : Map.of("catalogue",(Object)catalogue,"storage",storage).entrySet()) {
            var field = VaultTrackerPlugin.class.getDeclaredField(pair.getKey()); field.setAccessible(true); field.set(plugin,pair.getValue());
        }
        Command top = mock(Command.class); when(top.getName()).thenReturn("topitem");
        Command old = mock(Command.class); when(old.getName()).thenReturn("vaulttracker");
        CommandSender sender = mock(CommandSender.class); when(sender.hasPermission("vaulttracker.search")).thenReturn(true);
        Material diamond = mock(Material.class); when(diamond.isItem()).thenReturn(true);
        try (var materials = mockStatic(Material.class)) {
        materials.when(() -> Material.getMaterial("DIAMOND")).thenReturn(diamond);
        plugin.onCommand(sender,top,"topitem",new String[]{"daimond"});
        verify(sender).sendMessage(Component.text("[VaultTracker] Алмаз — топ владельцев, последние известные остатки:"));
        clearInvocations(sender);
        plugin.onCommand(sender,old,"vtrack",new String[]{"find","diamond"});
        verify(sender).sendMessage(Component.text("[VaultTracker] DIAMOND — последние известные остатки:"));
        when(sender.hasPermission("vaulttracker.search")).thenReturn(false);
        plugin.onCommand(sender,top,"topitem",new String[]{"diamond"});
        verify(sender).sendMessage(Component.text("[VaultTracker] Нет права vaulttracker.search."));
        assertTrue(plugin.onTabComplete(sender,top,"topitem",new String[]{"dia"}).isEmpty());
        }
    }
}

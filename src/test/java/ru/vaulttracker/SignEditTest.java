package ru.vaulttracker;

import net.kyori.adventure.text.Component;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.block.SignChangeEvent;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SignEditTest {
    private final UUID worldId = UUID.randomUUID(), owner = UUID.randomUUID();
    private final BlockKey sign = new BlockKey(worldId, 0, 0, 0);
    private final Catalogue catalogue = new Catalogue(v -> {});

    private VaultTrackerPlugin plugin() throws Exception {
        VaultTrackerPlugin plugin = mock(VaultTrackerPlugin.class, CALLS_REAL_METHODS);
        var field = VaultTrackerPlugin.class.getDeclaredField("catalogue");
        field.setAccessible(true); field.set(plugin, catalogue);
        // Avoid scheduling world work in this event-policy test.
        var stopping = VaultTrackerPlugin.class.getDeclaredField("stopping");
        stopping.setAccessible(true); stopping.set(plugin, true);
        catalogue.register(sign, owner, "Alex", List.of(new BlockKey(worldId, 0, 0, 1)),
                Map.of("DIAMOND", 7L), 1, 100);
        return plugin;
    }

    private SignChangeEvent edit(String line, UUID editor) {
        World world = mock(World.class); when(world.getUID()).thenReturn(worldId);
        Block block = mock(Block.class); when(block.getWorld()).thenReturn(world);
        Player player = mock(Player.class); when(player.getUniqueId()).thenReturn(editor);
        SignChangeEvent event = mock(SignChangeEvent.class);
        when(event.getBlock()).thenReturn(block); when(event.getPlayer()).thenReturn(player);
        when(event.getSide()).thenReturn(Side.FRONT); when(event.line(0)).thenReturn(Component.text(line));
        return event;
    }

    @Test void savingDisplayedNicknameKeepsRegistration() throws Exception {
        var plugin = plugin(); plugin.changedSign(edit("Alex", owner));
        assertEquals(owner, catalogue.get(sign).owner());
        assertEquals(7L, catalogue.get(sign).items().get("DIAMOND"));
    }

    @Test void clearingFirstLineUnregistersChest() throws Exception {
        var plugin = plugin(); plugin.changedSign(edit("", owner));
        assertNull(catalogue.get(sign));
    }

    @Test void savingNewOwnershipLabelKeepsRegistration() throws Exception {
        var plugin = plugin(); var event = edit("Собственность", owner);
        when(event.line(1)).thenReturn(Component.text("игрока"));
        when(event.line(2)).thenReturn(Component.text("Alex"));
        plugin.changedSign(event);
        assertEquals(owner, catalogue.get(sign).owner());
    }

    @Test void changingNewOwnershipNameUnregistersInsteadOfTransferring() throws Exception {
        var plugin = plugin(); var event = edit("Собственность", owner);
        when(event.line(1)).thenReturn(Component.text("игрока"));
        when(event.line(2)).thenReturn(Component.text("Steve"));
        plugin.changedSign(event);
        assertNull(catalogue.get(sign));
    }

    @Test void otherPlayerCannotEditNamedSign() throws Exception {
        var plugin = plugin(); var event = edit("Alex", UUID.randomUUID());
        plugin.validateSign(event);
        verify(event).setCancelled(true);
        assertEquals(owner, catalogue.get(sign).owner());
    }

    @Test void reenteringMarkerDoesNotTransferOwnership() throws Exception {
        var plugin = plugin(); plugin.changedSign(edit("[VaUlT]", UUID.randomUUID()));
        assertEquals(owner, catalogue.get(sign).owner());
    }
}

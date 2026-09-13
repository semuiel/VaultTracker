package ru.vaulttracker;

import net.kyori.adventure.text.Component;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.Player;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SignEditTest {
    private final UUID worldId = UUID.randomUUID(), owner = UUID.randomUUID();
    private final BlockKey sign = new BlockKey(worldId, 0, 0, 0);
    private final List<Snapshot> published = new ArrayList<>();
    private final Catalogue catalogue = new Catalogue(published::add);

    private VaultTrackerPlugin plugin() throws Exception {
        VaultTrackerPlugin plugin = mock(VaultTrackerPlugin.class, CALLS_REAL_METHODS);
        var field = VaultTrackerPlugin.class.getDeclaredField("catalogue");
        field.setAccessible(true); field.set(plugin, catalogue);
        var guard=VaultTrackerPlugin.class.getDeclaredField("guard");guard.setAccessible(true);guard.set(plugin,mock(GuardService.class));
        // Avoid scheduling world work in this event-policy test.
        var stopping = VaultTrackerPlugin.class.getDeclaredField("stopping");
        stopping.setAccessible(true); stopping.set(plugin, true);
        catalogue.register(sign, owner, "Alex", List.of(new BlockKey(worldId, 0, 0, 1)),
                Map.of("DIAMOND", 7L), 1, 100);
        published.clear();
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

    @Test void clearingFirstLineKeepsChestWithoutPublishingAnEvent() throws Exception {
        var plugin = plugin(); plugin.changedSign(edit("", owner));
        assertEquals(owner, catalogue.get(sign).owner());
        assertEquals(7L, catalogue.get(sign).items().get("DIAMOND"));
        assertTrue(published.isEmpty());
    }

    @Test void savingNewOwnershipLabelKeepsRegistration() throws Exception {
        var plugin = plugin(); var event = edit("Собственность", owner);
        when(event.line(1)).thenReturn(Component.text("игрока"));
        when(event.line(2)).thenReturn(Component.text("Alex"));
        plugin.changedSign(event);
        assertEquals(owner, catalogue.get(sign).owner());
    }

    @Test void changingDisplayedNameKeepsOriginalOwnerAndRegistration() throws Exception {
        var plugin = plugin(); var event = edit("Собственность", owner);
        when(event.line(1)).thenReturn(Component.text("игрока"));
        when(event.line(2)).thenReturn(Component.text("Steve"));
        plugin.changedSign(event);
        assertEquals(owner, catalogue.get(sign).owner());
        assertEquals("Alex", catalogue.get(sign).playerName());
        assertTrue(published.isEmpty());
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
    @Test void arbitraryFrontAndBackEditsDoNotPublishChanges() throws Exception {
        var plugin=plugin();Snapshot before=catalogue.get(sign);
        for(Side side:Side.values()) {
            var event=edit("Мои алмазы",owner);when(event.getSide()).thenReturn(side);
            plugin.validateSign(event);plugin.changedSign(event);
            verify(event,never()).setCancelled(true);
        }
        assertSame(before,catalogue.get(sign));assertTrue(published.isEmpty());
    }
    @Test void refreshPreservesCustomLabelAndStillDecoratesInitialMarker() {
        Sign block=mock(Sign.class);SignSide front=mock(SignSide.class);
        when(block.getSide(Side.FRONT)).thenReturn(front);
        for(String text:List.of("Мои алмазы","","Собственность")) {
            when(front.line(0)).thenReturn(Component.text(text));
            VaultTrackerPlugin.decorateOwner(block,"Alex");
        }
        verify(block,never()).update(anyBoolean(),anyBoolean());
        verify(front,never()).line(anyInt(),any(Component.class));
        when(front.line(0)).thenReturn(Component.text("[vault]"));
        VaultTrackerPlugin.decorateOwner(block,"Alex");
        verify(front).line(2,Component.text("Alex"));verify(block).update(true,false);
    }
    @Test void breakingSignStillRemovesRegistration() throws Exception {
        var plugin=plugin();var event=mock(BlockBreakEvent.class);
        Block block=edit("",owner).getBlock();
        Player breaker=mock(Player.class);when(breaker.getName()).thenReturn("Griefer");when(event.getPlayer()).thenReturn(breaker);
        when(event.getBlock()).thenReturn(block);plugin.broken(event);
        assertNull(catalogue.get(sign));assertEquals(1,published.size());assertFalse(published.getFirst().active());
    }
    @Test void breakingContainerStillRemovesRegistration() throws Exception {
        var plugin=plugin();var event=mock(BlockBreakEvent.class);Block block=edit("",owner).getBlock();
        Player breaker=mock(Player.class);when(breaker.getName()).thenReturn("Griefer");when(event.getPlayer()).thenReturn(breaker);
        when(block.getZ()).thenReturn(1);when(event.getBlock()).thenReturn(block);plugin.broken(event);
        assertNull(catalogue.get(sign));assertEquals(1,published.size());assertFalse(published.getFirst().active());
    }
}

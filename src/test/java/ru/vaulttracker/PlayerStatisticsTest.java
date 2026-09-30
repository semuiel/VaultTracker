package ru.vaulttracker;

import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerStatisticsTest {
    @Test void sparseSnapshotUsesBukkitCountersAndKeepsMaterialCategoriesSeparate() {
        OfflinePlayer player=mock(OfflinePlayer.class);
        Material stone=mock(Material.class),sword=mock(Material.class);
        when(stone.name()).thenReturn("STONE");when(stone.isBlock()).thenReturn(true);
        when(sword.name()).thenReturn("DIAMOND_SWORD");when(sword.isItem()).thenReturn(true);
        when(player.getStatistic(Statistic.JUMP)).thenReturn(42);
        when(player.getStatistic(Statistic.MINE_BLOCK,stone)).thenReturn(7);
        when(player.getStatistic(Statistic.CRAFT_ITEM,sword)).thenReturn(2);
        Map<String,Integer> raw=PlayerStatistics.capture(player,java.util.List.of(stone,sword));
        assertEquals(42,raw.get("custom:JUMP"));
        assertEquals(7,raw.get("mine:STONE"));
        assertEquals(2,raw.get("craft:DIAMOND_SWORD"));
        assertFalse(raw.containsKey("craft:STONE"));
    }
    @Test void mergePreservesHistoryAcrossMissingAndResetVanillaCounters() {
        var previous=PlayerStatistics.merge(null,100);
        assertEquals(previous,PlayerStatistics.merge(previous,0));
        previous=PlayerStatistics.merge(previous,120);
        assertEquals(120,previous.total());
        previous=PlayerStatistics.merge(previous,5);
        assertEquals(125,previous.total());
        assertEquals(5,previous.lastVanilla());
        assertEquals(128,PlayerStatistics.merge(previous,8).total());
    }
}

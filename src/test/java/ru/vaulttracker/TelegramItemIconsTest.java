package ru.vaulttracker;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TelegramItemIconsTest {
    @Test void distinguishesEquipmentFromResourcesAndKeepsNames() {
        assertEquals("💎",TelegramItemIcons.icon("DIAMOND"));
        assertEquals("💎",TelegramItemIcons.icon("DEEPSLATE_DIAMOND_ORE"));
        assertEquals("⛏️",TelegramItemIcons.icon("DIAMOND_PICKAXE"));
        assertEquals("⚔️",TelegramItemIcons.icon("DIAMOND_SWORD"));
        assertEquals("🍎",TelegramItemIcons.icon("GOLDEN_APPLE"));
        assertEquals("📚",TelegramItemIcons.icon("ENCHANTED_BOOK"));
        assertEquals("📦",TelegramItemIcons.icon("UNKNOWN_FUTURE_ITEM"));
        assertEquals("💎 Алмаз",TelegramItemIcons.label("DIAMOND"));
    }
}

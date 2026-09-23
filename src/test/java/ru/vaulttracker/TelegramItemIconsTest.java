package ru.vaulttracker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class TelegramItemIconsTest {
    @TempDir Path folder;
    @Test void distinguishesEquipmentFromResourcesAndKeepsNames() {
        assertEquals("💎",TelegramItemIcons.icon("DIAMOND"));
        assertEquals("💎",TelegramItemIcons.icon("DEEPSLATE_DIAMOND_ORE"));
        assertEquals("🛠️",TelegramItemIcons.icon("DIAMOND_PICKAXE"));
        assertEquals("⚔️",TelegramItemIcons.icon("DIAMOND_SWORD"));
        assertEquals("🍖",TelegramItemIcons.icon("GOLDEN_APPLE"));
        assertEquals("🧪",TelegramItemIcons.icon("ENCHANTED_BOOK"));
        assertEquals("•",TelegramItemIcons.icon("UNKNOWN_FUTURE_ITEM"));
        assertEquals("Алмаз",TelegramItemIcons.label("DIAMOND"));
    }
    @Test void generatesEveryMaterialAndLoadsCustomTelegramEmoji() throws Exception {
        TelegramItemIcons.configure(folder,Logger.getAnonymousLogger());Path file=folder.resolve("item-emojis.yml");
        assertTrue(Files.readString(file).contains("DIAMOND:"));
        var yaml=YamlConfiguration.loadConfiguration(file.toFile());yaml.set("items.DIAMOND.emoji","🔷");yaml.set("items.DIAMOND.customEmojiId","5339573139900735019");yaml.save(file.toFile());
        try {
            TelegramItemIcons.configure(folder,Logger.getAnonymousLogger());String icon=TelegramItemIcons.icon("DIAMOND");
            assertEquals("🔷",TelegramEmojiMarkup.plain(icon));assertTrue(TelegramEmojiMarkup.html(icon).text().contains("5339573139900735019"));
        } finally {yaml.set("items.DIAMOND.emoji","💎");yaml.set("items.DIAMOND.customEmojiId","");yaml.save(file.toFile());TelegramItemIcons.configure(folder,Logger.getAnonymousLogger());}
    }
}

package ru.vaulttracker;

import java.util.Locale;

/** Ordinary Unicode emoji: approximate resource symbols, not Minecraft textures. */
final class TelegramItemIcons {
    static String label(String material) { return icon(material)+" "+RussianItems.name(material); }
    static String groupLabel(ResourceGroups.Group group) { return icon(group.displayMaterial())+" "+group.title(); }
    static String icon(String material) {
        String name=material.toUpperCase(Locale.ROOT);
        if(name.endsWith("PICKAXE")) return "⛏️";
        if(name.endsWith("_AXE")) return "🪓";
        if(name.endsWith("SWORD")) return "⚔️";
        if(name.endsWith("HELMET") || name.endsWith("CHESTPLATE") || name.endsWith("LEGGINGS") || name.endsWith("BOOTS") || name.equals("SHIELD")) return "🛡️";
        if(name.contains("DIAMOND")) return "💎";
        if(name.contains("EMERALD")) return "🟢";
        if(name.contains("GOLD") && !name.contains("APPLE") && !name.contains("CARROT")) return "🟡";
        if(name.contains("IRON") || name.contains("NETHERITE")) return "🔩";
        if(name.contains("COPPER")) return "🟠";
        if(name.contains("REDSTONE")) return "🔴";
        if(name.contains("LAPIS")) return "🔵";
        if(name.contains("AMETHYST")) return "🟣";
        if(name.contains("COAL") || name.equals("CHARCOAL")) return "⚫";
        if(name.contains("BOOK")) return "📚";
        if(name.equals("PAPER") || name.contains("MAP")) return "📜";
        if(name.contains("SHULKER_BOX") || name.contains("CHEST") || name.equals("BARREL") || name.contains("BUNDLE")) return "📦";
        if(name.contains("POTION") || name.equals("GLASS_BOTTLE")) return "🧪";
        if(name.contains("BUCKET")) return "🪣";
        if(name.endsWith("_LOG") || name.endsWith("_WOOD") || name.endsWith("_PLANKS") || name.equals("STICK")) return "🪵";
        if(name.contains("LEAVES") || name.contains("SAPLING") || name.contains("SEEDS")) return "🌱";
        if(name.contains("APPLE")) return "🍎";
        if(name.contains("CARROT")) return "🥕";
        if(name.equals("BREAD") || name.equals("WHEAT")) return "🌾";
        if(name.contains("FISH") || name.contains("SALMON") || name.contains("COD")) return "🐟";
        if(name.contains("BEEF") || name.contains("PORKCHOP") || name.contains("CHICKEN") || name.contains("MUTTON")) return "🍖";
        if(name.contains("TORCH") || name.contains("CAMPFIRE") || name.equals("BLAZE_ROD")) return "🔥";
        if(name.contains("STONE") || name.contains("DEEPSLATE") || name.contains("OBSIDIAN") || name.contains("GRANITE") || name.contains("DIORITE") || name.contains("ANDESITE")) return "🪨";
        if(name.contains("BRICK")) return "🧱";
        if(name.contains("SAND") || name.contains("DIRT") || name.equals("GRAVEL")) return "🟫";
        return "📦";
    }
    private TelegramItemIcons() {}
}

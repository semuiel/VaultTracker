package ru.vaulttracker;
import java.util.*;

/** Stable category symbols shared by all Telegram item lists. */
final class TelegramItemIcons {
 static String label(String material) {return icon(material)+" "+RussianItems.name(material);}
 static String groupLabel(ResourceGroups.Group group) {return icon(group.displayMaterial())+" "+group.title();}
 static String icon(String material) {
  String n=material.toUpperCase(Locale.ROOT).replace("MINECRAFT:","");
  if(n.endsWith("SHULKER_BOX") || n.endsWith("_CHEST") || Set.of("CHEST","TRAPPED_CHEST","ENDER_CHEST","BARREL","BUNDLE").contains(n) || n.endsWith("_BUNDLE")) return "📦";
  if(n.endsWith("SWORD") || n.endsWith("_SPEAR") || Set.of("BOW","CROSSBOW","TRIDENT","MACE","ARROW","SPECTRAL_ARROW","TIPPED_ARROW").contains(n)) return "⚔️";
  if(n.endsWith("HELMET") || n.endsWith("CHESTPLATE") || n.endsWith("LEGGINGS") || n.endsWith("BOOTS") || n.endsWith("HORSE_ARMOR") || Set.of("SHIELD","ELYTRA","WOLF_ARMOR").contains(n)) return "🛡️";
  if(n.endsWith("PICKAXE") || n.endsWith("_AXE") || n.endsWith("SHOVEL") || n.endsWith("_HOE") || Set.of("SHEARS","FISHING_ROD","FLINT_AND_STEEL","BRUSH","COMPASS","RECOVERY_COMPASS","CLOCK","SPYGLASS","LEAD").contains(n)) return "🛠️";
  if(n.contains("POTION") || Set.of("GLASS_BOTTLE","DRAGON_BREATH","EXPERIENCE_BOTTLE","TOTEM_OF_UNDYING","ENCHANTED_BOOK","ENDER_PEARL","ENDER_EYE").contains(n)) return "🧪";
  if(n.contains("BOOK") || n.equals("PAPER") || n.contains("MAP")) return "📚";
  if(n.endsWith("BUCKET")) return "🪣";

  if(Set.of("APPLE","GOLDEN_APPLE","ENCHANTED_GOLDEN_APPLE","BREAD","CARROT","GOLDEN_CARROT","POTATO","BAKED_POTATO","POISONOUS_POTATO","BEETROOT","BEETROOT_SOUP","MUSHROOM_STEW","RABBIT_STEW","SUSPICIOUS_STEW","BEEF","COOKED_BEEF","PORKCHOP","COOKED_PORKCHOP","CHICKEN","COOKED_CHICKEN","MUTTON","COOKED_MUTTON","RABBIT","COOKED_RABBIT","COD","COOKED_COD","SALMON","COOKED_SALMON","TROPICAL_FISH","PUFFERFISH","COOKIE","CAKE","PUMPKIN_PIE","MELON_SLICE","SWEET_BERRIES","GLOW_BERRIES","DRIED_KELP","CHORUS_FRUIT","HONEY_BOTTLE","ROTTEN_FLESH","SPIDER_EYE").contains(n)) return "🍖";
  if(n.contains("SAPLING") || n.contains("LEAVES") || n.endsWith("SEEDS") || n.contains("FLOWER") || Set.of("WHEAT","BAMBOO","SUGAR_CANE","CACTUS","KELP","MOSS_BLOCK","DANDELION","POPPY","FERN","VINE").contains(n)) return "🌱";
  if(n.endsWith("_ORE") || n.endsWith("_INGOT") || n.endsWith("_NUGGET") || n.startsWith("RAW_") || Set.of("DIAMOND","EMERALD","COAL","CHARCOAL","LAPIS_LAZULI","QUARTZ","AMETHYST_SHARD","NETHERITE_SCRAP","ANCIENT_DEBRIS").contains(n)) return "💎";
  if(n.contains("REDSTONE") || n.contains("PISTON") || n.endsWith("_RAIL") || n.endsWith("_MINECART") || n.endsWith("_BUTTON") || n.endsWith("PRESSURE_PLATE") || Set.of("RAIL","MINECART","HOPPER","DROPPER","DISPENSER","OBSERVER","REPEATER","COMPARATOR","LEVER","CRAFTER","DAYLIGHT_DETECTOR","TARGET").contains(n)) return "⚙️";
  if(RussianItems.block(n)) return "🧱";
  return "•";
 }
 private TelegramItemIcons() {}
}

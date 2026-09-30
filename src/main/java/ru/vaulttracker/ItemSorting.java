package ru.vaulttracker;

import java.util.*;

/** Shared ordering for catalogue choices and scoped resource totals. */
final class ItemSorting {
    enum Mode { MINECRAFT, SERVER, ALPHABETICAL, AMOUNT }

    // Stable community-oriented defaults. Add new IDs here without changing persisted settings.
    private static final List<String> POPULAR=List.of(
            "DIAMOND", "NETHERITE_INGOT", "EMERALD", "ENCHANTED_GOLDEN_APPLE", "ELYTRA",
            "TOTEM_OF_UNDYING", "NETHERITE_SCRAP", "ANCIENT_DEBRIS", "DIAMOND_BLOCK",
            "GOLDEN_APPLE", "EXPERIENCE_BOTTLE", "SHULKER_BOX", "NETHER_STAR",
            "BEACON", "IRON_INGOT", "GOLD_INGOT", "NETHERITE_BLOCK", "REDSTONE",
            "LAPIS_LAZULI", "COAL", "OBSIDIAN", "ENDER_PEARL", "BLAZE_ROD",
            "GUNPOWDER", "FIREWORK_ROCKET", "TNT", "QUARTZ", "COPPER_INGOT",
            "STONE", "COBBLESTONE", "SMOOTH_STONE", "OAK_LOG", "OAK_PLANKS",
            "PAPER", "SUGAR_CANE", "WHEAT", "BONE_MEAL", "STRING", "AR", "IR");
    private static final Map<String,Integer> RANK=rank();

    static List<String> sort(Collection<String> items,Mode mode,Map<String,Long> scopedAmounts,Map<String,Long> searches) {
        List<String> result=new ArrayList<>(items);
        result.sort(comparator(mode,scopedAmounts,searches));
        return List.copyOf(result);
    }

    static Comparator<String> comparator(Mode mode,Map<String,Long> amounts,Map<String,Long> searches) {
        Comparator<String> alphabetical=Comparator.comparing(RussianItems::name,String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Comparator.naturalOrder());
        return switch(mode) {
            case MINECRAFT -> Comparator.comparingInt((String item)->RANK.getOrDefault(item,Integer.MAX_VALUE)).thenComparing(alphabetical);
            case SERVER -> Comparator.comparingLong((String item)->searches.getOrDefault(item,0L)).reversed()
                    .thenComparingInt(item->RANK.getOrDefault(item,Integer.MAX_VALUE)).thenComparing(alphabetical);
            case ALPHABETICAL -> alphabetical;
            case AMOUNT -> Comparator.comparingLong((String item)->amount(item,amounts)).reversed().thenComparing(alphabetical);
        };
    }

    static List<Catalogue.PersonalRank> personalRanks(Collection<Catalogue.PersonalRank> ranks,Mode mode,Map<String,Long> searches) {
        Map<String,Long> amounts=new HashMap<>();
        for(var rank:ranks) amounts.put(rank.material(),rank.amount());
        Comparator<String> items=comparator(mode,amounts,searches);
        return ranks.stream().sorted(Comparator.comparingInt(Catalogue.PersonalRank::place)
                .thenComparing(Catalogue.PersonalRank::material,items)
                .thenComparing(Catalogue.PersonalRank::label,String.CASE_INSENSITIVE_ORDER)).toList();
    }

    static String queryItem(String query) {
        ResourceGroups.Group group=ResourceGroups.resolve(query);
        if(group!=null) return group.code();
        return RussianItems.candidates(query).stream().findFirst().orElse(null);
    }

    private static long amount(String item,Map<String,Long> amounts) {
        if(amounts.containsKey(item)) return amounts.get(item);
        ResourceGroups.Group group=ResourceGroups.resolve(item);
        if(group==null) return amounts.getOrDefault(item,0L);
        return group.materials().stream().mapToLong(material->amounts.getOrDefault(material,0L)).sum();
    }

    private static Map<String,Integer> rank() {
        Map<String,Integer> result=new HashMap<>();
        for(int i=0;i<POPULAR.size();i++) result.put(POPULAR.get(i),i);
        return Map.copyOf(result);
    }
    private ItemSorting() {}
}

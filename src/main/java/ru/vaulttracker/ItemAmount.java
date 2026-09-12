package ru.vaulttracker;

import org.bukkit.Material;

final class ItemAmount {
    static String format(String material,long amount) {
        // Shulker boxes cannot themselves be packed into another shulker.
        if (amount < 27 || material.endsWith("SHULKER_BOX")) return amount+" шт.";
        Material type=Material.getMaterial(material);
        return format(amount,type==null ? 64 : Math.max(1,type.getMaxStackSize()));
    }
    static String format(long amount,int stackSize) {
        long capacity=27L*stackSize;
        if (stackSize<1) throw new IllegalArgumentException("Invalid stack size");
        if (amount<capacity) return amount+" шт.";
        long boxes=amount/capacity, rest=amount%capacity;
        long last=boxes%10, lastTwo=boxes%100;
        String word=last==1 && lastTwo!=11 ? "шалкер" : last>=2 && last<=4 && (lastTwo<12 || lastTwo>14) ? "шалкера" : "шалкеров";
        return amount+" шт. ("+boxes+" "+word+(rest==0 ? "" : " + "+rest+" шт.")+")";
    }
}

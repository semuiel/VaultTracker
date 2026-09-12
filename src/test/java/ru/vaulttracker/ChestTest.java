package ru.vaulttracker;
import org.junit.jupiter.api.Test;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Chest;
import org.bukkit.inventory.ItemStack;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ChestTest {
    private ItemStack item(Material material,int amount) {
        ItemStack item=mock(ItemStack.class); when(item.getType()).thenReturn(material); when(item.getAmount()).thenReturn(amount); return item;
    }
    @Test void combinesBothHalvesAndSkipsEmptySlots() {
        ItemStack[] first={null,item(Material.AIR,1),item(Material.DIAMOND,64),item(Material.STONE,3)};
        ItemStack[] second={item(Material.DIAMOND,10),item(Material.STONE,64),item(Material.DIRT,0)};
        assertEquals(Map.of("DIAMOND",74L,"STONE",67L),ItemCounter.count(List.of(first,second)));
    }
    @Test void partnerDirectionsMatchAllFourOrientations() {
        BlockFace[] facing={BlockFace.NORTH,BlockFace.EAST,BlockFace.SOUTH,BlockFace.WEST};
        BlockFace[] right={BlockFace.EAST,BlockFace.SOUTH,BlockFace.WEST,BlockFace.NORTH};
        for (int i=0;i<4;i++) {
            assertEquals(right[i],ChestLayout.partner(facing[i],Chest.Type.LEFT));
            assertEquals(right[i].getOppositeFace(),ChestLayout.partner(facing[i],Chest.Type.RIGHT));
            assertEquals(BlockFace.SELF,ChestLayout.partner(facing[i],Chest.Type.SINGLE));
        }
    }
}

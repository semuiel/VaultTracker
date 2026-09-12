package ru.vaulttracker;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Chest;
public final class ChestLayout {
    private ChestLayout() {}
    public static BlockFace partner(BlockFace facing, Chest.Type type) {
        if (type == Chest.Type.SINGLE) return BlockFace.SELF;
        BlockFace clockwise = switch (facing) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;
            default -> throw new IllegalArgumentException("Non-horizontal chest");
        };
        return type == Chest.Type.LEFT ? clockwise : clockwise.getOppositeFace();
    }
}

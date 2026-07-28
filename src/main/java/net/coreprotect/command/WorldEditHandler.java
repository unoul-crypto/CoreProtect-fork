package net.coreprotect.command;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.World;

import net.coreprotect.model.selection.SelectionRegistry;
import net.coreprotect.worldedit.WorldEditLogger;
import net.coreprotect.utility.ErrorReporter;

public class WorldEditHandler {

    protected static Integer[] runWorldEditCommand(CommandSender user) {
        Integer[] result = null;
        try {
            WorldEditPlugin worldEdit = WorldEditLogger.getWorldEdit(user.getServer());
            if (worldEdit != null && user instanceof Player) {
                LocalSession session = worldEdit.getSession((Player) user);
                World world = session.getSelectionWorld();
                if (world != null) {
                    Region region = session.getSelection(world);
                    if (region != null && world.getName().equals(((Player) user).getWorld().getName())) {
                        Region selection = region.clone();
                        BlockVector3 minimum = selection.getMinimumPoint();
                        BlockVector3 maximum = selection.getMaximumPoint();
                        int x = minimum.getBlockX();
                        int y = minimum.getBlockY();
                        int z = minimum.getBlockZ();
                        int width = region.getWidth();
                        int height = region.getHeight();
                        int length = region.getLength();
                        int max = width;
                        if (height > max) {
                            max = height;
                        }
                        if (length > max) {
                            max = length;
                        }
                        int xMin = x;
                        int xMax = maximum.getBlockX();
                        int yMin = y;
                        int yMax = maximum.getBlockY();
                        int zMin = z;
                        int zMax = maximum.getBlockZ();
                        result = new Integer[] { max, xMin, xMax, yMin, yMax, zMin, zMax, 1 };
                        if (!(selection instanceof CuboidRegion)) {
                            SelectionRegistry.register(result, (blockX, blockY, blockZ) -> selection.contains(BlockVector3.at(blockX, blockY, blockZ)));
                        }
                    }
                }
            }
        }
        catch (Exception e) {
            ErrorReporter.report(e);
        }
        return result;
    }
}

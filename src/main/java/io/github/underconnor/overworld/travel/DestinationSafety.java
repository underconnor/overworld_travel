package io.github.underconnor.overworld.travel;

import java.util.EnumSet;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;

final class DestinationSafety {
    private static final Set<Material> HAZARDS = EnumSet.of(Material.LAVA, Material.FIRE,
        Material.SOUL_FIRE, Material.CACTUS, Material.MAGMA_BLOCK, Material.CAMPFIRE,
        Material.SOUL_CAMPFIRE, Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW,
        Material.WITHER_ROSE, Material.POINTED_DRIPSTONE, Material.NETHER_PORTAL,
        Material.END_PORTAL, Material.END_GATEWAY);

    static boolean withinWorld(Location p) {
        World world = p.getWorld();
        return world != null && Double.isFinite(p.getX()) && Double.isFinite(p.getY())
            && Double.isFinite(p.getZ()) && Float.isFinite(p.getYaw()) && Float.isFinite(p.getPitch())
            && p.getY() >= world.getMinHeight() && p.getY() + 1.8 < world.getMaxHeight()
            && world.getWorldBorder().isInside(p.clone().add(-0.3, 0, -0.3))
            && world.getWorldBorder().isInside(p.clone().add(0.3, 0, 0.3));
    }

    // Checks the standing player's entire body and real block collision shapes (including slabs).
    static boolean safe(Location p) {
        if (!withinWorld(p)) return false;
        World world = p.getWorld();
        BoundingBox body = new BoundingBox(p.getX()-0.3, p.getY()+0.001, p.getZ()-0.3,
            p.getX()+0.3, p.getY()+1.8, p.getZ()+0.3);
        BoundingBox support = new BoundingBox(p.getX()-0.29, p.getY()-0.05, p.getZ()-0.29,
            p.getX()+0.29, p.getY()+0.001, p.getZ()+0.29);
        if (world.hasCollisionsIn(body) || !world.hasCollisionsIn(support)) return false;
        for (int x = (int)Math.floor(body.getMinX()); x <= (int)Math.floor(body.getMaxX()); x++)
            for (int y = (int)Math.floor(p.getY()-0.05); y <= (int)Math.floor(body.getMaxY()); y++)
                for (int z = (int)Math.floor(body.getMinZ()); z <= (int)Math.floor(body.getMaxZ()); z++)
                    if (HAZARDS.contains(world.getBlockAt(x,y,z).getType())) return false;
        return true;
    }
}

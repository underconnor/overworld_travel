package io.github.underconnor.overworld.travel;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DestinationSafetyTest {
    @Test
    void rejectsMissingWorldInvalidCoordinatesAndOutOfHeightLocations() {
        Terrain terrain = new Terrain();
        assertFalse(DestinationSafety.withinWorld(new Location(null, 0, 64, 0)));
        assertFalse(DestinationSafety.withinWorld(new Location(terrain.world, Double.NaN, 64, 0)));
        assertFalse(DestinationSafety.withinWorld(new Location(terrain.world, 0, Double.POSITIVE_INFINITY, 0)));
        assertFalse(DestinationSafety.withinWorld(new Location(terrain.world, 0, 64, 0, Float.NaN, 0)));
        assertFalse(DestinationSafety.withinWorld(new Location(terrain.world, 0, -65, 0)));
        assertFalse(DestinationSafety.withinWorld(new Location(terrain.world, 0, 319, 0)));
        assertTrue(DestinationSafety.withinWorld(new Location(terrain.world, 0, -64, 0)));
        assertTrue(DestinationSafety.withinWorld(new Location(terrain.world, 0, 318, 0)));
    }

    @Test
    void testsTheWholePlayerWidthAgainstTheWorldBorder() {
        Terrain terrain = new Terrain();
        assertTrue(DestinationSafety.withinWorld(new Location(terrain.world, 99.6, 64, 0)));
        assertFalse(DestinationSafety.withinWorld(new Location(terrain.world, 99.8, 64, 0)));
        assertFalse(DestinationSafety.withinWorld(new Location(terrain.world, 0, 64, -99.8)));
    }

    @Test
    void openSpaceWithSolidGroundIsSafeButAirborneDestinationIsRejected() {
        Terrain terrain = new Terrain();
        Location destination = new Location(terrain.world, 0.5, 64, 0.5);
        assertFalse(DestinationSafety.safe(destination));
        terrain.solid(new BoundingBox(0, 63, 0, 1, 64, 1));
        assertTrue(DestinationSafety.safe(destination));
        assertEquals(64, destination.getY(), "Safety checks must not mutate the requested destination");
    }

    @Test
    void rejectsHeadObstructionAndSideCollisionAcrossBlockEdges() {
        Terrain terrain = new Terrain();
        terrain.solid(new BoundingBox(-2, 63, -2, 2, 64, 2));
        terrain.solid(new BoundingBox(0, 65, 0, 1, 66, 1));
        assertFalse(DestinationSafety.safe(new Location(terrain.world, 0.5, 64, 0.5)));
        terrain.collisions.removeLast();
        terrain.solid(new BoundingBox(1, 64, 0, 2, 65, 1));
        assertFalse(DestinationSafety.safe(new Location(terrain.world, 0.8, 64, 0.5)));
        assertTrue(DestinationSafety.safe(new Location(terrain.world, 0.5, 64, 0.5)));
    }

    @Test
    void standingOnHalfSlabUsesCollisionHeightInsteadOfFullBlockAssumption() {
        Terrain terrain = new Terrain();
        terrain.solid(new BoundingBox(0, 63, 0, 1, 63.5, 1));
        assertTrue(DestinationSafety.safe(new Location(terrain.world, 0.5, 63.5, 0.5)));
        assertFalse(DestinationSafety.safe(new Location(terrain.world, 0.5, 63.25, 0.5)));
    }

    @ParameterizedTest
    @EnumSource(value = Material.class, names = {"LAVA", "FIRE", "SOUL_FIRE", "CACTUS", "MAGMA_BLOCK",
            "CAMPFIRE", "SOUL_CAMPFIRE", "SWEET_BERRY_BUSH", "POWDER_SNOW", "WITHER_ROSE",
            "POINTED_DRIPSTONE", "NETHER_PORTAL", "END_PORTAL", "END_GATEWAY"})
    void rejectsHazardsInTheDestinationAndSupportingBlock(Material hazard) {
        Terrain terrain = new Terrain();
        terrain.solid(new BoundingBox(0, 63, 0, 1, 64, 1));
        Location destination = new Location(terrain.world, 0.5, 64, 0.5);
        for (int y : new int[]{63, 64, 65}) {
            terrain.blocks.clear();
            terrain.blocks.put(new Position(0, y, 0), hazard);
            assertFalse(DestinationSafety.safe(destination), hazard + " at y=" + y);
        }
    }

    private record Position(int x, int y, int z) {}

    /** Small interface fakes keep these geometry tests independent of a server or mock framework. */
    private static final class Terrain {
        final List<BoundingBox> collisions = new ArrayList<>();
        final Map<Position, Material> blocks = new HashMap<>();
        final WorldBorder border = proxy(WorldBorder.class, (instance, method, args) -> switch (method.getName()) {
            case "isInside" -> {
                Location p = (Location) args[0];
                yield Math.abs(p.getX()) < 100 && Math.abs(p.getZ()) < 100;
            }
            default -> throw new UnsupportedOperationException(method.getName());
        });
        final World world = proxy(World.class, (instance, method, args) -> switch (method.getName()) {
            case "getMinHeight" -> -64;
            case "getMaxHeight" -> 320;
            case "getWorldBorder" -> border;
            case "hasCollisionsIn" -> collisions.stream().anyMatch(box -> box.overlaps((BoundingBox) args[0]));
            case "getBlockAt" -> {
                Material type = blocks.getOrDefault(new Position((int) args[0], (int) args[1], (int) args[2]), Material.AIR);
                yield proxy(Block.class, (block, blockMethod, blockArgs) -> {
                    if (blockMethod.getName().equals("getType")) return type;
                    throw new UnsupportedOperationException(blockMethod.getName());
                });
            }
            case "equals" -> instance == args[0];
            case "hashCode" -> System.identityHashCode(instance);
            case "toString" -> "Test world";
            default -> throw new UnsupportedOperationException(method.getName());
        });

        void solid(BoundingBox box) {
            collisions.add(box);
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}

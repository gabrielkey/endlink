package org.endstone.proxy.protocol.block;

import org.jose4j.json.JsonUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which network ids are stairs, which way they face, and what id the same stair has with a corner.
 *
 * <p>1.26.50's {@code minecraft:corner} is derived from the stairs around a block, so a backend that
 * has no such state cannot send it and the proxy has to work it out. That needs two lookups this
 * provides: an id back to the stair it describes, and a stair plus a corner shape forward to an id.
 *
 * <p><b>Facing.</b> Bedrock spells a stair's direction as {@code weirdo_direction}, whose numbering
 * is not the one used anywhere else in the protocol and is documented nowhere Mojang publishes. It is
 * 0 east, 1 west, 2 south, 3 north, taken from AllayMC's {@code EWSN_DIRECTION_4_MAPPER}, which a
 * Bedrock server uses to place stairs. Getting it wrong would not fail loudly — it would put corners
 * on the wrong stairs — so it is named here once and used everywhere rather than repeated.
 *
 * @see StairCornerPass
 */
public final class StairIndex {

    /** {@code weirdo_direction} in its own order. */
    public enum Facing {
        EAST(1, 0),
        WEST(-1, 0),
        SOUTH(0, 1),
        NORTH(0, -1);

        private final int dx;
        private final int dz;

        Facing(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }

        public int dx() {
            return dx;
        }

        public int dz() {
            return dz;
        }

        public Facing opposite() {
            return switch (this) {
                case EAST -> WEST;
                case WEST -> EAST;
                case SOUTH -> NORTH;
                case NORTH -> SOUTH;
            };
        }

        /** Counter-clockwise seen from above, which is what decides left from right. */
        public Facing counterClockwise() {
            return switch (this) {
                case NORTH -> WEST;
                case WEST -> SOUTH;
                case SOUTH -> EAST;
                case EAST -> NORTH;
            };
        }

        public boolean sameAxis(Facing other) {
            return (dx != 0) == (other.dx != 0);
        }

        static Facing ofWeirdoDirection(int value) {
            return switch (value) {
                case 0 -> EAST;
                case 1 -> WEST;
                case 2 -> SOUTH;
                case 3 -> NORTH;
                default -> throw new IllegalArgumentException("weirdo_direction " + value);
            };
        }
    }

    /** The corner shapes 1.26.50 gives a stair, spelled as Mojang spells them. */
    public enum Corner {
        NONE("none"),
        INNER_LEFT("inner_left"),
        INNER_RIGHT("inner_right"),
        OUTER_LEFT("outer_left"),
        OUTER_RIGHT("outer_right");

        private final String value;

        Corner(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    /** A stair as the corner rule needs to see it. */
    public record Stair(String identifier, Facing facing, boolean upsideDown) {
    }

    private final Map<Integer, Stair> byStairId;
    private final Map<Stair, Map<Corner, Integer>> idsByStair;

    private StairIndex(Map<Integer, Stair> byStairId, Map<Stair, Map<Corner, Integer>> idsByStair) {
        this.byStairId = Map.copyOf(byStairId);
        this.idsByStair = Map.copyOf(idsByStair);
    }

    /**
     * Builds the index from the same upgrade resource {@link BlockStateUpgrade} reads, using the
     * group that adds {@code minecraft:corner} — so a version step that stops touching stairs leaves
     * an empty index and this whole pass turns itself off.
     */
    public static StairIndex load(String resource) {
        String json;
        try (InputStream input = StairIndex.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing block state upgrade resource " + resource);
            }
            json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + resource, e);
        }

        Map<String, Object> root;
        try {
            root = JsonUtil.parseJson(json);
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse " + resource, e);
        }

        Map<Integer, Stair> byStairId = new HashMap<>();
        Map<Stair, Map<Corner, Integer>> idsByStair = new HashMap<>();

        for (Object rawGroup : (List<?>) root.get("groups")) {
            Map<?, ?> group = (Map<?, ?>) rawGroup;
            if (!addsCorner(group)) {
                continue;
            }
            for (Object rawName : (List<?>) group.get("blocks")) {
                String identifier = (String) rawName;
                for (int weirdo = 0; weirdo <= 3; weirdo++) {
                    for (boolean upsideDown : new boolean[]{false, true}) {
                        Stair stair = new Stair(identifier, Facing.ofWeirdoDirection(weirdo), upsideDown);
                        Map<Corner, Integer> ids = new HashMap<>();
                        for (Corner corner : Corner.values()) {
                            int id = BedrockBlockStateHash.of(identifier, List.of(
                                    BlockStateValue.of("minecraft:corner", BlockStateValue.Type.STRING, corner.value()),
                                    BlockStateValue.of("upside_down_bit", BlockStateValue.Type.BOOL, upsideDown),
                                    BlockStateValue.of("weirdo_direction", BlockStateValue.Type.INT, weirdo)));
                            ids.put(corner, id);
                        }
                        idsByStair.put(stair, Map.copyOf(ids));
                        // Every corner variant resolves, not only the cornerless one. The chunk pass
                        // only ever meets cornerless stairs, but settling a seam re-runs the rule
                        // over stairs it has already shaped, and those have to read back as the
                        // stairs they are or the second pass would see holes where the first worked.
                        for (int id : ids.values()) {
                            byStairId.put(id, stair);
                        }
                    }
                }
            }
        }
        return new StairIndex(byStairId, idsByStair);
    }

    private static boolean addsCorner(Map<?, ?> group) {
        for (Object rawAdded : (List<?>) group.get("added")) {
            if ("minecraft:corner".equals(((Map<?, ?>) rawAdded).get("name"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The stair a network id describes, or null if it is not a cornerless 1.26.50 stair.
     *
     * <p>Only cornerless ids resolve, and that is the point: this runs after the ids have been
     * carried up from a version with no corner state, so every stair in the chunk is cornerless. A
     * stair that already has a corner came from a 1.26.50 backend and needs nothing done to it.
     */
    public Stair stairAt(int runtimeId) {
        return byStairId.get(runtimeId);
    }

    /** The id for {@code stair} wearing {@code corner}. */
    public int idFor(Stair stair, Corner corner) {
        return idsByStair.get(stair).get(corner);
    }

    /** Whether this index knows any stairs at all. */
    public boolean isEmpty() {
        return byStairId.isEmpty();
    }

    public int size() {
        return byStairId.size();
    }
}

package org.endstone.proxy.protocol.block;

import org.jose4j.json.JsonUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which network ids are fences, panes, bars or trip wire, and what id each has wearing a given set
 * of arms.
 *
 * <p>The counterpart of {@link StairIndex} for 1.26.50's other addition. Where a stair gained one
 * {@code minecraft:corner} string, 58 blocks gained four {@code minecraft:connection_*} booleans,
 * and the same thing follows: a 1.26.45 backend has no such state, so the proxy sends all four
 * false and a fence line renders as a row of separate posts.
 *
 * <p><b>Two lookups, as the corner rule needs two.</b> An id back to the joining block it describes,
 * so the blocks around a position can be read; and a joining block plus four arms forward to an id,
 * so the answer can be written. Both are built by hashing rather than stored, for the reason
 * {@link BlockStateUpgrade} gives: a mistake in the table is then a mistake about Minecraft rather
 * than about arithmetic.
 *
 * <p><b>Every arm arrangement resolves, not only the armless one.</b> The chunk pass only ever meets
 * armless blocks, because they have just been carried up from a version with no such state, but
 * settling a seam re-runs the rule over blocks it has already reached out, and those have to read
 * back as the blocks they are. That is safe because a joining block presents the same face to its
 * neighbours in every one of its own states &mdash; the rule reads a neighbour's family and never
 * its arms &mdash; so running it again cannot drift.
 *
 * <p><b>Why the families are a file.</b> Whether one joining block reaches another is not a property
 * of either alone: a wooden fence reaches a wooden fence but not a nether brick fence, and every
 * pane and bar reaches every other one whatever its colour. Java decides that with block tags and
 * with {@code IronBarsBlock}, and 1.26.50's states exist for parity with Java, so the split is taken
 * from there and written down in {@code blockstate/block-joins-2192.json} rather than guessed from
 * the shape of a block's name at load time.
 *
 * @see BlockJoinPass
 */
public final class BlockJoinIndex {

    /** The sides a joining block can reach out on, and the order the arm bits are in. */
    public enum Side {
        NORTH(0, -1, "minecraft:connection_north"),
        SOUTH(0, 1, "minecraft:connection_south"),
        WEST(-1, 0, "minecraft:connection_west"),
        EAST(1, 0, "minecraft:connection_east");

        private final int dx;
        private final int dz;
        private final String property;

        Side(int dx, int dz, String property) {
            this.dx = dx;
            this.dz = dz;
            this.property = property;
        }

        public int dx() {
            return dx;
        }

        public int dz() {
            return dz;
        }

        public String property() {
            return property;
        }

        public Side opposite() {
            return switch (this) {
                case NORTH -> SOUTH;
                case SOUTH -> NORTH;
                case WEST -> EAST;
                case EAST -> WEST;
            };
        }

        /** The bit this side occupies in an arm pattern. */
        public int bit() {
            return 1 << ordinal();
        }
    }

    /** How many arm arrangements a joining block has: one bit per side. */
    public static final int ARM_PATTERNS = 1 << 4;

    /** No arms at all, which is what a block carried up from 1.26.45 arrives wearing. */
    public static final int NO_ARMS = 0;

    /**
     * A joining block as the rule needs to see it: the family that decides what it reaches, and the
     * states it carries that are none of the rule's business.
     *
     * <p>Trip wire is the only one of the 58 with states of its own &mdash; attached, disarmed,
     * powered and suspended &mdash; and they are carried through untouched, so an armed trip wire is
     * still the trip wire it was.
     */
    public record Joint(String identifier, String family, List<BlockStateValue> otherStates) {
    }

    private final Map<Integer, Joint> byId;
    private final Map<Joint, int[]> idsByJoint;

    private BlockJoinIndex(Map<Integer, Joint> byId, Map<Joint, int[]> idsByJoint) {
        this.byId = Map.copyOf(byId);
        this.idsByJoint = Map.copyOf(idsByJoint);
    }

    /**
     * Builds the index from the upgrade table and the family file together.
     *
     * <p>The upgrade table says which blocks gained the states and what states they already had; the
     * family file says which of them reach which. A block in one and not the other is a disagreement
     * between two files describing the same 58 blocks, and is refused rather than quietly dropped
     * &mdash; a fence missing from the index would leave exactly the defect this exists to fix, and
     * would leave it silently.
     */
    public static BlockJoinIndex load(String upgradeResource, String familyResource) {
        Map<String, Object> upgrade = readJson(upgradeResource);
        Map<String, Object> families = readJson(familyResource);

        Map<String, String> familyOf = new HashMap<>();
        for (Map.Entry<String, Object> entry : asMap(families.get("families")).entrySet()) {
            for (Object identifier : (List<?>) entry.getValue()) {
                familyOf.put((String) identifier, entry.getKey());
            }
        }

        Map<Integer, Joint> byId = new HashMap<>();
        Map<Joint, int[]> idsByJoint = new HashMap<>();
        int joining = 0;

        for (Object rawGroup : (List<?>) upgrade.get("groups")) {
            Map<?, ?> group = (Map<?, ?>) rawGroup;
            if (!addsConnections(group)) {
                continue;
            }
            for (Object rawName : (List<?>) group.get("blocks")) {
                String identifier = (String) rawName;
                String family = familyOf.get(identifier);
                if (family == null) {
                    throw new IllegalStateException(
                            familyResource + " does not say what " + identifier + " joins to");
                }
                joining++;
                for (List<BlockStateValue> other : otherStateCombinations(group.get("oldStates"))) {
                    Joint joint = new Joint(identifier, family, List.copyOf(other));
                    int[] ids = new int[ARM_PATTERNS];
                    for (int arms = 0; arms < ARM_PATTERNS; arms++) {
                        List<BlockStateValue> states = new ArrayList<>(other);
                        for (Side side : Side.values()) {
                            states.add(BlockStateValue.of(side.property(), BlockStateValue.Type.BOOL,
                                    (arms & side.bit()) != 0));
                        }
                        ids[arms] = BedrockBlockStateHash.of(identifier, states);
                        byId.put(ids[arms], joint);
                    }
                    idsByJoint.put(joint, ids);
                }
            }
        }

        if (joining != familyOf.size()) {
            throw new IllegalStateException(familyResource + " names " + familyOf.size()
                    + " joining blocks but " + upgradeResource + " has " + joining);
        }
        return new BlockJoinIndex(byId, idsByJoint);
    }

    /** The joining block a network id describes, or null if it is not one. */
    public Joint jointAt(int runtimeId) {
        return byId.get(runtimeId);
    }

    /** The id for {@code joint} reaching out on the sides named by {@code arms}. */
    public int idFor(Joint joint, int arms) {
        return idsByJoint.get(joint)[arms];
    }

    /**
     * Whether a joining block of {@code family} reaches out to a neighbour of {@code neighbourFamily},
     * which is null when the neighbour is not a joining block at all.
     *
     * <p>Java's rule, which these states exist for parity with, has three parts: a fence reaches a
     * fence of the same woodiness, a pane or bar reaches any pane or bar, and either reaches a block
     * whose face beside it is solid. The families answer the first two exactly. The third needs to
     * know whether an arbitrary block is solid, which this tree has no table for, so it is not
     * answered here and a fence beside a wall still stops short of it.
     */
    public static boolean reaches(String family, String neighbourFamily) {
        return family.equals(neighbourFamily);
    }

    /** Whether this index knows any joining blocks at all. */
    public boolean isEmpty() {
        return byId.isEmpty();
    }

    public int size() {
        return byId.size();
    }

    private static boolean addsConnections(Map<?, ?> group) {
        for (Object rawAdded : (List<?>) group.get("added")) {
            if (Side.NORTH.property().equals(((Map<?, ?>) rawAdded).get("name"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every combination of the states these blocks already had, which is one empty combination for
     * all of them but trip wire.
     */
    private static List<List<BlockStateValue>> otherStateCombinations(Object rawOldStates) {
        List<List<BlockStateValue>> result = new ArrayList<>();
        result.add(List.of());
        for (Object rawProperty : rawOldStates == null ? List.of() : (List<?>) rawOldStates) {
            Map<?, ?> property = (Map<?, ?>) rawProperty;
            String name = (String) property.get("name");
            BlockStateValue.Type type = BlockStateValue.Type.fromMetadata((String) property.get("type"));
            List<?> values = (List<?>) property.get("values");
            List<List<BlockStateValue>> expanded = new ArrayList<>(result.size() * values.size());
            for (List<BlockStateValue> prefix : result) {
                for (Object value : values) {
                    List<BlockStateValue> next = new ArrayList<>(prefix);
                    next.add(BlockStateValue.of(name, type, value));
                    expanded.add(next);
                }
            }
            result = expanded;
        }
        return result;
    }

    private static Map<String, Object> readJson(String resource) {
        String json;
        try (InputStream input = BlockJoinIndex.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing block join resource " + resource);
            }
            json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + resource, e);
        }
        try {
            return JsonUtil.parseJson(json);
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse " + resource, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        return (Map<String, Object>) raw;
    }
}

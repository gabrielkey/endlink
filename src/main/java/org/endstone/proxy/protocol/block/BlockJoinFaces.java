package org.endstone.proxy.protocol.block;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Which blocks a fence, pane, bar or wall reaches out to, and from which side.
 *
 * <p>{@link BlockJoinIndex} answers the easy half of Java's rule &mdash; a fence reaches a fence of
 * the same woodiness, a pane or bar reaches any other pane or bar &mdash; because both blocks are
 * ones the proxy already knows. This is the hard half: <em>a fence also reaches a block whose face
 * beside it is solid</em>. There is no way to work that out from the upgrade table, because it is a
 * fact about every block in the game rather than about the 58 that changed, and the proxy never sees
 * a block palette at all &mdash; only hashed ids.
 *
 * <p>Without it a fence line renders correctly in the open and stops dead where it meets a wall, and
 * a glass pane, which is nearly always set into a window frame and next to no other pane, connects
 * to nothing whatsoever. That is exactly the difference that was seen in game: fences fixed, glass
 * not.
 *
 * <p><b>The facts are Java's, and they were run rather than restated.</b> 1.26.50's
 * {@code minecraft:connection_*} states exist for parity with Java Edition, so Java's own
 * {@code connectsTo} and {@code attachsTo} are the rule. The generated table this loads is those
 * methods' answers, taken from the official server against its own registry for every block state on
 * every side, and keyed by Bedrock network id through a Bedrock-to-Java block-state mapping. Both
 * generated artifacts are Apache-2.0, from {@code viaendlink-next}, and their own inputs are Mojang's
 * server and block report, CloudburstMC/Data (Apache-2.0) and GeyserMC/mappings (MIT). Nothing
 * GPL-licensed is involved at any step, which the licence separation in {@code WORKSPACE.md}
 * requires. The resource header says all of this again where a reader of the data will find it.
 *
 * <p><b>Ids are the older version's, and that is deliberate.</b> The table is keyed by the id a
 * 1.26.40&ndash;45 backend sends. The join pass runs after those ids have been carried up to 1.26.50,
 * so a lookup goes back through {@link BlockStateUpgrade#toOlder} first &mdash; which is the identity
 * for every block 1.26.50 did not change, and for the 139 it did carries a cornered stair or an armed
 * fence back to the block it is. One table then answers for both numberings, and it does not have to
 * be regenerated when only the joining blocks' own states change.
 *
 * @see BlockJoinPass
 */
public final class BlockJoinFaces {

    /** The joining kinds, numbered as the generated table numbers them. */
    public static final int KIND_WOODEN_FENCE = 0;
    public static final int KIND_PANE = 1;
    public static final int KIND_WALL = 2;
    public static final int KIND_NETHER_BRICK_FENCE = 3;

    private static final int KINDS = 4;

    /** Guards against a corrupt resource asking for an absurd allocation. */
    private static final int MAX_FACES = 1 << 20;

    private final Map<Integer, Integer> byId;
    private final BlockStateUpgrade upgrade;

    private BlockJoinFaces(Map<Integer, Integer> byId, BlockStateUpgrade upgrade) {
        this.byId = Map.copyOf(byId);
        this.upgrade = upgrade;
    }

    /**
     * The kind a {@link BlockJoinIndex.Joint} family asks as, or -1 for one Java has no answer for.
     *
     * <p>Trip wire is the -1: Java joins trip wire to trip wire and to a hook facing it, and neither
     * is a question about whether a block is solid, so it is left to the family rule alone.
     */
    public static int kindOf(String family) {
        return switch (family) {
            case "wooden_fence" -> KIND_WOODEN_FENCE;
            case "pane" -> KIND_PANE;
            case "nether_brick_fence" -> KIND_NETHER_BRICK_FENCE;
            default -> -1;
        };
    }

    /** Loads the generated table, resolving ids through {@code upgrade} at lookup time. */
    public static BlockJoinFaces load(String resource, BlockStateUpgrade upgrade) {
        Map<Integer, Integer> byId = new HashMap<>();
        int declared = -1;
        try (InputStream input = BlockJoinFaces.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing block join face resource " + resource);
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                int space = line.indexOf(' ');
                if (space <= 0) {
                    throw new IllegalStateException(
                            resource + " line " + lineNumber + " is not a directive");
                }
                String directive = line.substring(0, space);
                String value = line.substring(space + 1);
                switch (directive) {
                    case "bedrock", "kinds" -> {
                        // Recorded in the resource for a reader; nothing here varies on them.
                    }
                    case "faces" -> {
                        declared = Integer.parseInt(value);
                        if (declared < 0 || declared > MAX_FACES) {
                            throw new IllegalStateException(
                                    resource + " declares " + declared + " faces");
                        }
                    }
                    case "face" -> {
                        int gap = value.indexOf(' ');
                        if (gap <= 0) {
                            throw new IllegalStateException(
                                    resource + " line " + lineNumber + " is a malformed face");
                        }
                        byId.put(Integer.parseInt(value.substring(0, gap)),
                                Integer.parseInt(value.substring(gap + 1), 16));
                    }
                    default -> throw new IllegalStateException(
                            resource + " line " + lineNumber + " says '" + directive + "'");
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + resource, e);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(resource + " holds a number that is not one", e);
        }

        if (declared >= 0 && declared != byId.size()) {
            // A truncated table would not fail, it would quietly stop connecting things, so the
            // count it declares is checked rather than trusted.
            throw new IllegalStateException(resource + " declares " + declared
                    + " faces and carries " + byId.size());
        }
        return new BlockJoinFaces(byId, upgrade);
    }

    /**
     * Whether a joining block of {@code kind} reaches the block {@code runtimeId} standing on its
     * {@code side}.
     *
     * <p>{@code side} is named from the joining block: a neighbour to its north is asked about with
     * {@link BlockJoinIndex.Side#NORTH}. The bit layout is the generated table's, which is the layout
     * the extraction wrote Java's answers into, so nothing here reinterprets the rule.
     */
    public boolean reaches(int kind, int runtimeId, BlockJoinIndex.Side side) {
        if (kind < 0 || kind >= KINDS) {
            return false;
        }
        Integer word = byId.get(upgrade.toOlder(runtimeId));
        return word != null && (word & (1 << (side.ordinal() * KINDS + kind))) != 0;
    }

    /** How many block states anything reaches out to. */
    public int size() {
        return byId.size();
    }

    public boolean isEmpty() {
        return byId.isEmpty();
    }
}

package org.endstone.proxy.protocol.block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settles the stair corners along a chunk boundary, once the chunk on the other side turns up.
 *
 * <p>{@link StairCornerPass} resolves a column against itself, which is right for all but its outer
 * ring: a stair on the chunk's own edge has a neighbour in the next column, and that column may not
 * have been sent yet. Left there, the result is a seam of squared-off corners every sixteen blocks
 * along every chunk boundary, running the whole length of a build — which is exactly what it looks
 * like in game, and what distinguishes it from a rule that is simply wrong.
 *
 * <p>The approach is the one {@code viaendlink-next} already uses for the same problem on its Java
 * side: remember each column as it is sent, and when its neighbour arrives, run the rule again over
 * the shared edge from <em>both</em> sides and send the blocks that moved as ordinary block updates.
 * The column that arrives second could have been resolved correctly in one go, but the first was
 * already sent, so both edges are settled the same way and there is one mechanism rather than two.
 *
 * <p><b>What makes this safe is that the rule cannot drift.</b> A corner is decided by the facing and
 * half of the stairs around a block and never by their corners, so running it again over stairs that
 * have already been shaped gives the same answer. That is why only the stairs need remembering, not
 * the states the column arrived with, and why a column can be settled against each of its four
 * neighbours independently and in any order.
 *
 * <p>Only stairs are remembered, and only their facing, half and the id they were sent as — a few
 * hundred entries for a chunk that has any, nothing at all for the great majority that do not. A
 * column is forgotten once all four of its seams are settled, and the oldest are dropped beyond
 * {@link #REMEMBERED_COLUMNS} so a player walking in a straight line cannot grow this without bound.
 */
public final class StairSeams {

    /**
     * How many columns to keep. A column is normally forgotten as soon as its fourth neighbour
     * arrives; this is the bound for the ones at the edge of the view, whose fourth neighbour never
     * comes. Matches the figure viaendlink-next settled on for the same job.
     */
    private static final int REMEMBERED_COLUMNS = 96;

    private static final int WIDTH = 16;

    /** A block that has to be corrected, at absolute world coordinates. */
    public record Correction(int x, int y, int z, int runtimeId) {
    }

    /** One remembered column: its stairs, and which of its four seams have been settled. */
    private static final class Column {
        private final Map<Integer, StairIndex.Stair> stairs;
        private final Map<Integer, Integer> sentIds;
        private int settledSides;

        Column(Map<Integer, StairIndex.Stair> stairs, Map<Integer, Integer> sentIds) {
            this.stairs = stairs;
            this.sentIds = sentIds;
        }
    }

    /** The four shared edges, as offsets to the neighbouring column. */
    private enum Side {
        WEST(-1, 0),
        EAST(1, 0),
        NORTH(0, -1),
        SOUTH(0, 1);

        private final int dx;
        private final int dz;

        Side(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }

        Side opposite() {
            return switch (this) {
                case WEST -> EAST;
                case EAST -> WEST;
                case NORTH -> SOUTH;
                case SOUTH -> NORTH;
            };
        }
    }

    private final StairIndex index;
    private final LinkedHashMap<Long, Column> columns = new LinkedHashMap<>();

    public StairSeams(StairIndex index) {
        this.index = index;
    }

    /** Forgets everything, for a backend switch or a dimension change. */
    public synchronized void reset() {
        columns.clear();
    }

    /**
     * Remembers a column that has just been sent and settles it against the neighbours already here.
     *
     * @param dimension which world this column belongs to; columns of different dimensions are
     *                  never neighbours, however close their coordinates look
     * @param collector the stairs this column was sent with, as gathered by {@link StairCornerPass}
     * @return every block that has to be corrected, on either side of any seam this completes;
     * empty when no neighbour is known yet, which is the common case for the first column of a view
     */
    public synchronized List<Correction> settle(
            int chunkX, int chunkZ, int dimension, int worldMinY, Collector collector) {
        if (index.isEmpty()) {
            return List.of();
        }
        Column own = new Column(collector.stairs, collector.sentIds);
        columns.put(key(chunkX, chunkZ, dimension), own);

        List<Correction> corrections = new ArrayList<>();
        for (Side side : Side.values()) {
            int neighbourX = chunkX + side.dx;
            int neighbourZ = chunkZ + side.dz;
            Column neighbour = columns.get(key(neighbourX, neighbourZ, dimension));
            if (neighbour == null) {
                continue;
            }
            own.settledSides |= 1 << side.ordinal();
            neighbour.settledSides |= 1 << side.opposite().ordinal();

            // Both edges: the column that arrived first was sent with nothing to reach out to, and
            // the one that arrived second was resolved before this neighbour was known.
            resolveEdge(own, neighbour, side, chunkX, chunkZ, worldMinY, corrections);
            resolveEdge(neighbour, own, side.opposite(), neighbourX, neighbourZ, worldMinY, corrections);

            if (neighbour.settledSides == 0b1111) {
                columns.remove(key(neighbourX, neighbourZ, dimension));
            }
        }
        if (own.settledSides == 0b1111) {
            columns.remove(key(chunkX, chunkZ, dimension));
        }
        while (columns.size() > REMEMBERED_COLUMNS) {
            columns.remove(columns.keySet().iterator().next());
        }
        return corrections;
    }

    /**
     * Runs the corner rule again over one edge of {@code column}, reading across into
     * {@code beyond}, and records every stair whose id is no longer what was sent.
     */
    private void resolveEdge(Column column, Column beyond, Side side,
                             int chunkX, int chunkZ, int worldMinY, List<Correction> corrections) {
        StairLookup around = (x, y, z) -> {
            if (x >= 0 && x < WIDTH && z >= 0 && z < WIDTH) {
                return column.stairs.get(position(x, y, z));
            }
            // One step past the edge being settled lands in the column beyond it, at the same place
            // measured from that column's own corner.
            int acrossX = x - side.dx * WIDTH;
            int acrossZ = z - side.dz * WIDTH;
            if (acrossX < 0 || acrossX >= WIDTH || acrossZ < 0 || acrossZ >= WIDTH) {
                // A diagonal step, or a step over one of the three edges this is not settling.
                // Neither is a neighbour of this edge, and no rule here takes a diagonal anyway.
                return null;
            }
            return beyond.stairs.get(position(acrossX, y, acrossZ));
        };

        int edgeX = side == Side.WEST ? 0 : side == Side.EAST ? WIDTH - 1 : -1;
        int edgeZ = side == Side.NORTH ? 0 : side == Side.SOUTH ? WIDTH - 1 : -1;
        for (Map.Entry<Integer, StairIndex.Stair> entry : column.stairs.entrySet()) {
            int packed = entry.getKey();
            int x = unpackX(packed);
            int z = unpackZ(packed);
            if ((edgeX >= 0 && x != edgeX) || (edgeZ >= 0 && z != edgeZ)) {
                continue;
            }
            int y = unpackY(packed);
            StairIndex.Stair stair = entry.getValue();
            int settled = index.idFor(stair, StairCornerPass.cornerOf(around, stair, x, y, z));
            Integer sent = column.sentIds.get(packed);
            if (sent != null && settled != sent) {
                column.sentIds.put(packed, settled);
                corrections.add(new Correction(
                        chunkX * WIDTH + x, worldMinY + y, chunkZ * WIDTH + z, settled));
            }
        }
    }

    /** Gathers the stairs of one column as {@link StairCornerPass} finds them. */
    public static final class Collector implements StairCornerPass.StairSink {
        private final Map<Integer, StairIndex.Stair> stairs = new HashMap<>();
        private final Map<Integer, Integer> sentIds = new HashMap<>();

        @Override
        public void accept(int x, int y, int z, StairIndex.Stair stair, int sentId) {
            int packed = position(x, y, z);
            stairs.put(packed, stair);
            sentIds.put(packed, sentId);
        }

        public boolean isEmpty() {
            return stairs.isEmpty();
        }

        public int size() {
            return stairs.size();
        }
    }

    /** Column-relative, with y counted from the bottom of the column rather than from the world's. */
    private static int position(int x, int y, int z) {
        return (y << 8) | (x << 4) | z;
    }

    private static int unpackX(int packed) {
        return (packed >> 4) & 0xF;
    }

    private static int unpackZ(int packed) {
        return packed & 0xF;
    }

    private static int unpackY(int packed) {
        return packed >>> 8;
    }

    /**
     * A column is identified by its dimension as well as its coordinates: chunk (0, 0) of the Nether
     * is not beside chunk (1, 0) of the Overworld, and settling one against the other would put
     * corners on stairs from a world the player has left.
     */
    private static long key(int chunkX, int chunkZ, int dimension) {
        return ((long) chunkX << 34) ^ ((long) (chunkZ & 0x3FFFFFFF) << 4) ^ (dimension & 0xF);
    }
}

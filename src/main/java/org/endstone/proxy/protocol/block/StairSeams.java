package org.endstone.proxy.protocol.block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settles the stair corners along a chunk boundary, once the chunk on the other side turns up.
 *
 * <p>{@link StairCornerPass} resolves a sub-chunk against itself, which is right for all but its
 * outer ring: a stair on the edge has a neighbour in the next chunk along, and that chunk may not
 * have been sent yet. Left there, the result is a line of squared-off corners every sixteen blocks
 * along every chunk boundary, running the whole length of a build.
 *
 * <p>The approach is the one {@code viaendlink-next} already uses for the same problem on its Java
 * side: remember each piece of world as it is sent, and when its neighbour arrives, run the rule
 * again over the shared edge from <em>both</em> sides and send the blocks that moved as ordinary
 * block updates. The piece that arrives second could have been resolved correctly in one go, but the
 * first was already sent, so both edges are settled the same way and there is one mechanism.
 *
 * <p><b>The unit is a sub-chunk, not a column.</b> That is not a detail: modern Bedrock servers do
 * not put blocks in {@code LevelChunkPacket} at all, they answer sub-chunk requests with
 * {@code SubChunkPacket}, one 16&times;16&times;16 piece at a time. A seam tracker that thought in
 * whole columns would simply never see a block on such a server. Keying by sub-chunk fits both
 * deliveries, and costs nothing, because the corner rule only ever looks sideways: two pieces are
 * neighbours when they share a height and their chunks are adjacent.
 *
 * <p><b>What makes this safe is that the rule cannot drift.</b> A corner is decided by the facing and
 * half of the stairs around a block and never by their corners, so running it again over stairs that
 * have already been shaped gives the same answer. That is why only the stairs need remembering, not
 * the blocks a piece arrived with, and why the four seams of a piece can be settled independently
 * and in any order.
 *
 * <p>Only pieces that actually contain a stair are remembered, which is a small minority of a world.
 * An absent neighbour and a neighbour with no stairs in it answer the rule identically, so nothing is
 * lost by forgetting the empty ones. A piece is dropped once all four of its seams are settled, and
 * the oldest go beyond {@link #REMEMBERED_PIECES} so a player walking in a straight line cannot grow
 * this without bound.
 */
public final class StairSeams {

    /**
     * How many sub-chunks with stairs in them to keep. A piece is normally forgotten as soon as its
     * fourth neighbour arrives; this is the bound for the ones at the edge of the view, whose fourth
     * neighbour never comes.
     */
    private static final int REMEMBERED_PIECES = 2048;

    private static final int WIDTH = 16;

    /** A block that has to be corrected, at absolute world coordinates. */
    public record Correction(int x, int y, int z, int runtimeId) {
    }

    /** Where a sub-chunk sits: its chunk, its height in sub-chunks, and which world it is in. */
    public record Piece(int dimension, int chunkX, int subChunkY, int chunkZ) {
    }

    private static final class Cell {
        private final Map<Integer, StairIndex.Stair> stairs;
        private final Map<Integer, Integer> sentIds;
        private int settledSides;

        Cell(Map<Integer, StairIndex.Stair> stairs, Map<Integer, Integer> sentIds) {
            this.stairs = stairs;
            this.sentIds = sentIds;
        }
    }

    /** The four shared edges, as offsets to the neighbouring chunk. */
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
    private final LinkedHashMap<Piece, Cell> pieces = new LinkedHashMap<>();
    private long remembered;
    private long corrected;

    public StairSeams(StairIndex index) {
        this.index = index;
    }

    /** Forgets everything, for a new world or a backend switch. */
    public synchronized void reset() {
        pieces.clear();
    }

    /** How many sub-chunks with stairs have been seen, for a session to report once. */
    public synchronized long remembered() {
        return remembered;
    }

    /** How many blocks have been corrected across a seam, for a session to report once. */
    public synchronized long corrected() {
        return corrected;
    }

    /**
     * Remembers a sub-chunk that has just been sent and settles it against the neighbours already
     * here.
     *
     * @param collector the stairs this piece was sent with, as gathered by {@link StairCornerPass}
     * @return every block that has to be corrected, on either side of any seam this completes
     */
    public synchronized List<Correction> settle(Piece piece, Collector collector) {
        if (index.isEmpty() || collector.isEmpty()) {
            // A piece with no stairs answers the rule exactly as an absent one does, so there is
            // nothing to remember and nothing any neighbour could learn from it.
            return List.of();
        }
        remembered++;
        Cell own = new Cell(collector.stairs, collector.sentIds);
        pieces.put(piece, own);

        List<Correction> corrections = new ArrayList<>();
        for (Side side : Side.values()) {
            Piece beside = new Piece(piece.dimension(), piece.chunkX() + side.dx,
                    piece.subChunkY(), piece.chunkZ() + side.dz);
            Cell neighbour = pieces.get(beside);
            if (neighbour == null) {
                continue;
            }
            own.settledSides |= 1 << side.ordinal();
            neighbour.settledSides |= 1 << side.opposite().ordinal();

            // Both edges: the piece that arrived first was sent with nothing to reach out to, and the
            // one that arrived second was resolved before this neighbour was known.
            resolveEdge(own, neighbour, side, piece, corrections);
            resolveEdge(neighbour, own, side.opposite(), beside, corrections);

            if (neighbour.settledSides == 0b1111) {
                pieces.remove(beside);
            }
        }
        if (own.settledSides == 0b1111) {
            pieces.remove(piece);
        }
        while (pieces.size() > REMEMBERED_PIECES) {
            pieces.remove(pieces.keySet().iterator().next());
        }
        corrected += corrections.size();
        return corrections;
    }

    /**
     * Runs the corner rule again over one edge of {@code cell}, reading across into {@code beyond},
     * and records every stair whose id is no longer the one that was sent.
     */
    private void resolveEdge(Cell cell, Cell beyond, Side side, Piece at, List<Correction> corrections) {
        StairLookup around = (x, y, z) -> {
            if (x >= 0 && x < WIDTH && z >= 0 && z < WIDTH) {
                return cell.stairs.get(position(x, y, z));
            }
            // One step past the edge being settled lands in the piece beyond it, at the same place
            // measured from that piece's own corner.
            int acrossX = x - side.dx * WIDTH;
            int acrossZ = z - side.dz * WIDTH;
            if (acrossX < 0 || acrossX >= WIDTH || acrossZ < 0 || acrossZ >= WIDTH) {
                // A diagonal step, or a step over one of the three edges this is not settling.
                // Neither is a neighbour of this edge, and the rule takes no diagonals anyway.
                return null;
            }
            return beyond.stairs.get(position(acrossX, y, acrossZ));
        };

        int edgeX = side == Side.WEST ? 0 : side == Side.EAST ? WIDTH - 1 : -1;
        int edgeZ = side == Side.NORTH ? 0 : side == Side.SOUTH ? WIDTH - 1 : -1;
        for (Map.Entry<Integer, StairIndex.Stair> entry : cell.stairs.entrySet()) {
            int packed = entry.getKey();
            int x = unpackX(packed);
            int z = unpackZ(packed);
            if ((edgeX >= 0 && x != edgeX) || (edgeZ >= 0 && z != edgeZ)) {
                continue;
            }
            int y = unpackY(packed);
            StairIndex.Stair stair = entry.getValue();
            int settled = index.idFor(stair, StairCornerPass.cornerOf(around, stair, x, y, z));
            Integer sent = cell.sentIds.get(packed);
            if (sent != null && settled != sent) {
                cell.sentIds.put(packed, settled);
                corrections.add(new Correction(
                        at.chunkX() * WIDTH + x,
                        at.subChunkY() * WIDTH + y,
                        at.chunkZ() * WIDTH + z,
                        settled));
            }
        }
    }

    /** Gathers the stairs of one sub-chunk as {@link StairCornerPass} finds them. */
    public static final class Collector implements StairCornerPass.StairSink {
        private final Map<Integer, StairIndex.Stair> stairs = new HashMap<>();
        private final Map<Integer, Integer> sentIds = new HashMap<>();

        @Override
        public void accept(int x, int y, int z, StairIndex.Stair stair, int sentId) {
            int packed = position(x, y & 0xF, z);
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

    /** Sub-chunk-relative, all three coordinates 0..15. */
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
        return (packed >> 8) & 0xF;
    }
}

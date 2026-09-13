package org.endstone.proxy.protocol.block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settles the arms of a fence, pane or bar along a chunk boundary, once the chunk on the other side
 * turns up.
 *
 * <p>{@link StairSeams} for the other 1.26.50 rule, and everything its documentation says about why
 * this is needed applies here unchanged: a joining block on a sub-chunk's outer ring reaches into a
 * piece that may not have been sent yet, so {@link BlockJoinPass} leaves it armless, and the result
 * without this is a break in every fence line and pane wall every sixteen blocks.
 *
 * <p>The difference is that this rule is even safer to run twice. A stair corner is decided by the
 * facing and half of the stairs around it and never by their corners; a fence's arm is decided by
 * its neighbour's <em>family</em> and never by anything that changes, so a block already reached out
 * once presents exactly the same face the second time. Nothing here can drift, in any order, from
 * either side of a seam.
 */
public final class BlockJoinSeams {

    /**
     * How many sub-chunks with joining blocks in them to keep. A piece is normally forgotten as soon
     * as its fourth neighbour arrives; this is the bound for the ones at the edge of the view, whose
     * fourth neighbour never comes.
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
        private final Map<Integer, BlockJoinIndex.Joint> joints;
        private final Map<Integer, Integer> sentIds;
        private int settledSides;

        Cell(Map<Integer, BlockJoinIndex.Joint> joints, Map<Integer, Integer> sentIds) {
            this.joints = joints;
            this.sentIds = sentIds;
        }
    }

    /** The four shared edges, as offsets to the neighbouring chunk. */
    private enum Edge {
        WEST(-1, 0),
        EAST(1, 0),
        NORTH(0, -1),
        SOUTH(0, 1);

        private final int dx;
        private final int dz;

        Edge(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }

        Edge opposite() {
            return switch (this) {
                case WEST -> EAST;
                case EAST -> WEST;
                case NORTH -> SOUTH;
                case SOUTH -> NORTH;
            };
        }
    }

    private final BlockJoinIndex index;
    private final LinkedHashMap<Piece, Cell> pieces = new LinkedHashMap<>();
    private long remembered;
    private long corrected;

    public BlockJoinSeams(BlockJoinIndex index) {
        this.index = index;
    }

    /** Forgets everything, for a new world or a backend switch. */
    public synchronized void reset() {
        pieces.clear();
    }

    /** How many sub-chunks with joining blocks have been seen, for a session to report once. */
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
     * @param collector the joining blocks this piece was sent with, as gathered by {@link BlockJoinPass}
     * @return every block that has to be corrected, on either side of any seam this completes
     */
    public synchronized List<Correction> settle(Piece piece, Collector collector) {
        if (index.isEmpty() || collector.isEmpty()) {
            // A piece with nothing joining in it answers the rule exactly as an absent one does, so
            // there is nothing to remember and nothing any neighbour could learn from it.
            return List.of();
        }
        remembered++;
        Cell own = new Cell(collector.joints, collector.sentIds);
        pieces.put(piece, own);

        List<Correction> corrections = new ArrayList<>();
        for (Edge edge : Edge.values()) {
            Piece beside = new Piece(piece.dimension(), piece.chunkX() + edge.dx,
                    piece.subChunkY(), piece.chunkZ() + edge.dz);
            Cell neighbour = pieces.get(beside);
            if (neighbour == null) {
                continue;
            }
            own.settledSides |= 1 << edge.ordinal();
            neighbour.settledSides |= 1 << edge.opposite().ordinal();

            // Both edges: the piece that arrived first was sent with nothing to reach out to, and the
            // one that arrived second was resolved before this neighbour was known.
            resolveEdge(own, neighbour, edge, piece, corrections);
            resolveEdge(neighbour, own, edge.opposite(), beside, corrections);

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
     * Runs the join rule again over one edge of {@code cell}, reading across into {@code beyond}, and
     * records every block whose id is no longer the one that was sent.
     */
    private void resolveEdge(Cell cell, Cell beyond, Edge edge, Piece at, List<Correction> corrections) {
        JoinLookup around = (x, y, z) -> {
            if (x >= 0 && x < WIDTH && z >= 0 && z < WIDTH) {
                return cell.joints.get(position(x, y, z));
            }
            // One step past the edge being settled lands in the piece beyond it, at the same place
            // measured from that piece's own corner.
            int acrossX = x - edge.dx * WIDTH;
            int acrossZ = z - edge.dz * WIDTH;
            if (acrossX < 0 || acrossX >= WIDTH || acrossZ < 0 || acrossZ >= WIDTH) {
                // A diagonal step, or a step over one of the three edges this is not settling.
                // Neither is a neighbour of this edge, and the rule takes no diagonals anyway.
                return null;
            }
            return beyond.joints.get(position(acrossX, y, acrossZ));
        };

        int edgeX = edge == Edge.WEST ? 0 : edge == Edge.EAST ? WIDTH - 1 : -1;
        int edgeZ = edge == Edge.NORTH ? 0 : edge == Edge.SOUTH ? WIDTH - 1 : -1;
        for (Map.Entry<Integer, BlockJoinIndex.Joint> entry : cell.joints.entrySet()) {
            int packed = entry.getKey();
            int x = unpackX(packed);
            int z = unpackZ(packed);
            if ((edgeX >= 0 && x != edgeX) || (edgeZ >= 0 && z != edgeZ)) {
                continue;
            }
            int y = unpackY(packed);
            BlockJoinIndex.Joint joint = entry.getValue();
            int settled = index.idFor(joint, BlockJoinPass.armsOf(around, joint, x, y, z));
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

    /** Gathers the joining blocks of one sub-chunk as {@link BlockJoinPass} finds them. */
    public static final class Collector implements BlockJoinPass.JointSink {
        private final Map<Integer, BlockJoinIndex.Joint> joints = new HashMap<>();
        private final Map<Integer, Integer> sentIds = new HashMap<>();

        @Override
        public void accept(int x, int y, int z, BlockJoinIndex.Joint joint, int sentId) {
            int packed = position(x, y & 0xF, z);
            joints.put(packed, joint);
            sentIds.put(packed, sentId);
        }

        public boolean isEmpty() {
            return joints.isEmpty();
        }

        public int size() {
            return joints.size();
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

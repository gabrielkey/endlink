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
 * <p>The difference is what a seam is allowed to change. A stair's corner is recomputed outright,
 * because every stair the rule consults is remembered. A joining block also reaches out to solid
 * blocks, and those are <em>not</em> remembered, so recomputing would take arms away that the pass
 * gave for a wall it could see and this cannot. So a seam only ever adds the one arm that crosses
 * it, which is the only thing the arrival of a neighbour can teach and cannot drift in any order.
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
     * Reaches the joining blocks along one edge of {@code cell} across into {@code beyond}, and
     * records every one whose id is no longer the one that was sent.
     *
     * <p><b>This only ever adds an arm.</b> It would be natural to re-run the whole rule here, and
     * that is what this did while the rule was only about other joining blocks &mdash; those are all
     * remembered, so a recomputation agreed with the original. It stopped being safe the moment a
     * block could also reach out to a solid neighbour: what is remembered of a piece is its joining
     * blocks, not the stone beside them, so a recomputation would find no wall where the pass had
     * found one and would take that arm away again. Adding the one arm that crosses the seam cannot
     * do that, and is all a seam can ever teach: before the neighbour arrived, the answer on that
     * side was "nothing there", and nothing that arrives later turns an arm off.
     *
     * <p><b>What this still does not do</b> is reach a block on the edge out to a <em>solid</em>
     * block in the next chunk, because that piece's ordinary blocks are not remembered &mdash; only
     * its joining ones. A pane wall crossing a chunk boundary therefore joins pane to pane across it,
     * but a pane at the very end of a run still stops short of the stone in the chunk beyond.
     */
    private void resolveEdge(Cell cell, Cell beyond, Edge edge, Piece at, List<Correction> corrections) {
        BlockJoinIndex.Side across = switch (edge) {
            case WEST -> BlockJoinIndex.Side.WEST;
            case EAST -> BlockJoinIndex.Side.EAST;
            case NORTH -> BlockJoinIndex.Side.NORTH;
            case SOUTH -> BlockJoinIndex.Side.SOUTH;
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
            BlockJoinIndex.Joint joint = entry.getValue();

            // One step past the edge lands in the piece beyond it, at the same place measured from
            // that piece's own corner.
            int acrossX = x + across.dx() - edge.dx * WIDTH;
            int acrossZ = z + across.dz() - edge.dz * WIDTH;
            BlockJoinIndex.Joint neighbour =
                    beyond.joints.get(position(acrossX, unpackY(packed), acrossZ));
            if (neighbour == null || !BlockJoinIndex.reaches(joint.family(), neighbour.family())) {
                continue;
            }

            Integer sent = cell.sentIds.get(packed);
            if (sent == null) {
                continue;
            }
            int settled = index.idFor(joint, index.armsOf(joint, sent) | across.bit());
            if (settled != sent) {
                cell.sentIds.put(packed, settled);
                corrections.add(new Correction(
                        at.chunkX() * WIDTH + x,
                        at.subChunkY() * WIDTH + unpackY(packed),
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

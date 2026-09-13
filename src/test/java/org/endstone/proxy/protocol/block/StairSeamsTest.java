package org.endstone.proxy.protocol.block;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.endstone.proxy.protocol.block.StairIndex.Corner;
import static org.endstone.proxy.protocol.block.StairIndex.Facing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chunk seam: a stair on a sub-chunk's edge, whose neighbour lives in the next chunk along.
 *
 * <p>{@link StairCornerPass} leaves those straight because the piece beyond has not arrived, which
 * is a line of squared-off corners along every chunk boundary. What is asserted here is the whole
 * point of {@link StairSeams}: that the line is corrected the moment the neighbour turns up, in
 * whichever order the two pieces happen to arrive, and that a piece with nothing beside it is left
 * exactly as it was sent.
 *
 * <p>The unit is a sub-chunk rather than a whole column because that is what a modern Bedrock server
 * actually sends: blocks arrive in {@code SubChunkPacket}, one 16-cube at a time, and a tracker that
 * only understood columns would never see a block on such a server at all.
 */
class StairSeamsTest {

    private static final StairIndex INDEX = StairIndex.load("/blockstate/2169-to-2192.json");
    private static final String OAK = "minecraft:oak_stairs";
    private static final int OVERWORLD = 0;
    private static final int NETHER = 1;
    /** Sub-chunk 4 of the overworld, which is y 64 to 79. */
    private static final int HEIGHT = 4;

    /**
     * Two stairs that meet across a boundary: a north-facing one at the east edge of chunk (0,0) and
     * a west-facing one directly north of it, which lives in chunk (0,-1). West is counter-clockwise
     * of north, so the first is an outer-left corner &mdash; and neither piece can see that alone.
     */
    @Test
    void aCornerAcrossAChunkBoundaryIsSettledWhenTheSecondPieceArrives() {
        StairSeams seams = new StairSeams(INDEX);

        // Chunk (0,0) arrives first, with a north-facing stair on its northern edge.
        StairSeams.Collector first = new StairSeams.Collector();
        first.accept(8, 6, 0, stair(Facing.NORTH), id(Facing.NORTH, Corner.NONE));
        assertTrue(seams.settle(piece(0, 0, OVERWORLD), first).isEmpty(),
                "nothing is beside it yet, so there is nothing to settle");

        // Chunk (0,-1) arrives, carrying the west-facing stair directly north of it.
        StairSeams.Collector second = new StairSeams.Collector();
        second.accept(8, 6, 15, stair(Facing.WEST), id(Facing.WEST, Corner.NONE));
        List<StairSeams.Correction> corrections = seams.settle(piece(0, -1, OVERWORLD), second);

        assertEquals(1, corrections.size(), "the stair in the first piece has to be corrected");
        StairSeams.Correction correction = corrections.get(0);
        assertEquals(8, correction.x());
        assertEquals(HEIGHT * 16 + 6, correction.y());
        assertEquals(0, correction.z());
        assertEquals(id(Facing.NORTH, Corner.OUTER_LEFT), correction.runtimeId());
    }

    /** The same pair the other way round: whichever arrives second, the seam ends up settled. */
    @Test
    void theArrivalOrderDoesNotMatter() {
        StairSeams seams = new StairSeams(INDEX);

        StairSeams.Collector north = new StairSeams.Collector();
        north.accept(8, 6, 15, stair(Facing.WEST), id(Facing.WEST, Corner.NONE));
        assertTrue(seams.settle(piece(0, -1, OVERWORLD), north).isEmpty());

        StairSeams.Collector south = new StairSeams.Collector();
        south.accept(8, 6, 0, stair(Facing.NORTH), id(Facing.NORTH, Corner.NONE));
        List<StairSeams.Correction> corrections = seams.settle(piece(0, 0, OVERWORLD), south);

        assertEquals(1, corrections.size());
        assertEquals(id(Facing.NORTH, Corner.OUTER_LEFT), corrections.get(0).runtimeId());
        assertEquals(0, corrections.get(0).z());
    }

    /**
     * A stair that was already sent with the right shape must not be corrected again. This is the
     * property that lets the rule be re-run at all: it reads facing and half, never the corner, so a
     * second pass over an already-shaped stair agrees with the first.
     */
    @Test
    void aStairThatIsAlreadyRightIsNotResent() {
        StairSeams seams = new StairSeams(INDEX);

        StairSeams.Collector first = new StairSeams.Collector();
        first.accept(8, 6, 0, stair(Facing.NORTH), id(Facing.NORTH, Corner.OUTER_LEFT));
        seams.settle(piece(0, 0, OVERWORLD), first);

        StairSeams.Collector second = new StairSeams.Collector();
        second.accept(8, 6, 15, stair(Facing.WEST), id(Facing.WEST, Corner.NONE));
        List<StairSeams.Correction> corrections = seams.settle(piece(0, -1, OVERWORLD), second);

        assertTrue(corrections.isEmpty(), "the shape it was sent with is the shape the rule gives");
    }

    /** Stairs away from the edge are the corner pass's business and must not be touched here. */
    @Test
    void aStairInTheMiddleOfAPieceIsLeftAlone() {
        StairSeams seams = new StairSeams(INDEX);

        StairSeams.Collector first = new StairSeams.Collector();
        first.accept(8, 6, 8, stair(Facing.NORTH), id(Facing.NORTH, Corner.NONE));
        seams.settle(piece(0, 0, OVERWORLD), first);

        StairSeams.Collector second = new StairSeams.Collector();
        second.accept(8, 6, 15, stair(Facing.WEST), id(Facing.WEST, Corner.NONE));
        assertTrue(seams.settle(piece(0, -1, OVERWORLD), second).isEmpty());
    }

    /** A piece with no stairs settles nothing, and is not even remembered. */
    @Test
    void anEmptyPieceSettlesNothing() {
        StairSeams seams = new StairSeams(INDEX);
        assertTrue(seams.settle(piece(0, 0, OVERWORLD), new StairSeams.Collector()).isEmpty());
        assertTrue(seams.settle(piece(0, 1, OVERWORLD), new StairSeams.Collector()).isEmpty());
    }

    /** East and west share an edge the same way north and south do. */
    @Test
    void theEastAndWestSeamsWorkToo() {
        StairSeams seams = new StairSeams(INDEX);

        // A west-facing stair on the west edge of chunk (0,0); the stair it turns against is the
        // last column of blocks in chunk (-1,0).
        StairSeams.Collector east = new StairSeams.Collector();
        east.accept(0, 6, 8, stair(Facing.WEST), id(Facing.WEST, Corner.NONE));
        assertTrue(seams.settle(piece(0, 0, OVERWORLD), east).isEmpty());

        StairSeams.Collector west = new StairSeams.Collector();
        west.accept(15, 6, 8, stair(Facing.NORTH), id(Facing.NORTH, Corner.NONE));
        List<StairSeams.Correction> corrections = seams.settle(piece(-1, 0, OVERWORLD), west);

        assertEquals(1, corrections.size());
        assertEquals(0, corrections.get(0).x(), "the corrected block is the one in chunk (0,0)");
        assertEquals(HEIGHT * 16 + 6, corrections.get(0).y());
    }

    /** Chunk (0,0) of one dimension is not beside chunk (0,-1) of another. */
    @Test
    void piecesOfDifferentDimensionsAreNeverNeighbours() {
        StairSeams seams = new StairSeams(INDEX);

        StairSeams.Collector overworld = new StairSeams.Collector();
        overworld.accept(8, 6, 0, stair(Facing.NORTH), id(Facing.NORTH, Corner.NONE));
        seams.settle(piece(0, 0, OVERWORLD), overworld);

        StairSeams.Collector nether = new StairSeams.Collector();
        nether.accept(8, 6, 15, stair(Facing.WEST), id(Facing.WEST, Corner.NONE));
        assertTrue(seams.settle(piece(0, -1, NETHER), nether).isEmpty(),
                "a piece in another dimension must not settle this one");
    }

    @Test
    void resetForgetsEverything() {
        StairSeams seams = new StairSeams(INDEX);
        StairSeams.Collector first = new StairSeams.Collector();
        first.accept(8, 6, 0, stair(Facing.NORTH), id(Facing.NORTH, Corner.NONE));
        seams.settle(piece(0, 0, OVERWORLD), first);
        seams.reset();

        StairSeams.Collector second = new StairSeams.Collector();
        second.accept(8, 6, 15, stair(Facing.WEST), id(Facing.WEST, Corner.NONE));
        assertTrue(seams.settle(piece(0, -1, OVERWORLD), second).isEmpty(),
                "after a reset the first piece is gone, so there is no seam to settle");
    }

    private static StairSeams.Piece piece(int chunkX, int chunkZ, int dimension) {
        return new StairSeams.Piece(dimension, chunkX, HEIGHT, chunkZ);
    }

    private static StairIndex.Stair stair(Facing facing) {
        return new StairIndex.Stair(OAK, facing, false);
    }

    private static int id(Facing facing, Corner corner) {
        return INDEX.idFor(new StairIndex.Stair(OAK, facing, false), corner);
    }
}

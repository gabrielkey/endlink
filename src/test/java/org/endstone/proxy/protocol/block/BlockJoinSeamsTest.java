package org.endstone.proxy.protocol.block;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.endstone.proxy.protocol.block.BlockJoinIndex.Side;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chunk seam for a fence: one on a sub-chunk's edge, whose neighbour lives in the next chunk.
 *
 * <p>{@link BlockJoinPass} leaves those armless because the piece beyond has not arrived, which is a
 * break in every fence line and pane wall every sixteen blocks. What is asserted here is the whole
 * point of {@link BlockJoinSeams}: that the break is closed the moment the neighbour turns up, in
 * whichever order the two pieces arrive, and that a piece with nothing beside it is left exactly as
 * it was sent.
 */
class BlockJoinSeamsTest {

    private static final BlockJoinIndex INDEX =
            BlockJoinIndex.load("/blockstate/2169-to-2192.json", "/blockstate/block-joins-2192.json");
    private static final String OAK_FENCE = "minecraft:oak_fence";
    private static final String NETHER_FENCE = "minecraft:nether_brick_fence";
    private static final int OVERWORLD = 0;
    private static final int NETHER = 1;
    /** Sub-chunk 4 of the overworld, which is y 64 to 79. */
    private static final int HEIGHT = 4;

    /**
     * Two fences that meet across a boundary: one at the north edge of chunk (0,0) and one directly
     * north of it, in chunk (0,-1). Each has to grow an arm towards the other, and neither piece can
     * see that alone.
     */
    @Test
    void aFenceLineAcrossAChunkBoundaryIsJoinedWhenTheSecondPieceArrives() {
        BlockJoinSeams seams = new BlockJoinSeams(INDEX);

        BlockJoinSeams.Collector first = new BlockJoinSeams.Collector();
        first.accept(8, 6, 0, joint(OAK_FENCE), armless(OAK_FENCE));
        assertTrue(seams.settle(piece(0, 0, OVERWORLD), first).isEmpty(),
                "nothing is beside it yet, so there is nothing to settle");

        BlockJoinSeams.Collector second = new BlockJoinSeams.Collector();
        second.accept(8, 6, 15, joint(OAK_FENCE), armless(OAK_FENCE));
        List<BlockJoinSeams.Correction> corrections = seams.settle(piece(0, -1, OVERWORLD), second);

        assertEquals(2, corrections.size(), "both fences have to reach out, one in each piece");
        BlockJoinSeams.Correction south = byZ(corrections, 0);
        assertEquals(8, south.x());
        assertEquals(HEIGHT * 16 + 6, south.y());
        assertEquals(INDEX.idFor(joint(OAK_FENCE), Side.NORTH.bit()), south.runtimeId());

        BlockJoinSeams.Correction north = byZ(corrections, -1);
        assertEquals(INDEX.idFor(joint(OAK_FENCE), Side.SOUTH.bit()), north.runtimeId());
    }

    /** Whichever arrives second, the seam ends up joined. */
    @Test
    void theArrivalOrderDoesNotMatter() {
        BlockJoinSeams seams = new BlockJoinSeams(INDEX);

        BlockJoinSeams.Collector north = new BlockJoinSeams.Collector();
        north.accept(8, 6, 15, joint(OAK_FENCE), armless(OAK_FENCE));
        assertTrue(seams.settle(piece(0, -1, OVERWORLD), north).isEmpty());

        BlockJoinSeams.Collector south = new BlockJoinSeams.Collector();
        south.accept(8, 6, 0, joint(OAK_FENCE), armless(OAK_FENCE));
        assertEquals(2, seams.settle(piece(0, 0, OVERWORLD), south).size());
    }

    /** East and west share an edge the same way north and south do. */
    @Test
    void theEastAndWestSeamsWorkToo() {
        BlockJoinSeams seams = new BlockJoinSeams(INDEX);

        BlockJoinSeams.Collector east = new BlockJoinSeams.Collector();
        east.accept(0, 6, 8, joint(OAK_FENCE), armless(OAK_FENCE));
        assertTrue(seams.settle(piece(0, 0, OVERWORLD), east).isEmpty());

        BlockJoinSeams.Collector west = new BlockJoinSeams.Collector();
        west.accept(15, 6, 8, joint(OAK_FENCE), armless(OAK_FENCE));
        List<BlockJoinSeams.Correction> corrections = seams.settle(piece(-1, 0, OVERWORLD), west);

        assertEquals(2, corrections.size());
        for (BlockJoinSeams.Correction correction : corrections) {
            int expected = correction.x() == 0 ? Side.WEST.bit() : Side.EAST.bit();
            assertEquals(INDEX.idFor(joint(OAK_FENCE), expected), correction.runtimeId(),
                    "the fence at x=" + correction.x() + " reaches the wrong way");
        }
    }

    /** Two fences that do not reach each other settle nothing, however close they are. */
    @Test
    void aSeamBetweenBlocksThatDoNotReachIsNotJoined() {
        BlockJoinSeams seams = new BlockJoinSeams(INDEX);

        BlockJoinSeams.Collector first = new BlockJoinSeams.Collector();
        first.accept(8, 6, 0, joint(OAK_FENCE), armless(OAK_FENCE));
        seams.settle(piece(0, 0, OVERWORLD), first);

        BlockJoinSeams.Collector second = new BlockJoinSeams.Collector();
        second.accept(8, 6, 15, joint(NETHER_FENCE), armless(NETHER_FENCE));
        assertTrue(seams.settle(piece(0, -1, OVERWORLD), second).isEmpty(),
                "wood does not reach nether brick, on a seam any more than inside a chunk");
    }

    /**
     * A fence already sent with the right arm must not be corrected again. This is the property that
     * lets the rule be re-run at all, and it is stronger here than for stairs: the rule reads a
     * neighbour's family, which no state of that neighbour can change.
     */
    @Test
    void aFenceThatIsAlreadyRightIsNotResent() {
        BlockJoinSeams seams = new BlockJoinSeams(INDEX);

        BlockJoinSeams.Collector first = new BlockJoinSeams.Collector();
        first.accept(8, 6, 0, joint(OAK_FENCE), INDEX.idFor(joint(OAK_FENCE), Side.NORTH.bit()));
        seams.settle(piece(0, 0, OVERWORLD), first);

        BlockJoinSeams.Collector second = new BlockJoinSeams.Collector();
        second.accept(8, 6, 15, joint(OAK_FENCE), INDEX.idFor(joint(OAK_FENCE), Side.SOUTH.bit()));
        assertTrue(seams.settle(piece(0, -1, OVERWORLD), second).isEmpty(),
                "the arms they were sent with are the arms the rule gives");
    }

    /** Blocks away from the edge are the join pass's business and must not be touched here. */
    @Test
    void aFenceInTheMiddleOfAPieceIsLeftAlone() {
        BlockJoinSeams seams = new BlockJoinSeams(INDEX);

        BlockJoinSeams.Collector first = new BlockJoinSeams.Collector();
        first.accept(8, 6, 8, joint(OAK_FENCE), armless(OAK_FENCE));
        seams.settle(piece(0, 0, OVERWORLD), first);

        BlockJoinSeams.Collector second = new BlockJoinSeams.Collector();
        second.accept(8, 6, 15, joint(OAK_FENCE), armless(OAK_FENCE));
        assertTrue(seams.settle(piece(0, -1, OVERWORLD), second).isEmpty());
    }

    @Test
    void anEmptyPieceSettlesNothing() {
        BlockJoinSeams seams = new BlockJoinSeams(INDEX);
        assertTrue(seams.settle(piece(0, 0, OVERWORLD), new BlockJoinSeams.Collector()).isEmpty());
        assertTrue(seams.settle(piece(0, 1, OVERWORLD), new BlockJoinSeams.Collector()).isEmpty());
    }

    @Test
    void piecesOfDifferentDimensionsAreNeverNeighbours() {
        BlockJoinSeams seams = new BlockJoinSeams(INDEX);

        BlockJoinSeams.Collector overworld = new BlockJoinSeams.Collector();
        overworld.accept(8, 6, 0, joint(OAK_FENCE), armless(OAK_FENCE));
        seams.settle(piece(0, 0, OVERWORLD), overworld);

        BlockJoinSeams.Collector nether = new BlockJoinSeams.Collector();
        nether.accept(8, 6, 15, joint(OAK_FENCE), armless(OAK_FENCE));
        assertTrue(seams.settle(piece(0, -1, NETHER), nether).isEmpty(),
                "a piece in another dimension must not settle this one");
    }

    @Test
    void resetForgetsEverything() {
        BlockJoinSeams seams = new BlockJoinSeams(INDEX);
        BlockJoinSeams.Collector first = new BlockJoinSeams.Collector();
        first.accept(8, 6, 0, joint(OAK_FENCE), armless(OAK_FENCE));
        seams.settle(piece(0, 0, OVERWORLD), first);
        seams.reset();

        BlockJoinSeams.Collector second = new BlockJoinSeams.Collector();
        second.accept(8, 6, 15, joint(OAK_FENCE), armless(OAK_FENCE));
        assertTrue(seams.settle(piece(0, -1, OVERWORLD), second).isEmpty(),
                "after a reset the first piece is gone, so there is no seam to settle");
    }

    private static BlockJoinSeams.Correction byZ(List<BlockJoinSeams.Correction> corrections, int z) {
        return corrections.stream().filter(correction -> correction.z() == z).findFirst()
                .orElseThrow(() -> new AssertionError("no correction at z=" + z));
    }

    private static BlockJoinSeams.Piece piece(int chunkX, int chunkZ, int dimension) {
        return new BlockJoinSeams.Piece(dimension, chunkX, HEIGHT, chunkZ);
    }

    private static int armless(String identifier) {
        return INDEX.idFor(joint(identifier), BlockJoinIndex.NO_ARMS);
    }

    private static BlockJoinIndex.Joint joint(String identifier) {
        return INDEX.jointAt(BedrockBlockStateHash.of(identifier, List.of(
                BlockStateValue.of(Side.EAST.property(), BlockStateValue.Type.BOOL, false),
                BlockStateValue.of(Side.NORTH.property(), BlockStateValue.Type.BOOL, false),
                BlockStateValue.of(Side.SOUTH.property(), BlockStateValue.Type.BOOL, false),
                BlockStateValue.of(Side.WEST.property(), BlockStateValue.Type.BOOL, false))));
    }
}

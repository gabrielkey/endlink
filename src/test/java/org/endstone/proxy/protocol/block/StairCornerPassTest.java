package org.endstone.proxy.protocol.block;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.endstone.proxy.protocol.block.StairIndex.Corner;
import static org.endstone.proxy.protocol.block.StairIndex.Facing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stair corner rule, and the bit array it has to survive.
 *
 * <p>Two things here can be wrong without anything failing. The corner rule can be <em>mirrored</em>,
 * turning every inner_left into an inner_right — which looks like corners that are still wrong, not
 * like a crash — so each of the four shapes is built from an explicit two-stair layout whose geometry
 * is spelled out in the test name and comment rather than taken from the code under test. And the bit
 * packing can be subtly wrong, which would move blocks rather than shapes, so a full sub-chunk is
 * round-tripped at every width Bedrock uses.
 */
class StairCornerPassTest {

    private static final StairIndex INDEX = StairIndex.load("/blockstate/2169-to-2192.json");
    private static final StairCornerPass PASS = new StairCornerPass(INDEX);
    private static final String OAK = "minecraft:oak_stairs";
    private static final int AIR = BedrockBlockStateHash.of("minecraft:air", java.util.List.of());

    @Test
    void theIndexKnowsEveryStair() {
        // 81 stair blocks, 4 facings, 2 halves, 5 corner shapes.
        assertEquals(81 * 4 * 2 * 5, INDEX.size());
        assertFalse(INDEX.isEmpty());
    }

    /**
     * Every corner variant resolves, not only the cornerless one, and that is load-bearing rather
     * than incidental: {@link StairSeams} re-runs the rule over stairs this pass has already shaped,
     * and if a shaped stair read back as "not a stair" the second pass would see holes where the
     * first had done its work, and would unshape the corners around them.
     */
    @Test
    void anIdCarriesBackTheStairItDescribes() {
        for (Corner corner : Corner.values()) {
            StairIndex.Stair stair = INDEX.stairAt(id(Facing.NORTH, false, corner));
            assertNotNull(stair, corner.value());
            assertEquals(OAK, stair.identifier());
            assertEquals(Facing.NORTH, stair.facing());
            assertFalse(stair.upsideDown());
        }
        assertNull(INDEX.stairAt(AIR), "and something that is not a stair still is not one");
    }

    /** Counter-clockwise seen from above: north to west to south to east. */
    @Test
    void counterClockwiseIsTheDirectionTheCornerNamesDependOn() {
        assertEquals(Facing.WEST, Facing.NORTH.counterClockwise());
        assertEquals(Facing.SOUTH, Facing.WEST.counterClockwise());
        assertEquals(Facing.EAST, Facing.SOUTH.counterClockwise());
        assertEquals(Facing.NORTH, Facing.EAST.counterClockwise());
    }

    @Test
    void facingVectorsMatchMinecraftsAxes() {
        assertEquals(1, Facing.EAST.dx());
        assertEquals(-1, Facing.WEST.dx());
        assertEquals(1, Facing.SOUTH.dz());
        assertEquals(-1, Facing.NORTH.dz());
        assertTrue(Facing.EAST.sameAxis(Facing.WEST));
        assertFalse(Facing.EAST.sameAxis(Facing.NORTH));
    }

    // --- the four corner shapes ---------------------------------------------------------------

    /**
     * A north-facing stair with a west-facing stair <em>in front of it</em> (to its north). West is
     * counter-clockwise of north, so this is the outer-left corner.
     */
    @Test
    void aStairWhoseFrontNeighbourTurnsLeftIsAnOuterLeftCorner() {
        assertEquals(Corner.OUTER_LEFT, cornerOf(
                at(8, 8, 8, Facing.NORTH),
                at(8, 8, 7, Facing.WEST)));
    }

    /** The same layout mirrored: east is clockwise of north, so the corner is outer-right. */
    @Test
    void aStairWhoseFrontNeighbourTurnsRightIsAnOuterRightCorner() {
        assertEquals(Corner.OUTER_RIGHT, cornerOf(
                at(8, 8, 8, Facing.NORTH),
                at(8, 8, 7, Facing.EAST)));
    }

    /**
     * A north-facing stair with a west-facing stair <em>behind it</em> (to its south) fills the
     * inside of the turn: inner-left, again because west is counter-clockwise of north.
     */
    @Test
    void aStairWhoseBackNeighbourTurnsLeftIsAnInnerLeftCorner() {
        assertEquals(Corner.INNER_LEFT, cornerOf(
                at(8, 8, 8, Facing.NORTH),
                at(8, 8, 9, Facing.WEST)));
    }

    @Test
    void aStairWhoseBackNeighbourTurnsRightIsAnInnerRightCorner() {
        assertEquals(Corner.INNER_RIGHT, cornerOf(
                at(8, 8, 8, Facing.NORTH),
                at(8, 8, 9, Facing.EAST)));
    }

    @Test
    void aLoneStairIsStraight() {
        assertEquals(Corner.NONE, cornerOf(at(8, 8, 8, Facing.NORTH)));
    }

    /** Two stairs in a row face the same way and neither is a corner. */
    @Test
    void aRunOfStairsIsStraight() {
        assertEquals(Corner.NONE, cornerOf(
                at(8, 8, 8, Facing.NORTH),
                at(8, 8, 7, Facing.NORTH),
                at(8, 8, 9, Facing.NORTH)));
    }

    /**
     * The cancelling clause. The turn to the north would make an outer corner, but the stair to the
     * east already continues this one's run, so Java keeps it straight and so must this.
     */
    @Test
    void aCornerIsCancelledWhenTheRunAlreadyContinuesBeside() {
        assertEquals(Corner.NONE, cornerOf(
                at(8, 8, 8, Facing.NORTH),
                at(8, 8, 7, Facing.WEST),
                at(9, 8, 8, Facing.NORTH)));
    }

    /** A stair on the other half is not part of the shape at all. */
    @Test
    void halvesDoNotCornerWithEachOther() {
        ByteBuf payload = subChunk(
                block(SubChunkStorage.index(8, 8, 8), id(Facing.NORTH, false, Corner.NONE)),
                block(SubChunkStorage.index(8, 8, 7), id(Facing.WEST, true, Corner.NONE)));
        assertNull(PASS.apply(payload, 1), "nothing should change, so no new payload");
    }

    /** Stairs on the chunk edge keep the straight shape, because the neighbour is not knowable. */
    @Test
    void aStairOnTheFootprintEdgeIsLeftStraight() {
        ByteBuf payload = subChunk(
                block(SubChunkStorage.index(0, 8, 8), id(Facing.WEST, false, Corner.NONE)),
                block(SubChunkStorage.index(0, 8, 7), id(Facing.NORTH, false, Corner.NONE)));
        ByteBuf result = PASS.apply(payload, 1);
        // The stair at z=7 may corner, but the one at x=0 facing out of the chunk must not be guessed.
        if (result != null) {
            assertEquals(id(Facing.WEST, false, Corner.NONE),
                    blockAt(result, SubChunkStorage.index(0, 8, 8)));
        }
    }

    @Test
    void aPayloadWithNoStairsIsLeftAlone() {
        ByteBuf payload = subChunk(block(SubChunkStorage.index(1, 1, 1), 12345));
        assertNull(PASS.apply(payload, 1));
    }

    @Test
    void refusesAnUnreadablePayload() {
        assertNull(PASS.apply(null, 1));
        assertNull(PASS.apply(Unpooled.buffer(), 1));
        ByteBuf bad = Unpooled.buffer();
        bad.writeByte(42);
        assertNull(PASS.apply(bad, 1));
    }

    // --- the bit array ------------------------------------------------------------------------

    /**
     * Every width Bedrock uses, round-tripped over a whole sub-chunk. 3, 5 and 6 bits waste two bits
     * of every word, which is the case a naive packer gets wrong and which would silently move blocks.
     */
    @Test
    void everyBitWidthSurvivesARoundTrip() {
        for (int paletteSize : new int[]{2, 4, 8, 16, 32, 64, 256, 4096}) {
            ByteBuf buffer = Unpooled.buffer();
            int[] expected = new int[SubChunkStorage.BLOCKS];
            SubChunkStorage storage = uniform(1000);
            for (int position = 0; position < SubChunkStorage.BLOCKS; position++) {
                int id = 1000 + (position % paletteSize);
                expected[position] = id;
                storage = storage.withBlockAt(position, id);
            }
            storage.write(buffer);

            SubChunkStorage readBack = SubChunkStorage.read(buffer);
            for (int position = 0; position < SubChunkStorage.BLOCKS; position++) {
                assertEquals(expected[position], readBack.blockAt(position),
                        "palette size " + paletteSize + " position " + position);
            }
            assertFalse(buffer.isReadable(), "the whole storage must be consumed");
        }
    }

    @Test
    void aUniformSubChunkRoundTripsWithNoWords() {
        ByteBuf buffer = Unpooled.buffer();
        uniform(77).write(buffer);
        assertEquals(0, buffer.getUnsignedByte(0) >>> 1, "a uniform sub-chunk is written at width 0");
        assertTrue(buffer.readableBytes() < 16,
                "width 0 means no word array at all, just the header and a one-entry palette");
        SubChunkStorage readBack = SubChunkStorage.read(buffer);
        assertEquals(77, readBack.blockAt(0));
        assertEquals(77, readBack.blockAt(SubChunkStorage.BLOCKS - 1));
    }

    @Test
    void positionsAreXMajorThenZThenY() {
        assertEquals(0, SubChunkStorage.index(0, 0, 0));
        assertEquals(1, SubChunkStorage.index(0, 1, 0));
        assertEquals(16, SubChunkStorage.index(0, 0, 1));
        assertEquals(256, SubChunkStorage.index(1, 0, 0));
        assertEquals(4095, SubChunkStorage.index(15, 15, 15));
    }

    // --- helpers ------------------------------------------------------------------------------

    private record Block(int position, int runtimeId) {
    }

    private static Block block(int position, int runtimeId) {
        return new Block(position, runtimeId);
    }

    private static Block at(int x, int y, int z, Facing facing) {
        return new Block(SubChunkStorage.index(x, y, z), id(facing, false, Corner.NONE));
    }

    private static int id(Facing facing, boolean upsideDown, Corner corner) {
        return INDEX.idFor(new StairIndex.Stair(OAK, facing, upsideDown), corner);
    }

    /** Runs the pass over a sub-chunk holding {@code blocks}, and reports the first one's corner. */
    private static Corner cornerOf(Block... blocks) {
        ByteBuf payload = subChunk(blocks);
        ByteBuf result = PASS.apply(payload, 1);
        int resulting = result == null
                ? blocks[0].runtimeId()
                : blockAt(result, blocks[0].position());
        for (Corner corner : Corner.values()) {
            StairIndex.Stair stair = INDEX.stairAt(blocks[0].runtimeId());
            if (INDEX.idFor(stair, corner) == resulting) {
                return corner;
            }
        }
        throw new AssertionError("the block is no longer a stair at all: " + resulting);
    }

    private static SubChunkStorage uniform(int runtimeId) {
        ByteBuf buffer = Unpooled.buffer();
        buffer.writeByte(1);
        org.cloudburstmc.protocol.common.util.VarInts.writeInt(buffer, 1);
        org.cloudburstmc.protocol.common.util.VarInts.writeInt(buffer, runtimeId);
        return SubChunkStorage.read(buffer);
    }

    private static ByteBuf subChunk(Block... blocks) {
        SubChunkStorage storage = uniform(AIR);
        for (Block block : blocks) {
            storage = storage.withBlockAt(block.position(), block.runtimeId());
        }
        ByteBuf payload = Unpooled.buffer();
        payload.writeByte(8);
        payload.writeByte(1);
        storage.write(payload);
        return payload;
    }

    private static int blockAt(ByteBuf payload, int position) {
        ByteBuf buffer = payload.slice();
        buffer.skipBytes(2);
        return SubChunkStorage.read(buffer).blockAt(position);
    }
}

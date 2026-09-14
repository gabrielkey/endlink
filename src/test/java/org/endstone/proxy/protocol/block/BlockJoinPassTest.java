package org.endstone.proxy.protocol.block;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.endstone.proxy.protocol.block.BlockJoinIndex.Side;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule that gives a fence, pane or bar its arms, and the bit array it has to survive.
 *
 * <p>The same two things can be silently wrong here as in {@link StairCornerPassTest}. The four
 * sides can be <em>swapped</em> — north for south, or the whole set rotated — which renders as arms
 * pointing the wrong way rather than as a crash, so every direction is asserted from an explicit
 * two-block layout whose geometry is spelled out rather than taken from the code under test. And the
 * bit packing can move blocks rather than arms, which is why the pass verifies itself and why the
 * round trip is covered next door.
 */
class BlockJoinPassTest {

    private static final BlockJoinIndex INDEX =
            BlockJoinIndex.load("/blockstate/2169-to-2192.json", "/blockstate/block-joins-2192.json");
    private static final BlockJoinFaces FACES = BlockJoinFaces.load(
            "/blockstate/block-join-faces-2169.txt",
            BlockStateUpgrade.load("/blockstate/2169-to-2192.json"));
    private static final BlockJoinPass PASS = new BlockJoinPass(INDEX, FACES);

    private static final String OAK_FENCE = "minecraft:oak_fence";
    private static final String SPRUCE_FENCE = "minecraft:spruce_fence";
    private static final String NETHER_FENCE = "minecraft:nether_brick_fence";
    private static final String GLASS_PANE = "minecraft:glass_pane";
    private static final String IRON_BARS = "minecraft:iron_bars";

    private static final int AIR = BedrockBlockStateHash.of("minecraft:air", List.of());

    @Test
    void theIndexKnowsEveryJoiningBlock() {
        // 57 blocks with no other states plus trip wire's 16, all with 16 arm arrangements.
        assertEquals((57 + 16) * BlockJoinIndex.ARM_PATTERNS, INDEX.size());
        assertFalse(INDEX.isEmpty());
    }

    /**
     * Every arm arrangement resolves, not only the armless one. This is load-bearing rather than
     * incidental: {@link BlockJoinSeams} re-runs the rule over blocks this pass has already reached
     * out, and a block that read back as "not a fence" would leave a hole where the first pass had
     * done its work.
     */
    @Test
    void anIdCarriesBackTheJoiningBlockItDescribes() {
        BlockJoinIndex.Joint oak = joint(OAK_FENCE);
        for (int arms = 0; arms < BlockJoinIndex.ARM_PATTERNS; arms++) {
            BlockJoinIndex.Joint read = INDEX.jointAt(INDEX.idFor(oak, arms));
            assertNotNull(read, "arms " + arms);
            assertEquals(OAK_FENCE, read.identifier());
            assertEquals("wooden_fence", read.family());
        }
        assertNull(INDEX.jointAt(AIR), "and something that is not a joining block still is not one");
    }

    /** Sixteen arm arrangements are sixteen different blocks, or two of them would render alike. */
    @Test
    void everyArmArrangementIsItsOwnId() {
        BlockJoinIndex.Joint oak = joint(OAK_FENCE);
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        for (int arms = 0; arms < BlockJoinIndex.ARM_PATTERNS; arms++) {
            assertTrue(ids.add(INDEX.idFor(oak, arms)), "arms " + arms + " duplicates another");
        }
    }

    /** An armless fence is the id the upgrade table carries a 1.26.45 fence forward to. */
    @Test
    void theArmlessIdIsTheOneTheUpgradeTableProduces() {
        BlockStateUpgrade upgrade = BlockStateUpgrade.load("/blockstate/2169-to-2192.json");
        assertEquals(
                INDEX.idFor(joint(OAK_FENCE), BlockJoinIndex.NO_ARMS),
                upgrade.toNewer(BedrockBlockStateHash.of(OAK_FENCE, List.of())));
    }

    // --- the four sides -----------------------------------------------------------------------

    @Test
    void aFenceReachesTheFenceToItsNorth() {
        assertEquals(Side.NORTH.bit(), armsOf(fence(8, 8, 8, OAK_FENCE), fence(8, 8, 7, OAK_FENCE)));
    }

    @Test
    void aFenceReachesTheFenceToItsSouth() {
        assertEquals(Side.SOUTH.bit(), armsOf(fence(8, 8, 8, OAK_FENCE), fence(8, 8, 9, OAK_FENCE)));
    }

    @Test
    void aFenceReachesTheFenceToItsWest() {
        assertEquals(Side.WEST.bit(), armsOf(fence(8, 8, 8, OAK_FENCE), fence(7, 8, 8, OAK_FENCE)));
    }

    @Test
    void aFenceReachesTheFenceToItsEast() {
        assertEquals(Side.EAST.bit(), armsOf(fence(8, 8, 8, OAK_FENCE), fence(9, 8, 8, OAK_FENCE)));
    }

    /** The middle of a run of three reaches both ways, which is the render the defect was about. */
    @Test
    void theMiddleOfARunReachesBothWays() {
        assertEquals(Side.WEST.bit() | Side.EAST.bit(), armsOf(
                fence(8, 8, 8, OAK_FENCE), fence(7, 8, 8, OAK_FENCE), fence(9, 8, 8, OAK_FENCE)));
    }

    @Test
    void aLoneFenceReachesNothing() {
        assertEquals(BlockJoinIndex.NO_ARMS, armsOf(fence(8, 8, 8, OAK_FENCE)));
    }

    // --- which blocks reach which -------------------------------------------------------------

    /** Wood is wood: Java's isSameFence asks only whether both are wooden, not which tree. */
    @Test
    void oneWoodenFenceReachesAnother() {
        assertEquals(Side.EAST.bit(),
                armsOf(fence(8, 8, 8, OAK_FENCE), fence(9, 8, 8, SPRUCE_FENCE)));
    }

    /** And nether brick is not wood, which is the one split in the whole fence set. */
    @Test
    void aWoodenFenceDoesNotReachANetherBrickFence() {
        assertEquals(BlockJoinIndex.NO_ARMS,
                armsOf(fence(8, 8, 8, OAK_FENCE), fence(9, 8, 8, NETHER_FENCE)));
        assertEquals(BlockJoinIndex.NO_ARMS,
                armsOf(fence(8, 8, 8, NETHER_FENCE), fence(9, 8, 8, OAK_FENCE)));
    }

    /** Java draws panes and bars with the same block, and its attachsTo makes no distinction. */
    @Test
    void aPaneReachesIronBars() {
        assertEquals(Side.EAST.bit(),
                armsOf(fence(8, 8, 8, GLASS_PANE), fence(9, 8, 8, IRON_BARS)));
    }

    @Test
    void aFenceDoesNotReachAPane() {
        assertEquals(BlockJoinIndex.NO_ARMS,
                armsOf(fence(8, 8, 8, OAK_FENCE), fence(9, 8, 8, GLASS_PANE)));
    }

    // --- Java's third clause: a solid face beside it ------------------------------------------

    /**
     * The clause that took a table of its own. Every one of these is Java's published answer, not a
     * restatement of the rule: stone is a full cube so everything reaches it; a barrier is a full
     * cube too but is named in {@code isExceptionForConnection}, so nothing does; and glass is a full
     * cube that is <em>not</em> an exception, which is the pair that catches a table built from
     * "looks solid" rather than from the game.
     */
    @Test
    void aFenceReachesASolidBlock() {
        assertEquals(Side.EAST.bit(), armsOf(fence(8, 8, 8, OAK_FENCE), plain(9, 8, 8, "minecraft:stone")));
    }

    /** And the pane, which is the case that was still wrong in game: glass set into a wall. */
    @Test
    void aPaneReachesASolidBlock() {
        assertEquals(Side.WEST.bit() | Side.EAST.bit(), armsOf(
                fence(8, 8, 8, GLASS_PANE),
                plain(7, 8, 8, "minecraft:stone"),
                plain(9, 8, 8, "minecraft:stone")));
    }

    /**
     * A barrier is a full cube and nothing reaches it, because Java names it in
     * {@code isExceptionForConnection}. It is not in the table at all, which is how the table spells
     * "nothing reaches this".
     */
    @Test
    void nothingReachesABarrierEvenThoughItIsAFullCube() {
        assertEquals(BlockJoinIndex.NO_ARMS,
                armsOf(fence(8, 8, 8, OAK_FENCE), plain(9, 8, 8, "minecraft:barrier")));
        assertEquals(BlockJoinIndex.NO_ARMS,
                armsOf(fence(8, 8, 8, GLASS_PANE), plain(9, 8, 8, "minecraft:barrier")));
    }

    @Test
    void aPaneReachesAGlassBlock() {
        assertEquals(Side.EAST.bit(), armsOf(fence(8, 8, 8, GLASS_PANE), plain(9, 8, 8, "minecraft:glass")));
    }

    /** A block the table has never heard of is reached by nothing, which is the safe way to be wrong. */
    @Test
    void anUnknownBlockIsReachedByNothing() {
        assertEquals(BlockJoinIndex.NO_ARMS,
                armsOf(fence(8, 8, 8, OAK_FENCE), block(SubChunkStorage.index(9, 8, 8), 12345)));
    }

    @Test
    void airIsReachedByNothing() {
        assertEquals(BlockJoinIndex.NO_ARMS,
                armsOf(fence(8, 8, 8, OAK_FENCE), block(SubChunkStorage.index(9, 8, 8), AIR)));
    }

    /**
     * The faces table is keyed by the older version's ids and the pass runs on the newer ones, so a
     * lookup has to go back through the upgrade table first. A stair is the block that proves it:
     * 1.26.50 gave it a corner state, so the id the pass meets is one the table has never seen.
     *
     * <p>It also pins which way round the sides are read, which is the mistake that would render as
     * arms pointing the wrong way rather than as a failure. A stair is solid on one side only, so the
     * same stair reaches a fence from one direction and not from the other &mdash; and Java, not this
     * test, decides which: {@code weirdo_direction} 1 is a west-facing stair, whose full face is the
     * one a fence to its west stands against.
     */
    @Test
    void aStairIsReachedThroughTheUpgradeTableAndOnlyOnItsSolidSide() {
        StairIndex stairs = StairIndex.load("/blockstate/2169-to-2192.json");
        assertEquals(Side.EAST.bit(), armsOf(
                        fence(8, 8, 8, OAK_FENCE),
                        block(SubChunkStorage.index(9, 8, 8), stair(stairs, StairIndex.Facing.WEST))),
                "a stair id must resolve in the faces table after being carried up to 1.26.50");
        assertEquals(BlockJoinIndex.NO_ARMS, armsOf(
                        fence(8, 8, 8, OAK_FENCE),
                        block(SubChunkStorage.index(9, 8, 8), stair(stairs, StairIndex.Facing.EAST))),
                "and the same stair turned around presents its open side, which nothing reaches");
    }

    // --- the payload --------------------------------------------------------------------------

    /** A fence on the chunk edge keeps its arms off, because the neighbour is not knowable yet. */
    @Test
    void aFenceOnTheFootprintEdgeIsLeftArmless() {
        ByteBuf payload = subChunk(
                fence(0, 8, 8, OAK_FENCE),
                fence(0, 8, 7, OAK_FENCE));
        ByteBuf result = PASS.apply(payload, 1);
        assertNotNull(result, "the two fences reach each other along z, so something changed");
        // They reach north and south, but neither may be guessed to reach west out of the chunk.
        assertEquals(INDEX.idFor(joint(OAK_FENCE), Side.NORTH.bit()),
                blockAt(result, SubChunkStorage.index(0, 8, 8)));
        assertEquals(INDEX.idFor(joint(OAK_FENCE), Side.SOUTH.bit()),
                blockAt(result, SubChunkStorage.index(0, 8, 7)));
    }

    @Test
    void aPayloadWithNothingJoiningIsLeftAlone() {
        assertNull(PASS.apply(subChunk(block(SubChunkStorage.index(1, 1, 1), 12345)), 1));
    }

    @Test
    void aPayloadOfLoneFencesIsLeftAlone() {
        assertNull(PASS.apply(subChunk(fence(1, 1, 1, OAK_FENCE), fence(9, 9, 9, OAK_FENCE)), 1),
                "nothing reaches anything, so no arms and no new payload");
    }

    @Test
    void refusesAnUnreadablePayload() {
        assertNull(PASS.apply(null, 1));
        assertNull(PASS.apply(Unpooled.buffer(), 1));
        ByteBuf bad = Unpooled.buffer();
        bad.writeByte(42);
        assertNull(PASS.apply(bad, 1));
    }

    /** Running the pass over its own result changes nothing: the rule reads families, not arms. */
    @Test
    void theRuleCannotDrift() {
        ByteBuf once = PASS.apply(subChunk(
                fence(8, 8, 8, OAK_FENCE), fence(7, 8, 8, OAK_FENCE), fence(9, 8, 8, OAK_FENCE)), 1);
        assertNotNull(once);
        assertNull(PASS.apply(once, 1), "a second pass has nothing left to do");
    }

    /** Trip wire's own four states are carried through untouched by the arms it gains. */
    @Test
    void tripWireKeepsTheStatesItAlreadyHad() {
        BlockJoinIndex.Joint powered = INDEX.jointAt(BedrockBlockStateHash.of("minecraft:trip_wire",
                List.of(
                        BlockStateValue.of("attached_bit", BlockStateValue.Type.BOOL, false),
                        BlockStateValue.of("disarmed_bit", BlockStateValue.Type.BOOL, false),
                        BlockStateValue.of("powered_bit", BlockStateValue.Type.BOOL, true),
                        BlockStateValue.of("suspended_bit", BlockStateValue.Type.BOOL, false),
                        BlockStateValue.of("minecraft:connection_east", BlockStateValue.Type.BOOL, false),
                        BlockStateValue.of("minecraft:connection_north", BlockStateValue.Type.BOOL, false),
                        BlockStateValue.of("minecraft:connection_south", BlockStateValue.Type.BOOL, false),
                        BlockStateValue.of("minecraft:connection_west", BlockStateValue.Type.BOOL, false))));
        assertNotNull(powered);
        assertEquals("trip_wire", powered.family());
        assertTrue(powered.otherStates().contains(
                        BlockStateValue.of("powered_bit", BlockStateValue.Type.BOOL, true)),
                "the arms must not be bought with the state the block already carried");
    }

    /** The collector sees every joining block in a finished payload, which is what settles seams. */
    @Test
    void collectReportsEveryJoiningBlock() {
        ByteBuf payload = subChunk(fence(1, 2, 3, OAK_FENCE), fence(4, 5, 6, GLASS_PANE));
        BlockJoinSeams.Collector collector = new BlockJoinSeams.Collector();
        assertTrue(PASS.collect(payload, 1, collector));
        assertEquals(2, collector.size());
    }

    // --- helpers ------------------------------------------------------------------------------

    private record Block(int position, int runtimeId) {
    }

    private static Block block(int position, int runtimeId) {
        return new Block(position, runtimeId);
    }

    /** A 1.26.50 stair, which is what the pass would meet after the ids were carried up. */
    private static int stair(StairIndex stairs, StairIndex.Facing facing) {
        return stairs.idFor(new StairIndex.Stair("minecraft:oak_stairs", facing, false),
                StairIndex.Corner.NONE);
    }

    /** A block with no states of its own, named as Mojang names it. */
    private static Block plain(int x, int y, int z, String identifier) {
        return new Block(SubChunkStorage.index(x, y, z),
                BedrockBlockStateHash.of(identifier, List.of()));
    }

    private static Block fence(int x, int y, int z, String identifier) {
        return new Block(SubChunkStorage.index(x, y, z),
                INDEX.idFor(joint(identifier), BlockJoinIndex.NO_ARMS));
    }

    private static BlockJoinIndex.Joint joint(String identifier) {
        BlockJoinIndex.Joint joint = INDEX.jointAt(BedrockBlockStateHash.of(identifier, List.of(
                BlockStateValue.of(Side.EAST.property(), BlockStateValue.Type.BOOL, false),
                BlockStateValue.of(Side.NORTH.property(), BlockStateValue.Type.BOOL, false),
                BlockStateValue.of(Side.SOUTH.property(), BlockStateValue.Type.BOOL, false),
                BlockStateValue.of(Side.WEST.property(), BlockStateValue.Type.BOOL, false))));
        assertNotNull(joint, identifier);
        return joint;
    }

    /** Runs the pass over a sub-chunk holding {@code blocks}, and reports the first one's arms. */
    private static int armsOf(Block... blocks) {
        ByteBuf payload = subChunk(blocks);
        ByteBuf result = PASS.apply(payload, 1);
        int resulting = result == null
                ? blocks[0].runtimeId()
                : blockAt(result, blocks[0].position());
        BlockJoinIndex.Joint joint = INDEX.jointAt(blocks[0].runtimeId());
        for (int arms = 0; arms < BlockJoinIndex.ARM_PATTERNS; arms++) {
            if (INDEX.idFor(joint, arms) == resulting) {
                return arms;
            }
        }
        throw new AssertionError("the block is no longer a joining block at all: " + resulting);
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

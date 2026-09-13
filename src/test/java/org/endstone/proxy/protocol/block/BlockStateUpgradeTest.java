package org.endstone.proxy.protocol.block;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 1.26.45 &rarr; 1.26.50 block id table, and the hash it is built from.
 *
 * <p>The hash is the part worth pinning hardest: it is computed independently by Minecraft on both
 * ends of the connection, so if this implementation disagrees with Mojang's by one byte, the table
 * maps ids nothing ever sends to ids nothing understands, and the symptom is <em>exactly</em> the
 * one it was written to fix. There is no way to assert against Mojang from a unit test, so what is
 * asserted here is every property the algorithm is specified to have &mdash; sorted keys, the tag
 * bytes, the empty root name, FNV-1a &mdash; plus the shape of the table it produces.
 */
class BlockStateUpgradeTest {

    private static final BlockStateUpgrade UPGRADE = BlockStateUpgrade.load("/blockstate/2169-to-2192.json");

    @Test
    void theTableCoversTheVersionStepItClaims() {
        assertEquals(2169, UPGRADE.fromProtocol());
        assertEquals(2192, UPGRADE.toProtocol());
        assertTrue(UPGRADE.source().contains("bedrock-samples"),
                "the table must record where it was diffed from, or nobody can regenerate it");
    }

    /**
     * 81 stairs at 8 old states each, 57 connection blocks at one, and trip wire at 16: 721 old block
     * states that a 1.26.45 backend can send and a 1.26.50 client could not previously draw.
     */
    @Test
    void everyAffectedOldStateHasAMapping() {
        assertEquals(81 * 8 + 57 + 16, UPGRADE.upgradeCount());
    }

    /**
     * Downgrading has to cover every value of the added properties, not just the defaults, because a
     * 1.26.50 backend genuinely sends inner-corner stairs and connected fences.
     */
    @Test
    void everyNewStateCanBeCarriedBack() {
        assertEquals(81 * 8 * 5 + 57 * 16 + 16 * 16, UPGRADE.downgradeCount());
    }

    @Test
    void aStairIsCarriedBothWays() {
        int oldStair = BedrockBlockStateHash.of("minecraft:oak_stairs", List.of(
                BlockStateValue.of("upside_down_bit", BlockStateValue.Type.BOOL, false),
                BlockStateValue.of("weirdo_direction", BlockStateValue.Type.INT, 2)));
        int newStair = BedrockBlockStateHash.of("minecraft:oak_stairs", List.of(
                BlockStateValue.of("minecraft:corner", BlockStateValue.Type.STRING, "none"),
                BlockStateValue.of("upside_down_bit", BlockStateValue.Type.BOOL, false),
                BlockStateValue.of("weirdo_direction", BlockStateValue.Type.INT, 2)));

        assertNotEquals(oldStair, newStair, "adding a property must change the hash, or nothing was broken");
        assertEquals(newStair, UPGRADE.toNewer(oldStair));
        assertEquals(oldStair, UPGRADE.toOlder(newStair));
    }

    /** A corner shape the old version cannot express still has to resolve to a stair, not to air. */
    @Test
    void aCornerStairFallsBackToAPlainStair() {
        int innerLeft = BedrockBlockStateHash.of("minecraft:oak_stairs", List.of(
                BlockStateValue.of("minecraft:corner", BlockStateValue.Type.STRING, "inner_left"),
                BlockStateValue.of("upside_down_bit", BlockStateValue.Type.BOOL, true),
                BlockStateValue.of("weirdo_direction", BlockStateValue.Type.INT, 1)));
        int plainOld = BedrockBlockStateHash.of("minecraft:oak_stairs", List.of(
                BlockStateValue.of("upside_down_bit", BlockStateValue.Type.BOOL, true),
                BlockStateValue.of("weirdo_direction", BlockStateValue.Type.INT, 1)));

        assertEquals(plainOld, UPGRADE.toOlder(innerLeft));
    }

    @Test
    void aStatelessFenceIsCarriedForward() {
        int oldFence = BedrockBlockStateHash.of("minecraft:oak_fence", List.of());
        int newFence = BedrockBlockStateHash.of("minecraft:oak_fence", List.of(
                BlockStateValue.of("minecraft:connection_east", BlockStateValue.Type.BOOL, false),
                BlockStateValue.of("minecraft:connection_north", BlockStateValue.Type.BOOL, false),
                BlockStateValue.of("minecraft:connection_south", BlockStateValue.Type.BOOL, false),
                BlockStateValue.of("minecraft:connection_west", BlockStateValue.Type.BOOL, false)));

        assertEquals(newFence, UPGRADE.toNewer(oldFence));
        assertEquals(oldFence, UPGRADE.toOlder(newFence));
    }

    /** A connected fence from a 1.26.50 backend must still be a fence on 1.26.45, not nothing. */
    @Test
    void aConnectedFenceFallsBackToAPlainFence() {
        int connected = BedrockBlockStateHash.of("minecraft:oak_fence", List.of(
                BlockStateValue.of("minecraft:connection_east", BlockStateValue.Type.BOOL, true),
                BlockStateValue.of("minecraft:connection_north", BlockStateValue.Type.BOOL, false),
                BlockStateValue.of("minecraft:connection_south", BlockStateValue.Type.BOOL, true),
                BlockStateValue.of("minecraft:connection_west", BlockStateValue.Type.BOOL, false)));
        assertEquals(BedrockBlockStateHash.of("minecraft:oak_fence", List.of()), UPGRADE.toOlder(connected));
    }

    /** Glass panes and iron bars are in the same change as fences, and were just as invisible. */
    @Test
    void panesAndBarsAreCoveredToo() {
        for (String block : new String[]{"minecraft:glass_pane", "minecraft:iron_bars",
                "minecraft:copper_bars", "minecraft:hard_glass_pane"}) {
            int old = BedrockBlockStateHash.of(block, List.of());
            assertTrue(UPGRADE.rewritesToNewer(old), block + " must be carried forward");
            assertNotEquals(old, UPGRADE.toNewer(old), block);
        }
    }

    /**
     * The other half of the design: a block 1.26.50 did not touch must keep its id exactly. If this
     * ever stops holding, the table has started rewriting blocks that were rendering perfectly well.
     */
    @Test
    void anUnchangedBlockIsLeftAlone() {
        for (String block : new String[]{"minecraft:stone", "minecraft:dirt", "minecraft:sand"}) {
            int id = BedrockBlockStateHash.of(block, List.of());
            assertFalse(UPGRADE.rewritesToNewer(id), block + " did not change in 1.26.50");
            assertEquals(id, UPGRADE.toNewer(id), block);
            assertEquals(id, UPGRADE.toOlder(id), block);
        }
        // Slabs are the control: the player who reported this saw slabs render while stairs did not.
        int slab = BedrockBlockStateHash.of("minecraft:oak_slab", List.of(
                BlockStateValue.of("minecraft:vertical_half", BlockStateValue.Type.STRING, "bottom")));
        assertFalse(UPGRADE.rewritesToNewer(slab));
    }

    // --- the hash algorithm itself ------------------------------------------------------------

    /** Property order on the wire is not the hash's order: the hash sorts, so the caller need not. */
    @Test
    void statesAreSortedBeforeHashing() {
        int oneOrder = BedrockBlockStateHash.of("minecraft:oak_stairs", List.of(
                BlockStateValue.of("weirdo_direction", BlockStateValue.Type.INT, 3),
                BlockStateValue.of("upside_down_bit", BlockStateValue.Type.BOOL, true)));
        int otherOrder = BedrockBlockStateHash.of("minecraft:oak_stairs", List.of(
                BlockStateValue.of("upside_down_bit", BlockStateValue.Type.BOOL, true),
                BlockStateValue.of("weirdo_direction", BlockStateValue.Type.INT, 3)));
        assertEquals(oneOrder, otherOrder);
    }

    /** The tag byte is hashed, so the same number written as a bool and as an int are two blocks. */
    @Test
    void theStateTypeIsPartOfTheHash() {
        assertNotEquals(
                BedrockBlockStateHash.of("minecraft:test", List.of(
                        BlockStateValue.of("x", BlockStateValue.Type.BOOL, true))),
                BedrockBlockStateHash.of("minecraft:test", List.of(
                        BlockStateValue.of("x", BlockStateValue.Type.INT, 1))));
    }

    @Test
    void unknownIsTheValueMojangReserves() {
        assertEquals(0xFFFFFFFE, BedrockBlockStateHash.of("minecraft:unknown", List.of()));
    }

    /**
     * FNV-1a, not FNV-1: xor then multiply. The two differ only in that order and produce completely
     * different values, so this pins the one property most easily got backwards.
     */
    @Test
    void theHashIsFnv1aNotFnv1() {
        assertEquals(0xE40C292C, BedrockBlockStateHash.fnv1a32(new byte[]{'a'}));
        assertEquals(0xBF9CF968, BedrockBlockStateHash.fnv1a32(new byte[]{'f', 'o', 'o', 'b', 'a', 'r'}));
    }
}

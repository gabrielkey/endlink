package org.endstone.proxy.protocol.block;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.endstone.proxy.protocol.ModernClientTo2169Translator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The end of the chain: a real packet, through the real edge, with the real table.
 *
 * <p>The unit tests either side of this one prove the hash and the payload parser in isolation. What
 * they cannot prove is that the two are actually joined up to the translator the registry hands a
 * 1.26.50 player — which is the whole bug. A chunk full of 1.26.45 stairs has to come out the other
 * side full of 1.26.50 stairs, or the player sees the holes again.
 */
class BlockStateTranslationTest {

    private static final BlockStateTranslation TRANSLATION = ModernClientTo2169Translator.blocks();

    private static final int OLD_STAIR = BedrockBlockStateHash.of("minecraft:oak_stairs", List.of(
            BlockStateValue.of("upside_down_bit", BlockStateValue.Type.BOOL, false),
            BlockStateValue.of("weirdo_direction", BlockStateValue.Type.INT, 0)));
    private static final int NEW_STAIR = BedrockBlockStateHash.of("minecraft:oak_stairs", List.of(
            BlockStateValue.of("minecraft:corner", BlockStateValue.Type.STRING, "none"),
            BlockStateValue.of("upside_down_bit", BlockStateValue.Type.BOOL, false),
            BlockStateValue.of("weirdo_direction", BlockStateValue.Type.INT, 0)));
    private static final int STONE = BedrockBlockStateHash.of("minecraft:stone", List.of());

    @Test
    void theEdgeIsWiredToTheTable() {
        assertEquals(2169, TRANSLATION.upgrade().fromProtocol());
        assertEquals(2192, TRANSLATION.upgrade().toProtocol());
        assertNotEquals(OLD_STAIR, NEW_STAIR);
    }

    @Test
    void aChunkOfStairsReachesA1_26_50ClientAsStairs() {
        LevelChunkPacket chunk = new LevelChunkPacket();
        chunk.setSubChunksLength(1);
        chunk.setData(subChunkWithPalette(OLD_STAIR, STONE));

        ModernClientTo2169Translator.INSTANCE.translateClientbound(chunk, null);

        assertEquals(List.of(NEW_STAIR, STONE), paletteOf(chunk.getData()),
                "the stair must be renumbered and the stone must not");
    }

    @Test
    void aBlockPlacedAfterTheChunkIsRenumberedToo() {
        UpdateBlockPacket update = new UpdateBlockPacket();
        update.setBlockPosition(Vector3i.ZERO);
        update.setDefinition(definition(OLD_STAIR));

        ModernClientTo2169Translator.INSTANCE.translateClientbound(update, null);

        assertEquals(NEW_STAIR, update.getDefinition().getRuntimeId());
    }

    @Test
    void anUnchangedBlockKeepsItsIdentityObject() {
        UpdateBlockPacket update = new UpdateBlockPacket();
        update.setBlockPosition(Vector3i.ZERO);
        BlockDefinition stone = definition(STONE);
        update.setDefinition(stone);

        ModernClientTo2169Translator.INSTANCE.translateClientbound(update, null);

        assertSame(stone, update.getDefinition(), "a block 1.26.50 did not touch must not be rebuilt");
    }

    /**
     * The serverbound half. A 1.26.50 client names the block it clicked in its own numbering, and a
     * 1.26.45 backend has to be told one it recognises or it rejects the interaction.
     */
    @Test
    void theBlockAPlayerClicksIsCarriedBackDown() {
        PlayerAuthInputPacket input = new PlayerAuthInputPacket();
        var itemUse = new org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction();
        itemUse.setBlockDefinition(definition(NEW_STAIR));
        input.setItemUseTransaction(itemUse);

        ModernClientTo2169Translator.INSTANCE.translateServerbound(input, null);

        assertEquals(OLD_STAIR, input.getItemUseTransaction().getBlockDefinition().getRuntimeId());
    }

    @Test
    void aPlayerAuthInputWithNoItemUseIsLeftAlone() {
        PlayerAuthInputPacket input = new PlayerAuthInputPacket();
        assertSame(input, ModernClientTo2169Translator.INSTANCE.translateServerbound(input, null));
    }

    // --- helpers ------------------------------------------------------------------------------

    private static ByteBuf subChunkWithPalette(int... palette) {
        ByteBuf buffer = Unpooled.buffer();
        buffer.writeByte(8);
        buffer.writeByte(1);
        buffer.writeByte((4 << 1) | 1);
        buffer.writeZero(512 * 4);
        VarInts.writeInt(buffer, palette.length);
        for (int id : palette) {
            VarInts.writeInt(buffer, id);
        }
        return buffer;
    }

    private static List<Integer> paletteOf(ByteBuf data) {
        ByteBuf buffer = data.slice();
        buffer.skipBytes(3);
        buffer.skipBytes(512 * 4);
        int size = VarInts.readInt(buffer);
        List<Integer> palette = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            palette.add(VarInts.readInt(buffer));
        }
        return palette;
    }

    private static BlockDefinition definition(int runtimeId) {
        return () -> runtimeId;
    }
}

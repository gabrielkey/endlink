package org.endstone.proxy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.RecordStartedPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetPlayerFurnaceOptionsPacket;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.endstone.proxy.protocol.block.BedrockBlockStateHash;
import org.endstone.proxy.protocol.block.BlockStateValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The upgrade edge: a 1.26.45 player who has not updated, on a backend that has.
 *
 * <p>The mirror of {@code BlockStateTranslationTest}, and it matters for the same reason in reverse.
 * Once the backends move to 1.26.50 it is the <em>client</em> that has never heard of the stair and
 * fence ids arriving in its chunks, so the holes appear for the players who lagged behind instead of
 * the ones who ran ahead.
 */
class LegacyClientTo2192TranslatorTest {

    private static final int OLD_FENCE = BedrockBlockStateHash.of("minecraft:oak_fence", List.of());
    private static final int NEW_FENCE_CONNECTED = BedrockBlockStateHash.of("minecraft:oak_fence", List.of(
            BlockStateValue.of("minecraft:connection_east", BlockStateValue.Type.BOOL, true),
            BlockStateValue.of("minecraft:connection_north", BlockStateValue.Type.BOOL, true),
            BlockStateValue.of("minecraft:connection_south", BlockStateValue.Type.BOOL, false),
            BlockStateValue.of("minecraft:connection_west", BlockStateValue.Type.BOOL, false)));
    private static final int STONE = BedrockBlockStateHash.of("minecraft:stone", List.of());

    @Test
    void aChunkFromA1_26_50_BackendReachesA1_26_45_ClientWithBlocksItKnows() {
        LevelChunkPacket chunk = new LevelChunkPacket();
        chunk.setSubChunksLength(1);
        chunk.setData(subChunkWithPalette(NEW_FENCE_CONNECTED, STONE));

        LegacyClientTo2192Translator.INSTANCE.translateClientbound(chunk, null);

        assertEquals(List.of(OLD_FENCE, STONE), paletteOf(chunk.getData()),
                "a connected fence has no 1.26.45 spelling, so it must arrive as a plain fence");
    }

    @Test
    void aBlockUpdateIsCarriedDownToo() {
        UpdateBlockPacket update = new UpdateBlockPacket();
        update.setBlockPosition(Vector3i.ZERO);
        update.setDefinition(definition(NEW_FENCE_CONNECTED));

        LegacyClientTo2192Translator.INSTANCE.translateClientbound(update, null);

        assertEquals(OLD_FENCE, update.getDefinition().getRuntimeId());
    }

    /**
     * The two packets 1.26.50 added are clientbound and have no id on a 2169 codec, so forwarding one
     * could not produce anything the client could read. Dropping is the only representable answer.
     */
    @Test
    void thePacketsThisClientHasNoIdForAreDropped() {
        assertNull(LegacyClientTo2192Translator.INSTANCE.translateClientbound(
                new SetPlayerFurnaceOptionsPacket(), null));
        assertNull(LegacyClientTo2192Translator.INSTANCE.translateClientbound(
                new RecordStartedPacket(), null));
    }

    @Test
    void everythingElseIsForwardedUntouched() {
        TextPacket text = new TextPacket();
        assertSame(text, LegacyClientTo2192Translator.INSTANCE.translateClientbound(text, null));
        assertSame(text, LegacyClientTo2192Translator.INSTANCE.translateServerbound(text, null));
    }

    /** Serverbound goes the other way: the client names a block in 1.26.45's numbering. */
    @Test
    void theBlockThePlayerClicksIsCarriedUpToTheBackend() {
        var transaction = new org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket();
        transaction.setBlockDefinition(definition(OLD_FENCE));

        LegacyClientTo2192Translator.INSTANCE.translateServerbound(transaction, null);

        assertEquals(BedrockBlockStateHash.of("minecraft:oak_fence", List.of(
                        BlockStateValue.of("minecraft:connection_east", BlockStateValue.Type.BOOL, false),
                        BlockStateValue.of("minecraft:connection_north", BlockStateValue.Type.BOOL, false),
                        BlockStateValue.of("minecraft:connection_south", BlockStateValue.Type.BOOL, false),
                        BlockStateValue.of("minecraft:connection_west", BlockStateValue.Type.BOOL, false))),
                transaction.getBlockDefinition().getRuntimeId());
    }

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
        buffer.skipBytes(3 + 512 * 4);
        int size = VarInts.readInt(buffer);
        List<Integer> palette = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            palette.add(VarInts.readInt(buffer));
        }
        return palette;
    }

    private static BlockDefinition definition(int runtimeId) {
        return () -> runtimeId;
    }
}

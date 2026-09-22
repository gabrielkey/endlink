package org.endstone.proxy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v354.Bedrock_v354;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;
import org.cloudburstmc.protocol.bedrock.data.InputInteractionModel;
import org.cloudburstmc.protocol.bedrock.data.InputMode;
import org.cloudburstmc.protocol.bedrock.data.ClientPlayMode;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.DimensionDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction;
import org.cloudburstmc.protocol.bedrock.data.MapDecoration;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundMapItemDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.DimensionDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.common.DefinitionRegistry;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Three wire layouts, pinned to Mojang's own protocol dump rather than to what this tree happened to
 * do.
 *
 * <p><b>Why these three and not a sweep.</b> Every one of them survived
 * {@code CrossProtocolPacketSweepTest} and every live join, because a proxy reads and writes with
 * the same serializer: a field it does not know about is missing from both halves, so a round trip
 * agrees with itself and proves nothing. The peer on the other side is what disagrees. So each test
 * here asserts against a number taken from the dump for 1.26.51 rather than from a round trip.</p>
 *
 * <p>Source: {@code EndstoneMC/protocol-docs} at the 1.26.51.1 dump, files
 * {@code types/ItemUseInventoryTransaction.json} and
 * {@code types/DimensionDefinitionGroup__DimensionDefinition.json}.</p>
 */
class WireLayoutAgainstMojangDumpTest {

    /**
     * {@code ItemUseInventoryTransaction} has {@code Hand} between {@code Slot} and {@code Item}.
     *
     * <p>{@code PlayerAuthInputPacket} reaches that structure through
     * {@code PackedItemUseLegacyInventoryTransaction}, so it is the same layout the standalone
     * {@code InventoryTransactionPacket} writes &mdash; and this tree had the byte in one and not
     * the other. Reading the hand as the start of the item does not fail loudly; it shifts every
     * field after it, which is why the assertion below is on the tail of the packet as much as on
     * the hand itself.</p>
     */
    @Test
    void playerAuthInputCarriesTheHandByteTheDumpPutsBeforeTheItem() {
        PlayerAuthInputPacket packet = authInputWithItemUse(1);

        PlayerAuthInputPacket decoded = (PlayerAuthInputPacket) roundTrip(Bedrock_v2193.CODEC, packet);
        ItemUseTransaction transaction = decoded.getItemUseTransaction();

        assertNotNull(transaction, "the transaction is what the whole packet hangs off");
        assertEquals(1, transaction.getHand(), "offhand is what was sent");
        assertEquals(4, transaction.getHotbarSlot(), "the field before the hand byte");
        // Everything past the hand is what a missing byte actually destroys.
        assertEquals(Vector3i.from(7, 8, 9), transaction.getBlockPosition());
        assertEquals(Vector3f.from(1.5f, 2.5f, 3.5f), transaction.getPlayerPosition());
        assertEquals(Vector3f.from(0.25f, 0.5f, 0.75f), transaction.getClickPosition());
        assertEquals(5, transaction.getClientCooldownState());
        // And the packet's own trailing fields, which are read after the transaction returns.
        assertEquals(Vector3f.from(0.1f, 0.2f, 0.3f), decoded.getCameraOrientation());
        assertEquals(Vector2f.from(0.4f, 0.5f), decoded.getRawMoveVector());
    }

    /** A main-hand interaction is the common case and must not be read as an offhand one. */
    @Test
    void playerAuthInputKeepsTheMainHand() {
        PlayerAuthInputPacket decoded =
                (PlayerAuthInputPacket) roundTrip(Bedrock_v2193.CODEC, authInputWithItemUse(0));

        assertEquals(0, decoded.getItemUseTransaction().getHand());
    }

    /**
     * A dimension definition's second varint is {@code Height Range}, not a maximum.
     *
     * <p>The dump names the pair {@code Minimum Y} then {@code Height Range}. This tree wrote the
     * maximum into the first and the minimum into the second, which round trips against itself and
     * is wrong against anybody else. The bytes are read back here rather than the object, because
     * the object is exactly what agreed with itself before.</p>
     */
    @Test
    void aDimensionWritesMinimumYThenHeightRange() {
        DimensionDataPacket packet = new DimensionDataPacket();
        packet.getDefinitions().add(new DimensionDefinition(
                "minecraft:overworld", 320, -64, 0, 0, new UUID(1L, 2L), "minecraft:plains"));

        ByteBuf buffer = encode(Bedrock_v2193.CODEC, packet);
        try {
            VarInts.readUnsignedInt(buffer);                 // definition count
            helperFor(Bedrock_v2193.CODEC).readString(buffer); // id
            assertEquals(-64, VarInts.readInt(buffer), "Minimum Y comes first");
            assertEquals(384, VarInts.readInt(buffer), "and then the range, 320 - -64");
        } finally {
            buffer.release();
        }
    }

    /** The same definition still survives a relay, which is what the old code got right. */
    @Test
    void aDimensionStillRoundTrips() {
        DimensionDataPacket packet = new DimensionDataPacket();
        packet.getDefinitions().add(new DimensionDefinition(
                "minecraft:overworld", 320, -64, 0, 0, new UUID(1L, 2L), "minecraft:plains"));

        DimensionDataPacket decoded = (DimensionDataPacket) roundTrip(Bedrock_v2193.CODEC, packet);
        DimensionDefinition definition = decoded.getDefinitions().get(0);

        assertEquals(-64, definition.getMinimumHeight());
        assertEquals(320, definition.getMaximumHeight());
    }

    /**
     * Map decorations decode on the v354 codec.
     *
     * <p>Its {@code deserialize} called {@code writeMapDecorations} &mdash; the writer &mdash; on
     * the buffer it was reading. Nothing the proxy speaks reaches it: v544 overrides
     * {@code deserialize} and so does v2168, which covers v1001 and every modern codec, so this is
     * a latent fault in vendored code from v354 to v534 rather than something that was breaking
     * maps. It is pinned anyway, because the next serializer to inherit from v354 would inherit
     * this too.</p>
     */
    @Test
    void mapDecorationsDecodeOnTheV354Codec() {
        ClientboundMapItemDataPacket packet = new ClientboundMapItemDataPacket();
        packet.setUniqueMapId(42L);
        packet.setDimensionId(0);
        packet.setScale((byte) 1);
        packet.setDecorations(List.of(new MapDecoration(3, 2, 7, 9, "spawn", 16711935)));

        ClientboundMapItemDataPacket decoded =
                (ClientboundMapItemDataPacket) roundTrip(Bedrock_v354.CODEC, packet);

        assertEquals(1, decoded.getDecorations().size(), "the decoration must come back");
        MapDecoration decoration = decoded.getDecorations().get(0);
        assertEquals("spawn", decoration.getLabel());
        assertEquals(7, decoration.getXOffset());
        assertEquals(9, decoration.getYOffset());
    }

    private static PlayerAuthInputPacket authInputWithItemUse(int hand) {
        ItemUseTransaction transaction = new ItemUseTransaction();
        transaction.setLegacyRequestId(0);
        transaction.setActionType(0);
        transaction.setTriggerType(ItemUseTransaction.TriggerType.PLAYER_INPUT);
        transaction.setBlockPosition(Vector3i.from(7, 8, 9));
        transaction.setBlockFace(1);
        transaction.setHotbarSlot(4);
        transaction.setHand(hand);
        transaction.setItemInHand(ItemData.AIR);
        transaction.setPlayerPosition(Vector3f.from(1.5f, 2.5f, 3.5f));
        transaction.setClickPosition(Vector3f.from(0.25f, 0.5f, 0.75f));
        transaction.setBlockDefinition(new SimpleBlockDefinition("minecraft:stone", 11, NbtMap.EMPTY));
        transaction.setClientInteractPrediction(ItemUseTransaction.PredictedResult.SUCCESS);
        transaction.setClientCooldownState(5);

        PlayerAuthInputPacket packet = new PlayerAuthInputPacket();
        packet.setRotation(Vector3f.from(1f, 2f, 3f));
        packet.setPosition(Vector3f.from(4f, 5f, 6f));
        packet.setMotion(Vector2f.from(0f, 0f));
        packet.setInputMode(InputMode.MOUSE);
        packet.setPlayMode(ClientPlayMode.SCREEN);
        packet.setInputInteractionModel(InputInteractionModel.CLASSIC);
        packet.setInteractRotation(Vector2f.from(0f, 0f));
        packet.setTick(1L);
        packet.setDelta(Vector3f.ZERO);
        packet.setAnalogMoveVector(Vector2f.from(0f, 0f));
        packet.setCameraOrientation(Vector3f.from(0.1f, 0.2f, 0.3f));
        packet.setRawMoveVector(Vector2f.from(0.4f, 0.5f));
        packet.getInputData().add(PlayerAuthInputData.PERFORM_ITEM_INTERACTION);
        packet.setItemUseTransaction(transaction);
        return packet;
    }

    private static ByteBuf encode(BedrockCodec codec, BedrockPacket packet) {
        ByteBuf buffer = Unpooled.buffer();
        codec.tryEncode(helperFor(codec), buffer, packet);
        return buffer;
    }

    private static BedrockPacket roundTrip(BedrockCodec codec, BedrockPacket packet) {
        int id = codec.getPacketDefinition(packet.getClass()).getId();
        ByteBuf buffer = encode(codec, packet);
        try {
            return codec.tryDecode(helperFor(codec), buffer, id);
        } finally {
            buffer.release();
        }
    }

    /** Both registries, for the same reason {@code CrossProtocolCoverageTest} installs them. */
    private static BedrockCodecHelper helperFor(BedrockCodec codec) {
        BedrockCodecHelper helper = codec.createHelper();
        helper.setBlockDefinitions(new DefinitionRegistry<>() {
            @Override
            public BlockDefinition getDefinition(int runtimeId) {
                return new SimpleBlockDefinition("minecraft:stone", runtimeId, NbtMap.EMPTY);
            }

            @Override
            public boolean isRegistered(BlockDefinition definition) {
                return definition != null;
            }
        });
        helper.setItemDefinitions(new DefinitionRegistry<>() {
            @Override
            public ItemDefinition getDefinition(int runtimeId) {
                return new SimpleItemDefinition("minecraft:air", runtimeId, false);
            }

            @Override
            public boolean isRegistered(ItemDefinition definition) {
                return definition != null;
            }
        });
        return helper;
    }
}

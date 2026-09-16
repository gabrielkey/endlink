package org.endstone.proxy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v2168.BedrockCodecHelper_v2168;
import org.cloudburstmc.protocol.bedrock.codec.v2169.BedrockCodecHelper_v2169;
import org.cloudburstmc.protocol.bedrock.codec.v2169.Bedrock_v2169;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;
import org.cloudburstmc.protocol.bedrock.data.FurnaceOptions;
import org.cloudburstmc.protocol.bedrock.data.PacketRecipient;
import org.cloudburstmc.protocol.bedrock.data.attributelayer.EnvironmentAttributeData;
import org.cloudburstmc.protocol.bedrock.data.attributelayer.FloatAttributeData;
import org.cloudburstmc.protocol.bedrock.data.attributelayer.UpdateEnvironmentAttributesData;
import org.cloudburstmc.protocol.bedrock.data.camera.CameraEase;
import org.cloudburstmc.protocol.bedrock.data.definitions.DimensionDefinition;
import org.cloudburstmc.protocol.bedrock.data.diagnostics.EntityDiagnosticTimingInfo;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundAttributeLayerSyncPacket;
import org.cloudburstmc.protocol.bedrock.packet.DimensionDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.RecordStartedPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerboundDiagnosticsPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetPlayerFurnaceOptionsPacket;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 1.26.45 &rarr; 1.26.50 hop, for the three fields a 1.26.45 peer cannot supply.
 *
 * <p><b>Why this is a separate test from the packet sweep.</b> {@code CrossProtocolPacketSweepTest}
 * populates every field of every packet before re-encoding it, so a field that is null <em>because
 * the sending version has no such field</em> is the one thing it can never see. That is how the
 * 1.26.40 {@code SubChunkPacket} regression shipped: chat worked and the world was empty. 1.26.50
 * adds three fields of exactly that kind &mdash; a dimension's default biome, an entity diagnostic's
 * position and dimension, and an environment attribute's noise alignment &mdash; and all three are
 * written by a method that rejects null.</p>
 *
 * <p>So each test below decodes with the <em>2169</em> codec, which is the only way to get the
 * genuinely absent value rather than a null someone remembered to set, and then encodes with 2193,
 * which is what the proxy does for a 1.26.50 client on a 1.26.45 backend. A throw here is a packet
 * the relay silently drops. 2193 rather than 2192 because 2193 is what ships and what is
 * registered; it inherits every serializer here from 2192 unchanged, so this exercises the same
 * code either way and pins the codec the proxy will actually hand a player.</p>
 */
class CrossProtocol2192AbsentFieldTest {

    private static final BedrockCodec OLD = Bedrock_v2169.CODEC;
    private static final BedrockCodec NEW = Bedrock_v2193.CODEC;

    @Test
    void aDimensionFromA1_26_45BackendReachesA1_26_50Client() {
        DimensionDataPacket packet = new DimensionDataPacket();
        packet.getDefinitions().add(new DimensionDefinition(
                "minecraft:overworld", 320, -64, 0, 0, UUID.randomUUID(), "minecraft:plains"));

        DimensionDataPacket decoded = (DimensionDataPacket) roundTrip(OLD, packet);
        // The premise: 2169 has no default biome to read, so it comes back absent, not empty.
        assertNull(decoded.getDefinitions().get(0).getDefaultBiome());

        assertDoesNotThrow(() -> encode(NEW, decoded),
                "DimensionData is sent before StartGame, so losing it costs the join, not a detail");
    }

    @Test
    void anEnvironmentAttributeFromA1_26_45BackendReachesA1_26_50Client() {
        ClientboundAttributeLayerSyncPacket packet = new ClientboundAttributeLayerSyncPacket();
        packet.setData(new UpdateEnvironmentAttributesData("fog", 0, List.of(
                new EnvironmentAttributeData(
                        "density",
                        null,
                        new FloatAttributeData(0.5f, FloatAttributeData.Operation.OVERRIDE, null, null),
                        null,
                        0,
                        20,
                        CameraEase.LINEAR,
                        0,
                        false,
                        null))));

        ClientboundAttributeLayerSyncPacket decoded =
                (ClientboundAttributeLayerSyncPacket) roundTrip(OLD, packet);
        UpdateEnvironmentAttributesData data = (UpdateEnvironmentAttributesData) decoded.getData();
        assertNull(data.getAttributes().get(0).getNoiseAlignment());

        assertDoesNotThrow(() -> encode(NEW, decoded));
    }

    @Test
    void anEntityDiagnosticFromA1_26_45PeerReachesA1_26_50Peer() {
        ServerboundDiagnosticsPacket packet = new ServerboundDiagnosticsPacket();
        packet.getEntityDiagnostics().add(new EntityDiagnosticTimingInfo(
                "Zombie", "minecraft:zombie", 1_000L, (byte) 5, Vector3f.from(1, 2, 3), "overworld"));

        ServerboundDiagnosticsPacket decoded = (ServerboundDiagnosticsPacket) roundTrip(OLD, packet);
        assertNull(decoded.getEntityDiagnostics().get(0).getPosition());
        assertNull(decoded.getEntityDiagnostics().get(0).getDimension());

        assertDoesNotThrow(() -> encode(NEW, decoded));
    }

    /**
     * The two packets 1.26.50 added are clientbound, so the only peer that can send one is a 1.26.50
     * backend &mdash; and the proxy's own graph runs newer client to older backend, never the
     * reverse. That is the whole reason {@link ModernClientTo2169Translator} needs no drop rule; if
     * either ever became serverbound, it would.
     */
    @Test
    void theTwoNewPacketsExistOnlyOnTheNewerSide() {
        assertEquals(351, NEW.getPacketDefinition(SetPlayerFurnaceOptionsPacket.class).getId());
        assertEquals(352, NEW.getPacketDefinition(RecordStartedPacket.class).getId());
        assertNull(OLD.getPacketDefinition(351));
        assertNull(OLD.getPacketDefinition(352));
        assertEquals(PacketRecipient.CLIENT, NEW.getPacketDefinition(352).getRecipient());
    }

    /**
     * 351 goes both ways, and the drop rule depends on it.
     *
     * <p>This tree copied {@code PacketRecipient.CLIENT} from upstream's 2192 codec, and upstream
     * has since corrected it to {@code BOTH} ({@code 863e6e91}). The difference is not cosmetic:
     * {@code tryDecode} refuses a packet whose definition names the other recipient, so with
     * {@code CLIENT} a 1.26.50 player who touched a furnace screen threw on decode; and with
     * {@code BOTH} but no drop rule, the same packet reaches a 2169 backend codec that has no
     * definition to encode it against. Both halves are needed, so both are pinned here and in
     * {@code ClientRelayPacketHandler.shouldDropCrossProtocolServerbound}.
     */
    @Test
    void theFurnaceOptionsPacketIsAcceptedFromAClientAndDroppedTowardsAnOlderBackend() {
        assertEquals(PacketRecipient.BOTH, NEW.getPacketDefinition(351).getRecipient());
        assertDoesNotThrow(() -> {
            ByteBuf buffer = Unpooled.buffer();
            try {
                SetPlayerFurnaceOptionsPacket packet = new SetPlayerFurnaceOptionsPacket();
                packet.setType(SetPlayerFurnaceOptionsPacket.FurnaceType.BLAST_FURNACE);
                packet.setOptions(new FurnaceOptions(
                        FurnaceOptions.FurnaceLeftTabIndex.RECIPE_FOOD, true,
                        FurnaceOptions.FurnaceLayout.DEFAULT));
                NEW.tryEncode(NEW.createHelper(), buffer, packet);
                // SERVER is the recipient a packet arriving from a player carries. This threw
                // "was sent to SERVER instead of CLIENT" before the recipient was corrected.
                assertEquals(packet, NEW.tryDecode(NEW.createHelper(), buffer, 351, PacketRecipient.SERVER));
            } finally {
                buffer.release();
            }
        });
    }

    /**
     * Every id the two versions share must still mean the same packet, or a relayed packet arrives
     * as something else entirely. 351 and 352 are the only additions, so this is otherwise equality.
     */
    @Test
    void theSharedPacketIdTableIsUnchanged() {
        for (int id = 0; id < 512; id++) {
            var oldDefinition = OLD.getPacketDefinition(id);
            var newDefinition = NEW.getPacketDefinition(id);
            if (id == 351 || id == 352) {
                assertNull(oldDefinition, "id " + id + " must be 1.26.50-only");
                continue;
            }
            if (oldDefinition == null && newDefinition == null) {
                continue;
            }
            assertTrue(oldDefinition != null && newDefinition != null, "id " + id + " exists on one side only");
            assertEquals(oldDefinition.getFactory().get().getClass(),
                    newDefinition.getFactory().get().getClass(),
                    "id " + id + " is a different packet on each side");
        }
    }

    /**
     * 1.26.50 inherits 1.26.45's {@code RemoveScore} shape, and this tree carries that shape as a
     * flag on the shared 2168 helper rather than as a codec of its own. So the 2192 helper has to
     * derive from the 2169 one &mdash; deriving from 2168 would leave a 1.26.50 peer one unparseable
     * version string away from the 1.26.44 shape, on a packet the server broadcasts.
     */
    @Test
    void theNewCodecKeepsThe1_26_45RemoveScoreShape() {
        BedrockCodecHelper_v2168 helper = (BedrockCodecHelper_v2168) NEW.createHelper();
        assertTrue(helper instanceof BedrockCodecHelper_v2169);
        assertFalse(helper.isRemoveScoreKeyedConstant());
        helper.setRemoveScoreKeyedConstant(true);
        assertFalse(helper.isRemoveScoreKeyedConstant(), "the setter must stay inert");
    }

    private static BedrockPacket roundTrip(BedrockCodec codec, BedrockPacket packet) {
        int id = codec.getPacketDefinition(packet.getClass()).getId();
        ByteBuf buffer = Unpooled.buffer();
        try {
            codec.tryEncode(codec.createHelper(), buffer, packet);
            return codec.tryDecode(codec.createHelper(), buffer, id);
        } finally {
            buffer.release();
        }
    }

    private static void encode(BedrockCodec codec, BedrockPacket packet) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            codec.tryEncode(codec.createHelper(), buffer, packet);
        } finally {
            buffer.release();
        }
    }
}

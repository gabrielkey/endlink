package org.cloudburstmc.protocol.bedrock.codec.v2192;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v2169.Bedrock_v2169;
import org.cloudburstmc.protocol.bedrock.codec.v2192.serializer.*;
import org.cloudburstmc.protocol.bedrock.data.PacketRecipient;
import org.cloudburstmc.protocol.bedrock.packet.*;

/**
 * Minecraft 1.26.50, network protocol 2192.
 *
 * <p>Ported from CloudburstMC/Protocol's own 2192 codec (branch {@code 3.0}, commit {@code b01ef8a}
 * plus the two follow-up fixes {@code 6d90389} and {@code 5ee1d85}), onto this tree's 2168/2169
 * codecs rather than upstream's. Two things had to change in the port and both matter:
 *
 * <ul>
 *   <li>{@link BedrockCodecHelper_v2192} derives from the <b>2169</b> helper here, because this tree
 *       carries the 1.26.44 {@code RemoveScore} difference as a flag on the shared 2168 helper where
 *       upstream carries it as a separate codec. See that class.
 *   <li>The serializers this codec does <em>not</em> replace keep this tree's 2168 versions, patches
 *       included — most visibly {@code MoveEntityDeltaSerializer_v2168}'s flag-set handling, which
 *       {@link MoveEntityDeltaSerializer_v2192} extends rather than restates.
 * </ul>
 *
 * <p>Unlike 2169, this is a real format change rather than a renumbering. Thirteen packets move and
 * two are new, so a 1.26.50 client on an older backend needs the packets rewritten, not passed
 * through — {@code CanonicalProtocol.sharesWireFormat} deliberately does not put 2192 in a family
 * with 2168/2169.
 *
 * <p><b>Nothing ever asks for 2192 on the wire.</b> The number belongs to Preview 1.26.50.20
 * through 1.26.50.27 and to nothing else; stable 1.26.50 renumbered to 2193 and changed not one
 * byte besides. This class stays the one that holds the format, because the format is what it
 * describes and what the preview dumps were read against;
 * {@link org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193} is the renumbering on top of
 * it, and is the codec the proxy actually registers. Read {@code 2192} in a type or resource name
 * here as "the shape 1.26.50 introduced", not as a protocol number any peer will ever send.
 */
public class Bedrock_v2192 extends Bedrock_v2169 {

    public static final BedrockCodec CODEC = Bedrock_v2169.CODEC.toBuilder()
            .protocolVersion(2192)
            .minecraftVersion("1.26.50")
            .helper(() -> new BedrockCodecHelper_v2192(ENTITY_DATA, GAME_RULE_TYPES, ITEM_STACK_REQUEST_TYPES,
                    CONTAINER_SLOT_TYPES, PLAYER_ABILITIES, TEXT_PROCESSING_ORIGINS))
            .updateSerializer(BossEventPacket.class, BossEventSerializer_v2192.INSTANCE)
            .updateSerializer(CameraPresetsPacket.class, CameraPresetsSerializer_v2192.INSTANCE)
            .updateSerializer(ClientboundAttributeLayerSyncPacket.class, ClientboundAttributeLayerSyncSerializer_v2192.INSTANCE)
            .updateSerializer(DebugDrawerPacket.class, DebugDrawerSerializer_v2192.INSTANCE)
            .updateSerializer(DimensionDataPacket.class, DimensionDataSerializer_v2192.INSTANCE)
            .updateSerializer(InventoryTransactionPacket.class, InventoryTransactionSerializer_v2192.INSTANCE)
            .updateSerializer(ItemStackResponsePacket.class, ItemStackResponseSerializer_v2192.INSTANCE)
            .updateSerializer(MoveEntityDeltaPacket.class, MoveEntityDeltaSerializer_v2192.INSTANCE)
            .updateSerializer(PlayerAuthInputPacket.class, PlayerAuthInputSerializer_v2192.INSTANCE)
            .updateSerializer(PlaySoundPacket.class, PlaySoundSerializer_v2192.INSTANCE)
            .updateSerializer(SubChunkPacket.class, SubChunkSerializer_v2192.INSTANCE)
            .updateSerializer(ServerboundDiagnosticsPacket.class, ServerboundDiagnosticsSerializer_v2192.INSTANCE)
            .updateSerializer(ServerboundPackSettingChangePacket.class, ServerboundPackSettingChangeSerializer_v2192.INSTANCE)
            .registerPacket(SetPlayerFurnaceOptionsPacket::new, SetPlayerFurnaceOptionsSerializer_v2192.INSTANCE, 351, PacketRecipient.BOTH)
            .registerPacket(RecordStartedPacket::new, RecordStartedSerializer_v2192.INSTANCE, 352, PacketRecipient.CLIENT)
            .build();
}

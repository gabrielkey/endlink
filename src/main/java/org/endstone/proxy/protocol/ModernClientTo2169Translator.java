package org.endstone.proxy.protocol;

import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.endstone.proxy.protocol.block.BlockJoinFaces;
import org.endstone.proxy.protocol.block.BlockJoinIndex;
import org.endstone.proxy.protocol.block.BlockStateTranslation;
import org.endstone.proxy.protocol.block.BlockStateUpgrade;
import org.endstone.proxy.protocol.block.StairIndex;

/**
 * Adjacent-version translator for the 1.26.50 (protocol 2193) &harr; 1.26.45 (protocol 2169) step.
 *
 * <p>This is the gap that matters while Minecraft 1.26.50 is out and server software is not: clients
 * update themselves, Endstone backends do not, so 2193 on the player leg and 2169 (or 2168) on the
 * backend leg is the ordinary deployment until the backends catch up.</p>
 *
 * <p><b>Not an {@link IdentityTranslator898}, even though it does not rewrite anything.</b> The
 * previous step, 2169 &rarr; 2168, is an identity edge because the two <em>share a wire format</em>
 * and differ in one field each codec helper settles for itself. This one does not: 1.26.50 reshapes
 * thirteen packets and adds two. It is an own class so that {@code sharesWireFormat} stays the only
 * thing that answers "same format?", and so the analysis below has somewhere to live and somewhere
 * for a real translation to be added when one is found to be needed.</p>
 *
 * <p><b>Why nothing needs rewriting anyway.</b> The reshaped packets are handled by the codecs the
 * way every earlier step's are &mdash; decode with one leg's codec, re-encode with the other's, and a
 * field one version has never heard of is simply never written. {@code Bedrock_v2192} overrides
 * thirteen serializers for exactly that. (That class holds the format; {@code Bedrock_v2193} is the
 * renumbering on top of it and is what is registered. 2192 was only ever the preview's number, and
 * the {@code 2192} in the type and resource names here reads as "the shape 1.26.50 introduced".)
 * Three of those serializers read a field older versions have no value for, so this tree's copies
 * write a documented neutral value rather than throwing:
 * {@code DimensionDataSerializer_v2192} (the default biome), {@code ServerboundDiagnosticsSerializer_v2192}
 * (an entity's position and dimension) and {@code ClientboundAttributeLayerSyncSerializer_v2192}
 * (the noise alignment). That is the "layout differences, not presence differences" trap the 1.26.40
 * {@code SubChunkPacket} regression was: a re-encode sweep populates every field and so never sees
 * it.</p>
 *
 * <p><b>The two new packets, and the one of them that did need a drop rule.</b>
 * {@code SetPlayerFurnaceOptionsPacket} (351) and {@code RecordStartedPacket} (352) both exist only
 * from 1.26.50. 352 is clientbound, so the only peer that could produce one is a 1.26.50
 * <em>backend</em>, and this edge runs the other way; it needs nothing.
 *
 * <p>351 was read the same way and that was wrong. It is {@code PacketRecipient.BOTH} &mdash;
 * upstream corrected its own codec in CloudburstMC/Protocol {@code 863e6e91}, after this tree had
 * already copied the {@code CLIENT} it had at the time &mdash; so a 1.26.50 client does send one,
 * and a 2169 backend codec has no id to encode it against. It is dropped serverbound by
 * {@code ClientRelayPacketHandler.shouldDropCrossProtocolServerbound}, which is where the packets
 * that exist on one leg and not the other are dropped and logged. Note what the mistake was: not a
 * misread wire format, but a <em>direction</em> taken on faith from a codec that had guessed. A new
 * packet's recipient is worth checking against a peer that actually sends it.</p>
 *
 * <p><b>What the cross-protocol machinery already takes care of.</b> Because 2193 is deliberately not
 * in {@code sharesWireFormat}'s family, this pairing is {@code isCrossProtocol()}, which already
 * drops {@code DebugDrawerPacket} clientbound and {@code ServerboundDiagnosticsPacket} serverbound
 * &mdash; two of the three packets whose 1.26.50 shape carries a field older versions cannot supply.
 * The neutral values above are the belt to that braces: an addon may register its own edges, and the
 * codecs must not throw whichever route a packet takes.</p>
 *
 * <p><b>What the codecs could not take care of: the blocks.</b> Everything above concerns packet
 * shape, and packet shape turned out not to be what broke. 1.26.50 added properties to 139 block
 * types &mdash; every stair, fence, glass pane, iron and copper bar and trip wire &mdash; and a
 * block's network id is a hash of its state, so every one of those ids became a number the other
 * side has never heard of. A 1.26.50 player on a 1.26.45 backend saw no stairs, no fences, no panes
 * and no bars: not misplaced, <em>absent</em>, because a client with no block for an id draws air.
 * No amount of correct packet encoding fixes that; the ids themselves have to be translated, which
 * is what {@link BlockStateTranslation} does here, in both directions, using a table diffed from
 * Mojang's own per-version block metadata.</p>
 */
public final class ModernClientTo2169Translator implements PacketTranslator {
    public static final ModernClientTo2169Translator INSTANCE = new ModernClientTo2169Translator();

    /**
     * Built once and shared: the table is immutable, costs a few hundred kilobytes of small maps, and
     * loading it per session would parse the same resource for every player who joins.
     */
    private static final StairIndex STAIRS = StairIndex.load("/blockstate/2169-to-2192.json");

    private static final BlockStateUpgrade UPGRADE =
            BlockStateUpgrade.load("/blockstate/2169-to-2192.json");

    private static final BlockJoinIndex JOINS = BlockJoinIndex.load(
            "/blockstate/2169-to-2192.json", "/blockstate/block-joins-2192.json");

    /**
     * Which blocks a fence or pane reaches out to. Keyed by the older version's ids and resolved
     * through {@code UPGRADE}, so one table answers for both numberings.
     */
    private static final BlockJoinFaces FACES =
            BlockJoinFaces.load("/blockstate/block-join-faces-2169.txt", UPGRADE);

    private static final BlockStateTranslation BLOCKS =
            new BlockStateTranslation(UPGRADE, STAIRS, JOINS, FACES);

    private ModernClientTo2169Translator() {
    }

    /** The block id table this edge applies, exposed for diagnostics and tests. */
    public static BlockStateTranslation blocks() {
        return BLOCKS;
    }

    /** The stair index, for a relay that has to settle chunk seams of its own. */
    public static StairIndex stairs() {
        return STAIRS;
    }

    /** The fence, pane and bar index, for the same reason. */
    public static BlockJoinIndex joins() {
        return JOINS;
    }

    @Override
    public BedrockPacket translateServerbound(BedrockPacket packet, TranslationContext context) {
        return BLOCKS.rewriteServerbound(packet, BLOCKS.toOlder());
    }

    @Override
    public BedrockPacket translateClientbound(BedrockPacket packet, TranslationContext context) {
        return BLOCKS.rewriteClientbound(packet, BLOCKS.toNewer());
    }

    @Override
    public AvailableCommandsPacket translateCommandTree(AvailableCommandsPacket packet, TranslationContext context) {
        return packet;
    }
}

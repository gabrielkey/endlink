package org.endstone.proxy.protocol;

import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.RecordStartedPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetPlayerFurnaceOptionsPacket;
import org.endstone.proxy.protocol.block.BlockStateTranslation;

/**
 * The 1.26.45 (protocol 2169) client on a 1.26.50 (protocol 2192) backend &mdash; the one edge in
 * this proxy that runs <em>up</em> a version.
 *
 * <p><b>Why this exists when the rule says it should not.</b> {@link ProtocolRegistry} states, and
 * means, that the proxy's own graph only goes newer client to older backend; upgrade edges are left
 * to addons, because translating a packet a client is too old to have ever seen is open-ended work.
 * This edge is the exception, and narrowly: the version step it crosses is
 * {@link ModernClientTo2169Translator}'s, reversed, and everything that makes that one safe is
 * symmetric. The codecs reshape the thirteen changed packets in either direction already; the block
 * id table is built from both versions' block definitions and inverts exactly; and the only packets
 * one side has and the other does not are the two named below.
 *
 * <p><b>Why it is worth having.</b> The other edge covers the ordinary case, where clients update
 * before servers do. This covers the case after the backends catch up and a player has not: without
 * it, a 1.26.45 player meeting a 1.26.50 backend is refused at the door with no path through the
 * graph at all, which is a worse outcome than the missing stairs that started this. Both pairings
 * exist during the days either side of a release, and a proxy that only handles one of them makes
 * the other an outage.
 *
 * <p><b>What it drops.</b> {@code SetPlayerFurnaceOptionsPacket} (351) and {@code RecordStartedPacket}
 * (352) are new at 1.26.50 and clientbound. A 2169 codec has no id for either, so re-encoding one for
 * a 1.26.45 player cannot produce anything that client could read &mdash; the packet is dropped
 * rather than forwarded. Both carry incidental UI state, so losing them costs a furnace screen
 * preference and a recording indicator, not gameplay. Nothing serverbound was added, so a 1.26.45
 * client sends nothing a 1.26.50 backend cannot parse.
 *
 * <p><b>What it renumbers.</b> The mirror of the other edge: blocks going <em>down</em> to the player
 * and <em>up</em> to the backend. A 1.26.50 backend's stairs, fences, panes and bars would otherwise
 * be invisible to a 1.26.45 client for precisely the same reason they were invisible the other way
 * round. A corner stair or a connected fence has no 1.26.45 spelling, so it arrives as the plain
 * block &mdash; which is what that client drew for itself before these were block states.
 */
public final class LegacyClientTo2192Translator implements PacketTranslator {
    public static final LegacyClientTo2192Translator INSTANCE = new LegacyClientTo2192Translator();

    /** The same table as the downgrade edge, read the other way. */
    private static final BlockStateTranslation BLOCKS = ModernClientTo2169Translator.blocks();

    private LegacyClientTo2192Translator() {
    }

    @Override
    public BedrockPacket translateServerbound(BedrockPacket packet, TranslationContext context) {
        return BLOCKS.rewriteServerbound(packet, BLOCKS.toNewer());
    }

    @Override
    public BedrockPacket translateClientbound(BedrockPacket packet, TranslationContext context) {
        if (packet instanceof SetPlayerFurnaceOptionsPacket || packet instanceof RecordStartedPacket) {
            return null;
        }
        return BLOCKS.rewriteClientbound(packet, BLOCKS.toOlder());
    }

    @Override
    public AvailableCommandsPacket translateCommandTree(AvailableCommandsPacket packet, TranslationContext context) {
        return packet;
    }
}

package org.cloudburstmc.protocol.bedrock.codec.v2193;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v2192.Bedrock_v2192;

/**
 * Minecraft 1.26.50 and 1.26.51, network protocol 2193.
 *
 * <p><b>2192 was never released.</b> It is the number Preview 1.26.50.20 through 1.26.50.27 asked
 * for, and the number every codec here was written against, because the preview was all there was
 * to write against. The stable release renumbered it to 2193 on the way out and changed nothing
 * else, so a proxy that speaks 2192 and only 2192 turns away every real 1.26.50 player with
 * {@code LOGIN_FAILED_SERVER_OLD} — which is exactly what it did:
 *
 * <pre>
 * Rejected /… with LOGIN_FAILED_SERVER_OLD: client protocol 2193, proxy speaks up to 2192 (1.26.50).
 * </pre>
 *
 * <p>"Changed nothing else" is checked rather than assumed. Mojang's own schema dump for
 * 1.26.50.27-preview ({@code EndstoneMC/protocol-docs} {@code 71fe02f7} on branch {@code r26_u5})
 * against the dump for 1.26.50.5-stable ({@code becee3c5} on the same branch) touches <b>two</b>
 * files: the README, and the protocol number constraint in
 * {@code packets/RequestNetworkSettingsPacket.json}. No packet, no type, no constraint anywhere
 * else moves. So every serializer, helper, entity data layout, type map and packet id is inherited
 * from {@link Bedrock_v2192} unchanged and this codec is a renumbering, in the same way
 * {@code Bedrock_v2169} was one.
 *
 * <p><b>1.26.51 is the same protocol.</b> The dump for 1.26.51.1-stable ({@code 0db61859}) against
 * 1.26.50.5 changes one line of the README and nothing else, so both releases are served by this
 * codec — see {@code CanonicalProtocol.V1_26_50}'s newest-release override, which is evidence here
 * rather than the guess the comment there refuses to make.
 *
 * <p>Diff two <em>commits</em> on {@code r26_u5}, never that branch against another: the branches
 * track release lines and move under you. {@code r26_u6} is already protocol 2208.
 *
 * <p>Upstream (CloudburstMC/Protocol, branch {@code 3.0}) has no 2193 codec as of {@code 863e6e91};
 * it is still on 2192 and has not caught the renumber. There is nothing to port when it does.
 */
public class Bedrock_v2193 extends Bedrock_v2192 {

    public static final BedrockCodec CODEC = Bedrock_v2192.CODEC.toBuilder()
            .protocolVersion(2193)
            .minecraftVersion("1.26.50")
            .build();
}

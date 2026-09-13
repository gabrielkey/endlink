package org.endstone.proxy.protocol.block;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The block network id Minecraft derives from a block state, for servers that hash them.
 *
 * <p>When {@code StartGame.blockNetworkIdsHashed} is set — which every modern Bedrock server does —
 * a block's network id is not an index into any palette that gets sent. It is the FNV-1a 32-bit hash
 * of the block state serialised as little-endian NBT, computed independently on both ends. That is
 * what makes a proxy between two Minecraft versions work at all: a block whose state definition did
 * not change hashes the same on both sides and needs no translation. It is also why one that
 * <em>did</em> change vanishes — the id the server sends matches nothing the client knows, and the
 * client draws air. See {@link BlockStateUpgrade}.
 *
 * <p>The byte layout is exact and unforgiving, so it is written out here rather than assembled with
 * an NBT library whose tag order or root naming might differ:
 *
 * <pre>
 *   0x0A  0x00 0x00                       compound, empty root name
 *   0x08  "name"   &lt;identifier&gt;           the block identifier
 *   0x0A  "states"                        compound, opened
 *         &lt;tag&gt; &lt;key&gt; &lt;value&gt; ...        every property, sorted by key
 *   0x00                                  end of states
 *   0x00                                  end of root
 * </pre>
 *
 * <p>Every string is a little-endian {@code uint16} length followed by UTF-8 bytes, and the property
 * order is a plain lexicographic sort of the keys — not the order the server happens to hold them
 * in. Ported from the reference implementation in df-mc/dragonfly
 * ({@code server/world/network_block_hash.go}).
 */
public final class BedrockBlockStateHash {

    private static final int FNV1A_32_OFFSET_BASIS = 0x811C9DC5;
    private static final int FNV1A_32_PRIME = 0x01000193;

    /** What {@code minecraft:unknown} hashes to; Mojang special-cases it rather than hashing it. */
    public static final int UNKNOWN = 0xFFFFFFFE;

    private BedrockBlockStateHash() {
    }

    /**
     * The network id for {@code identifier} with {@code states}.
     *
     * <p>{@code states} may arrive in any order; it is sorted here, because the caller building a
     * state from a metadata file has no reason to know that the order is part of the hash.
     */
    public static int of(String identifier, List<BlockStateValue> states) {
        if ("minecraft:unknown".equals(identifier)) {
            return UNKNOWN;
        }

        List<BlockStateValue> sorted = new ArrayList<>(states);
        sorted.sort(Comparator.comparing(BlockStateValue::name));

        ByteArrayOutputStream out = new ByteArrayOutputStream(64 + sorted.size() * 24);
        out.write(0x0A);
        out.write(0x00);
        out.write(0x00);

        out.write(0x08);
        writeString(out, "name");
        writeString(out, identifier);

        out.write(0x0A);
        writeString(out, "states");
        for (BlockStateValue state : sorted) {
            switch (state.type()) {
                case BOOL -> {
                    out.write(0x01);
                    writeString(out, state.name());
                    out.write(((Boolean) state.value()) ? 1 : 0);
                }
                case INT -> {
                    out.write(0x03);
                    writeString(out, state.name());
                    int value = ((Number) state.value()).intValue();
                    out.write(value & 0xFF);
                    out.write((value >>> 8) & 0xFF);
                    out.write((value >>> 16) & 0xFF);
                    out.write((value >>> 24) & 0xFF);
                }
                case STRING -> {
                    out.write(0x08);
                    writeString(out, state.name());
                    writeString(out, (String) state.value());
                }
            }
        }
        out.write(0x00);
        out.write(0x00);

        return fnv1a32(out.toByteArray());
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.write(bytes.length & 0xFF);
        out.write((bytes.length >>> 8) & 0xFF);
        out.write(bytes, 0, bytes.length);
    }

    static int fnv1a32(byte[] data) {
        int hash = FNV1A_32_OFFSET_BASIS;
        for (byte b : data) {
            hash ^= (b & 0xFF);
            hash *= FNV1A_32_PRIME;
        }
        return hash;
    }
}

package org.endstone.proxy.protocol.block;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.common.util.VarInts;

import java.util.function.IntUnaryOperator;

/**
 * Rewrites the block network ids inside a sub-chunk payload, leaving everything else byte-identical.
 *
 * <p>Chunk payloads reach the proxy as opaque bytes — the codecs never decode them — so translating
 * block ids across a version step means parsing the sub-chunk block storage here. The format, per
 * sub-chunk:
 *
 * <pre>
 *   version : byte            1 = one storage, no y index
 *                             8 = storage count follows
 *                             9 = storage count, then a signed y index
 *   [count] : byte
 *   [yIndex]: byte
 *   per storage:
 *     header : byte           (bitsPerBlock &lt;&lt; 1) | 1, the low bit meaning "network ids"
 *     words  : int32le * ceil(4096 / (32 / bitsPerBlock))     absent when bitsPerBlock is 0
 *     size   : signed varint  palette length
 *     ids    : signed varint * size
 * </pre>
 *
 * <p>Only the palette ids are touched. The word array indexes into the palette and does not change,
 * so it is copied verbatim — which also means a sub-chunk keeps its exact bit packing and the
 * rewrite cannot alter which block sits where. The ids are re-emitted rather than patched in place
 * because a varint changes length when its value does.
 *
 * <p><b>Everything here fails safe.</b> A payload this cannot parse is returned unchanged rather
 * than guessed at, so the worst case is the blocks that were missing before are still missing.
 * {@link #rewrite} also proves its own parse before trusting it: it re-encodes the palette it just
 * read with the original ids and checks the result is byte-for-byte the input. A misread of the
 * format — the wrong varint flavour, a bit-packing rule that moved — therefore turns into a
 * pass-through rather than into corrupted chunks, which on this path would mean an unreadable world
 * rather than a visible error.
 */
public final class SubChunkPaletteRewriter {

    /** Sub-chunk side length cubed; the number of blocks one storage indexes. */
    private static final int BLOCKS_PER_SUB_CHUNK = 4096;

    private SubChunkPaletteRewriter() {
    }

    /**
     * Rewrites the first {@code subChunkCount} sub-chunks of {@code data}.
     *
     * @param data          the payload, not consumed — its reader index is left where it was
     * @param subChunkCount how many sub-chunks the packet says are present, before any trailing
     *                      biome, border-block and block-entity data that must be copied untouched
     * @param map           old id to new id
     * @return a new buffer the caller owns, or null when the payload could not be parsed or nothing
     * in it needed rewriting
     */
    public static ByteBuf rewrite(ByteBuf data, int subChunkCount, IntUnaryOperator map) {
        if (data == null || subChunkCount < 0 || !data.isReadable()) {
            return null;
        }

        ByteBuf in = data.slice();
        ByteBuf out = Unpooled.buffer(in.readableBytes() + 64);
        ByteBuf control = Unpooled.buffer(in.readableBytes() + 64);
        boolean changed = false;
        try {
            for (int index = 0; index < subChunkCount; index++) {
                int version = in.readUnsignedByte();
                out.writeByte(version);
                control.writeByte(version);

                int storages;
                switch (version) {
                    case 1 -> storages = 1;
                    case 8, 9 -> {
                        storages = in.readUnsignedByte();
                        out.writeByte(storages);
                        control.writeByte(storages);
                        if (version == 9) {
                            int yIndex = in.readByte();
                            out.writeByte(yIndex);
                            control.writeByte(yIndex);
                        }
                    }
                    default -> {
                        return null;
                    }
                }
                if (storages < 0 || storages > 8) {
                    return null;
                }

                for (int storage = 0; storage < storages; storage++) {
                    changed |= rewriteStorage(in, out, control, map);
                }
            }

            // Biomes, border blocks and block entities: not block ids, never touched.
            out.writeBytes(in, in.readableBytes());

            if (!control.equals(sliceOfSameLength(data, control.readableBytes()))) {
                // The parse did not reproduce its own input, so it did not understand the payload.
                return null;
            }
            if (!changed) {
                return null;
            }

            ByteBuf result = out;
            out = null;
            return result;
        } catch (IndexOutOfBoundsException | IllegalArgumentException malformed) {
            return null;
        } finally {
            control.release();
            if (out != null) {
                out.release();
            }
        }
    }

    /** @return whether any id in this storage's palette actually changed */
    private static boolean rewriteStorage(ByteBuf in, ByteBuf out, ByteBuf control, IntUnaryOperator map) {
        int header = in.readUnsignedByte();
        out.writeByte(header);
        control.writeByte(header);

        int bitsPerBlock = header >>> 1;
        if (bitsPerBlock > 32) {
            throw new IllegalArgumentException("bitsPerBlock " + bitsPerBlock);
        }
        if (bitsPerBlock > 0) {
            int blocksPerWord = 32 / bitsPerBlock;
            int wordCount = (BLOCKS_PER_SUB_CHUNK + blocksPerWord - 1) / blocksPerWord;
            int byteCount = wordCount * 4;
            out.writeBytes(in, in.readerIndex(), byteCount);
            control.writeBytes(in, in.readerIndex(), byteCount);
            in.skipBytes(byteCount);
        }

        int paletteSize = VarInts.readInt(in);
        if (paletteSize < 0 || paletteSize > BLOCKS_PER_SUB_CHUNK) {
            throw new IllegalArgumentException("palette size " + paletteSize);
        }
        VarInts.writeInt(out, paletteSize);
        VarInts.writeInt(control, paletteSize);

        boolean changed = false;
        for (int entry = 0; entry < paletteSize; entry++) {
            int original = VarInts.readInt(in);
            int mapped = map.applyAsInt(original);
            changed |= mapped != original;
            VarInts.writeInt(out, mapped);
            VarInts.writeInt(control, original);
        }
        return changed;
    }

    private static ByteBuf sliceOfSameLength(ByteBuf data, int length) {
        return data.slice(data.readerIndex(), Math.min(length, data.readableBytes()));
    }
}

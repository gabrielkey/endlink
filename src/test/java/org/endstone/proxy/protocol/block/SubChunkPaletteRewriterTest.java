package org.endstone.proxy.protocol.block;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.Test;

import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The sub-chunk payload rewriter, which is the only code in the proxy that reads chunk block data.
 *
 * <p>The stakes are lopsided, and the tests are shaped around that. A rewrite that drops a block is
 * a hole in the world; a rewrite that shifts the word array by a byte is a world of noise; and
 * neither announces itself, because nothing downstream validates chunk bytes. So the rewriter
 * refuses to guess, and what is asserted here as hard as the happy path is that it hands back the
 * original payload untouched for anything it does not fully understand.
 */
class SubChunkPaletteRewriterTest {

    /** Every id shifted by a constant: easy to assert, and never accidentally the identity. */
    private static final IntUnaryOperator SHIFT = id -> id + 1_000_000;

    @Test
    void rewritesAPaletteAndLeavesTheBlockWordsExactlyWhereTheyWere() {
        byte[] words = words(4);
        ByteBuf payload = subChunk(8, 1, null, 4, words, new int[]{10, 20, 30});
        byte[] trailer = {(byte) 0xAB, (byte) 0xCD};
        payload.writeBytes(trailer);
        byte[] before = copyOf(payload);

        ByteBuf rewritten = SubChunkPaletteRewriter.rewrite(payload, 1, SHIFT);
        assertNotNull(rewritten, "a payload of this shape must be understood");

        ByteBuf expected = subChunk(8, 1, null, 4, words, new int[]{1_000_010, 1_000_020, 1_000_030});
        expected.writeBytes(trailer);
        assertArrayEquals(copyOf(expected), copyOf(rewritten));

        // The source is never consumed: the relay may still need it if anything downstream declines.
        assertArrayEquals(before, copyOf(payload));
    }

    @Test
    void carriesTheYIndexAndMultipleStorages() {
        byte[] words = words(2);
        ByteBuf payload = Unpooled.buffer();
        payload.writeByte(9);
        payload.writeByte(2);
        payload.writeByte(-4);
        writeStorage(payload, 2, words, new int[]{7});
        writeStorage(payload, 2, words, new int[]{8, 9});

        ByteBuf rewritten = SubChunkPaletteRewriter.rewrite(payload, 1, SHIFT);
        assertNotNull(rewritten);

        ByteBuf expected = Unpooled.buffer();
        expected.writeByte(9);
        expected.writeByte(2);
        expected.writeByte(-4);
        writeStorage(expected, 2, words, new int[]{1_000_007});
        writeStorage(expected, 2, words, new int[]{1_000_008, 1_000_009});
        assertArrayEquals(copyOf(expected), copyOf(rewritten));
    }

    /** Version 1 is a single storage with no count byte in front of it. */
    @Test
    void handlesTheSingleStorageVersion() {
        byte[] words = words(1);
        ByteBuf payload = subChunk(1, 0, null, 1, words, new int[]{5, 6});

        ByteBuf rewritten = SubChunkPaletteRewriter.rewrite(payload, 1, SHIFT);
        assertNotNull(rewritten);
        assertArrayEquals(copyOf(subChunk(1, 0, null, 1, words, new int[]{1_000_005, 1_000_006})),
                copyOf(rewritten));
    }

    /** Zero bits per block means the whole sub-chunk is one block and there is no word array. */
    @Test
    void handlesAUniformSubChunkWithNoWords() {
        ByteBuf payload = Unpooled.buffer();
        payload.writeByte(8);
        payload.writeByte(1);
        payload.writeByte((0 << 1) | 1);
        VarInts.writeInt(payload, 1);
        VarInts.writeInt(payload, 42);

        ByteBuf rewritten = SubChunkPaletteRewriter.rewrite(payload, 1, SHIFT);
        assertNotNull(rewritten);

        ByteBuf expected = Unpooled.buffer();
        expected.writeByte(8);
        expected.writeByte(1);
        expected.writeByte((0 << 1) | 1);
        VarInts.writeInt(expected, 1);
        VarInts.writeInt(expected, 1_000_042);
        assertArrayEquals(copyOf(expected), copyOf(rewritten));
    }

    /** Several sub-chunks in one LevelChunk payload, with biome data after them left alone. */
    @Test
    void rewritesEverySubChunkAndCopiesWhatFollowsThem() {
        byte[] words = words(4);
        ByteBuf payload = Unpooled.buffer();
        for (int i = 0; i < 3; i++) {
            payload.writeByte(8);
            payload.writeByte(1);
            writeStorage(payload, 4, words, new int[]{i + 1});
        }
        byte[] biomesAndBlockEntities = {1, 2, 3, 4, 5, 6, 7, 8};
        payload.writeBytes(biomesAndBlockEntities);

        ByteBuf rewritten = SubChunkPaletteRewriter.rewrite(payload, 3, SHIFT);
        assertNotNull(rewritten);

        ByteBuf expected = Unpooled.buffer();
        for (int i = 0; i < 3; i++) {
            expected.writeByte(8);
            expected.writeByte(1);
            writeStorage(expected, 4, words, new int[]{1_000_000 + i + 1});
        }
        expected.writeBytes(biomesAndBlockEntities);
        assertArrayEquals(copyOf(expected), copyOf(rewritten));
    }

    @Test
    void reportsNothingToDoWhenNoIdChanged() {
        ByteBuf payload = subChunk(8, 1, null, 4, words(4), new int[]{10, 20});
        assertNull(SubChunkPaletteRewriter.rewrite(payload, 1, id -> id),
                "an unchanged payload must not be copied; the relay keeps the original");
    }

    @Test
    void refusesAVersionItDoesNotKnow() {
        ByteBuf payload = Unpooled.buffer();
        payload.writeByte(42);
        payload.writeByte(1);
        assertNull(SubChunkPaletteRewriter.rewrite(payload, 1, SHIFT));
    }

    @Test
    void refusesATruncatedPayloadRatherThanGuessing() {
        ByteBuf payload = subChunk(8, 1, null, 4, words(4), new int[]{10, 20});
        ByteBuf truncated = payload.slice(0, payload.readableBytes() - 1);
        assertNull(SubChunkPaletteRewriter.rewrite(truncated, 1, SHIFT));
    }

    @Test
    void refusesAnImpossiblePaletteLength() {
        ByteBuf payload = Unpooled.buffer();
        payload.writeByte(8);
        payload.writeByte(1);
        payload.writeByte((4 << 1) | 1);
        payload.writeBytes(words(4));
        VarInts.writeInt(payload, 999_999);
        assertNull(SubChunkPaletteRewriter.rewrite(payload, 1, SHIFT));
    }

    @Test
    void handlesAnEmptyOrAbsentPayload() {
        assertNull(SubChunkPaletteRewriter.rewrite(null, 1, SHIFT));
        assertNull(SubChunkPaletteRewriter.rewrite(Unpooled.buffer(), 1, SHIFT));
    }

    /**
     * A LevelChunk that carries only biome data reports zero sub-chunks; every byte is then something
     * this must not touch.
     */
    @Test
    void copiesAPayloadWithNoSubChunksVerbatim() {
        ByteBuf payload = Unpooled.wrappedBuffer(new byte[]{9, 9, 9, 9});
        assertNull(SubChunkPaletteRewriter.rewrite(payload, 0, SHIFT));
    }

    // --- helpers ------------------------------------------------------------------------------

    private static ByteBuf subChunk(int version, int storages, Integer yIndex,
                                    int bitsPerBlock, byte[] words, int[] palette) {
        ByteBuf buffer = Unpooled.buffer();
        buffer.writeByte(version);
        if (version != 1) {
            buffer.writeByte(storages);
            if (version == 9) {
                buffer.writeByte(yIndex == null ? 0 : yIndex);
            }
        }
        writeStorage(buffer, bitsPerBlock, words, palette);
        return buffer;
    }

    private static void writeStorage(ByteBuf buffer, int bitsPerBlock, byte[] words, int[] palette) {
        buffer.writeByte((bitsPerBlock << 1) | 1);
        buffer.writeBytes(words);
        VarInts.writeInt(buffer, palette.length);
        for (int id : palette) {
            VarInts.writeInt(buffer, id);
        }
    }

    /** A word array of the size {@code bitsPerBlock} implies, filled with a recognisable pattern. */
    private static byte[] words(int bitsPerBlock) {
        int blocksPerWord = 32 / bitsPerBlock;
        int wordCount = (4096 + blocksPerWord - 1) / blocksPerWord;
        byte[] words = new byte[wordCount * 4];
        for (int i = 0; i < words.length; i++) {
            words[i] = (byte) (i * 7 + 3);
        }
        return words;
    }

    private static byte[] copyOf(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }

    /** The word array must be exactly the size the header implies, or every later field shifts. */
    @Test
    void theWordArraySizeMatchesTheBitPacking() {
        assertEquals(1024 * 4, words(8).length);
        assertEquals(512 * 4, words(4).length);
        // 3 bits packs 10 blocks per 32-bit word with 2 bits wasted, so 410 words cover 4096.
        assertEquals(410 * 4, words(3).length);
    }
}

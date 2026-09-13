package org.endstone.proxy.protocol.block;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.List;

/**
 * Works out {@code minecraft:corner} for every stair in a sub-chunk, because the backend cannot.
 *
 * <p>Before 1.26.50 a stair's corner shape was not a block state at all: the client worked it out
 * from the stairs around it while rendering. 1.26.50 made it server-authoritative, so a 1.26.45
 * backend — which has no such state and never will — leaves every stair saying {@code none}. The
 * blocks appear, and every inner and outer corner in the world renders as a straight stair pointing
 * the wrong way. This puts the shape back.
 *
 * <p><b>The rule is Java's.</b> Mojang's own note on the change says the new state is "for parity
 * with Java Edition", and the shape it describes is the one Java has computed in
 * {@code StairBlock.getStairsShape} for years: a stair whose front neighbour is a stair on the same
 * half and a different axis makes an outer corner; one whose back neighbour is, makes an inner
 * corner; and either is cancelled when the stair beside it already continues the run. Left and right
 * are counter-clockwise and clockwise of the facing, seen from above.
 *
 * <p><b>Sub-chunks are independent, which is what makes this affordable.</b> Every neighbour the rule
 * consults is horizontal and at the same height, so no lookup ever leaves the sub-chunk vertically
 * and there is no need to assemble the chunk column. What does leave it is the 16&times;16 footprint:
 * a stair on the chunk's own edge has a neighbour in a chunk the proxy may not have seen. Those are
 * treated as "not a stair", which yields the straight shape — the behaviour before this pass, for
 * the blocks it cannot know about, rather than a guess.
 *
 * <p><b>It verifies itself.</b> Unlike {@link SubChunkPaletteRewriter}, this re-packs the bit array,
 * so a mistake would not lose a stair shape but scramble which block is where. After writing, the
 * result is read back and every position compared against what was intended; a mismatch discards the
 * whole thing and the caller keeps the payload it had.
 */
public final class StairCornerPass {

    private final StairIndex index;

    public StairCornerPass(StairIndex index) {
        this.index = index;
    }

    /**
     * @param data          a payload whose block ids are already in the newer version's numbering
     * @param subChunkCount how many sub-chunks precede the biome and block-entity data
     * @return a new buffer the caller owns, or null when nothing changed or the payload could not be
     * read back exactly as intended
     */
    public ByteBuf apply(ByteBuf data, int subChunkCount) {
        if (index.isEmpty() || data == null || subChunkCount <= 0 || !data.isReadable()) {
            return null;
        }

        ByteBuf in = data.slice();
        ByteBuf out = Unpooled.buffer(in.readableBytes() + 256);
        boolean changed = false;
        try {
            for (int subChunk = 0; subChunk < subChunkCount; subChunk++) {
                int subChunkStart = out.writerIndex();
                int version = in.readUnsignedByte();
                out.writeByte(version);

                int storages;
                switch (version) {
                    case 1 -> storages = 1;
                    case 8, 9 -> {
                        storages = in.readUnsignedByte();
                        out.writeByte(storages);
                        if (version == 9) {
                            out.writeByte(in.readByte());
                        }
                    }
                    default -> {
                        return null;
                    }
                }
                if (storages < 0 || storages > 8) {
                    return null;
                }

                List<SubChunkStorage> layers = new ArrayList<>(storages);
                for (int layer = 0; layer < storages; layer++) {
                    layers.add(SubChunkStorage.read(in));
                }
                if (!layers.isEmpty()) {
                    SubChunkStorage blocks = layers.get(0);
                    SubChunkStorage cornered = applyCorners(blocks);
                    if (cornered != blocks) {
                        layers.set(0, cornered);
                        changed = true;
                    }
                }
                for (SubChunkStorage layer : layers) {
                    layer.write(out);
                }
                if (!verify(out, subChunkStart, layers, version)) {
                    return null;
                }
            }

            out.writeBytes(in, in.readableBytes());
            if (!changed) {
                return null;
            }
            ByteBuf result = out;
            out = null;
            return result;
        } catch (IndexOutOfBoundsException | IllegalArgumentException malformed) {
            return null;
        } finally {
            if (out != null) {
                out.release();
            }
        }
    }

    /** @return the storage with corners filled in, or the same instance when no stair needed one */
    private SubChunkStorage applyCorners(SubChunkStorage blocks) {
        SubChunkStorage result = blocks;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < 16; y++) {
                    int position = SubChunkStorage.index(x, y, z);
                    StairIndex.Stair stair = index.stairAt(blocks.blockAt(position));
                    if (stair == null) {
                        continue;
                    }
                    StairIndex.Corner corner = cornerOf(blocks, stair, x, y, z);
                    if (corner != StairIndex.Corner.NONE) {
                        result = result.withBlockAt(position, index.idFor(stair, corner));
                    }
                }
            }
        }
        return result;
    }

    /**
     * Java's {@code StairBlock.getStairsShape}, read against the sub-chunk rather than a world.
     *
     * <p>Neighbours are always read from {@code blocks}, the storage as it arrived, never from the
     * partially cornered result: a corner is decided by the stairs around it, not by what those
     * stairs have already been rewritten to, and mixing the two would let one stair's answer change
     * the next one's.
     */
    private StairIndex.Corner cornerOf(SubChunkStorage blocks, StairIndex.Stair stair, int x, int y, int z) {
        StairIndex.Facing facing = stair.facing();

        StairIndex.Stair front = stairAt(blocks, x + facing.dx(), y, z + facing.dz());
        if (front != null && front.upsideDown() == stair.upsideDown()
                && !front.facing().sameAxis(facing)
                && canTakeShape(blocks, stair, x, y, z, front.facing().opposite())) {
            return front.facing() == facing.counterClockwise()
                    ? StairIndex.Corner.OUTER_LEFT
                    : StairIndex.Corner.OUTER_RIGHT;
        }

        StairIndex.Facing back = facing.opposite();
        StairIndex.Stair behind = stairAt(blocks, x + back.dx(), y, z + back.dz());
        if (behind != null && behind.upsideDown() == stair.upsideDown()
                && !behind.facing().sameAxis(facing)
                && canTakeShape(blocks, stair, x, y, z, behind.facing())) {
            return behind.facing() == facing.counterClockwise()
                    ? StairIndex.Corner.INNER_LEFT
                    : StairIndex.Corner.INNER_RIGHT;
        }

        return StairIndex.Corner.NONE;
    }

    /** False when the stair on that side already continues this one's run, which cancels the corner. */
    private boolean canTakeShape(SubChunkStorage blocks, StairIndex.Stair stair,
                                 int x, int y, int z, StairIndex.Facing side) {
        StairIndex.Stair neighbour = stairAt(blocks, x + side.dx(), y, z + side.dz());
        return neighbour == null
                || neighbour.facing() != stair.facing()
                || neighbour.upsideDown() != stair.upsideDown();
    }

    /**
     * The stair at a position, or null when there is none — <em>or</em> when the position is outside
     * this sub-chunk's footprint. The chunk next door has not necessarily arrived, and treating the
     * unknown as "no stair" is what leaves an edge stair looking the way it does today instead of
     * inventing a shape for it.
     */
    private StairIndex.Stair stairAt(SubChunkStorage blocks, int x, int y, int z) {
        if (x < 0 || x > 15 || z < 0 || z > 15) {
            return null;
        }
        return index.stairAt(blocks.blockAt(SubChunkStorage.index(x, y, z)));
    }

    /**
     * Reads back the sub-chunk just written, starting at {@code subChunkStart}, and checks every one
     * of its 4096 positions still resolves to the block it was meant to.
     */
    private boolean verify(ByteBuf out, int subChunkStart, List<SubChunkStorage> expected, int version) {
        int headerLength = version == 1 ? 1 : (version == 9 ? 3 : 2);
        ByteBuf written = out.slice(subChunkStart, out.writerIndex() - subChunkStart);
        written.skipBytes(headerLength);
        try {
            for (SubChunkStorage layer : expected) {
                SubChunkStorage readBack = SubChunkStorage.read(written);
                for (int position = 0; position < SubChunkStorage.BLOCKS; position++) {
                    if (readBack.blockAt(position) != layer.blockAt(position)) {
                        return false;
                    }
                }
            }
            return !written.isReadable();
        } catch (IndexOutOfBoundsException | IllegalArgumentException malformed) {
            return false;
        }
    }
}

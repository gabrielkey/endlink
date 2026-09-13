package org.endstone.proxy.protocol.block;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.List;

/**
 * Reaches every fence, pane, bar and trip wire in a sub-chunk out to what stands beside it, because
 * the backend cannot.
 *
 * <p>The same fault as a squared-off stair corner and the same fix. Before 1.26.50 a fence's arms
 * were not block states at all: the Bedrock client worked them out from the blocks around it while
 * rendering. 1.26.50 made them server-authoritative &mdash; four {@code minecraft:connection_*}
 * booleans on 58 blocks &mdash; so a 1.26.45 backend, which has no such state and never will, leaves
 * every one of them saying it reaches nothing. The blocks appear, and every fence line is a row of
 * separate posts, every pane wall a row of separate panes. This puts the arms back.
 *
 * <p><b>The rule is Java's</b>, because the states exist for parity with Java: a fence reaches a
 * fence of the same woodiness, a pane or bar reaches any other pane or bar, and trip wire reaches
 * trip wire. {@link BlockJoinIndex#reaches} is the whole of it. Java's third clause &mdash; either
 * also reaches a block whose face beside it is solid &mdash; needs a solidity table this tree does
 * not have, so a fence run still stops short where it meets a wall. That is visibly better than a
 * row of posts and visibly short of right, and it is the next thing to do here.
 *
 * <p><b>Sub-chunks are independent, which is what makes this affordable.</b> Every neighbour the rule
 * consults is horizontal and at the same height, so no lookup leaves the sub-chunk vertically and
 * there is no need to assemble the chunk column. What does leave it is the 16&times;16 footprint: a
 * fence on the chunk's own edge has a neighbour in a chunk the proxy may not have seen. Those are
 * left armless here and settled by {@link BlockJoinSeams} once the neighbouring column arrives.
 *
 * <p><b>It verifies itself</b>, exactly as {@link StairCornerPass} does and for the same reason: this
 * re-packs the bit array, so a mistake would not lose an arm but scramble which block is where.
 * After writing, the result is read back and every position compared against what was intended; a
 * mismatch discards the whole thing and the caller keeps the payload it had.
 */
public final class BlockJoinPass {

    private final BlockJoinIndex index;

    public BlockJoinPass(BlockJoinIndex index) {
        this.index = index;
    }

    /** Told about each joining block the pass saw, at a column-relative position with y from the bottom. */
    public interface JointSink {
        void accept(int x, int y, int z, BlockJoinIndex.Joint joint, int sentId);
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
                    SubChunkStorage joined = applyArms(blocks);
                    if (joined != blocks) {
                        layers.set(0, joined);
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

    /**
     * Reports every joining block in a finished payload without changing anything.
     *
     * <p>The same arrangement {@link StairCornerPass#collect} explains: the pass runs inside the
     * translator, which is shared by every session and cannot hold per-player state, and settling a
     * seam needs exactly that. So the relay reads the finished payload back once, here.
     *
     * @return false if the payload could not be read, in which case nothing was reported
     */
    public boolean collect(ByteBuf data, int subChunkCount, JointSink found) {
        if (index.isEmpty() || data == null || subChunkCount <= 0 || !data.isReadable()) {
            return false;
        }
        ByteBuf in = data.slice();
        try {
            for (int subChunk = 0; subChunk < subChunkCount; subChunk++) {
                int version = in.readUnsignedByte();
                int storages;
                switch (version) {
                    case 1 -> storages = 1;
                    case 8, 9 -> {
                        storages = in.readUnsignedByte();
                        if (version == 9) {
                            in.readByte();
                        }
                    }
                    default -> {
                        return false;
                    }
                }
                if (storages < 0 || storages > 8) {
                    return false;
                }
                for (int layer = 0; layer < storages; layer++) {
                    SubChunkStorage blocks = SubChunkStorage.read(in);
                    if (layer != 0) {
                        continue;
                    }
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            for (int y = 0; y < 16; y++) {
                                int id = blocks.blockAt(SubChunkStorage.index(x, y, z));
                                BlockJoinIndex.Joint joint = index.jointAt(id);
                                if (joint != null) {
                                    found.accept(x, subChunk * 16 + y, z, joint, id);
                                }
                            }
                        }
                    }
                }
            }
            return true;
        } catch (IndexOutOfBoundsException | IllegalArgumentException malformed) {
            return false;
        }
    }

    /** @return the storage with arms filled in, or the same instance when nothing reached anything. */
    private SubChunkStorage applyArms(SubChunkStorage blocks) {
        SubChunkStorage result = blocks;
        JoinLookup around = lookupOver(blocks);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < 16; y++) {
                    int position = SubChunkStorage.index(x, y, z);
                    BlockJoinIndex.Joint joint = index.jointAt(blocks.blockAt(position));
                    if (joint == null) {
                        continue;
                    }
                    int arms = armsOf(around, joint, x, y, z);
                    if (arms != BlockJoinIndex.NO_ARMS) {
                        result = result.withBlockAt(position, index.idFor(joint, arms));
                    }
                }
            }
        }
        return result;
    }

    /**
     * Which sides this block reaches out on, read against whatever {@code around} knows.
     *
     * <p>Static and lookup-driven so {@link BlockJoinSeams} can run the very same rule over two
     * columns that arrived at different times. Neighbours are always read from the blocks as they
     * arrived, never from a partially armed result &mdash; though unlike a stair corner it would not
     * matter if they were, because the rule reads a neighbour's family and never its arms, which is
     * exactly what lets a seam be settled later over blocks already reached out once.
     */
    static int armsOf(JoinLookup around, BlockJoinIndex.Joint joint, int x, int y, int z) {
        int arms = BlockJoinIndex.NO_ARMS;
        for (BlockJoinIndex.Side side : BlockJoinIndex.Side.values()) {
            BlockJoinIndex.Joint neighbour = around.at(x + side.dx(), y, z + side.dz());
            if (neighbour != null && BlockJoinIndex.reaches(joint.family(), neighbour.family())) {
                arms |= side.bit();
            }
        }
        return arms;
    }

    /**
     * This sub-chunk as a lookup. Anything outside the 16&times;16 footprint reads as unknown, which
     * the rule answers with no arm on that side.
     */
    private JoinLookup lookupOver(SubChunkStorage blocks) {
        return (x, y, z) -> {
            if (x < 0 || x > 15 || z < 0 || z > 15) {
                return null;
            }
            return index.jointAt(blocks.blockAt(SubChunkStorage.index(x, y, z)));
        };
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

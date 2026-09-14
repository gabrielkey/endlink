package org.endstone.proxy.protocol.block;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;

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
 * <p><b>The rule is Java's</b>, because the states exist for parity with Java, and all of it is here.
 * A fence reaches a fence of the same woodiness and a pane or bar reaches any other pane or bar,
 * which is {@link BlockJoinIndex#reaches} and needs nothing but the two blocks; and either also
 * reaches a block whose face beside it is solid, which is {@link BlockJoinFaces} and is a fact about
 * every block in the game rather than about the 58 that changed. The second clause is not a
 * refinement: a fence line mostly stands in the open and joins to itself, but a glass pane is nearly
 * always set into a window frame with no other pane beside it, so without it fences look fixed and
 * glass looks untouched &mdash; which is exactly what was seen in game.
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

    /** What a sub-chunk's empty positions hold, so the census can tell a wall from open air. */
    private static final int AIR = BedrockBlockStateHash.AIR;

    private final BlockJoinIndex index;
    private final BlockJoinFaces faces;
    private final Census census = new Census();

    public BlockJoinPass(BlockJoinIndex index, BlockJoinFaces faces) {
        this.index = index;
        this.faces = faces;
    }

    /** What the rule has actually met in real worlds, for a session to report once. */
    public Census census() {
        return census;
    }

    /**
     * What the join rule met, counted per family, so the size of the part that is missing can be read
     * off a running proxy rather than guessed at.
     *
     * <p>The number that matters is {@code besideABlock}: a joining block left with no arms that has
     * a neighbour which is neither air nor another joining block. Java would have reached out to it
     * if that neighbour's face is solid, and this proxy cannot yet tell. It is the difference between
     * a fence line, which mostly stands in the open and joins to itself, and a glass pane, which is
     * mostly set into a wall and joins to nothing else &mdash; which is exactly the shape of what is
     * still wrong in game.
     *
     * <p>Global rather than per-session: the pass lives in the translator, which every player shares.
     * That is honest for a diagnostic and is said so where it is printed.
     */
    public static final class Census {

        private static final int SEEN = 0;
        private static final int ARMED = 1;
        private static final int BESIDE_A_BLOCK = 2;
        private static final int FROM_CLIENT = 3;
        private static final int COUNTERS = 4;

        private final Map<String, AtomicLongArray> byFamily = new ConcurrentHashMap<>();

        void saw(String family, boolean armed, boolean besideABlock) {
            AtomicLongArray counters = counters(family);
            counters.incrementAndGet(SEEN);
            if (armed) {
                counters.incrementAndGet(ARMED);
            } else if (besideABlock) {
                counters.incrementAndGet(BESIDE_A_BLOCK);
            }
        }

        void sawFromClient(String family) {
            counters(family).incrementAndGet(FROM_CLIENT);
        }

        private AtomicLongArray counters(String family) {
            return byFamily.computeIfAbsent(family, ignored -> new AtomicLongArray(COUNTERS));
        }

        public boolean isEmpty() {
            return byFamily.isEmpty();
        }

        /** One line per family: how many were met, how many reached out, how many could not. */
        public String summary() {
            StringBuilder out = new StringBuilder();
            byFamily.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        AtomicLongArray counters = entry.getValue();
                        if (out.length() > 0) {
                            out.append("; ");
                        }
                        out.append(entry.getKey())
                                .append(' ').append(counters.get(SEEN)).append(" seen, ")
                                .append(counters.get(ARMED)).append(" reaching out, ")
                                .append(counters.get(BESIDE_A_BLOCK)).append(" armless beside a block");
                        long fromClient = counters.get(FROM_CLIENT);
                        if (fromClient > 0) {
                            out.append(", ").append(fromClient).append(" read back from the client");
                        }
                    });
            return out.toString();
        }
    }

    /**
     * Records a block id the client itself named, which is the only way to see what the client
     * actually holds rather than what was sent to it.
     *
     * <p>A Bedrock client reports the block it clicked by its own network id. If that id is an armed
     * fence, the client took the arms this pass gave it; if it is the armless one, they never landed.
     * No screenshot answers that and this does.
     *
     * @return a description of the block for a diagnostic, or null if it is not a joining block
     */
    public String describeFromClient(int runtimeId) {
        BlockJoinIndex.Joint joint = index.jointAt(runtimeId);
        if (joint == null) {
            return null;
        }
        census.sawFromClient(joint.family());
        StringBuilder arms = new StringBuilder();
        for (BlockJoinIndex.Side side : BlockJoinIndex.Side.values()) {
            if ((index.armsOf(joint, runtimeId) & side.bit()) != 0) {
                arms.append(side.name().charAt(0));
            }
        }
        return joint.identifier() + " reaching " + (arms.length() == 0 ? "nothing" : arms);
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
                    int arms = armsOf(index, faces, around, joint, x, y, z);
                    if (arms != BlockJoinIndex.NO_ARMS) {
                        result = result.withBlockAt(position, index.idFor(joint, arms));
                    }
                    census.saw(joint.family(), arms != BlockJoinIndex.NO_ARMS,
                            besideABlock(blocks, x, y, z));
                }
            }
        }
        return result;
    }

    /**
     * Which sides this block reaches out on, read against whatever {@code around} knows.
     *
     * <p>Java's rule in full, for the kinds it answers for: a joining block reaches a neighbour of a
     * family it joins, and it reaches a neighbour whose face beside it is solid. The first half is
     * {@link BlockJoinIndex#reaches} and needs nothing but the two blocks. The second is
     * {@link BlockJoinFaces} and is the game's own answer for every block state there is, which is
     * what makes a glass pane in a stone window frame connect to the frame &mdash; the case the
     * family rule alone can say nothing about, and the one that was still wrong in game.
     *
     * <p>Trip wire has no entry in the second, because Java joins trip wire to trip wire and to a
     * hook facing it and neither is a question about solidity. It therefore joins by family alone,
     * which is what Java does with it too.
     *
     * <p>Static and lookup-driven so the same rule can be run over two columns that arrived at
     * different times. Neighbours are read from the blocks as they arrived, never from a partially
     * armed result &mdash; though unlike a stair corner it would not matter if they were, because the
     * rule reads a neighbour's family and its faces and never its arms, which is exactly what lets a
     * seam be settled later over blocks already reached out once.
     */
    static int armsOf(BlockJoinIndex index, BlockJoinFaces faces, JoinLookup around,
                      BlockJoinIndex.Joint joint, int x, int y, int z) {
        int kind = BlockJoinFaces.kindOf(joint.family());
        int arms = BlockJoinIndex.NO_ARMS;
        for (BlockJoinIndex.Side side : BlockJoinIndex.Side.values()) {
            int neighbourId = around.at(x + side.dx(), y, z + side.dz());
            BlockJoinIndex.Joint neighbour = index.jointAt(neighbourId);
            boolean joins = neighbour != null
                    ? BlockJoinIndex.reaches(joint.family(), neighbour.family())
                    : faces.reaches(kind, neighbourId, side);
            if (joins) {
                arms |= side.bit();
            }
        }
        return arms;
    }

    /**
     * Whether anything stands beside this position that is neither air nor another joining block.
     *
     * <p>Only for the census. Java would reach out to such a neighbour if its face were solid, and
     * this is the count of the times that question had to go unanswered.
     */
    private boolean besideABlock(SubChunkStorage blocks, int x, int y, int z) {
        for (BlockJoinIndex.Side side : BlockJoinIndex.Side.values()) {
            int nx = x + side.dx();
            int nz = z + side.dz();
            if (nx < 0 || nx > 15 || nz < 0 || nz > 15) {
                continue;
            }
            int id = blocks.blockAt(SubChunkStorage.index(nx, y, nz));
            if (id != AIR && index.jointAt(id) == null) {
                return true;
            }
        }
        return false;
    }

    /**
     * This sub-chunk as a lookup. Anything outside the 16&times;16 footprint reads as unknown, which
     * the rule answers with no arm on that side.
     */
    private static JoinLookup lookupOver(SubChunkStorage blocks) {
        return (x, y, z) -> {
            if (x < 0 || x > 15 || z < 0 || z > 15) {
                return JoinLookup.NOTHING;
            }
            return blocks.blockAt(SubChunkStorage.index(x, y, z));
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

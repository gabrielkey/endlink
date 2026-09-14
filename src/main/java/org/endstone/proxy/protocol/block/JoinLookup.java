package org.endstone.proxy.protocol.block;

/**
 * The blocks around a position, however the caller happens to know them.
 *
 * <p>{@link StairLookup}'s counterpart, and it exists for the same reason: the rule that gives a
 * fence its arms is the same whether it is being run over a sub-chunk that has just arrived or over
 * the seam between two columns that arrived minutes apart, and the only thing that differs is where
 * the neighbouring blocks come from.
 *
 * <p>This one answers with a block network id rather than with a joining block, because the rule
 * needs both halves of Java's question: whether the neighbour is a joining block of a family this
 * one reaches, and &mdash; when it is not &mdash; whether its face is solid, which is
 * {@link BlockJoinFaces} and is a question about any block in the game.
 *
 * <p>{@code x} and {@code z} are column-relative and may be one block outside 0..15; {@code y} is
 * counted from the bottom of the column. Anything not known &mdash; outside the world, or a column
 * the backend has not delivered &mdash; answers {@link #NOTHING}, which is air: air is not a joining
 * block and nothing reaches out to it, so "not known" and "nothing there" give the same answer
 * without needing a sentinel that some block might one day hash to.
 */
@FunctionalInterface
public interface JoinLookup {

    /** Air, which is what an unknown position reads as. */
    int NOTHING = BedrockBlockStateHash.AIR;

    int at(int x, int y, int z);
}

package org.endstone.proxy.protocol.block;

/**
 * The joining blocks around a position, however the caller happens to know them.
 *
 * <p>{@link StairLookup}'s counterpart, and it exists for the same reason: the rule that gives a
 * fence its arms is the same whether it is being run over a sub-chunk that has just arrived or over
 * the seam between two columns that arrived minutes apart, and the only thing that differs is where
 * the neighbouring blocks come from.
 *
 * <p>{@code x} and {@code z} are column-relative and may be one block outside 0..15; {@code y} is
 * counted from the bottom of the column. Anything not known &mdash; outside the world, or a column
 * the backend has not delivered &mdash; is null, which the rule reads as nothing to reach out to.
 */
@FunctionalInterface
public interface JoinLookup {

    BlockJoinIndex.Joint at(int x, int y, int z);
}

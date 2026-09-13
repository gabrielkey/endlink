package org.endstone.proxy.protocol.block;

/**
 * The stairs around a position, however the caller happens to know them.
 *
 * <p>The corner rule is the same whether it is being run over a sub-chunk that has just arrived or
 * over the seam between two columns that arrived minutes apart, and the only thing that differs is
 * where the neighbouring blocks come from. Keeping the rule behind this means it is written once.
 *
 * <p>{@code x} and {@code z} are column-relative and may be one block outside 0..15; {@code y} is
 * counted from the bottom of the column. Anything not known — outside the world, or a column the
 * backend has not delivered — is null, which the rule reads as "no stair" and answers with the
 * straight shape.
 */
@FunctionalInterface
public interface StairLookup {

    StairIndex.Stair at(int x, int y, int z);
}

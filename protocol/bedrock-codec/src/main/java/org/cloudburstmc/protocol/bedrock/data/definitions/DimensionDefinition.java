package org.cloudburstmc.protocol.bedrock.data.definitions;

import lombok.Value;

import java.util.UUID;

/**
 * One entry of {@code DimensionDataPacket}.
 *
 * <p>The wire carries {@code Minimum Y} and then a {@code Height Range} &mdash; a span, not a
 * second coordinate. The serializers convert, so {@link #getMaximumHeight()} is a real upper bound
 * here rather than the first varint on the wire. The constructor keeps its long-standing argument
 * order, maximum before minimum, so that addons built against it still compile.
 */
@Value
public class DimensionDefinition {
    String id;
    int maximumHeight;
    int minimumHeight;
    int generatorType;
    int dimensionType;
    /**
     * @since v2168
     */
    UUID packId;
    /**
     * @since v2192
     */
    String defaultBiome;
}

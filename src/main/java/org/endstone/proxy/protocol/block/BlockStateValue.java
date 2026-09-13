package org.endstone.proxy.protocol.block;

/**
 * One block state property and its value, in the three NBT shapes a Bedrock block state can use.
 *
 * <p>The type is carried explicitly rather than inferred from the value because it decides the NBT
 * tag byte, and the tag byte is hashed: a boolean written as an int is a different block. Mojang's
 * own metadata names the type for every property, so it is never guessed here.
 *
 * @see BedrockBlockStateHash
 */
public record BlockStateValue(String name, Type type, Object value) {

    public enum Type {
        /** NBT byte, 0 or 1. */
        BOOL,
        /** NBT int, little-endian. */
        INT,
        /** NBT string, length-prefixed UTF-8. */
        STRING;

        /** The spelling Mojang's {@code mojang-blocks.json} uses. */
        public static Type fromMetadata(String type) {
            return switch (type) {
                case "bool" -> BOOL;
                case "int" -> INT;
                case "string" -> STRING;
                default -> throw new IllegalArgumentException("Unknown block state type: " + type);
            };
        }
    }

    public static BlockStateValue of(String name, Type type, Object value) {
        return new BlockStateValue(name, type, value);
    }
}

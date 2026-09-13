package org.endstone.proxy.protocol.block;

import org.jose4j.json.JsonUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Block network ids that mean the same block on either side of one Minecraft version step.
 *
 * <p><b>What this is for.</b> Hashed block ids (see {@link BedrockBlockStateHash}) let a proxy carry
 * chunks between two Minecraft versions for free, but only for blocks whose state definition is
 * identical in both. When a version adds a property to a block, every state of that block hashes to
 * something the other side has never heard of, and the receiving client draws air. 1.26.50 did this
 * to 139 blocks: every stair gained {@code minecraft:corner}, and every fence, glass pane, iron or
 * copper bar and trip wire gained four {@code minecraft:connection_*} booleans. On a 1.26.45 backend
 * a 1.26.50 player saw no stairs, no fences, no panes and no bars at all.
 *
 * <p><b>How the table is built.</b> Not by hand, and not from a third-party mapping. Mojang publish
 * every vanilla block with its properties, their types and their legal values, per version, in
 * {@code metadata/vanilladata_modules/mojang-blocks.json} in Mojang/bedrock-samples. The resource
 * this class loads is the diff of two of those files, and it carries only the blocks whose property
 * set changed, the old properties as the older version defines them, and the added properties with
 * the default Mojang lists first. Both hashes are then computed here rather than stored, so the
 * table stays readable and a mistake in it is a mistake about Minecraft rather than about arithmetic.
 *
 * <p><b>Both directions.</b> Upgrading picks the new state with every added property at its default.
 * Downgrading maps <em>every</em> value of the added properties back to the one old state, because
 * the old version cannot express them — a 1.26.50 backend's inner-corner stair reaches a 1.26.45
 * client as a plain stair, which is what that client was always going to draw anyway.
 *
 * <p><b>What it deliberately does not do.</b> The added properties are set to their defaults, never
 * computed from surrounding blocks: a fence relayed up from 1.26.45 says it connects to nothing. The
 * proxy has no world model to compute them from, and the alternative on offer is not "slightly wrong
 * connections" but "no block at all". Whether the client re-derives the geometry itself, as it did
 * before these were block states, is a rendering question this cannot settle from here.
 */
public final class BlockStateUpgrade {

    private final int fromProtocol;
    private final int toProtocol;
    private final String source;
    private final Map<Integer, Integer> upgrade;
    private final Map<Integer, Integer> downgrade;

    private BlockStateUpgrade(int fromProtocol, int toProtocol, String source,
                              Map<Integer, Integer> upgrade, Map<Integer, Integer> downgrade) {
        this.fromProtocol = fromProtocol;
        this.toProtocol = toProtocol;
        this.source = source;
        this.upgrade = Map.copyOf(upgrade);
        this.downgrade = Map.copyOf(downgrade);
    }

    /** Loads a table from the jar, e.g. {@code "/blockstate/2169-to-2192.json"}. */
    public static BlockStateUpgrade load(String resource) {
        String json;
        try (InputStream input = BlockStateUpgrade.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing block state upgrade resource " + resource);
            }
            json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + resource, e);
        }

        Map<String, Object> root;
        try {
            root = JsonUtil.parseJson(json);
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse " + resource, e);
        }

        Map<Integer, Integer> upgrade = new HashMap<>();
        Map<Integer, Integer> downgrade = new HashMap<>();

        for (Object rawGroup : asList(root.get("groups"))) {
            Map<?, ?> group = (Map<?, ?>) rawGroup;
            List<List<BlockStateValue>> oldCombinations = combinations(properties(group.get("oldStates")));
            List<List<BlockStateValue>> addedCombinations = combinations(properties(group.get("added")));
            List<BlockStateValue> addedDefaults = defaults(group.get("added"));

            for (Object rawName : asList(group.get("blocks"))) {
                String name = (String) rawName;
                for (List<BlockStateValue> oldStates : oldCombinations) {
                    int oldHash = BedrockBlockStateHash.of(name, oldStates);

                    List<BlockStateValue> upgraded = new ArrayList<>(oldStates);
                    upgraded.addAll(addedDefaults);
                    upgrade.put(oldHash, BedrockBlockStateHash.of(name, upgraded));

                    for (List<BlockStateValue> added : addedCombinations) {
                        List<BlockStateValue> newStates = new ArrayList<>(oldStates);
                        newStates.addAll(added);
                        downgrade.put(BedrockBlockStateHash.of(name, newStates), oldHash);
                    }
                }
            }
        }

        return new BlockStateUpgrade(
                ((Number) root.get("fromProtocol")).intValue(),
                ((Number) root.get("toProtocol")).intValue(),
                String.valueOf(root.get("source")),
                upgrade,
                downgrade);
    }

    /** The id an older backend's block should be sent to the newer client as. */
    public int toNewer(int runtimeId) {
        return upgrade.getOrDefault(runtimeId, runtimeId);
    }

    /** The id a newer peer's block should be sent to the older one as. */
    public int toOlder(int runtimeId) {
        return downgrade.getOrDefault(runtimeId, runtimeId);
    }

    /** Whether {@code runtimeId} is one this table rewrites when moving to the newer version. */
    public boolean rewritesToNewer(int runtimeId) {
        return upgrade.containsKey(runtimeId);
    }

    public int fromProtocol() {
        return fromProtocol;
    }

    public int toProtocol() {
        return toProtocol;
    }

    public String source() {
        return source;
    }

    /** How many old block states this table can carry forward. */
    public int upgradeCount() {
        return upgrade.size();
    }

    /** How many new block states this table can carry back. */
    public int downgradeCount() {
        return downgrade.size();
    }

    private record Property(String name, BlockStateValue.Type type, List<Object> values) {
    }

    private static List<Property> properties(Object raw) {
        List<Property> properties = new ArrayList<>();
        for (Object entry : asList(raw)) {
            Map<?, ?> map = (Map<?, ?>) entry;
            BlockStateValue.Type type = BlockStateValue.Type.fromMetadata((String) map.get("type"));
            Object values = map.get("values");
            properties.add(new Property(
                    (String) map.get("name"),
                    type,
                    values == null ? List.of(map.get("default")) : new ArrayList<>(asList(values))));
        }
        return properties;
    }

    private static List<BlockStateValue> defaults(Object raw) {
        List<BlockStateValue> defaults = new ArrayList<>();
        for (Object entry : asList(raw)) {
            Map<?, ?> map = (Map<?, ?>) entry;
            defaults.add(BlockStateValue.of(
                    (String) map.get("name"),
                    BlockStateValue.Type.fromMetadata((String) map.get("type")),
                    map.get("default")));
        }
        return defaults;
    }

    /**
     * Every combination of every property's legal values, in order. An empty property list yields one
     * empty combination rather than none — a block with no states still has exactly one state.
     */
    private static List<List<BlockStateValue>> combinations(List<Property> properties) {
        List<List<BlockStateValue>> result = new ArrayList<>();
        result.add(List.of());
        for (Property property : properties) {
            List<List<BlockStateValue>> expanded = new ArrayList<>(result.size() * property.values().size());
            for (List<BlockStateValue> prefix : result) {
                for (Object value : property.values()) {
                    List<BlockStateValue> next = new ArrayList<>(prefix);
                    next.add(BlockStateValue.of(property.name(), property.type(), value));
                    expanded.add(next);
                }
            }
            result = expanded;
        }
        return result;
    }

    private static List<?> asList(Object raw) {
        return raw == null ? List.of() : (List<?>) raw;
    }
}

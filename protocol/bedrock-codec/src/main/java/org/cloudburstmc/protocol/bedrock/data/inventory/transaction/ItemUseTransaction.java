package org.cloudburstmc.protocol.bedrock.data.inventory.transaction;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import lombok.Data;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;

import java.util.List;

@Data
public class ItemUseTransaction {
    private int legacyRequestId;
    private final List<LegacySetItemSlotData> legacySlots = new ObjectArrayList<>();
    private boolean usingNetIds;
    private final List<InventoryActionData> actions = new ObjectArrayList<>();
    private int actionType;
    private Vector3i blockPosition;
    private int blockFace;
    private int hotbarSlot;
    /**
     * Which hand the interaction came from, as {@code HandSlot}: 0 main, 1 off.
     *
     * <p>Held as an {@code int} rather than an enum, matching {@code InventoryTransactionPacket}'s
     * field of the same name in this tree. Upstream reads it as {@code HandSlot.values()[b]}, which
     * throws {@link ArrayIndexOutOfBoundsException} for any other byte &mdash; and this value comes
     * straight off a client, so that is a decode fault a player can trigger at will. An int cannot.
     *
     * @since v2192
     */
    private int hand;
    private ItemData itemInHand;
    private Vector3f playerPosition;
    private Vector3f clickPosition;
    private BlockDefinition blockDefinition;
    /**
     * @since v712
     */
    private PredictedResult clientInteractPrediction;
    /**
     * @since v712
     */
    private TriggerType triggerType;
    /**
     * @since v944
     */
    private int clientCooldownState;

    public enum PredictedResult {
        FAILURE,
        SUCCESS
    }

    public enum TriggerType {
        UNKNOWN,
        PLAYER_INPUT,
        SIMULATION_TICK
    }
}

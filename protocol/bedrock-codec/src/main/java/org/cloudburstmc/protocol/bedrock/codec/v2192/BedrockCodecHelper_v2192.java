package org.cloudburstmc.protocol.bedrock.codec.v2192;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.codec.EntityDataTypeMap;
import org.cloudburstmc.protocol.bedrock.codec.v2169.BedrockCodecHelper_v2169;
import org.cloudburstmc.protocol.bedrock.data.Ability;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.TextProcessingEventOrigin;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestActionType;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseSlot;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventorySource;
import org.cloudburstmc.protocol.common.util.TypeMap;
import org.cloudburstmc.protocol.common.util.VarInts;

import static java.util.Objects.requireNonNull;

/**
 * 1.26.50's codec helper.
 *
 * <p>Extends the <b>2169</b> helper, not the 2168 one upstream derives from. The two differ in one
 * place — {@code isRemoveScoreKeyedConstant()} — and it is not a free choice: 1.26.50 inherits
 * 1.26.45's {@code RemoveScore} shape, and on the 2168 helper that shape is per-connection state
 * read from the peer's Minecraft version string, defaulting to the 1.26.44 shape whenever the string
 * cannot be parsed. Deriving from 2168 would leave a 1.26.50 peer one unparseable version string
 * away from a corrupted scoreboard removal, and {@code SetScore} is a broadcast.
 *
 * <p>Upstream has no such hazard to avoid because it expresses the 1.26.44 shape as a separate codec
 * ({@code Bedrock_v2168_hotfix4}) rather than as a flag on the shared helper. This tree does not
 * carry that codec, so the flag is what has to be settled, and {@link BedrockCodecHelper_v2169}
 * settles it — {@code false}, with an inert setter.
 *
 * <p>Everything below is upstream's: 1.26.50 moves the filtered custom name ahead of the durability
 * correction in an item-stack response slot, and puts each of an inventory source's two fields
 * behind its own presence flag.
 */
public class BedrockCodecHelper_v2192 extends BedrockCodecHelper_v2169 {

    public BedrockCodecHelper_v2192(EntityDataTypeMap entityData, TypeMap<Class<?>> gameRulesTypes, TypeMap<ItemStackRequestActionType> stackRequestActionTypes,
                                    TypeMap<ContainerSlotType> containerSlotTypes, TypeMap<Ability> abilities, TypeMap<TextProcessingEventOrigin> textProcessingEventOrigins) {
        super(entityData, gameRulesTypes, stackRequestActionTypes, containerSlotTypes, abilities, textProcessingEventOrigins);
    }

    @Override
    protected ItemStackResponseSlot readItemEntry(ByteBuf buffer) {
        int slot = buffer.readUnsignedByte();
        int hotbarSlot = buffer.readUnsignedByte();
        int count = buffer.readUnsignedByte();
        int stackNetworkId = buffer.readBoolean() ? VarInts.readInt(buffer) : 0;
        String customName = this.readString(buffer);
        String filteredCustomName = this.readOptional(buffer, null, this::readString);
        int durabilityCorrection = VarInts.readInt(buffer);
        return new ItemStackResponseSlot(slot, hotbarSlot, count, stackNetworkId,
                customName, durabilityCorrection, filteredCustomName);

    }

    @Override
    protected void writeItemEntry(ByteBuf buffer, ItemStackResponseSlot itemEntry) {
        buffer.writeByte(itemEntry.getSlot());
        buffer.writeByte(itemEntry.getHotbarSlot());
        buffer.writeByte(itemEntry.getCount());
        this.writeOptional(buffer, id->id > 0, itemEntry.getStackNetworkId(), VarInts::writeInt);
        this.writeString(buffer, itemEntry.getCustomName());
        this.writeOptionalNull(buffer, itemEntry.getFilteredCustomName(), this::writeString);
        VarInts.writeInt(buffer, itemEntry.getDurabilityCorrection());
    }

    @Override
    public InventorySource readSource(ByteBuf buffer) {
        InventorySource.Type type = InventorySource.Type.byId(VarInts.readUnsignedInt(buffer));

        int containerId = 0;
        InventorySource.Flag flag = null;
        if (buffer.readBoolean()) containerId = buffer.readByte();
        if (buffer.readBoolean()) flag = InventorySource.Flag.values()[VarInts.readUnsignedInt(buffer)];
        switch (type) {
            case CONTAINER:
                return InventorySource.fromContainerWindowId(containerId);
            case GLOBAL:
                return InventorySource.fromGlobalInventory();
            case WORLD_INTERACTION:
                if (flag == null) throw new IllegalStateException();
                return InventorySource.fromWorldInteraction(flag);
            case CREATIVE:
                return InventorySource.fromCreativeInventory();
            case NON_IMPLEMENTED_TODO:
                return InventorySource.fromNonImplementedTodo(containerId);
            case UNTRACKED_INTERACTION_UI:
                return InventorySource.fromUntrackedInteractionUI(containerId);
            default:
                return InventorySource.fromInvalid();
        }
    }

    @Override
    public void writeSource(ByteBuf buffer, InventorySource inventorySource) {
        requireNonNull(inventorySource, "InventorySource was null");

        VarInts.writeUnsignedInt(buffer, inventorySource.getType().id());

        switch (inventorySource.getType()) {
            case CONTAINER:
            case NON_IMPLEMENTED_TODO:
                buffer.writeBoolean(true);
                buffer.writeByte(inventorySource.getContainerId());
                break;
            default:
                buffer.writeBoolean(false);
                break;
        }

        switch (inventorySource.getType()) {
            case WORLD_INTERACTION:
                buffer.writeBoolean(true);
                VarInts.writeUnsignedInt(buffer, inventorySource.getFlag().ordinal());
                break;
            default:
                buffer.writeBoolean(false);
                break;
        }
    }
}

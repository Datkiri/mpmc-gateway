/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.StringTag;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.minecraft.Holder;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandlers;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectMap;
import com.viaversion.viaversion.libs.fastutil.ints.IntObjectPair;
import com.viaversion.viaversion.libs.mcstructs.converter.impl.v1_21_5.NbtConverter_v1_21_5;
import com.viaversion.viaversion.libs.mcstructs.core.Identifier;
import com.viaversion.viaversion.libs.mcstructs.dialog.ActionButton;
import com.viaversion.viaversion.libs.mcstructs.dialog.AfterAction;
import com.viaversion.viaversion.libs.mcstructs.dialog.Dialog;
import com.viaversion.viaversion.libs.mcstructs.dialog.Input;
import com.viaversion.viaversion.libs.mcstructs.dialog.action.CustomAllAction;
import com.viaversion.viaversion.libs.mcstructs.dialog.body.PlainMessageBody;
import com.viaversion.viaversion.libs.mcstructs.dialog.impl.MultiActionDialog;
import com.viaversion.viaversion.libs.mcstructs.dialog.impl.NoticeDialog;
import com.viaversion.viaversion.libs.mcstructs.dialog.input.BooleanInput;
import com.viaversion.viaversion.libs.mcstructs.dialog.input.NumberRangeInput;
import com.viaversion.viaversion.libs.mcstructs.dialog.input.SingleOptionInput;
import com.viaversion.viaversion.libs.mcstructs.dialog.input.TextInput;
import com.viaversion.viaversion.libs.mcstructs.dialog.serializer.DialogSerializer;
import com.viaversion.viaversion.libs.mcstructs.text.TextComponent;
import com.viaversion.viaversion.libs.mcstructs.text.components.StringComponent;
import com.viaversion.viaversion.libs.mcstructs.text.components.TranslationComponent;
import com.viaversion.viaversion.protocols.v26_2to26_3.packet.ClientboundPackets26_3;
import com.viaversion.viaversion.protocols.v26_2to26_3.packet.ServerboundPackets26_3;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.lenni0451.mcstructs_bedrock.forms.Form;
import net.lenni0451.mcstructs_bedrock.forms.elements.*;
import net.lenni0451.mcstructs_bedrock.forms.serializer.FormSerializer;
import net.lenni0451.mcstructs_bedrock.forms.types.ActionForm;
import net.lenni0451.mcstructs_bedrock.forms.types.CustomForm;
import net.lenni0451.mcstructs_bedrock.forms.types.ModalForm;
import net.lenni0451.mcstructs_bedrock.text.utils.BedrockTextUtils;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.chunk.BedrockBlockEntity;
import net.raphimc.viabedrock.api.model.container.ChestContainer;
import net.raphimc.viabedrock.api.model.container.Container;
import net.raphimc.viabedrock.api.model.container.player.ArmorContainer;
import net.raphimc.viabedrock.api.model.container.player.InventoryContainer;
import net.raphimc.viabedrock.api.model.container.player.OffhandContainer;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.api.util.TextUtil;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.*;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.ContainerInput;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.EquipmentSlot;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.FullContainerName;
import net.raphimc.viabedrock.protocol.rewriter.BlockStateRewriter;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.storage.*;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.ArrayList;
import java.util.logging.Level;

public final class InventoryPackets {

    private static final int DIALOG_BUTTON_WIDTH = 200;
    private static final int DIALOG_FAKE_BUTTON_WIDTH = 300;
    private static final String DIALOG_FAKE_BUTTON_TEXT = "This is not actually a button, but has to be one because dialogs don't support adding text only elements. Clicking it has the same effect as closing the dialog.";

    // .13-exp diagnostics: all of them are disabled (empty property / "full" mode) in production builds.
    private static final String CONTROL_PATH = System.getProperty("mpmc.req.control", "");
    private static final String CREATIVE_DUMP = System.getProperty("mpmc.creative.dump", "");
    private static final java.util.Map<Integer, String> REQ_INFO = new java.util.concurrent.ConcurrentHashMap<>();

    // Measured on live BDS 1.26.51 (protocol 2193) across 78 creative-slot cases
    // (gw-data/sweep-equip-boundary*-result.json): equipment containers only accept
    // items carrying the matching equippable component. Every other item is rejected
    // with status 55 = CannotPlaceItem. Java creative allows arbitrary items in those
    // slots, so the gateway classifies and skips the request instead of emitting a
    // packet the host is guaranteed to reject.
    // Offhand accepted: shield, totem_of_undying, arrow (all Java arrow variants map
    // to this Bedrock id), firework_rocket, nautilus_shell.
    private static final java.util.Set<String> OFFHAND_EQUIPPABLE = java.util.Set.of(
            "minecraft:shield", "minecraft:totem_of_undying", "minecraft:arrow",
            "minecraft:firework_rocket", "minecraft:nautilus_shell");

    // Java item variants with no Bedrock CREATIVE_CONTENT entry of their own.
    // Bedrock 1.26.51 lists minecraft:empty_map (and minecraft:arrow metadata
    // variants) but not minecraft:filled_map / minecraft:spectral_arrow.
    // spectrum_arrow is handled in ItemRewriter.bedrockItem() (Java->Bedrock alias).
    private static final java.util.Map<String, String> CREATIVE_ENTRY_ALIASES = java.util.Map.of(
            "minecraft:filled_map", "minecraft:empty_map");

    private static boolean isBedrockEquippable(final ContainerEnumName containerName, final int containerSlot, final String bedrockId) {
        if (bedrockId == null || bedrockId.isEmpty()) {
            return false;
        }
        if (containerName == ContainerEnumName.OffhandContainer) {
            return OFFHAND_EQUIPPABLE.contains(bedrockId);
        }
        if (containerName != ContainerEnumName.ArmorContainer) {
            return true; // normal inventory accepts every creative item
        }
        return switch (containerSlot) {
            case 0 -> bedrockId.endsWith("_helmet") || bedrockId.endsWith("_head")
                    || bedrockId.endsWith("_skull") || bedrockId.equals("minecraft:carved_pumpkin");
            case 1 -> bedrockId.endsWith("_chestplate") || bedrockId.equals("minecraft:elytra");
            case 2 -> bedrockId.endsWith("_leggings");
            case 3 -> bedrockId.endsWith("_boots");
            default -> false; // Body slot (4) is not visible to Java clients
        };
    }

    private static int findCreativeEntryByIdentifier(final InventoryTracker tracker, final ItemRewriter itemRewriter, final String bedrockId) {
        for (final Int2ObjectMap.Entry<BedrockItem> entry : tracker.getCreativeItems().int2ObjectEntrySet()) {
            if (bedrockId.equals(itemRewriter.bedrockIdentifier(entry.getValue().identifier()))) {
                return entry.getIntKey();
            }
        }
        return -1;
    }

    /**
     * A creative slot update that BDS cannot accept is resolved locally instead of
     * being dropped silently or sent anyway (which produces a status 55/49 rejection).
     * keepOptimistic=true keeps the Java client's item when Bedrock simply has no
     * creative entry; false restores the previous item when BDS actively refuses the
     * destination. In both cases the Java container is echoed so the client stays in
     * sync with the gateway, and the in-flight guard is released so queued clicks drain.
     */
    private static void skipCreativeRequest(final UserConnection user, final InventoryTracker tracker,
                                            final ContainerEnumName containerName, final int containerSlot,
                                            final int javaSlot, final BedrockItem newItem, final BedrockItem oldItem,
                                            final String reason, final boolean keepOptimistic) {
        final Container container = creativeContainer(tracker, containerName);
        if (containerSlot >= 0 && containerSlot < container.size()) {
            container.setItem(containerSlot, keepOptimistic && !newItem.isEmpty() ? newItem.copy() : oldItem);
        }
        ViaBedrock.getPlatform().getLogger().log(Level.INFO, "[CLASSIFIED] creative slot " + javaSlot
                + " -> " + containerName + "/" + containerSlot + " not sent to BDS: " + reason
                + " (javaItem=" + (newItem.isEmpty() ? "<unmapped>" : newItem.identifier()) + ")");
        PacketFactory.sendJavaContainerSetContent(user, tracker.getInventoryContainer());
        final long key = creativeSlotKey(containerName, containerSlot);
        tracker.getInFlightCreativeSlots().remove(key);
        flushDeferredCreativeSlot(user, key);
    }

    private static final class ReqConfig {
        String mode = "full";
        int force = -1;
        int amount = -1;
        int crafts = 1;
        boolean resultsFromCreative;
        boolean reset;
        String note = "control-off";
        // Diagnostic knobs for the ITEM_STACK_REQUEST transfer-action chain.
        // In production the control file is absent, so every value below is the
        // default and the wire encoding is identical to the released build.
        String dstContainer = "";
        int dstSlot = -1;
        int dstNetId = 0;
        String srcContainer = "";
        int srcSlot = 50;
        int srcNetId = Integer.MIN_VALUE;
        String midContainer = "";
        int midSlot = 0;
        int midNetId = Integer.MIN_VALUE;
        boolean swap;

        ContainerEnumName dst(final ContainerEnumName fallback) {
            return this.dstContainer.isEmpty() ? fallback : parseContainer(this.dstContainer, fallback);
        }

        ContainerEnumName src() {
            return this.srcContainer.isEmpty() ? ContainerEnumName.CreatedOutputContainer : parseContainer(this.srcContainer, ContainerEnumName.CreatedOutputContainer);
        }

        ContainerEnumName mid() {
            return this.midContainer.isEmpty() ? null : parseContainer(this.midContainer, null);
        }

        private static ContainerEnumName parseContainer(final String name, final ContainerEnumName fallback) {
            try {
                return ContainerEnumName.valueOf(name);
            } catch (final IllegalArgumentException e) {
                return fallback;
            }
        }

        boolean craftCreative() { return !this.mode.equals("no-cc") && !this.mode.equals("results-only"); }
        boolean results() { return !this.mode.equals("cc-only") && !this.mode.equals("cc-destroy-take") && !this.mode.equals("create"); }
        boolean create() { return this.mode.equals("create"); }
        boolean destroy() { return !this.mode.equals("no-destroy") && !this.mode.equals("cc-results-take") && !this.mode.equals("cc-only") && !this.mode.equals("results-only"); }
        boolean take() { return !this.mode.equals("no-take"); }
        boolean place() { return this.mode.equals("place"); }
        boolean emptyDescriptor() { return this.mode.equals("empty-descriptor"); }
        boolean descriptorFromCreative() { return this.resultsFromCreative || this.mode.equals("descriptor-match"); }
        int count(final int original) { return this.mode.equals("amount1") ? 1 : (this.amount > 0 ? this.amount : original); }
    }

    private static ReqConfig readControl() {
        final ReqConfig cfg = new ReqConfig();
        if (CONTROL_PATH.isEmpty()) {
            return cfg;
        }
        try {
            final String line = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(CONTROL_PATH)), java.nio.charset.StandardCharsets.UTF_8).trim();
            if (line.isEmpty()) {
                return cfg;
            }
            cfg.note = line;
            for (final String part : line.split("[,;]+")) {
                final String[] kv = part.split("=", 2);
                if (kv.length != 2) continue;
                final String key = kv[0].trim();
                final String value = kv[1].trim();
                switch (key) {
                    case "mode" -> cfg.mode = value;
                    case "force" -> cfg.force = Integer.parseInt(value);
                    case "amount" -> cfg.amount = Integer.parseInt(value);
                    case "crafts" -> cfg.crafts = Integer.parseInt(value);
                    case "resultsFromCreative" -> cfg.resultsFromCreative = Boolean.parseBoolean(value);
                    case "reset" -> cfg.reset = Boolean.parseBoolean(value);
                    case "dst" -> cfg.dstContainer = value;
                    case "dstSlot" -> cfg.dstSlot = Integer.parseInt(value);
                    case "dstNetId" -> cfg.dstNetId = Integer.parseInt(value);
                    case "src" -> cfg.srcContainer = value;
                    case "srcSlot" -> cfg.srcSlot = Integer.parseInt(value);
                    case "srcNetId" -> cfg.srcNetId = Integer.parseInt(value);
                    case "mid" -> cfg.midContainer = value;
                    case "midSlot" -> cfg.midSlot = Integer.parseInt(value);
                    case "midNetId" -> cfg.midNetId = Integer.parseInt(value);
                    case "swap" -> cfg.swap = Boolean.parseBoolean(value);
                    default -> { }
                }
            }
        } catch (final Exception ignored) {
            // A malformed/transient control file must never break a player's inventory request.
        }
        return cfg;
    }

    public static void register(final BedrockProtocol protocol) {
        protocol.registerClientbound(ClientboundBedrockPackets.CONTAINER_OPEN, ClientboundPackets26_3.OPEN_SCREEN, wrapper -> {
            final ChunkTracker chunkTracker = wrapper.user().get(ChunkTracker.class);
            final BlockStateRewriter blockStateRewriter = wrapper.user().get(BlockStateRewriter.class);
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final byte containerId = wrapper.read(Types.BYTE); // container id
            final byte rawType = wrapper.read(Types.BYTE); // type
            final ContainerType type = ContainerType.getByValue(rawType);
            if (type == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown ContainerType: " + rawType);
                wrapper.cancel();
                return;
            }
            final BlockPosition position = wrapper.read(BedrockTypes.BLOCK_POSITION); // position
            wrapper.read(BedrockTypes.VAR_LONG); // entity unique id

            if (inventoryTracker.isAnyScreenOpen()) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Server tried to open container while another container is open");
                PacketFactory.sendBedrockContainerClose(wrapper.user(), (byte) -1, ContainerType.NONE);
                wrapper.cancel();
                return;
            }
            final BedrockBlockEntity blockEntity = chunkTracker.getBlockEntity(position);
            TextComponent title = new TranslationComponent("container." + blockStateRewriter.tag(chunkTracker.getBlockState(position)));
            if (blockEntity != null && blockEntity.tag().get("CustomName") instanceof StringTag customNameTag) {
                title = TextUtil.stringToTextComponent(wrapper.user().get(ResourcePackStorage.class).getTexts().translate(customNameTag.getValue()));
            }

            final Container container;
            switch (type) {
                case INVENTORY -> {
                    inventoryTracker.setCurrentContainer(new InventoryContainer(wrapper.user(), containerId, position, inventoryTracker.getInventoryContainer()));
                    wrapper.cancel();
                    return;
                }
                case CONTAINER -> container = new ChestContainer(wrapper.user(), containerId, title, position, 27);
                case NONE, CAULDRON, JUKEBOX, ARMOR, HAND, HUD, DECORATED_POT -> { // Bedrock client can't open these containers
                    wrapper.cancel();
                    return;
                }
                default -> {
                    // throw new IllegalStateException("Unhandled ContainerType: " + type);
                    wrapper.cancel();
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Tried to open unimplemented container: " + type);
                    PacketFactory.sendBedrockContainerClose(wrapper.user(), containerId, ContainerType.NONE);
                    return;
                }
            }
            inventoryTracker.setCurrentContainer(container);

            wrapper.write(Types.VAR_INT, (int) containerId); // container id
            wrapper.write(Types.VAR_INT, BedrockProtocol.MAPPINGS.getBedrockToJavaContainers().get(type)); // type
            wrapper.write(Types.TAG, TextUtil.textComponentToNbt(title)); // title
        });
        protocol.registerClientbound(ClientboundBedrockPackets.CONTAINER_CLOSE, ClientboundPackets26_3.CONTAINER_CLOSE, new PacketHandlers() {
            @Override
            protected void register() {
                map(Types.BYTE, Types.VAR_INT); // container id
                handler(wrapper -> {
                    final ContainerType containerType = ContainerType.getByValue(wrapper.read(Types.BYTE)); // type
                    final boolean serverInitiated = wrapper.read(Types.BOOLEAN); // server initiated

                    final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
                    final Container container = serverInitiated ? inventoryTracker.getCurrentContainer() : inventoryTracker.getPendingCloseContainer();
                    if (container == null) {
                        wrapper.cancel();
                        return;
                    }

                    if (serverInitiated && containerType != container.type()) {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Server tried to close container, but container type was not correct");
                        wrapper.cancel();
                        return;
                    }
                    inventoryTracker.setCurrentContainerClosed(serverInitiated);
                });
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.INVENTORY_CONTENT, ClientboundPackets26_3.CONTAINER_SET_CONTENT, wrapper -> {
            final ItemRewriter itemRewriter = wrapper.user().get(ItemRewriter.class);
            final int containerId = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // container id
            final BedrockItem[] items = wrapper.read(itemRewriter.newItemArrayType()); // items
            final FullContainerName containerName = wrapper.read(BedrockTypes.FULL_CONTAINER_NAME); // container name
            final BedrockItem storageItem = wrapper.read(itemRewriter.newItemType()); // storage item

            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final Container container = inventoryTracker.getContainerClientbound((byte) containerId, containerName, storageItem);
            if (container != null && container.setItems(items)) {
                PacketFactory.writeJavaContainerSetContent(wrapper, container);
            } else {
                wrapper.cancel();
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.INVENTORY_SLOT, ClientboundPackets26_3.CONTAINER_SET_SLOT, wrapper -> {
            final ItemRewriter itemRewriter = wrapper.user().get(ItemRewriter.class);
            final int containerId = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // container id
            final int slot = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // slot
            final FullContainerName containerName = wrapper.read(BedrockTypes.OPTIONAL_FULL_CONTAINER_NAME); // container name
            final BedrockItem storageItem = wrapper.read(itemRewriter.optionalNewItemType()); // storage item
            final BedrockItem item = wrapper.read(itemRewriter.newItemType()); // item

            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final Container container = inventoryTracker.getContainerClientbound((byte) containerId, containerName, storageItem);
            if (container != null && container.setItem(slot, item)) {
                if (!container.isJavaVisible(slot)) {
                    wrapper.cancel();
                    return;
                }
                if (container.type() == ContainerType.HUD && slot == 0) { // cursor item
                    wrapper.setPacketType(ClientboundPackets26_3.SET_CURSOR_ITEM);
                } else {
                    wrapper.write(Types.VAR_INT, (int) container.javaContainerId()); // container id
                    wrapper.write(Types.VAR_INT, 0); // revision
                    wrapper.write(Types.SHORT, (short) container.javaSlot(slot)); // slot
                }
                wrapper.write(VersionedTypes.V26_3.item, container.getJavaItem(slot)); // item
            } else {
                wrapper.cancel();
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.MODAL_FORM_REQUEST, ClientboundPackets26_3.SHOW_DIALOG, wrapper -> {
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final int id = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // id
            final String data = wrapper.read(BedrockTypes.STRING); // data

            if (inventoryTracker.getCurrentContainer() != null || inventoryTracker.getCurrentForm() != null) {
                final PacketWrapper modalFormResponse = PacketWrapper.create(ServerboundBedrockPackets.MODAL_FORM_RESPONSE, wrapper.user());
                modalFormResponse.write(BedrockTypes.UNSIGNED_VAR_INT, id); // id
                modalFormResponse.write(Types.BOOLEAN, false); // has response
                modalFormResponse.write(Types.BOOLEAN, true); // has cancel reason
                modalFormResponse.write(Types.BYTE, (byte) ModalFormCancelReason.UserBusy.getValue()); // cancel reason
                modalFormResponse.sendToServer(BedrockProtocol.class);
                wrapper.cancel();
                return;
            }

            final Form form;
            try {
                form = FormSerializer.deserialize(data);
            } catch (final Throwable e) { // Bedrock client shows error modal form
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error while deserializing form data: " + data, e);
                wrapper.cancel();
                return;
            }
            final ResourcePackStorage resourcePackStorage = wrapper.user().get(ResourcePackStorage.class);
            form.setTranslator(resourcePackStorage.getTexts()::translate);
            inventoryTracker.setCurrentForm(IntObjectPair.of(id, form));

            final Identifier responseIdentifier = Identifier.of("viabedrock", "form/" + id);
            final CompoundTag exitButtonAdditions = new CompoundTag();
            exitButtonAdditions.putBoolean("exit", true);
            final ActionButton exitButton = new ActionButton(new StringComponent(resourcePackStorage.getTexts().get("gui.close")), DIALOG_BUTTON_WIDTH, new CustomAllAction(responseIdentifier, exitButtonAdditions));

            final Dialog dialog;
            if (form instanceof ModalForm modalForm) {
                final MultiActionDialog actionDialog = new MultiActionDialog(TextUtil.stringToTextComponent(form.getTitle()), true, false, AfterAction.CLOSE, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), exitButton, 1);
                addTextToDialog(wrapper.user(), actionDialog, modalForm.getText());
                final CompoundTag button1Additions = new CompoundTag();
                button1Additions.putInt("button_id", 0);
                actionDialog.getActions().add(new ActionButton(TextUtil.stringToTextComponent(modalForm.getButton1()), DIALOG_BUTTON_WIDTH, new CustomAllAction(responseIdentifier, button1Additions)));
                final CompoundTag button2Additions = new CompoundTag();
                button2Additions.putInt("button_id", 1);
                actionDialog.getActions().add(new ActionButton(TextUtil.stringToTextComponent(modalForm.getButton2()), DIALOG_BUTTON_WIDTH, new CustomAllAction(responseIdentifier, button2Additions)));
                dialog = actionDialog;
            } else if (form instanceof ActionForm actionForm) {
                if (actionForm.getElements().length == 0) { // Text only form
                    final NoticeDialog noticeDialog = new NoticeDialog(TextUtil.stringToTextComponent(form.getTitle()), true, false, AfterAction.CLOSE, new ArrayList<>(), new ArrayList<>(), exitButton);
                    addTextToDialog(wrapper.user(), noticeDialog, actionForm.getText());
                    dialog = noticeDialog;
                } else {
                    final MultiActionDialog actionDialog = new MultiActionDialog(TextUtil.stringToTextComponent(form.getTitle()), true, false, AfterAction.CLOSE, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), exitButton, 1);
                    addTextToDialog(wrapper.user(), actionDialog, actionForm.getText());
                    int buttonIndex = 0;
                    for (int elementIndex = 0; elementIndex < actionForm.getElements().length; elementIndex++) {
                        final FormElement element = actionForm.getElements()[elementIndex];
                        if (element instanceof ButtonFormElement button) {
                            final CompoundTag buttonAdditions = new CompoundTag();
                            buttonAdditions.putInt("button_id", buttonIndex);
                            actionDialog.getActions().add(new ActionButton(TextUtil.stringToTextComponent(button.getText()), DIALOG_BUTTON_WIDTH, new CustomAllAction(responseIdentifier, buttonAdditions)));
                            buttonIndex++;
                        } else if (element instanceof HeaderFormElement header) {
                            actionDialog.getActions().add(new ActionButton(TextUtil.stringToTextComponent(header.getText()), new StringComponent(DIALOG_FAKE_BUTTON_TEXT), DIALOG_FAKE_BUTTON_WIDTH, exitButton.getAction()));
                        } else if (element instanceof LabelFormElement label) {
                            actionDialog.getActions().add(new ActionButton(TextUtil.stringToTextComponent(label.getText()), new StringComponent(DIALOG_FAKE_BUTTON_TEXT), DIALOG_FAKE_BUTTON_WIDTH, exitButton.getAction()));
                        } else if (element instanceof DividerFormElement) {
                            // No op
                        } else {
                            throw new IllegalArgumentException("Unhandled form element type: " + element.getClass().getSimpleName());
                        }
                    }
                    dialog = actionDialog;
                }
            } else if (form instanceof CustomForm customForm) {
                final MultiActionDialog actionDialog = new MultiActionDialog(TextUtil.stringToTextComponent(form.getTitle()), true, false, AfterAction.CLOSE, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), exitButton, 1);
                for (int elementIndex = 0; elementIndex < customForm.getElements().length; elementIndex++) {
                    final FormElement element = customForm.getElements()[elementIndex];
                    final String inputKey = String.valueOf(elementIndex);
                    if (element instanceof CheckboxFormElement checkbox) {
                        final BooleanInput booleanInput = new BooleanInput(TextUtil.stringToTextComponent(checkbox.getText()));
                        booleanInput.setInitial(checkbox.getDefaultValue());
                        actionDialog.getInputs().add(new Input(inputKey, booleanInput));
                    } else if (element instanceof DropdownFormElement dropdown) {
                        final SingleOptionInput singleOptionInput = new SingleOptionInput(new ArrayList<>(dropdown.getOptions().length), TextUtil.stringToTextComponent(dropdown.getText()));
                        for (int dropdownIndex = 0; dropdownIndex < dropdown.getOptions().length; dropdownIndex++) {
                            final String option = dropdown.getOptions()[dropdownIndex];
                            singleOptionInput.getOptions().add(new SingleOptionInput.Entry(String.valueOf(dropdownIndex), TextUtil.stringToTextComponent(option), dropdownIndex == dropdown.getDefaultOption()));
                        }
                        actionDialog.getInputs().add(new Input(inputKey, singleOptionInput));
                    } else if (element instanceof SliderFormElement slider) {
                        final NumberRangeInput numberRangeInput = new NumberRangeInput(TextUtil.stringToTextComponent(slider.getText()), new NumberRangeInput.Range(slider.getMin(), slider.getMax(), slider.getDefaultValue(), slider.getStep()));
                        actionDialog.getInputs().add(new Input(inputKey, numberRangeInput));
                    } else if (element instanceof StepSliderFormElement stepSlider) {
                        final SingleOptionInput singleOptionInput = new SingleOptionInput(new ArrayList<>(stepSlider.getSteps().length), TextUtil.stringToTextComponent(stepSlider.getText()));
                        for (int stepIndex = 0; stepIndex < stepSlider.getSteps().length; stepIndex++) {
                            final String step = stepSlider.getSteps()[stepIndex];
                            final String stepKey = String.valueOf(stepIndex);
                            singleOptionInput.getOptions().add(new SingleOptionInput.Entry(stepKey, TextUtil.stringToTextComponent(step), stepIndex == stepSlider.getDefaultStep()));
                        }
                        actionDialog.getInputs().add(new Input(inputKey, singleOptionInput));
                    } else if (element instanceof TextFieldFormElement textField) {
                        final TextInput textInput = new TextInput(TextUtil.stringToTextComponent(textField.getText()));
                        textInput.setMaxLength(100);
                        textInput.setInitial(textField.getDefaultValue());
                        actionDialog.getInputs().add(new Input(inputKey, textInput));
                    } else if (element instanceof HeaderFormElement header) {
                        addTextToDialog(wrapper.user(), actionDialog, header.getText());
                    } else if (element instanceof LabelFormElement label) {
                        addTextToDialog(wrapper.user(), actionDialog, label.getText());
                    } else if (element instanceof DividerFormElement) {
                        if (wrapper.user().getProtocolInfo().protocolVersion().newerThanOrEqualTo(ProtocolVersion.v1_21_6)) {
                            final TextInput textInput = new TextInput(new StringComponent());
                            textInput.setLabelVisible(false);
                            textInput.setMaxLength(Integer.MAX_VALUE);
                            textInput.setMultiline(new TextInput.MultilineOptions(null, 1));
                            actionDialog.getInputs().add(new Input("dummy", textInput));
                        }
                    } else {
                        throw new IllegalArgumentException("Unhandled form element type: " + element.getClass().getSimpleName());
                    }
                }
                actionDialog.getActions().add(new ActionButton(TextUtil.stringToTextComponent(resourcePackStorage.getTexts().get("gui.submit")), DIALOG_BUTTON_WIDTH, new CustomAllAction(responseIdentifier, null)));
                dialog = actionDialog;
            } else {
                throw new IllegalArgumentException("Unhandled form type: " + form.getClass().getSimpleName());
            }

            wrapper.write(Types.TRUSTED_COMPOUND_TAG_HOLDER, Holder.of((CompoundTag) DialogSerializer.V1_21_6.getDirectCodec().serialize(NbtConverter_v1_21_5.INSTANCE, dialog).get())); // dialog data
        });
        protocol.registerClientbound(ClientboundBedrockPackets.CLOSE_FORM, ClientboundPackets26_3.CLEAR_DIALOG, wrapper -> {
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            if (inventoryTracker.getCurrentForm() != null) {
                inventoryTracker.closeCurrentForm();
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.PLAYER_HOTBAR, ClientboundPackets26_3.SET_HELD_SLOT, wrapper -> {
            final InventoryContainer inventoryContainer = wrapper.user().get(InventoryTracker.class).getInventoryContainer();
            final int slot = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // selected slot
            final byte containerId = wrapper.read(Types.BYTE); // container id
            final boolean shouldSelectSlot = wrapper.read(Types.BOOLEAN); // should select slot
            if (slot >= 0 && slot < 9 && containerId == inventoryContainer.containerId() && shouldSelectSlot) {
                wrapper.write(Types.VAR_INT, slot); // slot
            } else {
                wrapper.cancel();
                if (containerId != inventoryContainer.containerId()) { // Bedrock client doesn't render hotbar selection and held item anymore
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Tried to set hotbar slot with wrong container id: " + containerId);
                }
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.CONTAINER_REGISTRY_CLEANUP, null, wrapper -> {
            wrapper.cancel();
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final FullContainerName[] removedContainers = wrapper.read(BedrockTypes.FULL_CONTAINER_NAME_ARRAY); // removed containers
            for (FullContainerName containerName : removedContainers) {
                inventoryTracker.removeDynamicContainer(containerName);
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.PLAYER_ARMOR_DAMAGE, ClientboundPackets26_3.SET_EQUIPMENT, wrapper -> {
            if (!wrapper.user().get(GameSessionStorage.class).isInventoryServerAuthoritative()) {
                wrapper.cancel();
                return;
            }
            final int size = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // size
            if (size <= 0) {
                wrapper.cancel();
                return;
            }
            final Container armorContainer = wrapper.user().get(InventoryTracker.class).getArmorContainer();

            wrapper.write(Types.VAR_INT, wrapper.user().get(EntityTracker.class).getClientPlayer().javaId()); // entity id
            for (int i = 0; i < size; i++) {
                final int rawArmorSlot = wrapper.read(BedrockTypes.VAR_INT); // armor slot
                final SharedTypes_Legacy_ArmorSlot armorSlot = SharedTypes_Legacy_ArmorSlot.getByValue(rawArmorSlot);
                if (armorSlot == null) { // Bedrock client ignores the whole packet if an unknown armor slot is sent
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown SharedTypes_Legacy_ArmorSlot: " + rawArmorSlot);
                    wrapper.cancel();
                    return;
                }
                final short damage = wrapper.read(BedrockTypes.SHORT_LE); // damage

                final BedrockItem item = armorSlot.getValue() < armorContainer.size() ? armorContainer.getItem(armorSlot.getValue()) : BedrockItem.empty();
                if (item.tag() == null) {
                    item.setTag(new CompoundTag());
                }
                item.tag().putInt("Damage", damage);

                final EquipmentSlot equipmentSlot = switch (armorSlot) {
                    case Head -> EquipmentSlot.HEAD;
                    case Torso -> EquipmentSlot.CHEST;
                    case Legs -> EquipmentSlot.LEGS;
                    case Feet -> EquipmentSlot.FEET;
                    case Body -> EquipmentSlot.BODY;
                };
                wrapper.write(Types.BYTE, (byte) (equipmentSlot.ordinal() | (i < (size - 1) ? Byte.MIN_VALUE : 0))); // slot
                wrapper.write(VersionedTypes.V26_3.item, wrapper.user().get(ItemRewriter.class).javaItem(item)); // item
            }
        });

        protocol.registerServerbound(ServerboundPackets26_3.CONTAINER_CLICK, null, wrapper -> {
            wrapper.cancel();
            final int containerId = wrapper.read(Types.VAR_INT); // container id
            final int revision = wrapper.read(Types.VAR_INT); // revision
            final short slot = wrapper.read(Types.SHORT); // slot
            final byte button = wrapper.read(Types.BYTE); // button
            final ContainerInput action = ContainerInput.values()[wrapper.read(Types.VAR_INT)]; // action

            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            if (inventoryTracker.getPendingCloseContainer() != null) {
                wrapper.cancel();
                return;
            }
            final Container container = inventoryTracker.getContainerServerbound((byte) containerId);
            if (container == null) {
                if (containerId == ContainerID.CONTAINER_ID_INVENTORY.getValue()) {
                    // Bedrock client can send multiple OpenInventory requests if the server doesn't respond, so this is fine here
                    final PacketWrapper interact = PacketWrapper.create(ServerboundBedrockPackets.INTERACT, wrapper.user());
                    interact.write(Types.UNSIGNED_BYTE, (short) InteractPacketPayload_Action.OpenInventory.getValue()); // action
                    interact.write(BedrockTypes.UNSIGNED_VAR_LONG, wrapper.user().get(EntityTracker.class).getClientPlayer().runtimeId()); // target entity runtime id
                    interact.write(BedrockTypes.OPTIONAL_POSITION_3F, null); // position
                    interact.sendToServer(BedrockProtocol.class);
                    PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getInventoryContainer());
                }

                wrapper.cancel();
                return;
            }
            if (!container.handleClick(revision, slot, button, action)) {
                if (container.type() != ContainerType.INVENTORY) {
                    PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getInventoryContainer());
                }
                PacketFactory.sendJavaContainerSetContent(wrapper.user(), container);
            }
        });
        protocol.registerServerbound(ServerboundPackets26_3.SET_CREATIVE_MODE_SLOT, null, wrapper -> {
            wrapper.cancel();
            final int javaSlot = wrapper.read(Types.SHORT); // slot
            final Item javaItem = wrapper.read(VersionedTypes.V26_3.lengthPrefixedItem); // item

            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            if (inventoryTracker.getPendingCloseContainer() != null) {
                return;
            }
            handleCreativeModeSlot(wrapper.user(), javaSlot, javaItem);
        });
        protocol.registerClientbound(ClientboundBedrockPackets.CREATIVE_CONTENT, null, wrapper -> {
            wrapper.cancel();
            final byte[] raw = wrapper.read(Types.REMAINING_BYTES);
            final ItemRewriter itemRewriter = wrapper.user().get(ItemRewriter.class);
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final ByteBuf buffer = Unpooled.wrappedBuffer(raw);
            try {
                final int groupCount = BedrockTypes.UNSIGNED_VAR_INT.read(buffer); // groups
                final StringBuilder groups = new StringBuilder();
                for (int i = 0; i < groupCount; i++) {
                    final int category = buffer.readUnsignedByte(); // category
                    final String name = BedrockTypes.STRING.read(buffer); // name
                    final BedrockItem icon = itemRewriter.itemType().read(buffer); // icon item
                    groups.append('[').append(i).append(" cat=").append(category).append(" name=").append(name).append(" icon=").append(icon.identifier()).append('x').append(icon.amount()).append(']');
                }
                final int itemCount = BedrockTypes.UNSIGNED_VAR_INT.read(buffer); // items
                final StringBuilder dump = CREATIVE_DUMP.isEmpty() ? null : new StringBuilder("entryId\tbedrockId\tidentifier\tdata\tamount\tblockRuntimeId\thasTag\tgroupIndex\n");
                for (int i = 0; i < itemCount; i++) {
                    final int entryId = BedrockTypes.UNSIGNED_VAR_INT.read(buffer); // entry id
                    final BedrockItem item = itemRewriter.itemType().read(buffer); // item
                    final int groupIndex = BedrockTypes.UNSIGNED_VAR_INT.read(buffer); // group index
                    if (!item.isEmpty()) {
                        inventoryTracker.getCreativeItems().put(entryId, item);
                    }
                    if (dump != null) {
                        dump.append(entryId).append('\t').append(item.identifier()).append('\t')
                                .append(itemRewriter.bedrockIdentifier(item.identifier())).append('\t')
                                .append(item.data() & 0xFFFF).append('\t').append(item.amount()).append('\t')
                                .append(item.blockRuntimeId()).append('\t').append(item.tag() != null).append('\t')
                                .append(groupIndex).append('\n');
                    }
                }
                if (dump != null) {
                    try {
                        java.nio.file.Files.write(java.nio.file.Paths.get(CREATIVE_DUMP), dump.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        ViaBedrock.getPlatform().getLogger().log(Level.INFO, "CREATIVE_CONTENT dumped " + itemCount + " entries to " + CREATIVE_DUMP);
                    } catch (final Exception e) {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "CREATIVE_CONTENT dump failed: " + e);
                    }
                }
                ViaBedrock.getPlatform().getLogger().log(Level.INFO, "CREATIVE_CONTENT parsed: groups=" + groupCount + " " + groups + " items=" + itemCount + " stored=" + inventoryTracker.getCreativeItems().size() + " remaining=" + buffer.readableBytes());
            } catch (final Throwable t) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "CREATIVE_CONTENT parse failed after " + buffer.readerIndex() + "/" + raw.length + " bytes: " + t, t);
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "CREATIVE_CONTENT raw bytes: " + ByteBufUtil.hexDump(Unpooled.wrappedBuffer(raw, 0, Math.min(raw.length, 512))));
            } finally {
                buffer.release();
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.ITEM_STACK_RESPONSE, null, wrapper -> {
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final int responseCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // responses
            for (int i = 0; i < responseCount; i++) {
                final int status = wrapper.read(Types.UNSIGNED_BYTE); // status
                final int requestId = wrapper.read(BedrockTypes.VAR_INT); // request id (zigzag32)
                final InventoryTracker.PendingCreativeSlot pending = inventoryTracker.getPendingCreativeSlots().remove(requestId);

                if (wrapper.read(Types.BOOLEAN)) { // has containers
                    final int containerCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
                    for (int c = 0; c < containerCount; c++) {
                        final FullContainerName containerName = wrapper.read(BedrockTypes.FULL_CONTAINER_NAME);
                        final int slotCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
                        for (int s = 0; s < slotCount; s++) {
                            final int slot = wrapper.read(Types.UNSIGNED_BYTE); // slot
                            wrapper.read(Types.UNSIGNED_BYTE); // hotbar slot
                            final int count = wrapper.read(Types.UNSIGNED_BYTE); // count
                            final int stackId = wrapper.read(Types.BOOLEAN) ? wrapper.read(BedrockTypes.VAR_INT) : 0; // optional stack network id
                            wrapper.read(BedrockTypes.STRING); // custom name
                            wrapper.read(BedrockTypes.STRING); // filtered custom name
                            wrapper.read(BedrockTypes.VAR_INT); // durability correction

                            if (status == 0) {
                                applyItemStackResponseSlot(inventoryTracker, pending, containerName.name(), slot, count, stackId);
                            }
                        }
                    }
                }

                if (status != 0) {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Bedrock rejected item stack request " + requestId + " (status " + status + " = " + ItemStackNetResult.getByValue(status, ItemStackNetResult.Error) + ") cfg=" + REQ_INFO.remove(requestId));
                    if (pending != null) {
                        restoreCreativeSlot(wrapper.user(), inventoryTracker, pending);
                    }
                } else {
                    ViaBedrock.getPlatform().getLogger().log(Level.INFO, "Bedrock accepted item stack request " + requestId + " cfg=" + REQ_INFO.remove(requestId));
                }
                if (pending != null) {
                    // This response resolves the guard for the (container, slot). If the Java client
                    // queued a second click while it was in flight, send it now that BDS has assigned
                    // a real stack network id to the source item.
                    final long key = creativeSlotKey(pending.container(), pending.slot());
                    inventoryTracker.getInFlightCreativeSlots().remove(key);
                    flushDeferredCreativeSlot(wrapper.user(), key);
                }
            }
            wrapper.cancel();
        });
        protocol.registerServerbound(ServerboundPackets26_3.CUSTOM_CLICK_ACTION, ServerboundBedrockPackets.MODAL_FORM_RESPONSE, wrapper -> {
            final String id = wrapper.read(Types.STRING); // id
            final CompoundTag payload = (CompoundTag) wrapper.read(Types.CUSTOM_CLICK_ACTION_TAG); // payload
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            if (inventoryTracker.getCurrentForm() == null) {
                wrapper.cancel();
                return;
            }

            final Form form = inventoryTracker.getCurrentForm().right();
            final int formId = inventoryTracker.getCurrentForm().leftInt();
            if (!id.equals("viabedrock:form/" + formId)) {
                wrapper.cancel();
                return;
            }

            inventoryTracker.setCurrentForm(null);
            if (payload.contains("exit") && payload.getBoolean("exit")) {
                wrapper.write(BedrockTypes.UNSIGNED_VAR_INT, formId); // id
                wrapper.write(Types.BOOLEAN, false); // has response
                wrapper.write(Types.BOOLEAN, true); // has cancel reason
                wrapper.write(Types.BYTE, (byte) ModalFormCancelReason.UserClosed.getValue()); // cancel reason
                return;
            }

            if (form instanceof ModalForm modalForm) {
                modalForm.setClickedButton(payload.getInt("button_id"));
            } else if (form instanceof ActionForm actionForm) {
                actionForm.setClickedButton(payload.getInt("button_id"));
            } else if (form instanceof CustomForm customForm) {
                for (int elementIndex = 0; elementIndex < customForm.getElements().length; elementIndex++) {
                    final String inputKey = String.valueOf(elementIndex);
                    if (!payload.contains(inputKey)) {
                        continue;
                    }
                    final FormElement element = customForm.getElements()[elementIndex];
                    if (element instanceof CheckboxFormElement checkbox) {
                        checkbox.setChecked(payload.getBoolean(inputKey));
                    } else if (element instanceof DropdownFormElement dropdown) {
                        dropdown.setSelected(Integer.parseInt(payload.getString(inputKey)));
                    } else if (element instanceof SliderFormElement slider) {
                        slider.setCurrent(payload.getFloat(inputKey));
                    } else if (element instanceof StepSliderFormElement stepSlider) {
                        stepSlider.setSelected(Integer.parseInt(payload.getString(inputKey)));
                    } else if (element instanceof TextFieldFormElement textField) {
                        textField.setValue(payload.getString(inputKey));
                    }
                }
            } else {
                throw new IllegalArgumentException("Unhandled form type: " + form.getClass().getSimpleName());
            }

            wrapper.write(BedrockTypes.UNSIGNED_VAR_INT, formId); // id
            wrapper.write(Types.BOOLEAN, true); // has response
            wrapper.write(BedrockTypes.STRING, form.serializeResponse() + '\n'); // response
            wrapper.write(Types.BOOLEAN, false); // has cancel reason
        });
        protocol.registerServerbound(ServerboundPackets26_3.CONTAINER_CLOSE, ServerboundBedrockPackets.CONTAINER_CLOSE, new PacketHandlers() {
            @Override
            protected void register() {
                map(Types.VAR_INT, Types.BYTE); // container id
                create(Types.BYTE, (byte) ContainerType.NONE.getValue()); // type
                create(Types.BOOLEAN, false); // server initiated
                handler(wrapper -> {
                    final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
                    final byte containerId = wrapper.get(Types.BYTE, 0);
                    final Container container = inventoryTracker.getContainerServerbound(containerId);
                    if (container == null) {
                        wrapper.cancel();
                        return;
                    }

                    if (container.javaContainerId() != container.containerId()) {
                        wrapper.set(Types.BYTE, 0, container.containerId());
                    }
                    inventoryTracker.markPendingClose(container);
                });
            }
        });
        protocol.registerServerbound(ServerboundPackets26_3.SET_CARRIED_ITEM, ServerboundBedrockPackets.MOB_EQUIPMENT, wrapper -> {
            final short slot = wrapper.read(Types.SHORT); // slot
            wrapper.user().get(InventoryTracker.class).getInventoryContainer().setSelectedHotbarSlot((byte) slot, wrapper); // slot
        });
        protocol.registerServerbound(ServerboundPackets26_3.PICK_ITEM_FROM_BLOCK, ServerboundBedrockPackets.BLOCK_PICK_REQUEST, wrapper -> {
            wrapper.passthroughAndMap(Types.BLOCK_POSITION1_14, BedrockTypes.BLOCK_POSITION); // position
            wrapper.passthrough(Types.BOOLEAN); // include data
            wrapper.write(Types.UNSIGNED_BYTE, (short) 9); // number of empty hotbar slots (vanilla client always sends 9)
        });
        protocol.registerServerbound(ServerboundPackets26_3.PICK_ITEM_FROM_ENTITY, ServerboundBedrockPackets.ENTITY_PICK_REQUEST, wrapper -> {
            final int entityId = wrapper.read(Types.VAR_INT); // entity id
            final boolean includeData = wrapper.read(Types.BOOLEAN); // include data

            final Entity entity = wrapper.user().get(EntityTracker.class).getEntityByJid(entityId);
            if (entity == null) {
                wrapper.cancel();
                return;
            }

            wrapper.write(BedrockTypes.LONG_LE, entity.uniqueId()); // entity unique id
            wrapper.write(Types.UNSIGNED_BYTE, (short) 9); // number of empty hotbar slots (vanilla client always sends 9)
            wrapper.write(Types.BOOLEAN, includeData); // include data
        });
    }

    private static long creativeSlotKey(final ContainerEnumName containerName, final int containerSlot) {
        return ((long) containerName.ordinal() << 32) | (containerSlot & 0xffffffffL);
    }

    private static void flushDeferredCreativeSlot(final UserConnection user, final long slotKey) {
        final InventoryTracker tracker = user.get(InventoryTracker.class);
        final java.util.ArrayDeque<InventoryTracker.DeferredCreativeSlot> queue = tracker.getDeferredCreativeSlots().get(slotKey);
        while (queue != null && !queue.isEmpty()) {
            final InventoryTracker.DeferredCreativeSlot next = queue.poll();
            // The in-flight guard was already removed by the ITEM_STACK_RESPONSE handler,
            // so this call sends the next queued request against a fully updated tracker.
            handleCreativeModeSlot(user, next.javaSlot(), next.javaItem());
            if (tracker.getInFlightCreativeSlots().containsKey(slotKey)) {
                return; // request is in flight; the next response will flush the rest
            }
            // The queued request was dropped before being sent (invalid item / empty slot).
            // Keep draining so a later valid click is not stuck behind it.
        }
    }

    /**
     * BDS 1.26.51 addresses the player's offhand item at OffhandContainer slot 1
     * (this is also the slot OffhandContainer.onSlotChanged() writes into
     * MOB_EQUIPMENT). ViaBedrock's OffhandContainer tracker has a single slot 0,
     * so the slot used on the ITEM_STACK_REQUEST wire differs from the local
     * tracker slot. Writing slot 0 on the wire makes BDS reject the request with
     * status 50 FailedToValidateDstSlot / 55 CannotPlaceItem. Measured on live
     * BDS: slot 0 rejected, slot 1 accepted and the item is really equipped
     * (observer client receives entity_equipment slot=1 with the item).
     */
    private static int bedrockRequestSlot(final ContainerEnumName containerName, final int containerSlot) {
        return containerName == ContainerEnumName.OffhandContainer ? 1 : containerSlot;
    }

    private static void handleCreativeModeSlot(final UserConnection user, final int javaSlot, final Item javaItem) {
        final InventoryTracker inventoryTracker = user.get(InventoryTracker.class);
        final ContainerEnumName containerName = creativeSlotContainer(javaSlot);
        if (containerName == null) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unhandled creative mode slot: " + javaSlot);
            return;
        }
        final int containerSlot = creativeSlotIndex(javaSlot);
        final Container container = creativeContainer(inventoryTracker, containerName);
        if (containerSlot < 0 || containerSlot >= container.size()) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Creative mode slot " + javaSlot + " maps to out of bounds slot " + containerSlot);
            return;
        }
        final int requestSlot = bedrockRequestSlot(containerName, containerSlot);

        // A real Java 1.21.1 GUI client sends two SET_CREATIVE_MODE_SLOT packets per click.
        // If a Bedrock ITEM_STACK_REQUEST for this (container, slot) is still awaiting its
        // ITEM_STACK_RESPONSE, the optimistic source stack has no server-side network id yet
        // and BDS rejects the follow-up with status 49 (FailedToValidateSrcSlot). Queue it and
        // flush it only after the previous response has applied the real net id. This also
        // prevents two craft-action chains from being validated in the same server tick.
        final long slotKey = creativeSlotKey(containerName, containerSlot);
        final long now = System.currentTimeMillis();
        final Long inFlightSince = inventoryTracker.getInFlightCreativeSlots().get(slotKey);
        if (inFlightSince != null && now - inFlightSince <= 5000L) {
            inventoryTracker.getDeferredCreativeSlots()
                    .computeIfAbsent(slotKey, k -> new java.util.ArrayDeque<>())
                    .add(new InventoryTracker.DeferredCreativeSlot(javaSlot, javaItem));
            // Keep the Java client's view optimistic while Bedrock is still processing.
            container.setItem(containerSlot, user.get(ItemRewriter.class).bedrockItem(javaItem).copy());
            PacketFactory.sendJavaContainerSetContent(user, inventoryTracker.getInventoryContainer());
            return;
        }
        if (inFlightSince != null) {
            // BDS never answered within 5 s: abandon the stale guard so new clicks are not stuck.
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Creative slot " + javaSlot + " request timed out, dropping stale in-flight guard");
            inventoryTracker.getInFlightCreativeSlots().remove(slotKey);
        }
        inventoryTracker.getInFlightCreativeSlots().put(slotKey, now);

        final ReqConfig cfg = readControl();
        if (cfg.reset) {
            container.setItem(containerSlot, BedrockItem.empty());
        }
        final ItemRewriter itemRewriter = user.get(ItemRewriter.class);
        BedrockItem newItem = itemRewriter.bedrockItem(javaItem);
        final BedrockItem oldItem = container.getItem(containerSlot).copy();
        if (cfg.force >= 0) {
            final BedrockItem forced = inventoryTracker.getCreativeItems().get(cfg.force);
            if (forced == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Forced creative entry " + cfg.force + " not found");
                inventoryTracker.getInFlightCreativeSlots().remove(slotKey);
                return;
            }
            newItem = forced.copy();
        }
        if (cfg.amount > 0) {
            newItem.setAmount(cfg.amount);
        }
        if (newItem.isEmpty() && !javaItem.isEmpty()) {
            // ItemRewriter could not map this Java item to any Bedrock item. Previously
            // this fell through into the empty+empty branch and the click vanished with
            // no feedback at all ("cannot take items"). Classify it explicitly.
            skipCreativeRequest(user, inventoryTracker, containerName, containerSlot, javaSlot,
                    newItem, oldItem, "no Java -> Bedrock item mapping", false);
            return;
        }
        if (newItem.isEmpty() && oldItem.isEmpty()) {
            inventoryTracker.getInFlightCreativeSlots().remove(slotKey);
            return;
        }

        int creativeEntryId = -1;
        BedrockItem creativeItem = null;
        if (!newItem.isEmpty()) {
            creativeEntryId = cfg.force >= 0 ? cfg.force : findCreativeEntry(inventoryTracker, newItem);
            if (creativeEntryId == -1 && cfg.force < 0) {
                final String originalBedrockId = itemRewriter.bedrockIdentifier(newItem.identifier());
                final String alias = CREATIVE_ENTRY_ALIASES.get(originalBedrockId);
                if (alias != null) {
                    final int aliasId = findCreativeEntryByIdentifier(inventoryTracker, itemRewriter, alias);
                    if (aliasId >= 0) {
                        creativeEntryId = aliasId;
                        // Use the real Bedrock creative item as the request result so the
                        // CraftResults descriptor matches what BDS can actually create.
                        newItem = inventoryTracker.getCreativeItems().get(aliasId).copy();
                        ViaBedrock.getPlatform().getLogger().log(Level.INFO, "[CLASSIFIED] creative alias "
                                + originalBedrockId + " -> " + itemRewriter.bedrockIdentifier(newItem.identifier())
                                + " for Java item " + javaItem.identifier());
                    }
                }
            }
            creativeItem = inventoryTracker.getCreativeItems().get(creativeEntryId);
            if (creativeEntryId == -1 || creativeItem == null) {
                // Bedrock has no CREATIVE_CONTENT entry for this item. Keep the Java
                // client's optimistic view (it still "takes" the item) but never send a
                // request BDS cannot answer; record the semantic downgrade explicitly.
                skipCreativeRequest(user, inventoryTracker, containerName, containerSlot, javaSlot,
                        newItem, oldItem, "no Bedrock CREATIVE_CONTENT entry", true);
                return;
            }
        }
        // Measured on live BDS 1.26.51 (protocol 2193), jar .14: the same creative
        // ITEM_STACK_REQUEST layout (actions=3, crafts=1) is accepted with count 64
        // (status 0) but terminates the connection with
        // "Packet violation warning: PacketMalformed" for count 65/100/127.
        // Java creative lets the client send an amount above max_stack_size, so clamp
        // the wire amount to 64 before writing CraftResults / Take. The optimistic
        // container keeps the requested amount; ITEM_STACK_RESPONSE reconciles it.
        // Counts >=128 never reach this code (the Java encoder drops them first).
        final int count = clampStackCount(cfg.count(newItem.amount()), newItem);
        final BedrockItem resultItem = cfg.descriptorFromCreative() && creativeItem != null ? creativeItem : newItem;
        final String descriptor = resultItem.isEmpty() ? "<empty>" : String.valueOf(itemRewriter.bedrockIdentifier(resultItem.identifier()));

        if (!newItem.isEmpty() && !isBedrockEquippable(containerName, containerSlot,
                itemRewriter.bedrockIdentifier(newItem.identifier()))) {
            // Live BDS 1.26.51 returns status 55 CannotPlaceItem for every non-equippable
            // item placed into ArmorContainer / OffhandContainer. Restore the slot instead
            // of emitting a request the host is guaranteed to reject.
            skipCreativeRequest(user, inventoryTracker, containerName, containerSlot, javaSlot,
                    newItem, oldItem, "BDS rejects " + itemRewriter.bedrockIdentifier(newItem.identifier())
                            + " in " + containerName + "/" + containerSlot + " (status 55)", false);
            return;
        }

        final int requestId = inventoryTracker.nextItemStackRequestId();

        // Item stack requests are validated against the screen the server thinks is open. The Bedrock
        // inventory screen is only opened after the client sends Interact(OpenInventory), so make sure
        // it is open before sending our first request (the Java client has no equivalent packet).
        if (inventoryTracker.getCurrentContainer() == null && inventoryTracker.getPendingCloseContainer() == null) {
            final PacketWrapper openInventory = PacketWrapper.create(ServerboundBedrockPackets.INTERACT, user);
            openInventory.write(Types.UNSIGNED_BYTE, (short) InteractPacketPayload_Action.OpenInventory.getValue()); // action
            openInventory.write(BedrockTypes.UNSIGNED_VAR_LONG, user.get(EntityTracker.class).getClientPlayer().runtimeId()); // target entity runtime id
            openInventory.write(BedrockTypes.OPTIONAL_POSITION_3F, null); // position
            openInventory.sendToServer(BedrockProtocol.class);
        }

        final boolean doDestroy = cfg.destroy() && !oldItem.isEmpty();
        final ContainerEnumName midContainer = cfg.mid();
        final int transferActions = cfg.take() && !newItem.isEmpty() ? (midContainer != null ? 2 : 1) : 0;
        final int actionCount = (cfg.craftCreative() && !newItem.isEmpty() ? 1 : 0)
                + (cfg.results() && !newItem.isEmpty() ? 1 : 0)
                + (cfg.create() && !newItem.isEmpty() ? 1 : 0)
                + (doDestroy ? 1 : 0)
                + transferActions;

        final PacketWrapper request = PacketWrapper.create(ServerboundBedrockPackets.ITEM_STACK_REQUEST, user);
        request.write(BedrockTypes.UNSIGNED_VAR_INT, 1); // requests
        request.write(BedrockTypes.VAR_INT, requestId); // request id (zigzag32)
        request.write(BedrockTypes.UNSIGNED_VAR_INT, actionCount); // actions

        // The vanilla client always sends CraftCreative -> CraftResultsDeprecated -> (Destroy) -> Take/Place.
        if (cfg.craftCreative() && !newItem.isEmpty()) {
            writeItemStackRequestAction(request, ItemStackRequestActionType.CraftCreative);
            request.write(BedrockTypes.UNSIGNED_VAR_INT, creativeEntryId); // creative item network id
            request.write(Types.UNSIGNED_BYTE, (short) clampStackCount(cfg.crafts)); // times crafted
        }
        if (cfg.results() && !newItem.isEmpty()) {
            writeItemStackRequestAction(request, ItemStackRequestActionType.CraftResults);
            request.write(BedrockTypes.UNSIGNED_VAR_INT, 1); // result items
            writeStackRequestItem(request, itemRewriter, resultItem, count, cfg.emptyDescriptor());
            request.write(Types.UNSIGNED_BYTE, (short) clampStackCount(cfg.crafts)); // times crafted
        }
        if (cfg.create() && !newItem.isEmpty()) {
            writeItemStackRequestAction(request, ItemStackRequestActionType.Create);
            request.write(Types.UNSIGNED_BYTE, (short) 0); // results slot
        }
        if (doDestroy) {
            writeItemStackRequestAction(request, ItemStackRequestActionType.Destroy);
            request.write(Types.UNSIGNED_BYTE, (short) clampStackCount(oldItem.amount(), oldItem)); // count
            writeItemStackRequestSlot(request, containerName, requestSlot, oldItem.netId() != null ? oldItem.netId() : 0); // source
        }
        if (cfg.take() && !newItem.isEmpty()) {
            final ContainerEnumName dstContainer = cfg.dst(containerName);
            final int dstSlot = cfg.dstSlot >= 0 ? cfg.dstSlot : bedrockRequestSlot(dstContainer, containerSlot);
            final int srcNetId = cfg.srcNetId == Integer.MIN_VALUE ? creativeTakeSourceStackId(requestId) : cfg.srcNetId;
            final int midNetId = cfg.midNetId == Integer.MIN_VALUE ? creativeTakeSourceStackId(requestId) : cfg.midNetId;
            if (midContainer != null) {
                // Diagnostic two-step chain: create -> intermediate container -> equipment slot.
                writeItemStackRequestAction(request, ItemStackRequestActionType.Take);
                request.write(Types.UNSIGNED_BYTE, (short) count); // count
                writeItemStackRequestSlot(request, ContainerEnumName.CreatedOutputContainer, 50, srcNetId); // source
                writeItemStackRequestSlot(request, midContainer, cfg.midSlot, 0); // intermediate destination

                writeItemStackRequestAction(request, ItemStackRequestActionType.Place);
                request.write(Types.UNSIGNED_BYTE, (short) count); // count
                writeItemStackRequestSlot(request, midContainer, cfg.midSlot, midNetId); // intermediate source
                writeItemStackRequestSlot(request, dstContainer, dstSlot, cfg.dstNetId); // destination
            } else if (cfg.swap) {
                writeItemStackRequestAction(request, ItemStackRequestActionType.Swap);
                writeItemStackRequestSlot(request, cfg.src(), cfg.srcSlot, srcNetId); // source
                writeItemStackRequestSlot(request, dstContainer, dstSlot, cfg.dstNetId); // destination
            } else {
                writeItemStackRequestAction(request, cfg.place() ? ItemStackRequestActionType.Place : ItemStackRequestActionType.Take);
                request.write(Types.UNSIGNED_BYTE, (short) count); // count
                // Stacks created by the current request are referenced by the request id, which doubles
                // as their stack network id. Slots address it with the created output protocol slot 50.
                writeItemStackRequestSlot(request, cfg.src(), cfg.srcSlot, srcNetId); // source
                writeItemStackRequestSlot(request, dstContainer, dstSlot, cfg.dstNetId); // destination
            }
        }

        request.write(BedrockTypes.UNSIGNED_VAR_INT, 0); // custom names
        request.write(BedrockTypes.INT_LE, 0); // filter cause
        request.sendToServer(BedrockProtocol.class);

        final String info = "cfg=" + cfg.note + " mode=" + cfg.mode + " creativeId=" + creativeEntryId
                + " newId=" + newItem.identifier() + " newIdentifier=" + itemRewriter.bedrockIdentifier(newItem.identifier())
                + " newData=" + (newItem.data() & 0xFFFF) + " newBlockRuntimeId=" + newItem.blockRuntimeId()
                + " amount=" + newItem.amount() + " count=" + count + " descriptor=" + descriptor
                + " descriptorId=" + resultItem.identifier() + " descriptorData=" + (resultItem.data() & 0xFFFF)
                + " descriptorBlockRuntimeId=" + resultItem.blockRuntimeId()
                + " creativeEntryId=" + (creativeItem == null ? -1 : creativeItem.identifier())
                + " creativeEntryData=" + (creativeItem == null ? -1 : (creativeItem.data() & 0xFFFF))
                + " creativeEntryBlockRuntimeId=" + (creativeItem == null ? -1 : creativeItem.blockRuntimeId())
                + " actions=" + actionCount + " crafts=" + cfg.crafts;
        REQ_INFO.put(requestId, info);
        ViaBedrock.getPlatform().getLogger().log(Level.INFO, "Creative slot " + javaSlot + " -> " + containerName + "/" + containerSlot
                + " request=" + requestId + " srcStack=" + creativeTakeSourceStackId(requestId)
                + " new=" + newItem.identifier() + ":" + newItem.data() + "x" + newItem.amount()
                + " old=" + oldItem.identifier() + ":" + oldItem.data() + "x" + oldItem.amount()
                + " oldNetId=" + oldItem.netId() + " " + info);
        inventoryTracker.getPendingCreativeSlots().put(requestId, new InventoryTracker.PendingCreativeSlot(containerName, containerSlot, oldItem, newItem.copy()));
        container.setItem(containerSlot, newItem.copy()); // optimistic update until the server responds

        // Java 1.21-1.21.1 waits for a server-side slot ack after SET_CREATIVE_MODE_SLOT;
        // 1.21.2+ clients apply the slot optimistically (mineflayer feature
        // "noAckOnCreateSetSlotPacket"). Bedrock's ITEM_STACK_RESPONSE only updates our
        // own tracker and never emits a Java CONTAINER_SET_SLOT, so without this echo the
        // old Java client sees no inventory update at all ("cannot take items"). Echo the
        // full container content, exactly as restoreCreativeSlot does when BDS rejects
        // the request; that later restore still wins if the request fails.
        PacketFactory.sendJavaContainerSetContent(user, inventoryTracker.getInventoryContainer());
    }

    private static ContainerEnumName creativeSlotContainer(final int javaSlot) {
        if (javaSlot >= 9 && javaSlot <= 44) { // hotbar and main inventory
            return ContainerEnumName.CombinedHotbarAndInventoryContainer;
        } else if (javaSlot >= 5 && javaSlot <= 8) { // armor
            return ContainerEnumName.ArmorContainer;
        } else if (javaSlot == 45) { // offhand
            return ContainerEnumName.OffhandContainer;
        }
        return null;
    }

    private static int creativeSlotIndex(final int javaSlot) {
        if (javaSlot >= 36 && javaSlot <= 44) {
            return javaSlot - 36;
        } else if (javaSlot >= 9 && javaSlot <= 35) {
            return javaSlot;
        } else if (javaSlot >= 5 && javaSlot <= 8) {
            return javaSlot - 5;
        } else if (javaSlot == 45) {
            return 0;
        }
        return -1;
    }

    private static Container creativeContainer(final InventoryTracker inventoryTracker, final ContainerEnumName containerName) {
        if (containerName == ContainerEnumName.ArmorContainer) {
            return inventoryTracker.getArmorContainer();
        } else if (containerName == ContainerEnumName.OffhandContainer) {
            return inventoryTracker.getOffhandContainer();
        } else {
            return inventoryTracker.getInventoryContainer();
        }
    }

    private static int findCreativeEntry(final InventoryTracker inventoryTracker, final BedrockItem item) {
        int fallback = -1;
        for (Int2ObjectMap.Entry<BedrockItem> entry : inventoryTracker.getCreativeItems().int2ObjectEntrySet()) {
            final BedrockItem creativeItem = entry.getValue();
            if (creativeItem.identifier() == item.identifier() && creativeItem.data() == item.data()) {
                return entry.getIntKey();
            } else if (fallback == -1 && creativeItem.identifier() == item.identifier()) {
                fallback = entry.getIntKey();
            }
        }
        return fallback;
    }

    private static void writeItemStackRequestAction(final PacketWrapper wrapper, final ItemStackRequestActionType type) {
        final int id = type.getValue();
        wrapper.write(BedrockTypes.UNSIGNED_VAR_INT, id <= 6 ? id : id - 2); // action variant (PlaceInContainer/TakeOutContainer are never sent)
        wrapper.write(Types.UNSIGNED_BYTE, (short) id); // legacy action id
    }

    private static void writeItemStackRequestSlot(final PacketWrapper wrapper, final ContainerEnumName containerName, final int slot, final int stackId) {
        wrapper.write(BedrockTypes.FULL_CONTAINER_NAME, new FullContainerName(containerName, null));
        wrapper.write(Types.UNSIGNED_BYTE, (short) slot);
        wrapper.write(BedrockTypes.INT_LE, stackId);
    }

    private static void writeStackRequestItem(final PacketWrapper wrapper, final ItemRewriter itemRewriter, final BedrockItem item, final int count) {
        writeStackRequestItem(wrapper, itemRewriter, item, count, false);
    }

    private static void writeStackRequestItem(final PacketWrapper wrapper, final ItemRewriter itemRewriter, final BedrockItem item, final int count, final boolean emptyDescriptor) {
        final String identifier = itemRewriter.bedrockIdentifier(item.identifier());
        final boolean present = !emptyDescriptor && identifier != null && !item.isEmpty();
        wrapper.write(BedrockTypes.UNSIGNED_VAR_INT, present ? 1 : 0); // item descriptor variant (1 = name)
        wrapper.write(Types.UNSIGNED_BYTE, (short) (present ? 1 : 0)); // legacy descriptor variant
        if (present) {
            wrapper.write(BedrockTypes.STRING, identifier); // item identifier
            wrapper.write(BedrockTypes.VAR_INT, item.data() & 0xFFFF); // metadata (zigzag32)
        }
        // BDS rejects count=0 as well: even an empty descriptor must carry a count in [1, 64].
        wrapper.write(BedrockTypes.SHORT_LE, (short) clampStackCount(count, item)); // count
        wrapper.write(BedrockTypes.UNSIGNED_VAR_INT, present ? item.blockRuntimeId() : 0); // block runtime id
        if (!present) {
            // gophertunnel's itemUserData(present=false) writes a single zero-length varuint32.
            wrapper.write(BedrockTypes.UNSIGNED_VAR_INT, 0); // extra data length
            return;
        }
        // ItemExtraDataWithoutBlockingTick: has_nbt (lu16) + two li32-prefixed string arrays.
        final boolean shield = identifier.equals("minecraft:shield");
        wrapper.write(BedrockTypes.UNSIGNED_VAR_INT, shield ? 18 : 10); // extra data length
        wrapper.write(BedrockTypes.SHORT_LE, (short) 0); // has nbt = false
        wrapper.write(BedrockTypes.INT_LE, 0); // can place on (empty)
        wrapper.write(BedrockTypes.INT_LE, 0); // can break (empty)
        if (shield) {
            wrapper.write(BedrockTypes.LONG_LE, item.blockingTicks()); // blocking tick
        }
    }

    /**
     * BDS 1.26.51 (protocol 2193) validates every stack count / times-crafted field of
     * ITEM_STACK_REQUEST (packet id 147) against [1, 64]. A value outside terminates the
     * connection with "Packet violation warning: PacketMalformed ... the input is outside
     * of the accepted range [1, 64]". Measured on the live host with count=65/100/127 and
     * with an empty CraftResults descriptor (which used to write count=0).
     *
     * Additionally, BDS validates an item amount against THAT ITEM'S OWN max stack size:
     * egg / snowball / ender_pearl (max 16) are rejected with
     * "status 51 = InvalidAdjustedAmount" when the request carries 32 / 64 / 65, even
     * though 64 is inside the generic [1,64] range. Measured 2026-11-12 on BDS 1.26.51
     * with jar .16: egg16 accepted, egg32 / egg64 / egg65(clamped to 64) / snowball32 /
     * pearl64 all status 51. Item-count fields therefore use the per-item overload;
     * timesCrafted (a recipe counter, not an item stack) keeps the generic [1,64] clamp.
     */
    private static int clampStackCount(final int value) {
        return Math.max(1, Math.min(value, 64));
    }

    private static int clampStackCount(final int value, final BedrockItem item) {
        return clampStackCount(value, item == null || item.isEmpty() ? Integer.MIN_VALUE : item.identifier());
    }

    private static int clampStackCount(final int value, final int bedrockId) {
        final Integer limit = BEDROCK_STACK_LIMITS.get(bedrockId);
        return Math.max(1, Math.min(value, limit == null ? 64 : limit));
    }

    /**
     * Bedrock 1.26.51 max stack sizes for items whose limit is below 64.
     * Generated from minecraft-data/minecraft-data/data/bedrock/1.26.51/items.json
     * (field "stackSize"): 2076 items = 1788 with 64, 54 with 16, 234 with 1.
     * Only the 288 non-64 entries are stored; an unknown id defaults to 64.
     * Format "bedrockId:stackSize,...". Ids may be negative (component items).
     * Clamping to a smaller limit is safe: it never produces a malformed packet,
     * at worst it under-delivers items for an id whose runtime limit differs.
     */
    private static final String BEDROCK_STACK_LIMITS_DATA =
            "-1130:16,-993:16,-627:1,-626:1,-625:1,-624:1,-623:1,-622:1,-621:1,-620:1,-619:1,-618:1,-617:1,-616:1," +
            "-615:1,-614:1,-613:1,-534:16,-522:16,-508:16,-507:16,-506:16,-505:16,-504:16,-503:16,-502:16,-501:16," +
            "-500:16,205:1,218:1,260:1,286:1,290:1,298:1,299:1,300:1,301:1,302:1,309:1,310:1,311:1,312:1,313:1," +
            "314:1,315:1,316:1,317:1,318:1,319:1,320:1,321:1,322:1,325:1,326:1,327:1,328:1,332:1,333:1,334:1," +
            "335:1,336:1,338:1,339:1,340:1,341:1,342:1,343:1,344:1,345:1,346:1,347:1,348:1,349:1,350:1,351:1," +
            "352:1,353:1,354:1,355:1,356:1,357:1,358:1,361:16,363:16,364:1,365:1,366:1,367:1,368:1,369:1,370:1," +
            "371:1,372:1,373:1,374:1,377:16,378:1,379:1,380:1,381:1,382:1,383:1,392:1,393:16,395:1,421:1,424:1," +
            "425:16,430:1,521:1,522:16,527:1,531:1,535:1,536:1,540:1,541:1,542:1,543:1,544:1,545:1,546:1,547:1," +
            "548:1,549:1,550:1,551:1,552:1,553:1,554:1,555:1,556:1,571:1,572:1,573:1,574:1,577:16,578:1,583:1," +
            "585:1,586:16,587:16,588:16,589:16,590:16,591:1,592:1,593:1,594:1,595:1,596:1,597:1,598:1,599:1,600:1," +
            "602:1,604:16,606:16,607:1,608:1,611:16,615:1,616:1,617:1,619:1,620:1,621:1,622:1,623:1,625:1,626:1," +
            "627:1,628:1,630:16,631:16,634:1,635:1,640:1,641:1,642:1,645:1,649:16,650:1,651:1,653:1,654:1,655:1," +
            "656:1,657:1,658:1,659:1,664:1,665:1,666:16,667:16,668:1,669:1,695:1,715:1,723:1,724:1,725:1,726:16," +
            "729:16,730:16,732:1,733:1,734:1,735:1,736:1,737:1,738:1,739:1,740:1,741:1,742:1,743:1,744:1,745:1," +
            "746:1,747:1,749:1,750:1,751:1,752:1,753:1,754:1,755:1,756:1,757:1,759:1,763:1,764:1,765:1,766:1," +
            "767:1,768:1,770:1,771:1,772:1,773:16,774:16,775:16,776:16,777:16,778:16,779:16,780:16,781:16,782:16," +
            "783:16,784:16,785:16,786:16,787:16,788:16,789:16,790:16,832:1,833:1,834:1,835:1,836:1,837:1,854:1," +
            "855:1,856:1,857:1,858:1,859:1,860:1,861:1,862:1,863:1,864:1,865:1,866:1,867:1,868:1,869:1,870:1," +
            "871:1,872:1,873:1,874:1,875:1,876:1,877:1";

    private static final java.util.Map<Integer, Integer> BEDROCK_STACK_LIMITS = buildStackLimits();

    private static java.util.Map<Integer, Integer> buildStackLimits() {
        final java.util.Map<Integer, Integer> limits = new java.util.HashMap<>();
        for (final String entry : BEDROCK_STACK_LIMITS_DATA.split(",")) {
            final int sep = entry.indexOf(':');
            if (sep > 0) {
                limits.put(Integer.valueOf(entry.substring(0, sep)), Integer.valueOf(entry.substring(sep + 1)));
            }
        }
        return java.util.Collections.unmodifiableMap(limits);
    }

    private static int creativeTakeSourceStackId(final int requestId) {
        final String mode = System.getProperty("mpmc.reqid.src", "request");
        if (mode.equals("zero")) {
            return 0;
        }
        if (mode.equals("neg")) {
            return -requestId;
        }
        return requestId;
    }


    private static void applyItemStackResponseSlot(final InventoryTracker inventoryTracker, final InventoryTracker.PendingCreativeSlot pending, final ContainerEnumName containerName, final int slot, final int count, final int stackId) {
        final Container container = creativeContainer(inventoryTracker, containerName);
        // BDS reports the offhand item at OffhandContainer slot 1 on the wire, but
        // ViaBedrock's tracker keeps it in the single slot 0. Normalize before
        // applying the server-assigned stack network id, otherwise the next
        // click on this slot references a stale source (status 49).
        final int targetSlot = containerName == ContainerEnumName.OffhandContainer && slot == 1 ? 0 : slot;
        if (targetSlot < 0 || targetSlot >= container.size()) {
            return;
        }
        if (count <= 0) {
            container.setItem(targetSlot, BedrockItem.empty());
            return;
        }

        BedrockItem item = container.getItem(targetSlot);
        if (item.isEmpty() && pending != null) {
            item = pending.newItem();
        }
        if (item.isEmpty()) {
            return;
        }
        final BedrockItem updated = item.copy();
        updated.setAmount(count);
        updated.setNetId(stackId);
        container.setItem(targetSlot, updated);
    }

    private static void restoreCreativeSlot(final UserConnection user, final InventoryTracker inventoryTracker, final InventoryTracker.PendingCreativeSlot pending) {
        final Container container = creativeContainer(inventoryTracker, pending.container());
        if (pending.slot() >= 0 && pending.slot() < container.size()) {
            container.setItem(pending.slot(), pending.oldItem());
        }
        PacketFactory.sendJavaContainerSetContent(user, inventoryTracker.getInventoryContainer());
    }

    private static void addTextToDialog(final UserConnection userConnection, final Dialog dialog, final String text) {
        if (dialog.getInputs().isEmpty()) {
            for (String line : BedrockTextUtils.split(text, "\n")) {
                dialog.getBody().add(new PlainMessageBody(TextUtil.stringToTextComponent(line)));
            }
        } else {
            if (userConnection.getProtocolInfo().protocolVersion().newerThanOrEqualTo(ProtocolVersion.v1_21_6)) {
                for (String line : BedrockTextUtils.split(text, "\n")) {
                    final TextInput textInput = new TextInput(TextUtil.stringToTextComponent(line));
                    textInput.setMaxLength(Integer.MAX_VALUE);
                    textInput.setMultiline(new TextInput.MultilineOptions(null, 1));
                    dialog.getInputs().add(new Input("dummy", textInput));
                }
            } else { // VB compatibility
                dialog.getInputs().add(new Input("dummy", new BooleanInput(TextUtil.stringToTextComponent(text))));
            }
        }
    }

    private InventoryPackets() {
    }

}

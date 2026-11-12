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
package net.raphimc.viabedrock.protocol;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.GameMode;
import com.viaversion.viaversion.api.minecraft.Vector3d;
import com.viaversion.viaversion.api.platform.providers.ViaProviders;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.packet.Direction;
import com.viaversion.viaversion.api.protocol.packet.PacketType;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandler;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.exception.CancelException;
import com.viaversion.viaversion.exception.InformativeException;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import com.viaversion.viaversion.protocols.base.ClientboundLoginPackets;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ServerboundPackets26_1;
import com.viaversion.viaversion.protocols.v1_21_5to1_21_6.packet.ServerboundPackets1_21_6;
import com.viaversion.viaversion.protocols.v26_2to26_3.packet.ClientboundConfigurationPackets26_3;
import com.viaversion.viaversion.protocols.v26_2to26_3.packet.ClientboundPackets26_3;
import com.viaversion.viaversion.protocols.v26_2to26_3.packet.ServerboundPackets26_3;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.protocol.StatelessTransitionProtocol;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.platform.ViaBedrockConfig;
import net.raphimc.viabedrock.protocol.data.BedrockMappingData;
import net.raphimc.viabedrock.protocol.packet.*;
import net.raphimc.viabedrock.protocol.provider.BlobCacheProvider;
import net.raphimc.viabedrock.protocol.provider.NettyPipelineProvider;
import net.raphimc.viabedrock.protocol.provider.ResourcePackProvider;
import net.raphimc.viabedrock.protocol.provider.SkinProvider;
import net.raphimc.viabedrock.protocol.rewriter.InventoryTransactionRewriter;
import net.raphimc.viabedrock.protocol.storage.*;
import net.raphimc.viabedrock.protocol.task.*;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.logging.Level;

public class BedrockProtocol extends StatelessTransitionProtocol<ClientboundBedrockPackets, ClientboundPackets26_3, ServerboundBedrockPackets, ServerboundPackets26_3> {

    public static final BedrockMappingData MAPPINGS = new BedrockMappingData();

    /** Per-packet wire audit, enabled with -Dmpmc.audit=true. Off by default in production. */
    private static final boolean AUDIT = Boolean.getBoolean("mpmc.audit");

    /** Guards the one-time ACCEPT_TELEPORTATION compatibility patch on ViaBackwards. */
    private static volatile boolean teleportAckCompatPatched;

    /** Guards the one-time INTERACT (entity right-click) compatibility patch on ViaBackwards. */
    private static volatile boolean interactCompatPatched;

    // Explicit disposition for every Bedrock clientbound packet that ViaBedrock does not translate.
    // Bedrock 1.26.51 defines 169 clientbound packet types. 92 are translated to a Java packet and
    // 21 are explicitly cancelled in UnhandledPackets; the remaining 56 are listed here with a
    // reason instead of disappearing into a silent, generic fallback. "java-counterpart-unmapped"
    // marks types where Java defines a nominal counterpart but ViaBedrock has no safe wire/registry
    // mapping: emitting a guessed packet would produce a malformed Java packet, so the packet is
    // consumed and terminated cleanly. No packet type is left without an explicit decision.
    private static final String NO_JAVA = "no-java-packet";
    private static final String JAVA_UNMAPPED = "java-counterpart-unmapped";
    private static final String DEBUG_TOOLING = "bedrock-only:debug-editor-script";
    private static final String EDUCATION = "bedrock-only:education-agent";
    private static final String CLIENT_UI = "bedrock-only:client-ui";
    private static final String TELEMETRY = "bedrock-only:telemetry";

    private static final Map<ClientboundBedrockPackets, String> CLIENTBOUND_DISPOSITIONS = buildDispositions();

    private static Map<ClientboundBedrockPackets, String> buildDispositions() {
        final Map<ClientboundBedrockPackets, String> m = new EnumMap<>(ClientboundBedrockPackets.class);
        putAll(m, JAVA_UNMAPPED,
            ClientboundBedrockPackets.CONTAINER_SET_DATA,
            ClientboundBedrockPackets.CRAFTING_DATA,
            ClientboundBedrockPackets.SPAWN_EXPERIENCE_ORB,
            ClientboundBedrockPackets.MAP_ITEM_DATA,
            ClientboundBedrockPackets.UPDATE_TRADE,
            ClientboundBedrockPackets.UPDATE_EQUIP,
            ClientboundBedrockPackets.PLAYER_START_ITEM_COOLDOWN,
            ClientboundBedrockPackets.UNLOCKED_RECIPES,
            ClientboundBedrockPackets.TRIM_DATA);
        putAll(m, NO_JAVA,
            ClientboundBedrockPackets.SERVER_PLAYER_POST_MOVE_POSITION,
            ClientboundBedrockPackets.HURT_ARMOR,
            ClientboundBedrockPackets.SIMPLE_EVENT,
            ClientboundBedrockPackets.SHOW_CREDITS,
            ClientboundBedrockPackets.SET_LAST_HURT_BY,
            ClientboundBedrockPackets.COMPLETED_USING_ITEM,
            ClientboundBedrockPackets.MOTION_PREDICTION_HINTS,
            ClientboundBedrockPackets.ANIMATE_ENTITY,
            ClientboundBedrockPackets.SYNC_ENTITY_PROPERTY,
            ClientboundBedrockPackets.ADD_VOLUME_ENTITY,
            ClientboundBedrockPackets.REMOVE_VOLUME_ENTITY,
            ClientboundBedrockPackets.SIMULATION_TYPE,
            ClientboundBedrockPackets.CHANGE_MOB_PROPERTY,
            ClientboundBedrockPackets.UPDATE_CLIENT_INPUT_LOCKS,
            ClientboundBedrockPackets.AWARD_ACHIEVEMENT,
            ClientboundBedrockPackets.MOVEMENT_EFFECT,
            ClientboundBedrockPackets.PLAYER_UPDATE_ENTITY_OVERRIDES,
            ClientboundBedrockPackets.PLAYER_LOCATION,
            ClientboundBedrockPackets.SYNC_WORLD_CLOCKS,
            ClientboundBedrockPackets.RECORD_STARTED);
        putAll(m, CLIENT_UI,
            ClientboundBedrockPackets.SHOW_STORE_OFFER,
            ClientboundBedrockPackets.SERVER_SETTINGS_RESPONSE,
            ClientboundBedrockPackets.ON_SCREEN_TEXTURE_ANIMATION,
            ClientboundBedrockPackets.MULTIPLAYER_SETTINGS,
            ClientboundBedrockPackets.PLAYER_ENCHANT_OPTIONS,
            ClientboundBedrockPackets.NPC_DIALOGUE,
            ClientboundBedrockPackets.TOAST_REQUEST,
            ClientboundBedrockPackets.CAMERA_SPLINE,
            ClientboundBedrockPackets.SET_PLAYER_INVENTORY_OPTIONS,
            ClientboundBedrockPackets.SET_PLAYER_FURNACE_OPTIONS,
            ClientboundBedrockPackets.STONECUTTER_SET_RECIPE);
        putAll(m, DEBUG_TOOLING,
            ClientboundBedrockPackets.ADD_BEHAVIOR_TREE,
            ClientboundBedrockPackets.AUTOMATION_CLIENT_CONNECT,
            ClientboundBedrockPackets.STRUCTURE_TEMPLATE_DATA_RESPONSE,
            ClientboundBedrockPackets.POSITION_TRACKING_DB_SERVER_BROADCAST,
            ClientboundBedrockPackets.DEBUG_INFO,
            ClientboundBedrockPackets.DEBUG_RENDERER,
            ClientboundBedrockPackets.TICKING_AREAS_LOAD_STATUS,
            ClientboundBedrockPackets.EDITOR_NETWORK,
            ClientboundBedrockPackets.FEATURE_REGISTRY,
            ClientboundBedrockPackets.SERVER_STATS,
            ClientboundBedrockPackets.GAME_TEST_RESULTS,
            ClientboundBedrockPackets.JIGSAW_STRUCTURE_DATA,
            ClientboundBedrockPackets.VOXEL_SHAPES);
        putAll(m, EDUCATION,
            ClientboundBedrockPackets.AGENT_ACTION_EVENT,
            ClientboundBedrockPackets.AGENT_ANIMATION);
        putAll(m, TELEMETRY,
            ClientboundBedrockPackets.LEGACY_TELEMETRY_EVENT);
        return Collections.unmodifiableMap(m);
    }

    private static void putAll(final Map<ClientboundBedrockPackets, String> map, final String reason,
                               final ClientboundBedrockPackets... packets) {
        for (final ClientboundBedrockPackets packet : packets) {
            map.put(packet, reason);
        }
    }

    private static final EnumSet<ClientboundBedrockPackets> LOGIN_STATE_WHITELIST = EnumSet.of(
        ClientboundBedrockPackets.NETWORK_SETTINGS,
        ClientboundBedrockPackets.SERVER_TO_CLIENT_HANDSHAKE,
        ClientboundBedrockPackets.PLAY_STATUS,
        ClientboundBedrockPackets.DISCONNECT,
        ClientboundBedrockPackets.PACKET_VIOLATION_WARNING,
        ClientboundBedrockPackets.NETWORK_STACK_LATENCY
    );

    private static final EnumSet<ClientboundBedrockPackets> BEFORE_PLAY_STATE_WHITELIST = EnumSet.of(
        ClientboundBedrockPackets.RESOURCE_PACKS_INFO,
        ClientboundBedrockPackets.RESOURCE_PACK_DATA_INFO,
        ClientboundBedrockPackets.RESOURCE_PACK_CHUNK_DATA,
        ClientboundBedrockPackets.RESOURCE_PACK_STACK,
        ClientboundBedrockPackets.BIOME_DEFINITION_LIST,
        ClientboundBedrockPackets.DIMENSION_DATA,
        ClientboundBedrockPackets.AVAILABLE_COMMANDS,
        ClientboundBedrockPackets.START_GAME
    );

    static {
        BEFORE_PLAY_STATE_WHITELIST.addAll(LOGIN_STATE_WHITELIST);
    }

    public BedrockProtocol() {
        super(ClientboundBedrockPackets.class, ClientboundPackets26_3.class, ServerboundBedrockPackets.class, ServerboundPackets26_3.class);
    }

    @Override
    protected void registerPackets() {
        LoginPackets.register(this);
        ConfigurationPackets.register(this);
        PlayPackets.register(this);
        MultiStatePackets.register(this);
        ResourcePackPackets.register(this);
        JoinPackets.register(this);
        ChatPackets.register(this);
        ClientPlayerPackets.register(this);
        OtherPlayerPackets.register(this);
        WorldPackets.register(this);
        LocatorBarPackets.register(this);
        EntityPackets.register(this);
        HudPackets.register(this);
        InventoryPackets.register(this);
        WorldEffectPackets.register(this);
        UnhandledPackets.register(this);

        InteractionPackets.register(this);

        // Every packet type that ViaBedrock does not translate gets an explicit, audited
        // disposition. A silent cancel would make such packets invisible; these handlers keep
        // the exact same clean termination but record one line per packet (with a reason) when
        // -Dmpmc.audit=true is set. After this block NO clientbound or serverbound packet type
        // is left unregistered, so the generic fallback can never hide a packet silently.
        for (ClientboundBedrockPackets packet : this.unmappedClientboundPacketType.getEnumConstants()) {
            if (!this.hasRegisteredClientbound(packet)) {
                final String reason = CLIENTBOUND_DISPOSITIONS.get(packet);
                if (reason == null) {
                    ViaBedrock.getPlatform().getLogger().log(Level.SEVERE,
                        "[AUDIT] UNCLASSIFIED clientbound packet " + packet.name() + " has no disposition");
                }
                classifyClientbound(packet, reason == null ? "unclassified" : reason);
            }
        }
        for (ServerboundPackets26_3 packet : this.unmappedServerboundPacketType.getEnumConstants()) {
            if (!this.hasRegisteredServerbound(packet)) {
                classifyServerbound(packet);
            }
        }
    }

    private void classifyClientbound(final ClientboundBedrockPackets packet, final String reason) {
        this.registerClientbound(packet, null, wrapper -> {
            if (AUDIT) {
                ViaBedrock.getPlatform().getLogger().info("[AUDIT] CLASSIFIED dir=CLIENTBOUND id="
                    + packet.getId() + " name=" + packet.name() + " reason=" + reason);
            }
            wrapper.cancel();
        });
    }

    private void classifyServerbound(final ServerboundPackets26_3 packet) {
        this.registerServerbound(packet, null, wrapper -> {
            if (AUDIT) {
                ViaBedrock.getPlatform().getLogger().info("[AUDIT] CLASSIFIED dir=SERVERBOUND id="
                    + packet.getId() + " name=" + packet.name() + " reason=no-bedrock-counterpart");
            }
            wrapper.cancel();
        });
    }

    @Override
    public void register(final ViaProviders providers) {
        providers.require(NettyPipelineProvider.class);
        providers.register(ResourcePackProvider.class, ViaBedrock.getConfig().getPackCacheMode().createProvider());
        providers.register(BlobCacheProvider.class, ViaBedrock.getConfig().getBlobCacheMode().createProvider());
        providers.register(SkinProvider.class, new SkinProvider());

        if (!ViaBedrock.getConfig().getBlobCacheMode().equals(ViaBedrockConfig.BlobCacheMode.DISABLED) && !providers.get(BlobCacheProvider.class).hasBlob(0L)) {
            providers.get(BlobCacheProvider.class).addBlob(0L, new byte[0]);
        }

        Via.getPlatform().runRepeatingSync(new KeepAliveTask(), 20L);
        Via.getPlatform().runRepeatingSync(new BreakingTickTask(), 1L);
        Via.getPlatform().runRepeatingSync(new ChunkTrackerTickTask(), 2L);
        Via.getPlatform().runRepeatingSync(new BlobCacheTickTask(), 2L);
        Via.getPlatform().runRepeatingSync(new EntityTrackerTickTask(), 1L);
        Via.getPlatform().runRepeatingSync(new InventoryTrackerTickTask(), 1L);

    }

    @Override
    public void init(final UserConnection user) {
        user.put(new ClientSettingsStorage("en_us", 12, 0, true, (short) 127, 1, false, true, 0));
        user.put(new GameSessionStorage(user));
        user.put(new ResourcePackDownloadTracker());
        user.put(new BlobCache(user));
        user.put(new PacketSyncStorage(user));
        user.put(new DeferredClientboundPackets(user));
        user.put(new ChannelStorage());
        user.put(new PlayerListStorage());
        user.put(new ScoreboardTracker());
        user.put(new InventoryTracker(user));
        user.put(new BreakingTracker(user));

        user.put(new InventoryTransactionRewriter(user));
        user.put(new MapTracker(user));

        installTeleportAckCompatibility(user);
    }

    /**
     * Java 1.21-1.21.1 is served through ViaBackwards Protocol26_3To26_2, whose
     * EntityPacketRewriter26_3 unconditionally calls passthrough(VAR_INT) on
     * ACCEPT_TELEPORTATION. ViaVersion can replay that ack internally with an
     * already-consumed 1-byte buffer (ridx=1, widx=1, no typed PacketValue); the
     * stock handler then throws IndexOutOfBoundsException and ViaProxy kicks the
     * client. Re-register a defensive handler on that protocol instance: read the
     * teleport id only when a typed value or a readable byte actually exists, keep
     * the id in ViaBackwards' storage, then cancel exactly as the stock handler did.
     * This touches only ViaBedrock code and ViaVersion's public registration API;
     * no ViaBackwards/ViaVersion jar is modified and no foreign source is copied.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void installTeleportAckCompatibility(final UserConnection user) {
        if (teleportAckCompatPatched) {
            return;
        }
        synchronized (BedrockProtocol.class) {
            if (teleportAckCompatPatched) {
                return;
            }
            for (final Protocol protocol : user.getProtocolInfo().getPipeline().pipes()) {
                if (!"com.viaversion.viabackwards.protocol.v26_3to26_2.Protocol26_3To26_2"
                        .equals(protocol.getClass().getName())) {
                    continue;
                }
                protocol.registerServerbound(State.PLAY, 0, 0, (PacketHandler) wrapper -> {
                    final PacketWrapperImpl impl = (PacketWrapperImpl) wrapper;
                    final boolean typed = wrapper.isReadable(Types.VAR_INT, 0);
                    final boolean buffered = impl.getInputBuffer() != null
                        && impl.getInputBuffer().isReadable();
                    final Integer id = typed || buffered
                        ? (Integer) wrapper.passthrough(Types.VAR_INT)
                        : null;
                    setCurrentTeleportId(wrapper.user(), protocol, id);
                    wrapper.cancel();
                }, true);
                teleportAckCompatPatched = true;
                ViaBedrock.getPlatform().getLogger().info(
                    "[mpmc] Installed defensive ACCEPT_TELEPORTATION handler on Protocol26_3To26_2");
                break;
            }
        }
    }

    /** ViaBackwards storage is not a compile dependency of ViaBedrock; call its public setter. */
    private static void setCurrentTeleportId(final UserConnection user, final Protocol protocol,
                                             final Integer id) {
        try {
            final Object storables = user.storables(protocol);
            storables.getClass().getMethod("setCurrentTeleportId", Integer.class).invoke(storables, id);
        } catch (final ReflectiveOperationException ignored) {
            // No compatible storage: the downstream MOVE_PLAYER_POS_ROT handler no-ops.
        }
    }

    /**
     * Java 1.21-1.21.11 sends one combined serverbound INTERACT (use_entity) packet whose
     * payload is [entityId, action, ...], with action 0 = INTERACT, 1 = ATTACK and
     * 2 = INTERACT_AT. Since 26.1 the plain INTERACT and INTERACT_AT variants were merged:
     * the 26.1/26.3 INTERACT packet is [entityId, hand, LOW_PRECISION_VECTOR, secondaryAction].
     * ViaBackwards EntityPacketRewriter26_1 converts action 2 correctly but calls
     * wrapper.cancel() for action 0, so a plain right-click on an entity never reaches
     * BedrockProtocol and Bedrock never receives an ItemUseOnEntityTransaction.
     *
     * Measured on BDS 1.26.51 with a real Java 1.21.1 client and the default-off pipeline
     * probe: the use_entity frame [0x16] reached step=9:before:Protocol26_1To1_21_11
     * (id remapped 22 -> 24 -> 25) and stopped there; exactly one packet out of 691
     * missed step 10. Re-register the handler on that protocol instance through ViaVersion's
     * public registration API and synthesise a zero click position for action 0.
     * No ViaBackwards/ViaVersion jar is modified and no foreign source is copied.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void installInteractCompatibility(final UserConnection user) {
        if (interactCompatPatched) {
            return;
        }
        synchronized (BedrockProtocol.class) {
            if (interactCompatPatched) {
                return;
            }
            for (final Protocol protocol : user.getProtocolInfo().getPipeline().pipes()) {
                if (!"com.viaversion.viabackwards.protocol.v26_1to1_21_11.Protocol26_1To1_21_11"
                        .equals(protocol.getClass().getName())) {
                    continue;
                }
                // replaceServerbound() keeps the existing id mapping and only swaps the handler;
                // registerServerbound() would reject the duplicate packet type.
                protocol.replaceServerbound(ServerboundPackets1_21_6.INTERACT, (PacketHandler) wrapper -> {
                    wrapper.passthrough(Types.VAR_INT); // entity id
                    final int action = (Integer) wrapper.read(Types.VAR_INT);
                    switch (action) {
                        case 0: // INTERACT without a position: 26.1 requires a vector.
                            wrapper.passthrough(Types.VAR_INT); // hand
                            wrapper.write(Types.LOW_PRECISION_VECTOR, new Vector3d(0D, 0D, 0D));
                            // The trailing secondary-action boolean stays in the input buffer
                            // and is serialized after the typed values, matching 26.3 INTERACT.
                            break;
                        case 1: // ATTACK, or spectate when the client is in spectator mode.
                            wrapper.setPacketType(isSpectator(wrapper.user(), protocol)
                                ? ServerboundPackets26_1.SPECTATE_ENTITY
                                : ServerboundPackets26_1.ATTACK);
                            wrapper.read(Types.BOOLEAN);
                            break;
                        case 2: // INTERACT_AT: keep the original click position.
                            final float x = (Float) wrapper.read(Types.FLOAT);
                            final float y = (Float) wrapper.read(Types.FLOAT);
                            final float z = (Float) wrapper.read(Types.FLOAT);
                            wrapper.passthrough(Types.VAR_INT); // hand
                            wrapper.write(Types.LOW_PRECISION_VECTOR, new Vector3d(x, y, z));
                            break;
                        default:
                            throw new IllegalArgumentException("Invalid interact action " + action);
                    }
                });
                interactCompatPatched = true;
                ViaBedrock.getPlatform().getLogger().info(
                    "[mpmc] Installed INTERACT compatibility handler on Protocol26_1To1_21_11");
                break;
            }
        }
    }

    /** ViaBackwards storage is not a compile dependency of ViaBedrock; call its public getter. */
    private static boolean isSpectator(final UserConnection user, final Protocol protocol) {
        try {
            final Object storables = user.storables(protocol);
            final int gameMode = (Integer) storables.getClass().getMethod("gameMode").invoke(storables);
            return gameMode == GameMode.SPECTATOR.id();
        } catch (final ReflectiveOperationException ignored) {
            return false;
        }
    }

    @Override
    public BedrockMappingData getMappingData() {
        return MAPPINGS;
    }

    @Override
    public void transform(final Direction direction, final State state, final PacketWrapper wrapper) throws InformativeException, CancelException {
        // Default-off. Only installs on a serverbound transform, where the full chain is present
        // and BedrockProtocol is the last element; re-installs automatically after cleanPipes().
        MpmcPipelineDiagnostics.install(wrapper.user(), direction);
        // Retry the compatibility patch here: ProtocolPipelineImpl.add(Collection) calls
        // init() before addAll(), so init() alone may see an incomplete pipeline. The login
        // handshake always reaches BedrockProtocol before any PLAY teleport ack.
        if (!teleportAckCompatPatched) {
            installTeleportAckCompatibility(wrapper.user());
        }
        if (!interactCompatPatched) {
            installInteractCompatibility(wrapper.user());
        }
        // Capture identity BEFORE transformation: handlers may change the packet id (Java->Bedrock
        // / Bedrock->Java) or set it to -1 on cancel. Terminal audit lines must report the original
        // wire id, otherwise a Java id would be looked up in the Bedrock registry and mislabelled.
        final int preId = wrapper.getId();
        final String preName = AUDIT ? packetName(direction, state, preId) : "";
        final int preBytes = AUDIT ? inputBytes(wrapper) : 0;
        if (AUDIT) {
            final boolean hasHandler = direction == Direction.CLIENTBOUND
                ? hasBedrockClientboundHandler(preId)
                : this.hasRegisteredServerbound(state, preId);
            audit(direction, state, wrapper, preId, preName, preBytes, hasHandler, "PRE");
        }

        if (direction == Direction.CLIENTBOUND) {
            State serverState = wrapper.user().getProtocolInfo().getServerState();
            final ClientboundBedrockPackets packet = ClientboundBedrockPackets.getPacket(wrapper.getId());
            if (packet == null) {
                final ByteBuf content = ((PacketWrapperImpl) wrapper).getInputBuffer();
                ViaBedrock.getPlatform().getLogger().warning("Received unknown packet " + wrapper.getId() + " in state " + serverState + " with content: " + ByteBufUtil.hexDump(content));
                throw CancelException.generate();
            }
            if (serverState == State.LOGIN && !LOGIN_STATE_WHITELIST.contains(packet) && BEFORE_PLAY_STATE_WHITELIST.contains(packet)) { // Bedrock client can skip the login state
                // Upstream re-injects a synthetic Bedrock PLAY_STATUS(LoginSuccess) into the
                // clientbound pipeline here. That wrapper has a non-null PacketType, so
                // PacketWrapperImpl.constructPacket() uses packetType.state() (LOGIN) instead of
                // the live connection state: the State.LOGIN handler emits a Java LOGIN_FINISHED
                // (packet id 0x02) and calls onLoginSuccess() a second time. ViaProxy has already
                // completed the Java LOGIN handshake, so this duplicate 0x02 reaches the client
                // in CONFIGURATION, where 0x02 is DISCONNECT with an NBT reason. The Java client
                // then parses the LOGIN_FINISHED payload (16-byte UUID + username) as an invalid
                // NBT tag type (e.g. 0x75 = 117), logs "Chunk size is 26 but only 2 was read",
                // and mineflayer reports a bogus kick {"type":117}. Bootstrap the Bedrock-side
                // state machine directly instead: move to CONFIGURATION and send only the Bedrock
                // CLIENT_CACHE_STATUS that the real PLAY_STATUS(LoginSuccess) would have sent.
                // Never replay a Java login packet into a later connection state.
                ViaBedrock.getPlatform().getLogger().warning("Skipping LOGIN state (Bedrock-side bootstrap, no Java LOGIN_FINISHED replay)");
                wrapper.user().getProtocolInfo().setServerState(State.CONFIGURATION);
                serverState = State.CONFIGURATION;
                JoinPackets.sendClientCacheStatus(wrapper.user());
            }
            if (serverState != State.PLAY && !BEFORE_PLAY_STATE_WHITELIST.contains(packet)) { // Bedrock client ignores most packets before receiving the START_GAME packet
                final ByteBuf content = ((PacketWrapperImpl) wrapper).getInputBuffer();
                wrapper.user().get(DeferredClientboundPackets.class).enqueue(wrapper.getId(), ByteBufUtil.getBytes(content, content.readerIndex(), content.readableBytes()));
                if (AUDIT) {
                    ViaBedrock.getPlatform().getLogger().info("[AUDIT] DEFERRED dir=CLIENTBOUND state=" + state
                        + " id=" + preId + " name=" + preName + " bytes=" + preBytes);
                }
                throw CancelException.generate();
            }
        }

        String postPhase = "POST";
        try {
            super.transform(direction, state, wrapper);
        } catch (final CancelException e) {
            postPhase = "CANCEL";
            throw e;
        } catch (final InformativeException e) {
            postPhase = "ERROR";
            throw e;
        } catch (final RuntimeException e) {
            postPhase = "ERROR";
            throw e;
        } finally {
            if (AUDIT) {
                audit(direction, state, wrapper, preId, preName, preBytes, true, postPhase);
            }
        }
    }

    /**
     * One line per packet per phase. PRE carries the incoming wire id/name and whether an explicit
     * handler is registered. POST carries the outgoing id (mappedJavaId for clientbound,
     * toHost=true + Bedrock name for serverbound). CANCEL/ERROR mark deliberate termination or
     * failure and are never reported as "sent to host". CLASSIFIED lines come from the explicit
     * disposition handlers. Wrapped in try/catch so auditing can never change packet processing.
     */
    private static void audit(final Direction direction, final State state, final PacketWrapper wrapper,
                              final int preId, final String preName, final int preBytes,
                              final boolean hasHandler, final String phase) {
        if (!AUDIT) {
            return;
        }
        try {
            final int outId = wrapper.getId();
            final String detail;
            if ("PRE".equals(phase)) {
                detail = (direction == Direction.CLIENTBOUND ? "hasHandler=" : "hasHandler=")
                    + hasHandler + (direction == Direction.SERVERBOUND ? " fromJavaClient=true" : "");
            } else if ("CANCEL".equals(phase)) {
                detail = "cancelled=true outId=" + outId;
            } else if ("ERROR".equals(phase)) {
                detail = "error=true outId=" + outId;
            } else if (direction == Direction.CLIENTBOUND) {
                detail = "mappedJavaId=" + outId + " javaName=" + javaClientboundName(state, outId);
            } else {
                final ServerboundBedrockPackets sb = ServerboundBedrockPackets.getPacket(outId);
                detail = sb == null
                    ? "unmapped=true outId=" + outId
                    : "toHost=true outId=" + outId + " outName=" + sb.name();
            }
            ViaBedrock.getPlatform().getLogger().info("[AUDIT] " + phase + " dir=" + direction
                + " state=" + state + " id=" + preId + " name=" + preName
                + " bytes=" + preBytes + " " + detail);
        } catch (final Throwable ignored) {
            // Audit must never influence packet processing.
        }
    }

    private boolean hasBedrockClientboundHandler(final int id) {
        final ClientboundBedrockPackets cb = ClientboundBedrockPackets.getPacket(id);
        return cb != null && this.hasRegisteredClientbound(cb);
    }

    private static String packetName(final Direction direction, final State state, final int id) {
        return direction == Direction.CLIENTBOUND
            ? nameOfBedrockClientbound(id)
            : javaServerboundName(state, id);
    }

    private static int inputBytes(final PacketWrapper wrapper) {
        try {
            final ByteBuf buf = ((PacketWrapperImpl) wrapper).getInputBuffer();
            return buf == null ? -1 : buf.readableBytes();
        } catch (final Throwable ignored) {
            return -1;
        }
    }

    private static String nameOfBedrockClientbound(final int id) {
        final ClientboundBedrockPackets cb = ClientboundBedrockPackets.getPacket(id);
        return cb == null ? "UNKNOWN_BEDROCK_ID_" + id : cb.name();
    }

    private static String javaClientboundName(final State state, final int id) {
        try {
            if (state == State.LOGIN) {
                for (final ClientboundLoginPackets packet : ClientboundLoginPackets.values()) {
                    if (packet.getId() == id) {
                        return packet.name();
                    }
                }
            } else if (state == State.CONFIGURATION) {
                for (final ClientboundConfigurationPackets26_3 packet : ClientboundConfigurationPackets26_3.values()) {
                    if (packet.getId() == id) {
                        return packet.name();
                    }
                }
            } else if (state == State.PLAY) {
                for (final ClientboundPackets26_3 packet : ClientboundPackets26_3.values()) {
                    if (packet.getId() == id) {
                        return packet.name();
                    }
                }
            }
        } catch (final Throwable ignored) {
        }
        return "JAVA_" + state.name() + "_ID_" + id;
    }

    private static String javaServerboundName(final State state, final int id) {
        if (state == State.PLAY) {
            try {
                for (final ServerboundPackets26_3 packet : ServerboundPackets26_3.values()) {
                    if (packet.getId() == id) {
                        return packet.name();
                    }
                }
            } catch (final Throwable ignored) {
            }
        }
        // Java uses separate packet enums for LOGIN/CONFIGURATION/HANDSHAKE. Do not guess a PLAY
        // name from a foreign id: report state + raw id so the record stays unambiguous.
        return "JAVA_" + state.name() + "_ID_" + id;
    }

    public static void kickForIllegalState(final UserConnection user, final String reason) {
        kickForIllegalState(user, reason, null);
    }

    public static void kickForIllegalState(final UserConnection user, final String reason, final Throwable e) {
        ViaBedrock.getPlatform().getLogger().log(Level.SEVERE, "Illegal state: " + reason, e);

        final PacketType disconnectPacketType = switch (user.getProtocolInfo().getServerState()) {
            case LOGIN -> ClientboundLoginPackets.LOGIN_DISCONNECT;
            case CONFIGURATION -> ClientboundConfigurationPackets26_3.DISCONNECT;
            case PLAY -> ClientboundPackets26_3.DISCONNECT;
            default -> throw new IllegalStateException("Unexpected state: " + user.getProtocolInfo().getServerState());
        };
        try {
            final PacketWrapper disconnect = PacketWrapper.create(disconnectPacketType, user);
            PacketFactory.writeJavaDisconnect(disconnect, "§4ViaBedrock encountered an error:\n§c" + reason + "\n\n§rPlease report this issue on the ViaBedrock GitHub page.");
            disconnect.send(BedrockProtocol.class);
        } catch (final Throwable ignored) {
        }

        if (user.getChannel() != null) {
            user.getChannel().flush();
            user.getChannel().close();
        }
    }

}

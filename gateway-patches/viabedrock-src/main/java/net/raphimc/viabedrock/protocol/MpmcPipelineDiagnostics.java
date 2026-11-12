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
import com.viaversion.viaversion.api.debug.DebugHandler;
import com.viaversion.viaversion.api.protocol.AbstractProtocol;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.packet.Direction;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.exception.CancelException;
import com.viaversion.viaversion.exception.InformativeException;
import com.viaversion.viaversion.protocols.v26_2to26_3.packet.ClientboundPackets26_3;
import com.viaversion.viaversion.protocols.v26_2to26_3.packet.ServerboundPackets26_3;
import net.raphimc.viabedrock.ViaBedrock;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Default-off diagnostic, enabled with {@code -Dmpmc.debug.pipeline=true}.
 *
 * <p>{@code PacketWrapperImpl.apply()} walks the serverbound protocol list and calls
 * {@code Protocol.transform()} on each element. A handler that calls {@code wrapper.cancel()}
 * makes {@code AbstractProtocol.transform()} throw {@link CancelException}, aborting the whole
 * loop: the packet never reaches {@link BedrockProtocol} and no error is logged. That is the
 * failure mode being investigated for 1.21.1 {@code use_entity}: the raw TCP frame leaves the
 * client, but the BedrockProtocol audit never sees it.</p>
 *
 * <p>This diagnostic inserts a pure no-op {@code Protocol} before every real protocol in the
 * serverbound list and logs the wrapper id at each step. The last probe reached for a packet
 * identifies the protocol immediately after it as the layer that cancelled/consumed the wrapper.
 * It also enables ViaVersion's own pre/post transform logger.</p>
 *
 * <p>Safety notes derived from ViaVersion 5.12.1 bytecode: {@code apply()} iterates by index
 * with the size cached before the loop, and BedrockProtocol is the last serverbound protocol,
 * so replacing the list contents inside BedrockProtocol.transform() cannot cause a
 * ConcurrentModificationException or double-transform. {@code reversedProtocolList} is a
 * separate ArrayList copy and is deliberately left untouched, so clientbound traffic keeps the
 * original chain. Probes never call super.transform() and never modify the wrapper.</p>
 */
public final class MpmcPipelineDiagnostics {

    private static final boolean ENABLED = Boolean.getBoolean("mpmc.debug.pipeline");
    private static volatile boolean debugHandlerEnabled;

    private MpmcPipelineDiagnostics() {
    }

    public static void install(final UserConnection user, final Direction direction) {
        if (!ENABLED || direction != Direction.SERVERBOUND) {
            return;
        }
        if (!debugHandlerEnabled) {
            synchronized (MpmcPipelineDiagnostics.class) {
                if (!debugHandlerEnabled) {
                    try {
                        final DebugHandler debug = Via.getManager().debugHandler();
                        debug.setEnabled(true);
                        debug.setLogPrePacketTransform(true);
                        debug.setLogPostPacketTransform(true);
                        ViaBedrock.getPlatform().getLogger()
                                .warning("[PIPEDIAG] ViaVersion transform logger enabled");
                    } catch (final Throwable t) {
                        warn("DebugHandler", t);
                    }
                    debugHandlerEnabled = true;
                }
            }
        }
        try {
            final Object pipeline = user.getProtocolInfo().getPipeline();
            final Field field = pipeline.getClass().getDeclaredField("protocolList");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            final List<Protocol> list = (List<Protocol>) field.get(pipeline);
            boolean hasProbe = false;
            boolean hasBedrock = false;
            for (final Protocol protocol : list) {
                if (protocol instanceof PipelineProbe) {
                    hasProbe = true;
                }
                if (protocol instanceof BedrockProtocol) {
                    hasBedrock = true;
                }
            }
            // Only install once the full chain is present, and re-install if cleanPipes() wiped it.
            if (hasProbe || !hasBedrock) {
                return;
            }
            final StringBuilder chain = new StringBuilder();
            final List<Protocol> withProbes = new ArrayList<>(list.size() * 2 + 1);
            for (int i = 0; i < list.size(); i++) {
                final Protocol protocol = list.get(i);
                chain.append('[').append(i).append(']')
                        .append(protocol.getClass().getSimpleName()).append(' ');
                withProbes.add(new PipelineProbe(i + ":before:" + protocol.getClass().getSimpleName()));
                withProbes.add(protocol);
            }
            withProbes.add(new PipelineProbe(list.size() + ":end"));
            ViaBedrock.getPlatform().getLogger()
                    .warning("[PIPEDIAG] serverbound chain=" + chain);
            list.clear();
            list.addAll(withProbes);
            ViaBedrock.getPlatform().getLogger()
                    .warning("[PIPEDIAG] probes installed=" + withProbes.size()
                            + " user=" + System.identityHashCode(user));
        } catch (final Throwable t) {
            warn("probe install", t);
        }
    }

    private static void warn(final String stage, final Throwable t) {
        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "[PIPEDIAG] " + stage + " failed: " + t, t);
    }

    /** No-op observation point: never calls super.transform(), so it cannot alter a packet. */
    public static final class PipelineProbe
            extends AbstractProtocol<ClientboundPackets26_3, ClientboundPackets26_3,
            ServerboundPackets26_3, ServerboundPackets26_3> {

        private final String label;

        public PipelineProbe(final String label) {
            super(ClientboundPackets26_3.class, ClientboundPackets26_3.class,
                    ServerboundPackets26_3.class, ServerboundPackets26_3.class);
            this.label = label;
        }

        @Override
        public void transform(final Direction direction, final State state, final PacketWrapper wrapper)
                throws InformativeException, CancelException {
            if (direction == Direction.SERVERBOUND) {
                ViaBedrock.getPlatform().getLogger().info("[PIPEDIAG] step=" + label
                        + " id=" + wrapper.getId() + " state=" + state
                        + " cancelled=" + wrapper.isCancelled());
            }
        }
    }
}

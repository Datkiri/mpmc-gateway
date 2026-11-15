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
import io.netty.util.AttributeKey;
import net.raphimc.viabedrock.ViaBedrock;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Default-off diagnostic, enabled with {@code -Dmpmc.debug.pipeline=true}.
 *
 * <p>Independent {@code -Dmpmc.debug.timing=true} aggregates event-loop queue delay,
 * task runtime and auth-input serialization intervals. Reports are emitted on auth-input
 * activity at most once every five seconds; serialization timing is not wire-send timing.</p>
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
    private static final boolean TIMING_ENABLED = Boolean.getBoolean("mpmc.debug.timing");
    private static final AttributeKey<TimingWindow> TIMING = AttributeKey.valueOf("viabedrock-timing-window");

    public static long timingNow() {
        return TIMING_ENABLED ? System.nanoTime() : 0L;
    }

    public static void recordTask(final UserConnection user, final boolean chunk, final long queuedAt, final long startedAt) {
        if (TIMING_ENABLED) {
            timingWindow(user).task(chunk, startedAt - queuedAt, System.nanoTime() - startedAt);
        }
    }

    public static void recordAuthInput(final UserConnection user) {
        if (!TIMING_ENABLED) {
            return;
        }
        final long now = System.nanoTime();
        final TimingWindow window = timingWindow(user);
        window.authInput(now);
        if (window.ready(now)) {
            ViaBedrock.getPlatform().getLogger().info(window.report(now));
            window.reset(now);
        }
    }

    private static TimingWindow timingWindow(final UserConnection user) {
        TimingWindow window = user.getChannel().attr(TIMING).get();
        if (window == null) {
            window = new TimingWindow(System.nanoTime());
            user.getChannel().attr(TIMING).set(window);
        }
        return window;
    }

    // Accessed only on the connection event loop; reports contain no packet or identity data.
    static final class TimingWindow {
        private long windowStart;
        private long previousAuth;
        private boolean hasPreviousAuth;
        private long authCount;
        private long intervalCount;
        private long intervalTotal;
        private long intervalMax;
        private long authGaps;
        private final long[] taskCount = new long[2];
        private final long[] queueMax = new long[2];
        private final long[] runtimeMax = new long[2];

        TimingWindow(final long now) {
            this.windowStart = now;
        }

        void task(final boolean chunk, final long queueNanos, final long runtimeNanos) {
            final int index = chunk ? 0 : 1;
            this.taskCount[index]++;
            this.queueMax[index] = Math.max(this.queueMax[index], queueNanos);
            this.runtimeMax[index] = Math.max(this.runtimeMax[index], runtimeNanos);
        }

        void authInput(final long now) {
            this.authCount++;
            if (this.hasPreviousAuth) {
                final long interval = now - this.previousAuth;
                this.intervalCount++;
                this.intervalTotal += interval;
                this.intervalMax = Math.max(this.intervalMax, interval);
                if (interval > 100_000_000L) {
                    this.authGaps++;
                }
            }
            this.previousAuth = now;
            this.hasPreviousAuth = true;
        }

        boolean ready(final long now) {
            return now - this.windowStart >= 5_000_000_000L;
        }

        String report(final long now) {
            return "[TIMING] windowMs=" + (now - this.windowStart) / 1_000_000L
                    + " authSerialized=" + this.authCount
                    + " authIntervalAvgUs=" + (this.intervalCount == 0 ? 0 : this.intervalTotal / this.intervalCount / 1_000L)
                    + " authIntervalMaxUs=" + this.intervalMax / 1_000L
                    + " authGapsOver100ms=" + this.authGaps
                    + " chunkTicks=" + this.taskCount[0]
                    + " chunkQueueMaxUs=" + this.queueMax[0] / 1_000L
                    + " chunkRuntimeMaxUs=" + this.runtimeMax[0] / 1_000L
                    + " entityTicks=" + this.taskCount[1]
                    + " entityQueueMaxUs=" + this.queueMax[1] / 1_000L
                    + " entityRuntimeMaxUs=" + this.runtimeMax[1] / 1_000L;
        }

        void reset(final long now) {
            this.windowStart = now;
            this.authCount = 0;
            this.intervalCount = 0;
            this.intervalTotal = 0;
            this.intervalMax = 0;
            this.authGaps = 0;
            for (int i = 0; i < this.taskCount.length; i++) {
                this.taskCount[i] = 0;
                this.queueMax[i] = 0;
                this.runtimeMax[i] = 0;
            }
        }
    }

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

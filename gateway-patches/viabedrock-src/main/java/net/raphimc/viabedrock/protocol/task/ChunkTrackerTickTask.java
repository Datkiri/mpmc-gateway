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
package net.raphimc.viabedrock.protocol.task;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.MpmcPipelineDiagnostics;
import net.raphimc.viabedrock.protocol.storage.ChunkTracker;

import java.util.concurrent.atomic.AtomicBoolean;

public class ChunkTrackerTickTask implements Runnable {

    private static final AttributeKey<AtomicBoolean> TICK_PENDING = AttributeKey.valueOf("viabedrock-chunk-tick-pending");

    @Override
    public void run() {
        for (UserConnection info : Via.getManager().getConnectionManager().getConnections()) {
            if (info.has(ChunkTracker.class)) {
                final long queuedAt = MpmcPipelineDiagnostics.timingNow();
                enqueueTick(info.getChannel(), () -> {
                    final long startedAt = MpmcPipelineDiagnostics.timingNow();
                    try {
                        final ChunkTracker chunkTracker = info.get(ChunkTracker.class);
                        if (chunkTracker != null) {
                            chunkTracker.tick();
                        }
                    } catch (final Throwable e) {
                        BedrockProtocol.kickForIllegalState(info, "Error ticking chunk tracker. See console for details.", e);
                    } finally {
                        MpmcPipelineDiagnostics.recordTask(info, true, queuedAt, startedAt);
                    }
                });
            }
        }
    }

    static boolean enqueueTick(final Channel channel, final Runnable tick) {
        AtomicBoolean pending = channel.attr(TICK_PENDING).get();
        if (pending == null) {
            final AtomicBoolean created = new AtomicBoolean();
            final AtomicBoolean existing = channel.attr(TICK_PENDING).setIfAbsent(created);
            pending = existing != null ? existing : created;
        }
        if (!pending.compareAndSet(false, true)) {
            return false;
        }
        final AtomicBoolean guard = pending;
        try {
            channel.eventLoop().submit(() -> {
                try {
                    if (channel.isActive()) {
                        tick.run();
                    }
                } finally {
                    guard.set(false);
                }
            });
        } catch (final RuntimeException | Error e) {
            guard.set(false);
            throw e;
        }
        return true;
    }

}

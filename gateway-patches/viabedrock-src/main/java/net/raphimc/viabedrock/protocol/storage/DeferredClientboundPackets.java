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
package net.raphimc.viabedrock.protocol.storage;

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import io.netty.buffer.Unpooled;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.protocol.BedrockProtocol;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Holds Bedrock packets which arrived before the Java client reached the PLAY state, so they can be replayed once it did.
 * <p>
 * The Bedrock server starts streaming world data right after START_GAME, while the Java client still finishes its
 * configuration phase. Dropping those packets (the previous behaviour) results in missing player list entries, world
 * events, clock syncs and similar state that is never re-sent.
 */
public class DeferredClientboundPackets extends StoredObject {

    private static final int MAX_PACKETS = 32_768;
    private static final long MAX_BYTES = 64L * 1024L * 1024L;

    private final List<DeferredPacket> packets = new ArrayList<>();
    private long queuedBytes;
    private boolean overflowReported;

    public DeferredClientboundPackets(final UserConnection user) {
        super(user);
    }

    public void enqueue(final int packetId, final byte[] data) {
        if (this.packets.size() >= MAX_PACKETS || this.queuedBytes + data.length > MAX_BYTES) {
            if (!this.overflowReported) {
                this.overflowReported = true;
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Deferred packet queue is full, dropping packet " + packetId + " and any further pre-PLAY packets");
            }
            return;
        }
        this.packets.add(new DeferredPacket(packetId, data));
        this.queuedBytes += data.length;
    }

    public boolean isEmpty() {
        return this.packets.isEmpty();
    }

    public void flush() {
        if (this.packets.isEmpty()) {
            return;
        }
        final List<DeferredPacket> queued = new ArrayList<>(this.packets);
        this.packets.clear();
        this.queuedBytes = 0L;
        if (ViaBedrock.getPlatform().getLogger().isLoggable(Level.FINE)) {
            ViaBedrock.getPlatform().getLogger().log(Level.FINE, "Replaying " + queued.size() + " packet(s) received before PLAY state");
        }
        for (final DeferredPacket packet : queued) {
            try {
                final PacketWrapper wrapper = PacketWrapper.create(packet.id(), Unpooled.wrappedBuffer(packet.data()), this.user());
                // The payload is still a *Bedrock* packet, so BedrockProtocol must transform it.
                // send(protocol) defaults to skipCurrentPipeline=true, which would hand the raw
                // Bedrock id straight to the Java protocols (e.g. id 124 LEVEL_EVENT_GENERIC is
                // read as SYSTEM_CHAT 0x7C) and produce a malformed packet.
                wrapper.send(BedrockProtocol.class, false);
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to replay deferred packet " + packet.id(), e);
            }
        }
    }

    private record DeferredPacket(int id, byte[] data) {
    }

}

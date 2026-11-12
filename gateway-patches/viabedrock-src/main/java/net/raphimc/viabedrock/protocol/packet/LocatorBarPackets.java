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

import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v26_2to26_3.packet.ClientboundPackets26_3;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.UUID;

public final class LocatorBarPackets {

    public static void register(final BedrockProtocol protocol) {
        protocol.registerClientbound(ClientboundBedrockPackets.LOCATOR_BAR, null, wrapper -> {
            // Locator bar waypoints were introduced in Bedrock 1.21.90 (protocol 800) and are sent as
            // full waypoint snapshots. Java has an equivalent packet since 1.21.6 (tracked waypoint).
            final int waypointCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
            final boolean javaSupportsWaypoints = wrapper.user().getProtocolInfo().protocolVersion().newerThanOrEqualTo(ProtocolVersion.v1_21_6);

            for (int i = 0; i < waypointCount; i++) {
                final UUID groupHandle = wrapper.read(BedrockTypes.UUID);
                wrapper.read(BedrockTypes.UNSIGNED_INT_LE); // update flags
                if (wrapper.read(Types.BOOLEAN)) { // has visible
                    wrapper.read(Types.BOOLEAN); // visible
                }
                Position3f position = null;
                if (wrapper.read(Types.BOOLEAN)) { // has world position
                    position = wrapper.read(BedrockTypes.POSITION_3F); // position
                    wrapper.read(BedrockTypes.VAR_INT); // dimension id
                }
                String texturePath = null;
                if (wrapper.read(Types.BOOLEAN)) { // has texture path
                    texturePath = wrapper.read(BedrockTypes.STRING); // texture path
                }
                if (wrapper.read(Types.BOOLEAN)) { // has icon size
                    wrapper.read(BedrockTypes.POSITION_2F); // icon size
                }
                int color = 0;
                boolean hasColor = wrapper.read(Types.BOOLEAN); // has color
                if (hasColor) {
                    color = wrapper.read(BedrockTypes.INT_LE); // color (ARGB, big-endian since 1.26.40)
                }
                if (wrapper.read(Types.BOOLEAN)) { // has client position authority
                    wrapper.read(Types.BOOLEAN); // client position authority
                }
                if (wrapper.read(Types.BOOLEAN)) { // has actor unique id
                    wrapper.read(BedrockTypes.VAR_LONG); // actor unique id
                }
                final int action = wrapper.read(Types.UNSIGNED_BYTE); // action

                if (!javaSupportsWaypoints) {
                    continue;
                }
                final int operation = switch (action) {
                    case 1 -> 0; // add -> track
                    case 2 -> 1; // remove -> untrack
                    case 3 -> 2; // update -> update
                    default -> -1; // none
                };
                if (operation == -1) {
                    continue;
                }

                final PacketWrapper trackedWaypoint = PacketWrapper.create(ClientboundPackets26_3.TRACKED_WAYPOINT, wrapper.user());
                trackedWaypoint.write(Types.VAR_INT, operation); // operation
                trackedWaypoint.write(Types.BOOLEAN, true); // has uuid
                trackedWaypoint.write(Types.UUID, groupHandle); // uuid
                trackedWaypoint.write(Types.STRING, waypointStyle(texturePath)); // icon style
                if (hasColor) {
                    trackedWaypoint.write(Types.BOOLEAN, true); // has color
                    trackedWaypoint.write(Types.UNSIGNED_BYTE, (short) ((color >> 16) & 0xFF)); // red
                    trackedWaypoint.write(Types.UNSIGNED_BYTE, (short) ((color >> 8) & 0xFF)); // green
                    trackedWaypoint.write(Types.UNSIGNED_BYTE, (short) (color & 0xFF)); // blue
                } else {
                    trackedWaypoint.write(Types.BOOLEAN, false); // no color
                }
                if (operation == 1 || position == null) {
                    trackedWaypoint.write(Types.VAR_INT, 0); // type: empty
                } else {
                    trackedWaypoint.write(Types.VAR_INT, 1); // type: vec3i
                    trackedWaypoint.write(Types.VAR_INT, (int) Math.floor(position.x())); // x
                    trackedWaypoint.write(Types.VAR_INT, (int) Math.floor(position.y())); // y
                    trackedWaypoint.write(Types.VAR_INT, (int) Math.floor(position.z())); // z
                }
                trackedWaypoint.send(BedrockProtocol.class);
            }
        });
    }

    private static String waypointStyle(final String texturePath) {
        // Vanilla Java only ships "minecraft:default"; custom textures have no Java equivalent.
        return "minecraft:default";
    }

    private LocatorBarPackets() {
    }

}

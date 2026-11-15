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

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v26_2to26_3.packet.ClientboundPackets26_3;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InteractPacketPayload_Action;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PlayerActionType;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

public final class PlayerActionPacketFactory {

    public static void sendBedrockPlayerAction(final UserConnection user, final long entityId, final PlayerActionType actionType, final BlockPosition position, final BlockPosition resultPosition, final int face) {
        final PacketWrapper startItemUseOn = PacketWrapper.create(ServerboundBedrockPackets.PLAYER_ACTION, user);
        startItemUseOn.write(BedrockTypes.UNSIGNED_VAR_LONG, entityId); // entity runtime id
        startItemUseOn.write(BedrockTypes.VAR_INT, actionType.getValue()); // action type
        startItemUseOn.write(BedrockTypes.BLOCK_POSITION, position); // block position
        startItemUseOn.write(BedrockTypes.BLOCK_POSITION, resultPosition); // result position
        startItemUseOn.write(BedrockTypes.VAR_INT, face); // face
        startItemUseOn.sendToServer(BedrockProtocol.class);
    }

    public static void sendBedrockDismount(final UserConnection user, final long entityRuntimeId) {
        final PacketWrapper dismountPacket = PacketWrapper.create(ServerboundBedrockPackets.INTERACT, user);
        dismountPacket.write(Types.UNSIGNED_BYTE, (short) InteractPacketPayload_Action.StopRiding.getValue()); // action
        dismountPacket.write(BedrockTypes.UNSIGNED_VAR_LONG, entityRuntimeId); // target entity runtime id
        dismountPacket.write(BedrockTypes.OPTIONAL_POSITION_3F, null); // position
        dismountPacket.sendToServer(BedrockProtocol.class);
    }

    public static void sendJavaSetPassengers(final UserConnection user, final Entity vehicle) {
        final PacketWrapper setPassengersPacket = PacketWrapper.create(ClientboundPackets26_3.SET_PASSENGERS, user);
        writeJavaSetPassengers(setPassengersPacket, vehicle);
        setPassengersPacket.send(BedrockProtocol.class);
    }

    public static void writeJavaSetPassengers(final PacketWrapper wrapper, final Entity vehicle) {
        final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
        final int[] passengerIds = new int[vehicle.passengers().size()];
        for (int i = 0; i < passengerIds.length; i++) {
            passengerIds[i] = entityTracker.getEntityByUid(vehicle.passengers().get(i)).javaId();
        }
        wrapper.write(Types.VAR_INT, vehicle.javaId()); // vehicle
        wrapper.write(Types.VAR_INT_ARRAY_PRIMITIVE, passengerIds); // passenger ids
    }

    private PlayerActionPacketFactory() {
    }

}

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
package net.raphimc.viabedrock.test;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandler;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import com.viaversion.viaversion.protocols.v26_2to26_3.packet.ClientboundPackets26_3;
import com.viaversion.viaversion.api.minecraft.entities.EntityTypes26_3;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.PlayerActionPacketFactory;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorLinkType;
import net.raphimc.viabedrock.protocol.model.EntityLink;
import net.raphimc.viabedrock.protocol.model.PlayerAbilities;
import net.raphimc.viabedrock.protocol.packet.InteractionPackets;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.ActorDataIds;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.ActorFlags;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.types.entitydata.EntityDataTypesBedrock;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

public final class EntityRegressionTest {

    public static void main(final String[] args) throws Exception {
        testUnsignedActorFlags();
        testPartialPositionUpdates();
        testPassengerPackets();
        System.out.println("Entity regression tests passed: unsigned flags, all partial-position masks, and passenger packet handlers");
    }

    private static void testPassengerPackets() throws Exception {
        final EntityTracker[] tracker = new EntityTracker[1];
        final UserConnection user = (UserConnection) Proxy.newProxyInstance(UserConnection.class.getClassLoader(),
            new Class<?>[]{UserConnection.class}, (proxy, method, arguments) -> {
                if (method.getName().equals("get")) {
                    return arguments[0] == EntityTracker.class ? tracker[0] : null;
                }
                throw new UnsupportedOperationException(method.getName());
            });
        tracker[0] = new EntityTracker(user);
        final ClientPlayerEntity player = new ClientPlayerEntity(user, 300L, new UUID(0L, 3L), new PlayerAbilities(100L, (byte) 0, (byte) 0));
        tracker[0].addEntity(player, false);
        final Entity vehicle = tracker[0].addEntity(new Entity(user, 7L, 10L, "minecraft:pig", 9, new UUID(0L, 1L), EntityTypes26_3.PIG));
        final Entity passenger = tracker[0].addEntity(new Entity(user, 8L, 11L, "minecraft:pig", 10, new UUID(0L, 2L), EntityTypes26_3.PIG));
        final CapturingProtocol protocol = new CapturingProtocol();
        InteractionPackets.register(protocol);
        require(protocol.linkHandler != null, "SET_ENTITY_LINK handler was not registered");
        invokeLink(protocol.linkHandler, user, vehicle, player, ActorLinkType.Riding, new int[]{1});
        require(player.mountEntityRuntimeId() == vehicle.runtimeId(), "Mount state was not updated");
        invokeLink(protocol.linkHandler, user, vehicle, passenger, ActorLinkType.Passenger, new int[]{1, 10});
        final PacketWrapperImpl helperPacket = new PacketWrapperImpl(ClientboundPackets26_3.SET_PASSENGERS, null, user);
        PlayerActionPacketFactory.writeJavaSetPassengers(helperPacket, vehicle);
        assertPassengerFields(helperPacket, 9, new int[]{1, 10});
        invokeLink(protocol.linkHandler, user, vehicle, player, ActorLinkType.None, new int[]{10});
        require(player.mountEntityRuntimeId() == -1L, "Dismount state was not cleared");
        invokeLink(protocol.linkHandler, user, vehicle, passenger, ActorLinkType.None, new int[0]);
    }

    private static void invokeLink(final PacketHandler handler, final UserConnection user, final Entity vehicle,
                                   final Entity passenger, final ActorLinkType type, final int[] expected) throws Exception {
        final PacketWrapperImpl packet = new PacketWrapperImpl(ClientboundPackets26_3.SET_PASSENGERS, null, user);
        packet.write(BedrockTypes.ENTITY_LINK, new EntityLink(vehicle.uniqueId(), passenger.uniqueId(), type, false, false, 0F));
        packet.resetReader();
        handler.handle(packet);
        require(!packet.isCancelled(), "Known entity link must not be cancelled");
        assertPassengerFields(packet, vehicle.javaId(), expected);
    }

    private static void assertPassengerFields(final PacketWrapperImpl packet, final int vehicleId, final int[] expected) throws Exception {
        packet.resetReader();
        require(packet.read(Types.VAR_INT) == vehicleId, "Wrong vehicle id");
        // Read the same typed array as the downstream ViaBackwards passenger handler.
        require(Arrays.equals(packet.read(Types.VAR_INT_ARRAY_PRIMITIVE), expected), "Wrong passenger array");
        require(!packet.isReadable(Types.VAR_INT, 0), "Unexpected separate passenger count or id");
    }

    private static final class CapturingProtocol extends BedrockProtocol {
        private PacketHandler linkHandler;

        @Override
        public void registerClientbound(final ClientboundBedrockPackets packet, final ClientboundPackets26_3 mapped,
                                        final PacketHandler handler, final boolean override) {
            if (packet == ClientboundBedrockPackets.SET_ENTITY_LINK) {
                this.linkHandler = handler;
            }
        }
    }

    private static void testUnsignedActorFlags() {
        final Entity entity = newEntity();
        final long[] words = {0L, 1L, 2L, Long.MAX_VALUE, Long.MIN_VALUE, -1L};
        for (long low : words) {
            for (long high : words) {
                entity.entityData().put(ActorDataIds.RESERVED_0, new EntityData(ActorDataIds.RESERVED_0.getValue(), EntityDataTypesBedrock.LONG, low));
                entity.entityData().put(ActorDataIds.RESERVED_092, new EntityData(ActorDataIds.RESERVED_092.getValue(), EntityDataTypesBedrock.LONG, high));
                final Set<ActorFlags> expected = EnumSet.noneOf(ActorFlags.class);
                for (ActorFlags flag : ActorFlags.values()) {
                    final int bit = flag.getValue();
                    if (bit >= 0 && bit < 64 && (low & (1L << bit)) != 0
                        || bit >= 64 && bit < 128 && (high & (1L << (bit - 64))) != 0) {
                        expected.add(flag);
                    }
                }
                require(entity.entityFlags().equals(expected), "Actor flags differ for low=" + low + ", high=" + high);
            }
        }
        entity.entityData().remove(ActorDataIds.RESERVED_092);
        entity.entityData().put(ActorDataIds.RESERVED_0, new EntityData(ActorDataIds.RESERVED_0.getValue(), EntityDataTypesBedrock.LONG, Long.MIN_VALUE));
        require(entity.entityFlags().equals(EnumSet.of(ActorFlags.EATING)), "Missing upper word must not sign-extend EATING");
        entity.entityData().clear();
        require(entity.entityFlags().isEmpty(), "Missing metadata must produce no actor flags");
    }

    private static void testPartialPositionUpdates() {
        final Entity entity = newEntity();
        for (int mask = 0; mask < 8; mask++) {
            final boolean hasX = (mask & 1) != 0;
            final boolean hasY = (mask & 2) != 0;
            final boolean hasZ = (mask & 4) != 0;
            entity.setPosition(new Position3f(10F, 65F, 20F));
            entity.updatePosition(hasX, -3F, hasY, 66F, hasZ, 0F);
            final Position3f expected = new Position3f(hasX ? -3F : 10F, hasY ? 66F : 65F, hasZ ? 0F : 20F);
            require(entity.position().equals(expected), "Position differs for axis mask=" + mask + ": " + entity.position());
        }
    }

    private static Entity newEntity() {
        return new Entity(null, 7L, 10L, "minecraft:pig", 2, new UUID(0L, 1L), EntityTypes26_3.PIG);
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private EntityRegressionTest() {
    }

}

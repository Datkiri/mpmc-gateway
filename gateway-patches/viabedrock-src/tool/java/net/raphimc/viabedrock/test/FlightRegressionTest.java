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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.test;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Type;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.AbilitiesIndex;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PlayerAuthInputData;
import net.raphimc.viabedrock.protocol.model.PlayerAbilities;
import net.raphimc.viabedrock.protocol.packet.ClientPlayerPackets;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.EnumSet;
import java.util.UUID;

public final class FlightRegressionTest {

    public static void main(final String[] args) throws Exception {
        final UserConnection user = (UserConnection) Proxy.newProxyInstance(UserConnection.class.getClassLoader(), new Class<?>[]{UserConnection.class}, (proxy, method, arguments) -> {
            if (method.getName().equals("get")) {
                return null;
            }
            throw new UnsupportedOperationException(method.getName());
        });
        final PlayerAbilities abilities = new PlayerAbilities(7L, (byte) 0, (byte) 0);
        final ClientPlayerEntity player = new ClientPlayerEntity(user, 10L, new UUID(0L, 1L), abilities);
        for (int length = 1; length <= 6; length++) {
            for (int bits = 0; bits < (1 << length); bits++) {
                player.authInputData().clear();
                player.addAuthInputData(PlayerAuthInputData.HandledTeleport);
                for (int index = 0; index < length; index++) {
                    final boolean flying = (bits & (1 << index)) != 0;
                    player.queueFlightTransition(flying);
                    final EnumSet<PlayerAuthInputData> expected = EnumSet.of(PlayerAuthInputData.HandledTeleport, flying ? PlayerAuthInputData.StartFlying : PlayerAuthInputData.StopFlying);
                    require(player.authInputData().equals(expected), "Wrong final flight transition for length=" + length + ", bits=" + bits + ", index=" + index);
                    require(!abilities.getBooleanValue(AbilitiesIndex.MayFly), "Queuing a flight transition must not grant flight permission");
                    require(!abilities.getBooleanValue(AbilitiesIndex.Flying), "Queuing a flight transition must not alter ability values");
                }
            }
        }
        require(!player.canRequestFlight(true), "Denied MayFly must reject flight start");
        abilities.getOrCreateCacheLayer().setAbility(AbilitiesIndex.MayFly, true);
        require(player.canRequestFlight(true), "Granted MayFly must allow flight request");
        abilities.getOrCreateCacheLayer().setAbility(AbilitiesIndex.Flying, true);
        abilities.getOrCreateCacheLayer().setAbility(AbilitiesIndex.MayFly, false);
        require(!player.canRequestFlight(true), "Revoked MayFly must reject flight start");
        require(player.canRequestFlight(false), "Revoked MayFly must still allow flight stop");
        abilities.getOrCreateCacheLayer().setAbility(AbilitiesIndex.MayFly, true);
        player.authInputData().clear();
        player.addAuthInputData(PlayerAuthInputData.HandledTeleport);
        player.queueFlightTransition(true);
        player.reconcileFlightTransition();
        require(player.authInputData().contains(PlayerAuthInputData.StartFlying), "Granted flight start must survive reconciliation");
        final PlayerAbilities revoked = new PlayerAbilities(7L, (byte) 0, (byte) 0);
        final var abilitiesField = net.raphimc.viabedrock.api.model.entity.PlayerEntity.class.getDeclaredField("abilities");
        abilitiesField.setAccessible(true);
        abilitiesField.set(player, revoked);
        player.reconcileFlightTransition();
        require(player.authInputData().equals(EnumSet.of(PlayerAuthInputData.HandledTeleport)), "Authoritative revoke before tick must remove START without synthesizing STOP");
        require(!player.abilities().getBooleanValue(AbilitiesIndex.Flying), "Reconciliation must preserve host Flying=false");
        revoked.getOrCreateCacheLayer().setAbility(AbilitiesIndex.Flying, true);
        player.queueFlightTransition(false);
        player.reconcileFlightTransition();
        require(player.authInputData().equals(EnumSet.of(PlayerAuthInputData.HandledTeleport, PlayerAuthInputData.StopFlying)), "Pending STOP must survive permission revocation");
        require(player.abilities().getBooleanValue(AbilitiesIndex.Flying), "Reconciliation must preserve host Flying=true");
        testRequestBytes();
        System.out.println("Flight regression tests passed: 126 toggle sequences, permission guards, and exact request bytes");
    }

    private static void testRequestBytes() throws Exception {
        final Method write = ClientPlayerPackets.class.getDeclaredMethod("writeFlightAbilityRequest", PacketWrapper.class, boolean.class);
        write.setAccessible(true);
        for (boolean flying : new boolean[]{false, true}) {
            final ByteBuf buffer = Unpooled.buffer();
            try {
                final PacketWrapper wrapper = (PacketWrapper) Proxy.newProxyInstance(PacketWrapper.class.getClassLoader(), new Class<?>[]{PacketWrapper.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("write")) {
                        writeValue(buffer, (Type<?>) arguments[0], arguments[1]);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
                write.invoke(null, wrapper, flying);
                final String expected = flying ? "12010100000000" : "12010000000000";
                require(ByteBufUtil.hexDump(buffer).equals(expected), "RequestAbility payload must include zigzag ability9, bool type1, value and mandatory float0");
            } finally {
                buffer.release();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void writeValue(final ByteBuf buffer, final Type<T> type, final Object value) throws Exception {
        type.write(buffer, (T) value);
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private FlightRegressionTest() {
    }

}

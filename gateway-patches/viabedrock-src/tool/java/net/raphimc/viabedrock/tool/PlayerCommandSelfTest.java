package net.raphimc.viabedrock.tool;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandler;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import com.viaversion.viaversion.protocols.v26_2to26_3.packet.ServerboundPackets26_3;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PlayerActionType;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.PlayerCommandAction;
import net.raphimc.viabedrock.protocol.model.PlayerAbilities;
import net.raphimc.viabedrock.protocol.packet.ClientPlayerPackets;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;

import java.lang.reflect.Proxy;
import java.util.UUID;

/** Exercises the registered command handler without a network or a running server. */
public final class PlayerCommandSelfTest {
    public static void main(final String[] args) throws Exception {
        final EntityTracker[] tracker = new EntityTracker[1];
        final UserConnection user = (UserConnection) Proxy.newProxyInstance(UserConnection.class.getClassLoader(),
            new Class<?>[] {UserConnection.class}, (proxy, method, arguments) -> {
                if (method.getName().equals("get")) return arguments[0] == EntityTracker.class ? tracker[0] : null;
                throw new UnsupportedOperationException(method.getName());
            });
        tracker[0] = new EntityTracker(user);
        final CapturingPlayer player = new CapturingPlayer(user);
        tracker[0].addEntity(player, false);
        final CapturingProtocol protocol = new CapturingProtocol();
        ClientPlayerPackets.register(protocol);
        require(protocol.commandHandler != null, "PLAYER_COMMAND handler not registered");

        invoke(protocol.commandHandler, user, PlayerCommandAction.STOP_SLEEPING);
        require(player.sent == PlayerActionType.StopSleeping && player.sendCount == 1,
            "Leave bed must send exactly one Bedrock StopSleeping action");
        for (PlayerCommandAction action : new PlayerCommandAction[] {
            PlayerCommandAction.START_RIDING_JUMP, PlayerCommandAction.STOP_RIDING_JUMP, PlayerCommandAction.OPEN_INVENTORY
        }) {
            invoke(protocol.commandHandler, user, action);
        }
        require(player.sendCount == 1, "Unsupported riding commands must not fabricate player actions");
        System.out.println("PASS PLAYER_COMMAND: StopSleeping dispatched once; three unsupported riding actions cancel without exception");
    }

    private static void invoke(final PacketHandler handler, final UserConnection user,
                               final PlayerCommandAction action) throws Exception {
        final ByteBuf input = Unpooled.buffer();
        try {
            Types.VAR_INT.write(input, 1);
            Types.VAR_INT.write(input, action.ordinal());
            Types.VAR_INT.write(input, 45);
            final PacketWrapperImpl packet = new PacketWrapperImpl(ServerboundPackets26_3.PLAYER_COMMAND, input, user);
            handler.handle(packet);
            require(packet.isCancelled(), "Java command must be consumed");
            require(!input.isReadable(), "Command handler left unread fields");
        } finally {
            input.release();
        }
    }

    private static final class CapturingPlayer extends ClientPlayerEntity {
        private PlayerActionType sent;
        private int sendCount;

        private CapturingPlayer(final UserConnection user) {
            super(user, 300L, new UUID(0L, 1L), new PlayerAbilities(100L, (byte) 0, (byte) 0));
        }

        @Override
        public void sendPlayerActionPacketToServer(final PlayerActionType action) {
            sent = action;
            sendCount++;
        }
    }

    private static final class CapturingProtocol extends BedrockProtocol {
        private PacketHandler commandHandler;

        @Override
        public void registerServerbound(final ServerboundPackets26_3 packet, final ServerboundBedrockPackets mapped,
                                        final PacketHandler handler, final boolean override) {
            if (packet == ServerboundPackets26_3.PLAYER_COMMAND) commandHandler = handler;
        }
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) throw new AssertionError(message);
    }
}

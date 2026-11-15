package net.raphimc.viabedrock.tool;

import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InteractPacketPayload_Action;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/** Pins the protocol-2193 Interact layout, including the optional-position presence byte. */
public final class InteractWireSelfTest {
    public static void main(final String[] args) throws Exception {
        check(InteractPacketPayload_Action.InteractUpdate, 0L, null, 3);
        check(InteractPacketPayload_Action.OpenInventory, 300L, null, 4);
        check(InteractPacketPayload_Action.StopRiding, 300L, null, 4);
        check(InteractPacketPayload_Action.InteractUpdate, 1L << 40, new Position3f(0.25F, -1.5F, 2F), 20);
        check(InteractPacketPayload_Action.StopRiding, 300L, new Position3f(-4F, 64F, 0F), 16);
        final ByteBuf missingPresence = Unpooled.wrappedBuffer(new byte[] {6, 0});
        try {
            expectRejected(missingPresence);
        } finally {
            missingPresence.release();
        }
        final ByteBuf trailingPosition = Unpooled.buffer();
        try {
            trailingPosition.writeByte(6).writeByte(0).writeBoolean(false).writeFloatLE(1F);
            expectRejected(trailingPosition);
        } finally {
            trailingPosition.release();
        }
        System.out.println("PASS Interact protocol2193: 5 valid variants, absent optional flag and trailing bytes rejected");
    }

    private static void check(final InteractPacketPayload_Action action, final long runtimeId,
                              final Position3f position, final int expectedLength) throws Exception {
        final PacketWrapperImpl packet = new PacketWrapperImpl(ServerboundBedrockPackets.INTERACT, null, null);
        packet.write(Types.UNSIGNED_BYTE, (short) action.getValue());
        packet.write(BedrockTypes.UNSIGNED_VAR_LONG, runtimeId);
        packet.write(BedrockTypes.OPTIONAL_POSITION_3F, position);
        final ByteBuf bytes = Unpooled.buffer();
        try {
            packet.writeProcessedValues(bytes);
            require(readUnsignedVarLong(bytes) == ServerboundBedrockPackets.INTERACT.getId(), "Unexpected packet id");
            require(bytes.readableBytes() == expectedLength, "Unexpected payload size for " + action);
            require(bytes.readUnsignedByte() == action.getValue(), "Action must be uint8");
            require(readUnsignedVarLong(bytes) == runtimeId, "Runtime id lost precision");
            require(bytes.readBoolean() == (position != null), "Optional position presence mismatch");
            if (position != null) {
                require(bytes.readFloatLE() == position.x(), "Position X must be LE float");
                require(bytes.readFloatLE() == position.y(), "Position Y must be LE float");
                require(bytes.readFloatLE() == position.z(), "Position Z must be LE float");
            }
            require(!bytes.isReadable(), "Trailing bytes in Interact");
        } finally {
            bytes.release();
        }
    }

    private static long readUnsignedVarLong(final ByteBuf bytes) {
        long value = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            final int next = bytes.readUnsignedByte();
            value |= (long) (next & 0x7F) << shift;
            if ((next & 0x80) == 0) return value;
        }
        throw new IllegalArgumentException("Overlong runtime id");
    }

    private static void expectRejected(final ByteBuf bytes) {
        boolean rejected = false;
        try {
            bytes.readUnsignedByte();
            readUnsignedVarLong(bytes);
            if (bytes.readBoolean()) {
                bytes.readFloatLE();
                bytes.readFloatLE();
                bytes.readFloatLE();
            }
            if (bytes.isReadable()) throw new IllegalArgumentException("Trailing bytes");
        } catch (IndexOutOfBoundsException | IllegalArgumentException expected) {
            rejected = true;
        }
        require(rejected, "Malformed Interact accepted");
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) throw new AssertionError(message);
    }
}

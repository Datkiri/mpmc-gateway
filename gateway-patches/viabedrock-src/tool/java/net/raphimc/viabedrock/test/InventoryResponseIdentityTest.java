package net.raphimc.viabedrock.test;

import com.viaversion.viaversion.api.connection.UserConnection;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.packet.InventoryPackets;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class InventoryResponseIdentityTest {

    private static final ContainerEnumName INVENTORY = ContainerEnumName.CombinedHotbarAndInventoryContainer;
    private static final List<String> FAILURES = new ArrayList<>();

    public static void main(final String[] args) throws Exception {
        final Method apply = InventoryPackets.class.getDeclaredMethod("applyItemStackResponseSlot",
                InventoryTracker.class, InventoryTracker.PendingCreativeSlot.class,
                ContainerEnumName.class, int.class, int.class, int.class);
        apply.setAccessible(true);
        final UserConnection user = (UserConnection) Proxy.newProxyInstance(UserConnection.class.getClassLoader(),
                new Class<?>[]{UserConnection.class}, (proxy, method, arguments) -> {
                    throw new AssertionError("Unexpected connection access: " + method.getName());
                });
        final InventoryTracker tracker = new InventoryTracker(user);
        final BedrockItem acknowledged = new BedrockItem(1, (short) 0, (byte) 4);
        final BedrockItem queued = new BedrockItem(3, (short) 0, (byte) 9);
        final InventoryTracker.PendingCreativeSlot pending = new InventoryTracker.PendingCreativeSlot(
                INVENTORY, 1, BedrockItem.empty(), acknowledged);
        final long key = ((long) INVENTORY.ordinal() << 32) | 1L;
        final var deferred = new InventoryTracker.DeferredCreativeSlot(37, queued.copy());
        tracker.getDeferredCreativeSlots().put(key, new ArrayDeque<>());
        tracker.getDeferredCreativeSlots().get(key).add(deferred);

        // Reproduce the state produced by queuing B while request A is in flight.
        tracker.getInventoryContainer().setItem(1, queued.copy());
        apply.invoke(null, tracker, pending, INVENTORY, 1, 4, 101);
        check("response A restores A identity", tracker.getInventoryContainer().getItem(1).identifier() == 1);
        check("response A preserves server count", tracker.getInventoryContainer().getItem(1).amount() == 4);
        check("next request source has acknowledged network id", Integer.valueOf(101).equals(tracker.getInventoryContainer().getItem(1).netId()));
        check("queued B remains queued", tracker.getDeferredCreativeSlots().get(key).peek() == deferred);
        check("pending snapshot remains immutable", acknowledged.netId() == null && acknowledged.amount() == 4);

        tracker.getInventoryContainer().setItem(2, new BedrockItem(5));
        apply.invoke(null, tracker, pending, INVENTORY, 2, 2, 202);
        check("unrelated populated slot retains identity", tracker.getInventoryContainer().getItem(2).identifier() == 5);
        apply.invoke(null, tracker, pending, INVENTORY, 3, 2, 203);
        check("unrelated empty slot does not clone A", tracker.getInventoryContainer().getItem(3).isEmpty());
        apply.invoke(null, tracker, pending, ContainerEnumName.ArmorContainer, 1, 1, 204);
        check("unrelated container does not clone A", tracker.getArmorContainer().getItem(1).isEmpty());

        tracker.getInventoryContainer().setItem(1, queued.copy());
        apply.invoke(null, tracker, pending, ContainerEnumName.HotbarContainer, 1, 4, 301);
        check("hotbar response alias resolves matching pending slot", tracker.getInventoryContainer().getItem(1).identifier() == 1);
        final InventoryTracker.PendingCreativeSlot mainInventoryPending = new InventoryTracker.PendingCreativeSlot(
                INVENTORY, 10, BedrockItem.empty(), acknowledged);
        tracker.getInventoryContainer().setItem(10, queued.copy());
        apply.invoke(null, tracker, mainInventoryPending, ContainerEnumName.InventoryContainer, 10, 4, 303);
        check("main inventory alias keeps absolute inventory slot", tracker.getInventoryContainer().getItem(10).identifier() == 1);
        tracker.getInventoryContainer().setItem(1, queued.copy());
        apply.invoke(null, tracker, pending, ContainerEnumName.CursorContainer, 1, 4, 302);
        check("unrelated response container does not use pending identity", tracker.getInventoryContainer().getItem(1).identifier() == 3);

        tracker.getInventoryContainer().setItem(1, queued.copy());
        apply.invoke(null, tracker, null, INVENTORY, 1, 6, 401);
        check("pending-null response retains identity", tracker.getInventoryContainer().getItem(1).identifier() == 3);
        check("pending-null response updates count and network id", tracker.getInventoryContainer().getItem(1).amount() == 6
                && Integer.valueOf(401).equals(tracker.getInventoryContainer().getItem(1).netId()));
        apply.invoke(null, tracker, pending, INVENTORY, 1, 0, 0);
        check("zero count clears acknowledged slot", tracker.getInventoryContainer().getItem(1).isEmpty());
        apply.invoke(null, tracker, pending, INVENTORY, 1, 4, 501);
        check("empty target restores pending A", tracker.getInventoryContainer().getItem(1).identifier() == 1);

        if (!FAILURES.isEmpty()) {
            throw new AssertionError("Inventory response identity failures: " + String.join("; ", FAILURES));
        }
        System.out.println("PASS: all inventory response identity checks");
    }

    private static void check(final String name, final boolean passed) {
        System.out.println((passed ? "PASS: " : "FAIL: ") + name);
        if (!passed) {
            FAILURES.add(name);
        }
    }

    private InventoryResponseIdentityTest() {
    }
}

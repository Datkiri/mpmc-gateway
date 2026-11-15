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

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.util.AttributeKey;
import io.netty.util.DefaultAttributeMap;
import io.netty.util.concurrent.ImmediateEventExecutor;
import net.raphimc.viabedrock.protocol.MpmcPipelineDiagnostics;
import net.raphimc.viabedrock.protocol.task.ChunkTrackerTickTask;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

public final class TimingRegressionTest {

    public static void main(final String[] args) throws Exception {
        if (Boolean.getBoolean("mpmc.debug.timing")) {
            throw new IllegalStateException("Run this regression without mpmc.debug.timing enabled");
        }
        check(MpmcPipelineDiagnostics.timingNow() == 0L, "Timing must default off");
        MpmcPipelineDiagnostics.recordAuthInput(null);
        MpmcPipelineDiagnostics.recordTask(null, true, 0L, 0L);
        testChunkGuard();
        testTimingWindow();
        System.out.println("Timing regression passed: pending coalescing, completion, inactivity, failures, rejection, default-off metrics and timestamp aggregation");
    }

    private static void testChunkGuard() throws Exception {
        final Method enqueue = ChunkTrackerTickTask.class.getDeclaredMethod("enqueueTick", Channel.class, Runnable.class);
        enqueue.setAccessible(true);
        final FakeChannel fake = new FakeChannel();
        final AtomicInteger ticks = new AtomicInteger();
        check((boolean) invoke(enqueue, null, fake.channel, (Runnable) ticks::incrementAndGet), "First tick must enqueue");
        for (int i = 0; i < 100; i++) {
            check(!(boolean) invoke(enqueue, null, fake.channel, (Runnable) ticks::incrementAndGet), "Pending ticks must coalesce");
        }
        check(fake.queue.size() == 1, "Only one task may be queued");
        fake.queue.remove().run();
        check(ticks.get() == 1, "One queued tick must execute");
        check((boolean) invoke(enqueue, null, fake.channel, (Runnable) ticks::incrementAndGet), "Completed guard must reset");
        fake.active = false;
        fake.queue.remove().run();
        check(ticks.get() == 1, "Inactive channel must not tick");
        fake.active = true;
        final Runnable failure = () -> { throw new IllegalStateException("expected tick failure"); };
        check((boolean) invoke(enqueue, null, fake.channel, failure), "Inactive guard must reset");
        try {
            fake.queue.remove().run();
            throw new AssertionError("Expected tick failure");
        } catch (final IllegalStateException expected) {
            check(expected.getMessage().equals("expected tick failure"), "Unexpected tick error");
        }
        fake.reject = true;
        try {
            invoke(enqueue, null, fake.channel, (Runnable) ticks::incrementAndGet);
            throw new AssertionError("Expected submission rejection");
        } catch (final RejectedExecutionException expected) {
            // A rejected submission must not leave the guard latched.
        }
        fake.reject = false;
        check((boolean) invoke(enqueue, null, fake.channel, (Runnable) ticks::incrementAndGet), "Rejected guard must reset");
        fake.queue.remove().run();
        check(ticks.get() == 2, "Tick must resume after rejection");
        final FakeChannel other = new FakeChannel();
        invoke(enqueue, null, fake.channel, (Runnable) ticks::incrementAndGet);
        check((boolean) invoke(enqueue, null, other.channel, (Runnable) ticks::incrementAndGet), "Different channels must not share guards");
        fake.queue.remove().run();
        other.queue.remove().run();
    }

    private static void testTimingWindow() throws Exception {
        final Class<?> type = Class.forName(MpmcPipelineDiagnostics.class.getName() + "$TimingWindow");
        final Constructor<?> constructor = type.getDeclaredConstructor(long.class);
        constructor.setAccessible(true);
        final Object window = constructor.newInstance(0L);
        final Method auth = accessible(type, "authInput", long.class);
        final Method task = accessible(type, "task", boolean.class, long.class, long.class);
        final Method ready = accessible(type, "ready", long.class);
        final Method report = accessible(type, "report", long.class);
        final Method reset = accessible(type, "reset", long.class);
        invoke(auth, window, 0L);
        invoke(auth, window, 50_000_000L);
        invoke(auth, window, 200_000_000L);
        invoke(task, window, true, 12_000_000L, 35_000_000L);
        invoke(task, window, false, 7_000_000L, 2_000_000L);
        check(!(boolean) invoke(ready, window, 4_999_999_999L), "Report must remain rate limited");
        check((boolean) invoke(ready, window, 5_000_000_000L), "Report must become ready at five seconds");
        final String text = (String) invoke(report, window, 5_000_000_000L);
        check(text.contains("authSerialized=3 authIntervalAvgUs=100000 authIntervalMaxUs=150000 authGapsOver100ms=1"), "Auth interval aggregates incorrect: " + text);
        check(text.contains("chunkTicks=1 chunkQueueMaxUs=12000 chunkRuntimeMaxUs=35000"), "Chunk aggregates incorrect: " + text);
        check(text.contains("entityTicks=1 entityQueueMaxUs=7000 entityRuntimeMaxUs=2000"), "Entity aggregates incorrect: " + text);
        invoke(reset, window, 200_000_000L);
        invoke(auth, window, 250_000_000L);
        final String next = (String) invoke(report, window, 250_000_000L);
        check(next.contains("authSerialized=1 authIntervalAvgUs=50000 authIntervalMaxUs=50000 authGapsOver100ms=0"), "Cross-window interval must be retained: " + next);
        check(next.contains("chunkTicks=0 chunkQueueMaxUs=0 chunkRuntimeMaxUs=0"), "Task aggregates must reset");
    }

    private static Method accessible(final Class<?> type, final String name, final Class<?>... parameters) throws Exception {
        final Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static Object invoke(final Method method, final Object target, final Object... arguments) throws Exception {
        try {
            return method.invoke(target, arguments);
        } catch (final InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw e;
        }
    }

    private static void check(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class FakeChannel {
        private final Queue<Runnable> queue = new ArrayDeque<>();
        private final DefaultAttributeMap attributes = new DefaultAttributeMap();
        private boolean active = true;
        private boolean reject;
        private final EventLoop eventLoop = (EventLoop) Proxy.newProxyInstance(EventLoop.class.getClassLoader(), new Class<?>[]{EventLoop.class}, (proxy, method, args) -> {
            if (method.getName().equals("submit")) {
                if (this.reject) {
                    throw new RejectedExecutionException("expected submission failure");
                }
                this.queue.add((Runnable) args[0]);
                return ImmediateEventExecutor.INSTANCE.newSucceededFuture(null);
            }
            throw new UnsupportedOperationException(method.getName());
        });
        private final Channel channel = (Channel) Proxy.newProxyInstance(Channel.class.getClassLoader(), new Class<?>[]{Channel.class}, (proxy, method, args) -> switch (method.getName()) {
            case "eventLoop" -> this.eventLoop;
            case "isActive" -> this.active;
            case "attr" -> this.attributes.attr((AttributeKey<?>) args[0]);
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }
}

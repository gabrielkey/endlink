package org.endstone.proxy.backend;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PacedResourcePackSenderTest {
    @Test
    void androidStyleConcurrentRequestsAreSentOnePerInterval() {
        AtomicBoolean connected = new AtomicBoolean(true);
        List<Integer> sentChunks = new ArrayList<>();
        ArrayDeque<ScheduledTask> scheduled = new ArrayDeque<>();
        PacedResourcePackSender sender = new PacedResourcePackSender(
                connected::get,
                (packId, chunkIndex) -> sentChunks.add(chunkIndex),
                (task, delay, unit) -> scheduled.addLast(new ScheduledTask(task, delay, unit))
        );
        UUID packId = UUID.randomUUID();

        for (int chunkIndex = 0; chunkIndex < 29; chunkIndex++) {
            sender.enqueue(packId, chunkIndex);
        }

        assertEquals(List.of(0), sentChunks);
        for (int chunkIndex = 1; chunkIndex < 29; chunkIndex++) {
            ScheduledTask task = scheduled.removeFirst();
            assertEquals(PacedResourcePackSender.CHUNK_INTERVAL_MILLIS, task.delay());
            assertEquals(TimeUnit.MILLISECONDS, task.unit());
            task.command().run();
            assertEquals(chunkIndex + 1, sentChunks.size());
            assertEquals(chunkIndex, sentChunks.get(chunkIndex));
        }

        scheduled.removeFirst().command().run();
        assertEquals(29, sentChunks.size());
        assertEquals(0, scheduled.size());
    }

    private record ScheduledTask(Runnable command, long delay, TimeUnit unit) {
    }
}

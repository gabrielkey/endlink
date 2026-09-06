package org.endstone.proxy.backend;

import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Prevents clients that request a whole resource pack concurrently from turning it into one large
 * RakNet burst.
 *
 * <p>Android asks for every advertised chunk almost at once. Sending each 100KB response directly
 * from the request handler can therefore enqueue several megabytes in a few milliseconds, which is
 * enough to stall the reliable UDP stream on a mobile link. Keep the first response immediate, then
 * hold the send slot briefly before releasing each queued response.</p>
 */
final class PacedResourcePackSender {
    static final long CHUNK_INTERVAL_MILLIS = 20;

    private final BooleanSupplier connected;
    private final ChunkSender sender;
    private final DelayedScheduler scheduler;
    private final ArrayDeque<ChunkRequest> pending = new ArrayDeque<>();
    private boolean sendSlotHeld;

    PacedResourcePackSender(
            BooleanSupplier connected,
            ChunkSender sender,
            DelayedScheduler scheduler
    ) {
        this.connected = connected;
        this.sender = sender;
        this.scheduler = scheduler;
    }

    void enqueue(UUID packId, int chunkIndex) {
        if (!connected.getAsBoolean()) {
            return;
        }
        pending.addLast(new ChunkRequest(packId, chunkIndex));
        if (sendSlotHeld) {
            return;
        }
        sendSlotHeld = true;
        sendNextAndHoldSlot();
    }

    private void sendNextAndHoldSlot() {
        if (!connected.getAsBoolean()) {
            pending.clear();
            sendSlotHeld = false;
            return;
        }
        ChunkRequest request = pending.pollFirst();
        if (request == null) {
            sendSlotHeld = false;
            return;
        }
        sender.send(request.packId(), request.chunkIndex());
        scheduler.schedule(this::releaseSendSlot, CHUNK_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void releaseSendSlot() {
        if (pending.isEmpty()) {
            sendSlotHeld = false;
            return;
        }
        sendNextAndHoldSlot();
    }

    @FunctionalInterface
    interface ChunkSender {
        void send(UUID packId, int chunkIndex);
    }

    @FunctionalInterface
    interface DelayedScheduler {
        void schedule(Runnable task, long delay, TimeUnit unit);
    }

    private record ChunkRequest(UUID packId, int chunkIndex) {
    }
}

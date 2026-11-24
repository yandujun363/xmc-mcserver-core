package xmc.core.session;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Emits tunnel bytes once per Minecraft tick.
 *
 * <p>The socket reader queues proxy data instead of writing it straight to the
 * connection; every tick drains the queue into one batch of data-channel
 * payloads and appends one small padding payload. An idle tunnel therefore
 * keeps the 20 Hz cadence of a real server instead of going silent, and a busy
 * one looks like a per-tick burst rather than a bulk TCP stream.
 *
 * <p>The queue is bounded: a client that does not drain pushes back through
 * {@link #offer(byte[])} to the tunnel socket instead of growing memory.
 *
 * <p>Deliberately free of Minecraft types: the unit tests run without a game
 * runtime, and only the timing and framing policy lives here.
 */
public final class TickSender {
    /** One padding payload keeps an idle tick non-empty, like a real server. */
    private static final byte[] TICK_PADDING = new byte[1];

    private static final int MAX_QUEUED_BYTES = 4 * 1024 * 1024;

    @FunctionalInterface
    public interface Sink {
        /**
         * Sends one payload.
         *
         * @return false when the connection can no longer accept payloads
         */
        boolean send(byte[] data);
    }

    private final Sink dataSink;
    private final Sink paddingSink;
    private final int maxPayloadBytes;
    private final int maxQueuedBytes;

    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();

    private int queuedBytes;
    private boolean closed;

    public TickSender(Sink dataSink, Sink paddingSink, int maxPayloadBytes) {
        this(dataSink, paddingSink, maxPayloadBytes, MAX_QUEUED_BYTES);
    }

    public TickSender(Sink dataSink, Sink paddingSink, int maxPayloadBytes, int maxQueuedBytes) {
        if (maxPayloadBytes < 1) {
            throw new IllegalArgumentException("maxPayloadBytes must be positive");
        }
        if (maxQueuedBytes < maxPayloadBytes) {
            throw new IllegalArgumentException("maxQueuedBytes must be at least maxPayloadBytes");
        }
        this.dataSink = dataSink;
        this.paddingSink = paddingSink;
        this.maxPayloadBytes = maxPayloadBytes;
        this.maxQueuedBytes = maxQueuedBytes;
    }

    /**
     * Queues proxy bytes for the next tick.
     *
     * <p>Blocks while the queue is full so a slow client throttles the tunnel
     * socket; returns false once the sender is closed.
     */
    public boolean offer(byte[] data) throws InterruptedException {
        if (data.length == 0) {
            return true;
        }
        synchronized (this) {
            while (!closed && queuedBytes >= maxQueuedBytes) {
                wait();
            }
            if (closed) {
                return false;
            }
            queue.add(data);
            queuedBytes += data.length;
            return true;
        }
    }

    /**
     * Writes one tick worth of payloads: the queued data, then one padding
     * payload.
     *
     * @return false when the connection is gone and the session must close
     */
    public boolean tick() {
        List<byte[]> batch;
        synchronized (this) {
            if (closed) {
                return false;
            }
            batch = drain();
        }
        for (byte[] payload : batch) {
            if (!dataSink.send(payload)) {
                close();
                return false;
            }
        }
        if (!paddingSink.send(TICK_PADDING)) {
            close();
            return false;
        }
        return true;
    }

    /**
     * Writes whatever is still queued without a padding payload and stops
     * accepting more, so Close does not drop the last bytes.
     *
     * @return false when the last batch could not be written
     */
    public boolean close() {
        List<byte[]> batch;
        synchronized (this) {
            if (closed) {
                return true;
            }
            batch = drain();
            closed = true;
            notifyAll();
        }
        boolean written = true;
        for (byte[] payload : batch) {
            written &= dataSink.send(payload);
        }
        return written;
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    /** Takes the queue and returns it as payloads of at most {@code maxPayloadBytes}. */
    private List<byte[]> drain() {
        if (queuedBytes == 0) {
            return List.of();
        }

        byte[] joined = new byte[queuedBytes];
        int offset = 0;
        while (!queue.isEmpty()) {
            byte[] next = queue.poll();
            System.arraycopy(next, 0, joined, offset, next.length);
            offset += next.length;
        }
        queuedBytes = 0;
        notifyAll();

        if (joined.length <= maxPayloadBytes) {
            return List.of(joined);
        }

        List<byte[]> payloads = new ArrayList<>((joined.length + maxPayloadBytes - 1) / maxPayloadBytes);
        for (int start = 0; start < joined.length; start += maxPayloadBytes) {
            int end = Math.min(joined.length, start + maxPayloadBytes);
            payloads.add(Arrays.copyOfRange(joined, start, end));
        }
        return payloads;
    }
}

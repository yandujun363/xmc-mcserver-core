package xmc.core.session;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickSenderTest {
    private final List<byte[]> data = new ArrayList<>();
    private final List<byte[]> padding = new ArrayList<>();

    private TickSender sender(int maxPayloadBytes) {
        return new TickSender(data::add, padding::add, maxPayloadBytes);
    }

    @Test
    void idleTickStillSendsOnePaddingPayload() {
        TickSender sender = sender(24 * 1024);

        assertTrue(sender.tick());

        assertTrue(data.isEmpty(), "an idle tick must not invent data payloads");
        assertEquals(1, padding.size());
        assertEquals(1, padding.get(0).length, "the tick padding is one byte, like the Xray side");
    }

    @Test
    void oneTickIsOneBatch() throws Exception {
        TickSender sender = sender(24 * 1024);

        assertTrue(sender.offer(new byte[]{1, 2}));
        assertTrue(sender.offer(new byte[]{3}));
        assertTrue(sender.tick());

        assertEquals(1, data.size(), "one tick must not emit one payload per offer");
        assertArrayEquals(new byte[]{1, 2, 3}, data.get(0));
        assertEquals(1, padding.size());
        assertTrue(sender.tick(), "a second tick still succeeds");
        assertEquals(1, data.size(), "nothing new is queued");
        assertEquals(2, padding.size());
    }

    @Test
    void aBatchAboveThePayloadLimitIsSplitInsideTheSameTick() throws Exception {
        TickSender sender = sender(4);

        assertTrue(sender.offer(new byte[]{1, 2, 3}));
        assertTrue(sender.offer(new byte[]{4, 5, 6, 7, 8}));
        assertTrue(sender.tick());

        assertEquals(2, data.size());
        assertArrayEquals(new byte[]{1, 2, 3, 4}, data.get(0));
        assertArrayEquals(new byte[]{5, 6, 7, 8}, data.get(1));
        assertEquals(1, padding.size(), "the padding payload is appended once per tick");
    }

    @Test
    void closeFlushesTheQueueWithoutPadding() throws Exception {
        TickSender sender = sender(24 * 1024);

        assertTrue(sender.offer(new byte[]{9, 8, 7}));
        assertTrue(sender.close());

        assertEquals(1, data.size(), "Close must not drop the last bytes");
        assertArrayEquals(new byte[]{9, 8, 7}, data.get(0));
        assertTrue(padding.isEmpty(), "a flush is not a tick and carries no padding");
        assertTrue(sender.isClosed());
        assertFalse(sender.tick());
    }

    @Test
    void offeringAfterCloseIsRejected() throws Exception {
        TickSender sender = sender(24 * 1024);
        sender.close();

        assertFalse(sender.offer(new byte[]{1}));
        assertTrue(data.isEmpty());
    }

    @Test
    void aFailingSinkClosesTheSender() throws Exception {
        TickSender sender = new TickSender(chunk -> false, chunk -> true, 24 * 1024);

        assertTrue(sender.offer(new byte[]{1}));
        assertFalse(sender.tick());
        assertTrue(sender.isClosed());
    }

    @Test
    void anOfferBlocksWhileTheQueueIsFull() throws Exception {
        // Payload limit 2 and a queue cap of 2: the second offer has no room.
        TickSender sender = new TickSender(data::add, padding::add, 2, 2);

        assertTrue(sender.offer(new byte[]{1, 2}));
        Thread blocked = new Thread(() -> {
            try {
                sender.offer(new byte[]{3});
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        blocked.start();
        blocked.join(100);
        assertTrue(blocked.isAlive(), "the queue must push back instead of growing");

        sender.tick();
        blocked.join(1_000);
        assertFalse(blocked.isAlive(), "draining the queue releases the blocked offer");
        assertEquals(1, data.size(), "the tick only carries what was queued before it");
        assertArrayEquals(new byte[]{1, 2}, data.get(0));

        assertTrue(sender.tick());
        assertEquals(2, data.size(), "the released offer goes out in the next tick");
        assertArrayEquals(new byte[]{3}, data.get(1));
    }

    @Test
    void thePayloadLimitMustBeSane() {
        assertThrows(IllegalArgumentException.class, () -> sender(0));
        assertThrows(IllegalArgumentException.class,
                () -> new TickSender(chunk -> true, chunk -> true, 8, 4));
    }
}

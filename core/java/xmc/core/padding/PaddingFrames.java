package xmc.core.padding;

import java.util.List;
import java.util.Random;

/**
 * Writes a padding turn as a sequence of Minecraft custom payload frames on
 * the configured padding channel (default {@code xmc:pad}).
 *
 * <p>Nothing here is a "record whose length includes its own varint" any more:
 * every write becomes a real configuration-state custom payload packet, so the
 * vanilla server (and any vanilla-splitter based pipeline) parses it without
 * being taken over. Packet boundaries from the captured templates are kept as
 * write boundaries, and one chunk is split further when it exceeds the custom
 * payload limit.
 */
public final class PaddingFrames {
    /**
     * Serverbound custom payload data is capped at 32767 bytes by vanilla. The
     * same budget is used in both directions so both sides emit the same shapes.
     */
    public static final int MAX_DATA_LENGTH = 32767;

    private PaddingFrames() {
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @FunctionalInterface
    public interface PayloadSink {
        /** Sends one padding-channel payload. Called on the session thread. */
        void send(byte[] data);
    }

    /**
     * Sends one padding turn.
     *
     * @return the number of payload bytes that were handed to the sink
     */
    public static int writeTurn(PayloadSink sink, PaddingTurn turn, int prefixLength,
                                Sleeper sleeper, Random random) throws InterruptedException {
        PaddingSelect.sleepRange(sleeper, turn.startDelay(), random);

        PaddingSelect.Selection selection = PaddingSelect.select(turn, prefixLength, random);
        int payloadLength = selection.targetLength() - prefixLength;
        if (payloadLength < 1) {
            throw new IllegalStateException("target length " + selection.targetLength()
                    + " leaves an invalid padding payload of " + payloadLength + " bytes");
        }

        List<Integer> chunks = selection.chunks();
        int[] delayMinMs;
        int[] delayMaxMs;
        if (chunks == null) {
            int chunkLength = turn.writeChunkMax();
            if (chunkLength == 0) {
                chunkLength = PaddingSelect.DEFAULT_CHUNK_LENGTH;
            } else if (turn.writeChunkMin() > 0) {
                chunkLength = PaddingSelect.randomBetween(turn.writeChunkMin(), chunkLength, random);
            }
            chunks = PaddingSelect.defaultChunks(payloadLength, chunkLength);
            delayMinMs = new int[chunks.size()];
            delayMaxMs = new int[chunks.size()];
            for (int i = 1; i < chunks.size(); i++) {
                delayMinMs[i] = turn.chunkDelay().minMs();
                delayMaxMs[i] = turn.chunkDelay().maxMs();
            }
        } else {
            delayMinMs = selection.delayMinMs();
            delayMaxMs = selection.delayMaxMs();
        }

        int written = 0;
        for (int i = 0; i < chunks.size(); i++) {
            PaddingSelect.sleepRange(sleeper, new PaddingDelayRange(delayMinMs[i], delayMaxMs[i]), random);
            int chunkLength = chunks.get(i);
            written += sendChunk(sink, chunkLength);
        }
        return written;
    }

    /**
     * Splits one captured packet boundary into payloads that vanilla can carry.
     * The frame overhead (length varint, packet id and channel) is added by the
     * vanilla encoder, so only the data length is split here.
     */
    static int sendChunk(PayloadSink sink, int length) {
        int parts = Math.max(1, (length + MAX_DATA_LENGTH - 1) / MAX_DATA_LENGTH);
        int base = length / parts;
        int remainder = length % parts;
        for (int i = 0; i < parts; i++) {
            sink.send(new byte[base + (i < remainder ? 1 : 0)]);
        }
        return length;
    }
}

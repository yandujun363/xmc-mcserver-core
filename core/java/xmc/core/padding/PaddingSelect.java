package xmc.core.padding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Picks the length and chunk boundaries of a padding turn.
 *
 * <p>The templates come from controlled Minecraft 26.1.2 logins and are ported
 * from Xray-core's {@code padding_preset.go}. Only the sender uses this class;
 * in frame mode the receiver never validates padding, because the padding is
 * ordinary Minecraft traffic that vanilla already parsed.
 */
final class PaddingSelect {
    static final int MAX_TURN_LENGTH = 8 * 1024 * 1024;
    static final int MAX_CHUNK_LENGTH = 48 * 1024;
    static final int DEFAULT_CHUNK_LENGTH = 16 * 1024;

    private PaddingSelect() {
    }

    record Selection(int targetLength, List<Integer> chunks, int[] delayMinMs, int[] delayMaxMs) {
    }

    static Selection select(PaddingTurn turn, int prefixLength, Random random) {
        if (turn.variants().isEmpty()) {
            boolean hasSendRange = turn.sendMin() != 0 || turn.sendMax() != 0;
            int minimum = hasSendRange ? turn.sendMin() : turn.minLength();
            int maximum = hasSendRange ? turn.sendMax() : turn.maxLength();
            return new Selection(randomBetween(minimum, maximum, random), null, null, null);
        }
        PaddingVariant variant = turn.variants().get(random.nextInt(turn.variants().size()));
        Trimmed trimmed = trimPrefix(variant, prefixLength);
        List<Integer> chunks = new ArrayList<>(trimmed.chunks().length);
        for (int chunk : trimmed.chunks()) {
            chunks.add(chunk);
        }
        return new Selection(variant.totalLength(), chunks, trimmed.delayMinMs(), trimmed.delayMaxMs());
    }

    static List<Integer> defaultChunks(int length, int writeChunkLength) {
        List<Integer> chunks = new ArrayList<>();
        int remaining = length;
        while (remaining > 0) {
            int chunk = Math.min(remaining, writeChunkLength);
            chunks.add(chunk);
            remaining -= chunk;
        }
        return chunks;
    }

    static void sleepRange(PaddingFrames.Sleeper sleeper, PaddingDelayRange range, Random random)
            throws InterruptedException {
        int delay = range.minMs() == range.maxMs()
                ? range.minMs()
                : randomBetween(range.minMs(), range.maxMs(), random);
        if (delay > 0) {
            sleeper.sleep(delay);
        }
    }

    static int randomBetween(int minimum, int maximum, Random random) {
        if (minimum >= maximum) {
            return minimum;
        }
        return minimum + random.nextInt(maximum - minimum + 1);
    }

    static boolean acceptsLength(PaddingTurn turn, int length) {
        if (turn.variants().isEmpty()) {
            return length >= turn.minLength() && length <= turn.maxLength();
        }
        for (PaddingVariant variant : turn.variants()) {
            if (variant.totalLength() == length) {
                return true;
            }
        }
        return false;
    }

    private static Trimmed trimPrefix(PaddingVariant variant, int prefixLength) {
        int remainingPrefix = prefixLength;
        int firstChunk = 0;
        int[] chunks = variant.chunks();
        while (firstChunk < chunks.length && remainingPrefix > 0) {
            int chunkLength = chunks[firstChunk];
            if (remainingPrefix < chunkLength) {
                throw new IllegalStateException("prefix length " + prefixLength + " splits chunk " + firstChunk);
            }
            remainingPrefix -= chunkLength;
            firstChunk++;
        }
        if (remainingPrefix != 0 || firstChunk == chunks.length) {
            throw new IllegalStateException("prefix length " + prefixLength + " leaves no padding record");
        }
        int[] remainingChunks = Arrays.copyOfRange(chunks, firstChunk, chunks.length);
        int[] delayMinMs = new int[remainingChunks.length];
        int[] delayMaxMs = new int[remainingChunks.length];
        if (variant.hasDelays()) {
            delayMinMs = Arrays.copyOfRange(variant.delayMinMs(), firstChunk, chunks.length);
            delayMaxMs = Arrays.copyOfRange(variant.delayMaxMs(), firstChunk, chunks.length);
        }
        return new Trimmed(remainingChunks, delayMinMs, delayMaxMs);
    }

    private record Trimmed(int[] chunks, int[] delayMinMs, int[] delayMaxMs) {
    }
}

package xmc.core.padding;

import xmc.core.protocol.VarInts;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Padding turn reader/writer, ported from Xray-core's {@code padding.go}.
 */
public final class PaddingCodec {
    public static final int MAX_TURN_LENGTH = 8 * 1024 * 1024;
    public static final int MAX_CHUNK_LENGTH = 48 * 1024;
    private static final int DEFAULT_CHUNK_LENGTH = 16 * 1024;
    private static final byte[] ZEROS = new byte[8192];

    private PaddingCodec() {
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public static void readTurn(InputStream in, PaddingTurn turn, int prefixLength) throws IOException {
        int recordLength = VarInts.read(in);
        int headerLength = VarInts.size(recordLength);
        if (recordLength < headerLength || recordLength > MAX_TURN_LENGTH) {
            throw new IOException("invalid padding record length: " + recordLength);
        }
        int totalLength = prefixLength + recordLength;
        if (!acceptsLength(turn, totalLength)) {
            if (!turn.variants().isEmpty()) {
                throw new IOException("padding turn length " + totalLength + " is not an allowed variant");
            }
            throw new IOException("padding turn length " + totalLength
                    + " is outside " + turn.minLength() + "-" + turn.maxLength());
        }
        skipFully(in, recordLength - headerLength);
    }

    public static void writeTurn(OutputStream out, PaddingTurn turn, int prefixLength,
                                 Sleeper sleeper, Random random) throws IOException, InterruptedException {
        sleepRange(sleeper, turn.startDelay(), random);

        Selection selection = select(turn, prefixLength, random);
        int recordLength = selection.targetLength - prefixLength;
        if (recordLength < 1) {
            throw new IOException("target length " + selection.targetLength
                    + " leaves an invalid record length " + recordLength);
        }
        byte[] header = VarInts.encode(recordLength);

        List<Integer> chunks = selection.chunks;
        int[] delayMinMs;
        int[] delayMaxMs;
        if (chunks == null) {
            int chunkLength = turn.writeChunkMax();
            if (chunkLength == 0) {
                chunkLength = DEFAULT_CHUNK_LENGTH;
            } else if (turn.writeChunkMin() > 0) {
                chunkLength = randomBetween(turn.writeChunkMin(), chunkLength, random);
            }
            chunks = defaultChunks(recordLength, chunkLength);
            delayMinMs = new int[chunks.size()];
            delayMaxMs = new int[chunks.size()];
            for (int i = 1; i < chunks.size(); i++) {
                delayMinMs[i] = turn.chunkDelay().minMs();
                delayMaxMs[i] = turn.chunkDelay().maxMs();
            }
        } else {
            delayMinMs = selection.delayMinMs;
            delayMaxMs = selection.delayMaxMs;
        }

        if (chunks.get(0) < header.length) {
            throw new IOException("first padding chunk " + chunks.get(0) + " is shorter than header " + header.length);
        }

        int written = 0;
        for (int i = 0; i < chunks.size(); i++) {
            sleepRange(sleeper, new PaddingDelayRange(delayMinMs[i], delayMaxMs[i]), random);
            int chunkLength = chunks.get(i);
            if (i == 0) {
                out.write(header);
                writeZeros(out, chunkLength - header.length);
            } else {
                writeZeros(out, chunkLength);
            }
            written += chunkLength;
        }
        if (written != recordLength) {
            throw new IOException("padding chunks total " + written + ", want " + recordLength);
        }
        out.flush();
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

    private static Selection select(PaddingTurn turn, int prefixLength, Random random) {
        if (turn.variants().isEmpty()) {
            boolean hasSendRange = turn.sendMin() != 0 || turn.sendMax() != 0;
            int minimum = hasSendRange ? turn.sendMin() : turn.minLength();
            int maximum = hasSendRange ? turn.sendMax() : turn.maxLength();
            return new Selection(randomBetween(minimum, maximum, random), null, null, null);
        }
        PaddingVariant variant = turn.variants().get(random.nextInt(turn.variants().size()));
        Trimmed trimmed = trimPrefix(variant, prefixLength);
        List<Integer> chunks = new ArrayList<>(trimmed.chunks.length);
        for (int chunk : trimmed.chunks) {
            chunks.add(chunk);
        }
        return new Selection(variant.totalLength(), chunks, trimmed.delayMinMs, trimmed.delayMaxMs);
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

    private static List<Integer> defaultChunks(int recordLength, int writeChunkLength) {
        List<Integer> chunks = new ArrayList<>();
        int remaining = recordLength;
        while (remaining > 0) {
            int chunk = Math.min(remaining, writeChunkLength);
            chunks.add(chunk);
            remaining -= chunk;
        }
        return chunks;
    }

    private static void sleepRange(Sleeper sleeper, PaddingDelayRange range, Random random) throws InterruptedException {
        int delay = range.minMs() == range.maxMs()
                ? range.minMs()
                : randomBetween(range.minMs(), range.maxMs(), random);
        if (delay > 0) {
            sleeper.sleep(delay);
        }
    }

    private static int randomBetween(int minimum, int maximum, Random random) {
        if (minimum >= maximum) {
            return minimum;
        }
        return minimum + random.nextInt(maximum - minimum + 1);
    }

    private static void writeZeros(OutputStream out, int length) throws IOException {
        int remaining = length;
        while (remaining > 0) {
            int count = Math.min(remaining, ZEROS.length);
            out.write(ZEROS, 0, count);
            remaining -= count;
        }
    }

    private static void skipFully(InputStream in, int length) throws IOException {
        int remaining = length;
        byte[] scratch = new byte[Math.min(remaining, 8192)];
        while (remaining > 0) {
            int count = in.read(scratch, 0, Math.min(remaining, scratch.length));
            if (count < 0) {
                throw new IOException("unexpected end of stream while reading padding");
            }
            remaining -= count;
        }
    }

    private record Selection(int targetLength, List<Integer> chunks, int[] delayMinMs, int[] delayMaxMs) {
    }

    private record Trimmed(int[] chunks, int[] delayMinMs, int[] delayMaxMs) {
    }
}

package xmc.core.padding;

import java.util.List;

/**
 * One startup-padding turn.
 *
 * @param minLength    accepted minimum total frame length when {@code variants} is empty
 * @param maxLength    accepted maximum total frame length when {@code variants} is empty
 * @param sendMin      chosen send length minimum, or 0 to use {@code minLength}
 * @param sendMax      chosen send length maximum, or 0 to use {@code maxLength}
 * @param startDelay   delay before the turn
 * @param chunkDelay   delay between chunks of generated padding
 * @param writeChunkMin minimum generated chunk length, 0 when not applicable
 * @param writeChunkMax generated chunk length; 0 means the default 16 KiB buffer
 * @param variants     exact chunk templates when the turn is a captured variant
 */
public record PaddingTurn(
        Direction direction,
        int minLength,
        int maxLength,
        int sendMin,
        int sendMax,
        PaddingDelayRange startDelay,
        PaddingDelayRange chunkDelay,
        int writeChunkMin,
        int writeChunkMax,
        List<PaddingVariant> variants) {

    public PaddingTurn {
        if (sendMin < 0 || sendMax < 0) {
            throw new IllegalArgumentException("negative send range");
        }
        if (sendMin != 0 && sendMax < sendMin) {
            throw new IllegalArgumentException("invalid send range " + sendMin + "-" + sendMax);
        }
        variants = List.copyOf(variants);
    }

    public enum Direction {
        CLIENT_TO_SERVER,
        SERVER_TO_CLIENT
    }

    public boolean sendsFromServer() {
        return direction == Direction.SERVER_TO_CLIENT;
    }

    public PaddingTurn withSendRange(int min, int max) {
        return new PaddingTurn(direction, minLength, maxLength, min, max, startDelay, chunkDelay,
                writeChunkMin, writeChunkMax, variants);
    }
}

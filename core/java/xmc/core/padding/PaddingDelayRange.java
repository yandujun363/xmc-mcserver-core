package xmc.core.padding;

/**
 * Inclusive millisecond delay range used between padding chunks.
 */
public record PaddingDelayRange(int minMs, int maxMs) {
    public static final PaddingDelayRange ZERO = new PaddingDelayRange(0, 0);

    public PaddingDelayRange {
        if (minMs < 0 || maxMs < minMs) {
            throw new IllegalArgumentException("invalid delay range " + minMs + "-" + maxMs);
        }
    }
}

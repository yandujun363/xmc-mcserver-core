package xmc.core.padding;

/**
 * A chunk template of a captured padding turn. Chunk boundaries matter for
 * write pacing; receivers only validate the total length.
 */
public record PaddingVariant(int[] chunks, int[] delayMinMs, int[] delayMaxMs) {
    public PaddingVariant {
        if (chunks.length == 0) {
            throw new IllegalArgumentException("empty padding variant");
        }
        if ((delayMinMs == null) != (delayMaxMs == null)) {
            throw new IllegalArgumentException("incomplete delay arrays");
        }
        if (delayMinMs != null && delayMinMs.length != chunks.length) {
            throw new IllegalArgumentException("delay arrays must match chunks");
        }
        chunks = chunks.clone();
        if (delayMinMs != null) {
            delayMinMs = delayMinMs.clone();
            delayMaxMs = delayMaxMs.clone();
        }
    }

    public int totalLength() {
        int total = 0;
        for (int chunk : chunks) {
            total += chunk;
        }
        return total;
    }

    public boolean hasDelays() {
        return delayMinMs != null;
    }
}

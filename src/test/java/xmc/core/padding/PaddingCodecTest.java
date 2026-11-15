package xmc.core.padding;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PaddingCodecTest {
    private static final PaddingCodec.Sleeper NO_SLEEP = millis -> {
    };

    @Test
    void serverSendRangesStayInsideAcceptedBounds() {
        for (int seed = 0; seed < 200; seed++) {
            List<PaddingTurn> schedule = PaddingPreset26.serverSchedule(new Random(seed));
            for (PaddingTurn turn : schedule) {
                if (!turn.sendsFromServer() || turn.sendMin() == 0) {
                    continue;
                }
                assertTrue(turn.sendMin() >= turn.minLength(), "sendMin below accepted range");
                assertTrue(turn.sendMax() <= turn.maxLength(), "sendMax above accepted range");
            }
        }
    }

    /**
     * The client writes the first turn with the 2-byte Login Acknowledged packet
     * as its prefix. The server reads it back with the same prefix.
     */
    @Test
    void readsFirstClientTurnWithLoginAckPrefix() throws IOException {
        PaddingTurn first = PaddingPreset26.startup().get(0);
        int targetLength = first.variants().get(0).totalLength();
        int recordLength = targetLength - 2;

        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        // The ack itself was already consumed by the caller.
        wire.write(xmc.core.protocol.VarInts.encode(recordLength));
        wire.write(new byte[recordLength - xmc.core.protocol.VarInts.size(recordLength)]);

        PaddingCodec.readTurn(new ByteArrayInputStream(wire.toByteArray()), first, 2);
    }

    @Test
    void writesAndReadsServerPaddingTurns() throws Exception {
        for (int seed = 0; seed < 20; seed++) {
            List<PaddingTurn> server = PaddingPreset26.serverSchedule(new Random(seed));
            ByteArrayOutputStream wire = new ByteArrayOutputStream();
            Random random = new Random(seed);
            for (PaddingTurn turn : server) {
                if (turn.sendsFromServer()) {
                    PaddingCodec.writeTurn(wire, turn, 0, NO_SLEEP, random);
                }
            }
            ByteArrayInputStream input = new ByteArrayInputStream(wire.toByteArray());
            for (PaddingTurn turn : server) {
                if (turn.sendsFromServer()) {
                    PaddingCodec.readTurn(input, turn, 0);
                }
            }
            assertTrue(input.available() == 0, "leftover bytes after reading server padding");
        }
    }
}

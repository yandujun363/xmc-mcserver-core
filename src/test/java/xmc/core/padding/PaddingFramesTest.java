package xmc.core.padding;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaddingFramesTest {
    private static final PaddingFrames.Sleeper NO_SLEEP = millis -> {
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
     * The framed server turns must stay exactly on the captured total lengths:
     * only the framing became native, the traffic shape did not change.
     */
    @Test
    void serverTurnsKeepTheirCapturedLengths() throws Exception {
        for (int seed = 0; seed < 50; seed++) {
            Random random = new Random(seed);
            List<PaddingTurn> schedule = PaddingPreset26.serverSchedule(random);
            for (PaddingTurn turn : schedule) {
                if (!turn.sendsFromServer()) {
                    continue;
                }
                List<byte[]> payloads = new ArrayList<>();
                int total = PaddingFrames.writeTurn(payloads::add, turn, 0, NO_SLEEP, random);
                int sum = payloads.stream().mapToInt(payload -> payload.length).sum();
                assertEquals(sum, total, "reported byte count differs from the payloads");
                assertTrue(PaddingSelect.acceptsLength(turn, total),
                        "turn sent " + total + " bytes outside its captured shape");
            }
        }
    }

    @Test
    void longChunksAreSplitBelowTheVanillaPayloadLimit() throws Exception {
        PaddingTurn turn = new PaddingTurn(
                PaddingTurn.Direction.SERVER_TO_CLIENT,
                200_000, 200_000, 0, 0,
                PaddingDelayRange.ZERO, PaddingDelayRange.ZERO, 0, 0, List.of());
        List<byte[]> payloads = new ArrayList<>();
        int total = PaddingFrames.writeTurn(payloads::add, turn, 0, NO_SLEEP, new Random(7));
        assertEquals(200_000, total);
        assertTrue(payloads.size() >= 7, "expected the chunk to be split");
        for (byte[] payload : payloads) {
            assertTrue(payload.length <= PaddingFrames.MAX_DATA_LENGTH,
                    "payload of " + payload.length + " bytes exceeds the vanilla limit");
        }
    }

    @Test
    void tinyChunksStillBecomeOnePayload() throws Exception {
        PaddingTurn turn = new PaddingTurn(
                PaddingTurn.Direction.SERVER_TO_CLIENT,
                2, 2, 0, 0,
                PaddingDelayRange.ZERO, PaddingDelayRange.ZERO, 0, 0, List.of());
        List<byte[]> payloads = new ArrayList<>();
        int total = PaddingFrames.writeTurn(payloads::add, turn, 0, NO_SLEEP, new Random(3));
        assertEquals(2, total);
        assertEquals(1, payloads.size());
        assertEquals(2, payloads.get(0).length);
    }

    @Test
    void firstTurnPrefixIsNotSent() throws Exception {
        PaddingTurn first = PaddingPreset26.startup().get(0);
        List<byte[]> payloads = new ArrayList<>();
        int total = PaddingFrames.writeTurn(payloads::add, first, 2, NO_SLEEP, new Random(11));
        int target = first.variants().get(0).totalLength();
        assertEquals(target - 2, total, "the Login Acknowledged bytes must not be repeated");
    }

    @Test
    void clientTurnsAreSkippedByTheServerSchedule() {
        Random random = new Random(5);
        List<PaddingTurn> schedule = PaddingPreset26.serverSchedule(random);
        long clientTurns = schedule.stream().filter(turn -> !turn.sendsFromServer()).count();
        assertTrue(clientTurns > 0, "the schedule must contain client turns");
        // The session only writes server turns; nothing else may be consumed.
        assertFalse(schedule.stream().allMatch(PaddingTurn::sendsFromServer));
    }
}

package xmc.core.padding;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Padding presets captured from controlled Minecraft 26.1.2 logins and ported
 * from Xray-core's padding_preset.go.
 *
 * <p>This file is generated data; keep it in sync with Xray-core when the
 * preset changes. Lengths are bytes, delays are milliseconds.
 */
public final class PaddingPreset26 {
    private static final int[][][] SERVER_SEND_RANGES = {
            {{346, 18812}, {388, 20689}, {41428, 65584}, {41428, 63563}, {51983, 51983}, {309, 19484}, {74, 40686}, {30, 44114}, {9941, 35818}, {35, 42885}, {37, 47553}, {26, 1121}, {11, 45629}, {55, 10035}, {427, 52536}, {35, 22708}},
            {{346, 18812}, {51445, 61077}, {575, 20915}, {41428, 63563}, {42, 26813}, {309, 19484}, {74, 40686}, {30, 44114}, {9941, 35818}, {35, 42885}, {37, 47553}, {16162, 60622}, {11, 45629}, {55, 10035}, {427, 52536}, {35, 22708}},
            {{51702, 58638}, {388, 20689}, {575, 20915}, {86, 2772}, {42, 26813}, {24837, 25083}, {74, 40686}, {30, 44114}, {26, 1464}, {35, 42885}, {37, 47553}, {16162, 60622}, {11, 45629}, {55, 10035}, {427, 52536}, {35, 22708}},
            {{51702, 58638}, {388, 20689}, {575, 20915}, {86, 2772}, {42, 26813}, {24837, 25083}, {74, 40686}, {30, 44114}, {9941, 35818}, {35, 42885}, {37, 47553}, {16162, 60622}, {11, 45629}, {30237, 62027}, {64014, 65622}, {38987, 59401}},
            {{346, 18812}, {51445, 61077}, {575, 20915}, {86, 2772}, {42, 26813}, {309, 19484}, {74, 40686}, {30, 44114}, {9941, 35818}, {35, 42885}, {37, 47553}, {26, 1121}, {11, 45629}, {30237, 62027}, {427, 52536}, {35, 22708}},
            {{346, 18812}, {388, 20689}, {41428, 65584}, {86, 2772}, {42, 26813}, {309, 19484}, {63885, 63885}, {30, 44114}, {26, 1464}, {35, 42885}, {37, 47553}, {16162, 60622}, {11, 45629}, {55, 10035}, {427, 52536}, {35, 22708}},
            {{51702, 58638}, {388, 20689}, {575, 20915}, {86, 2772}, {42, 26813}, {309, 19484}, {74, 40686}, {30, 44114}, {9941, 35818}, {35, 42885}, {37, 47553}, {26, 1121}, {11, 45629}, {30237, 62027}, {427, 52536}, {38987, 59401}},
            {{346, 18812}, {51445, 61077}, {41428, 65584}, {86, 2772}, {42, 26813}, {309, 19484}, {74, 40686}, {30, 44114}, {9941, 35818}, {35, 42885}, {37, 47553}, {26, 1121}, {60808, 60808}, {55, 10035}, {427, 52536}, {35, 22708}},
            {{346, 18812}, {388, 20689}, {575, 20915}, {86, 2772}, {42, 26813}, {309, 19484}, {74, 40686}, {30, 44114}, {26, 1464}, {52194, 59407}, {61765, 65328}, {16162, 60622}, {11, 45629}, {30237, 62027}, {427, 52536}, {35, 22708}},
            {{51702, 58638}, {388, 20689}, {575, 20915}, {86, 2772}, {42, 26813}, {309, 19484}, {74, 40686}, {30, 44114}, {9941, 35818}, {52194, 59407}, {37, 47553}, {16162, 60622}, {11, 45629}, {30237, 62027}, {427, 52536}, {38987, 59401}},
            {{346, 18812}, {388, 20689}, {575, 20915}, {86, 2772}, {42, 26813}, {309, 19484}, {74, 40686}, {30, 44114}, {9941, 35818}, {35, 42885}, {37, 47553}, {16162, 60622}, {11, 45629}, {30237, 62027}, {64014, 65622}, {38987, 59401}},
            {{51702, 58638}, {388, 20689}, {575, 20915}, {86, 2772}, {42, 26813}, {309, 19484}, {74, 40686}, {30, 44114}, {26, 1464}, {35, 42885}, {61765, 65328}, {16162, 60622}, {11, 45629}, {30237, 62027}, {427, 52536}, {38987, 59401}},
            {{51702, 58638}, {388, 20689}, {575, 20915}, {86, 2772}, {42, 26813}, {309, 19484}, {74, 40686}, {30, 44114}, {9941, 35818}, {52194, 59407}, {37, 47553}, {16162, 60622}, {11, 45629}, {30237, 62027}, {427, 52536}, {38987, 59401}},
            {{346, 18812}, {388, 20689}, {575, 20915}, {86, 2772}, {42, 26813}, {309, 19484}, {74, 40686}, {66128, 66128}, {9941, 35818}, {52194, 59407}, {37, 47553}, {16162, 60622}, {11, 45629}, {55, 10035}, {427, 52536}, {38987, 59401}},
            {{346, 18812}, {388, 20689}, {575, 20915}, {41428, 63563}, {42, 26813}, {309, 19484}, {74, 40686}, {30, 44114}, {26, 1464}, {35, 42885}, {37, 47553}, {16162, 60622}, {11, 45629}, {55, 10035}, {427, 52536}, {35, 22708}},
            {{51702, 58638}, {388, 20689}, {575, 20915}, {86, 2772}, {42, 26813}, {309, 19484}, {74, 40686}, {30, 44114}, {26, 1464}, {35, 42885}, {37, 47553}, {26, 1121}, {11, 45629}, {30237, 62027}, {427, 52536}, {35, 22708}},
            {{51702, 58638}, {388, 20689}, {575, 20915}, {86, 2772}, {42, 26813}, {309, 19484}, {74, 40686}, {30, 44114}, {9941, 35818}, {35, 42885}, {37, 47553}, {16162, 60622}, {11, 45629}, {55, 10035}, {427, 52536}, {35, 22708}},
            {{346, 18812}, {51445, 61077}, {575, 20915}, {41428, 63563}, {42, 26813}, {309, 19484}, {74, 40686}, {30, 44114}, {26, 1464}, {35, 42885}, {37, 47553}, {26, 1121}, {11, 45629}, {55, 10035}, {427, 52536}, {35, 22708}},
            {{51702, 58638}, {388, 20689}, {575, 20915}, {86, 2772}, {42, 26813}, {309, 19484}, {74, 40686}, {30, 44114}, {26, 1464}, {35, 42885}, {61765, 65328}, {16162, 60622}, {11, 45629}, {30237, 62027}, {427, 52536}, {35, 22708}},
            {{51702, 58638}, {388, 20689}, {575, 20915}, {86, 2772}, {42, 26813}, {309, 19484}, {74, 40686}, {30, 44114}, {26, 1464}, {35, 42885}, {37, 47553}, {16162, 60622}, {11, 45629}, {55, 10035}, {427, 52536}, {35, 22708}}
    };

    private PaddingPreset26() {
    }

    static List<PaddingTurn> startup() {
        return List.of(
            variants(PaddingTurn.Direction.CLIENT_TO_SERVER, 0, 0,
                    chunks(2, 26, 16)),
            variants(PaddingTurn.Direction.SERVER_TO_CLIENT, 0, 20,
                    chunks(26, 21, 25)),
            variants(PaddingTurn.Direction.CLIENT_TO_SERVER, 2, 22,
                    chunks(25)),
            variants(PaddingTurn.Direction.SERVER_TO_CLIENT, 20, 50,
                    paced(new int[]{1590, 226, 329, 229, 186, 151, 78, 81, 79, 235, 67, 67, 78, 71, 82, 74, 982, 117, 1118, 1038, 970, 400, 239, 49, 50, 95, 65, 104, 32320, 2}, new int[]{28, 29}, new int[]{1, 44}, new int[]{4, 61})),
            variants(PaddingTurn.Direction.CLIENT_TO_SERVER, 10, 35,
                    chunks(2)),
            variants(PaddingTurn.Direction.SERVER_TO_CLIENT, 35, 50,
                    paced(new int[]{4941, 252, 259, 267, 268, 251, 303, 259, 264, 54, 346}, new int[]{5, 10}, new int[]{1, 9}, new int[]{5, 20}),
                    paced(new int[]{4941, 262, 284, 272, 260, 260, 313, 264, 151, 224, 207, 215, 224, 390}, new int[]{7, 13}, new int[]{1, 9}, new int[]{5, 20}),
                    paced(new int[]{4941, 257, 272, 275, 260, 260, 313, 283, 274, 226, 207, 230, 215, 204, 221, 352}, new int[]{8, 15}, new int[]{1, 9}, new int[]{5, 20}),
                    paced(new int[]{4941, 259, 272, 288, 260, 260, 311, 270, 70, 236, 223, 201, 210, 352}, new int[]{7, 13}, new int[]{1, 9}, new int[]{5, 20}),
                    paced(new int[]{4941, 255, 269, 277, 263, 260, 136, 207, 210, 232, 325}, new int[]{5, 10}, new int[]{1, 9}, new int[]{5, 20}),
                    paced(new int[]{4941, 259, 270, 274, 263, 258, 327, 170, 210, 375}, new int[]{5, 9}, new int[]{1, 9}, new int[]{5, 20}),
                    paced(new int[]{4941, 257, 275, 291, 260, 260, 325, 269, 70, 230, 226, 207, 221, 352}, new int[]{7, 13}, new int[]{1, 9}, new int[]{5, 20}),
                    paced(new int[]{4941, 252, 273, 262, 252, 254, 306, 93}, new int[]{4, 7}, new int[]{1, 9}, new int[]{5, 20}),
                    paced(new int[]{4941, 273, 270, 269, 258, 256, 322, 221, 207, 215, 438}, new int[]{5, 10}, new int[]{1, 9}, new int[]{5, 20}),
                    paced(new int[]{4941, 259, 275, 274, 250, 258, 308, 267, 154, 233, 209, 207, 213, 393}, new int[]{7, 13}, new int[]{1, 9}, new int[]{5, 20}),
                    paced(new int[]{4941, 254, 267, 272, 260, 253, 311, 167, 204, 232, 207, 481, 8}, new int[]{6, 12}, new int[]{1, 9}, new int[]{5, 20}),
                    paced(new int[]{4941, 259, 269, 272, 261, 313, 207, 213, 500, 19}, new int[]{5, 9}, new int[]{1, 9}, new int[]{5, 20}),
                    paced(new int[]{4941, 262, 269, 274, 263, 274, 311, 270, 242, 210, 229, 221, 210, 431}, new int[]{7, 13}, new int[]{1, 9}, new int[]{5, 20}),
                    paced(new int[]{4941, 259, 265, 277, 263, 277, 316, 269, 156, 204, 210, 226, 207, 413}, new int[]{7, 13}, new int[]{1, 9}, new int[]{5, 20}),
                    paced(new int[]{4941, 215, 251, 249, 317, 260, 270, 249, 52}, new int[]{4, 8}, new int[]{1, 9}, new int[]{5, 20}),
                    paced(new int[]{4941, 224, 263, 277, 316, 267, 272, 260, 138, 230, 226, 207, 204, 352}, new int[]{7, 13}, new int[]{1, 9}, new int[]{5, 20}),
                    paced(new int[]{4941, 221, 258, 263, 319, 269, 288, 263, 136, 204, 210, 220, 207, 378}, new int[]{7, 13}, new int[]{1, 9}, new int[]{5, 20}),
                    paced(new int[]{4941, 221, 258, 260, 316, 273, 291, 226, 204, 229, 213, 489, 8}, new int[]{6, 12}, new int[]{1, 9}, new int[]{5, 20}),
                    paced(new int[]{4941, 238, 260, 261, 306, 272, 277, 260, 224, 241, 212, 207, 204, 393}, new int[]{7, 13}, new int[]{1, 9}, new int[]{5, 20}),
                    paced(new int[]{4941, 224, 260, 260, 309, 272, 277, 277, 138, 207, 207, 212, 241, 352}, new int[]{7, 13}, new int[]{1, 9}, new int[]{5, 20}))
        );
    }

    static List<PaddingTurn> playJoin() {
        return List.of(
            range(PaddingTurn.Direction.CLIENT_TO_SERVER, 6, 883, 1, 30, 0, 0, 0, 1024),
            range(PaddingTurn.Direction.SERVER_TO_CLIENT, 346, 58638, 1, 45, 1, 4, 32768, 49152),
            range(PaddingTurn.Direction.CLIENT_TO_SERVER, 6, 887, 1, 30, 0, 0, 0, 1024),
            range(PaddingTurn.Direction.SERVER_TO_CLIENT, 388, 61077, 1, 45, 1, 4, 32768, 49152),
            range(PaddingTurn.Direction.CLIENT_TO_SERVER, 2, 50, 1, 30, 0, 0, 0, 1024),
            range(PaddingTurn.Direction.SERVER_TO_CLIENT, 575, 65584, 1, 45, 1, 4, 32768, 49152),
            range(PaddingTurn.Direction.CLIENT_TO_SERVER, 6, 45, 1, 30, 0, 0, 0, 1024),
            range(PaddingTurn.Direction.SERVER_TO_CLIENT, 86, 63563, 1, 45, 1, 4, 32768, 49152),
            range(PaddingTurn.Direction.CLIENT_TO_SERVER, 2, 44, 1, 30, 0, 0, 0, 1024),
            range(PaddingTurn.Direction.SERVER_TO_CLIENT, 42, 51983, 1, 45, 1, 4, 32768, 49152),
            range(PaddingTurn.Direction.CLIENT_TO_SERVER, 2, 851, 1, 30, 0, 0, 0, 1024),
            range(PaddingTurn.Direction.SERVER_TO_CLIENT, 309, 25083, 1, 45, 1, 4, 32768, 49152),
            range(PaddingTurn.Direction.CLIENT_TO_SERVER, 2, 19, 1, 30, 0, 0, 0, 1024),
            range(PaddingTurn.Direction.SERVER_TO_CLIENT, 74, 63885, 1, 45, 1, 4, 32768, 49152),
            range(PaddingTurn.Direction.CLIENT_TO_SERVER, 8, 24, 1, 30, 0, 0, 0, 1024),
            range(PaddingTurn.Direction.SERVER_TO_CLIENT, 30, 66128, 1, 45, 1, 4, 32768, 49152),
            range(PaddingTurn.Direction.CLIENT_TO_SERVER, 2, 19, 1, 30, 0, 0, 0, 1024),
            range(PaddingTurn.Direction.SERVER_TO_CLIENT, 26, 35818, 1, 45, 1, 4, 32768, 49152),
            range(PaddingTurn.Direction.CLIENT_TO_SERVER, 6, 19, 1, 30, 0, 0, 0, 1024),
            range(PaddingTurn.Direction.SERVER_TO_CLIENT, 35, 59407, 1, 45, 1, 4, 32768, 49152),
            range(PaddingTurn.Direction.CLIENT_TO_SERVER, 6, 19, 1, 30, 0, 0, 0, 1024),
            range(PaddingTurn.Direction.SERVER_TO_CLIENT, 37, 65328, 1, 45, 1, 4, 32768, 49152),
            range(PaddingTurn.Direction.CLIENT_TO_SERVER, 2, 19, 1, 30, 0, 0, 0, 1024),
            range(PaddingTurn.Direction.SERVER_TO_CLIENT, 26, 60622, 1, 45, 1, 4, 32768, 49152),
            range(PaddingTurn.Direction.CLIENT_TO_SERVER, 6, 19, 1, 30, 0, 0, 0, 1024),
            range(PaddingTurn.Direction.SERVER_TO_CLIENT, 11, 60808, 1, 45, 1, 4, 32768, 49152),
            range(PaddingTurn.Direction.CLIENT_TO_SERVER, 8, 43, 1, 30, 0, 0, 0, 1024),
            range(PaddingTurn.Direction.SERVER_TO_CLIENT, 55, 62027, 1, 45, 1, 4, 32768, 49152),
            range(PaddingTurn.Direction.CLIENT_TO_SERVER, 2, 19, 1, 30, 0, 0, 0, 1024),
            range(PaddingTurn.Direction.SERVER_TO_CLIENT, 427, 65622, 1, 45, 1, 4, 32768, 49152),
            range(PaddingTurn.Direction.CLIENT_TO_SERVER, 5, 19, 1, 30, 0, 0, 0, 1024),
            range(PaddingTurn.Direction.SERVER_TO_CLIENT, 35, 59401, 1, 45, 1, 4, 32768, 49152),
            range(PaddingTurn.Direction.CLIENT_TO_SERVER, 6, 19, 1, 30, 0, 0, 0, 1024)
        );
    }

    public static List<PaddingTurn> full() {
        ArrayList<PaddingTurn> turns = new ArrayList<>(startup());
        turns.addAll(playJoin());
        return turns;
    }

    /**
     * Builds the server-side schedule: exactly like Xray-core, one of the 20
     * captured branch profiles selects the length range of every server-to-client
     * play-join turn.
     */
    public static List<PaddingTurn> serverSchedule(Random random) {
        List<PaddingTurn> schedule = new ArrayList<>(full());
        int[][] ranges = SERVER_SEND_RANGES[random.nextInt(SERVER_SEND_RANGES.length)];
        int playJoinStart = startup().size();
        for (int i = 0; i < ranges.length; i++) {
            int index = playJoinStart + 1 + i * 2;
            schedule.set(index, schedule.get(index).withSendRange(ranges[i][0], ranges[i][1]));
        }
        return schedule;
    }

    private static PaddingTurn variants(PaddingTurn.Direction direction, int startMinMs, int startMaxMs,
                                        PaddingVariant... variants) {
        return new PaddingTurn(direction, 0, 0, 0, 0,
                new PaddingDelayRange(startMinMs, startMaxMs), PaddingDelayRange.ZERO, 0, 0,
                List.of(variants));
    }

    private static PaddingTurn range(PaddingTurn.Direction direction, int minLength, int maxLength,
                                     int startMinMs, int startMaxMs, int chunkMinMs, int chunkMaxMs,
                                     int writeChunkMin, int writeChunkMax) {
        return new PaddingTurn(direction, minLength, maxLength, 0, 0,
                new PaddingDelayRange(startMinMs, startMaxMs),
                new PaddingDelayRange(chunkMinMs, chunkMaxMs),
                writeChunkMin, writeChunkMax, List.of());
    }

    private static PaddingVariant chunks(int... chunks) {
        return new PaddingVariant(chunks, null, null);
    }

    private static PaddingVariant paced(int[] chunks, int[] pauseIndices, int[] pauseMinMs, int[] pauseMaxMs) {
        int[] min = new int[chunks.length];
        int[] max = new int[chunks.length];
        for (int i = 0; i < pauseIndices.length; i++) {
            min[pauseIndices[i]] = pauseMinMs[i];
            max[pauseIndices[i]] = pauseMaxMs[i];
        }
        return new PaddingVariant(chunks, min, max);
    }
}

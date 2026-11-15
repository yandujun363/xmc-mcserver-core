package xmc.probe;

import xmc.core.padding.PaddingCodec;
import xmc.core.padding.PaddingPreset26;
import xmc.core.padding.PaddingTurn;
import xmc.core.protocol.FrameCodec;
import xmc.core.protocol.VarInts;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Developer-only integration probe against a running dev server.
 *
 * <pre>
 * ./gradlew probe          # XMC: Login Ack + padding + xmc:data relay
 * ./gradlew probeVanilla   # ordinary client: must reach the Play state
 * </pre>
 *
 * <p>The probe does not authenticate: the development server runs in offline
 * mode, which is enough to exercise everything the mod does (landing detection,
 * padding, channel relay). Real deployments need online-mode so that the XMC
 * client can complete a vanilla login.
 */
public final class XmcProbe {
    private static final int PROTOCOL = 775;
    private static final String HOST = "127.0.0.1";
    private static final int SERVER_PORT = 25565;
    private static final int TUNNEL_PORT = 10086;
    private static final UUID PROFILE_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private static final int LOGIN_SUCCESS = 0x02;
    private static final int LOGIN_ACKNOWLEDGED = 0x03;
    private static final int CONFIGURATION_CLIENTBOUND_CUSTOM_PAYLOAD = 0x01;
    private static final int CONFIGURATION_CLIENTBOUND_DISCONNECT = 0x02;
    private static final int CONFIGURATION_CLIENTBOUND_FINISH = 0x03;
    private static final int CONFIGURATION_CLIENTBOUND_KEEP_ALIVE = 0x04;
    private static final int CONFIGURATION_CLIENTBOUND_PING = 0x05;
    private static final int CONFIGURATION_CLIENTBOUND_SELECT_KNOWN_PACKS = 0x0e;
    private static final int CONFIGURATION_SERVERBOUND_CUSTOM_PAYLOAD = 0x02;
    private static final int CONFIGURATION_SERVERBOUND_FINISH = 0x03;
    private static final int CONFIGURATION_SERVERBOUND_KEEP_ALIVE = 0x04;
    private static final int CONFIGURATION_SERVERBOUND_PONG = 0x05;
    private static final int CONFIGURATION_SERVERBOUND_SELECT_KNOWN_PACKS = 0x07;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("vanilla")) {
            vanillaLanding();
            return;
        }
        tunnelledLanding();
    }

    // ---------------------------------------------------------------- XMC path

    private static void tunnelledLanding() throws Exception {
        ServerSocket tunnelServer = new ServerSocket();
        tunnelServer.bind(new InetSocketAddress(HOST, TUNNEL_PORT));
        Thread tunnelThread = new Thread(() -> {
            try (Socket tunnel = tunnelServer.accept()) {
                System.out.println("[probe] tunnel connected from " + tunnel.getRemoteSocketAddress());
                InputStream in = tunnel.getInputStream();
                OutputStream out = tunnel.getOutputStream();
                byte[] buffer = new byte[1024];
                int read = in.read(buffer);
                String received = new String(buffer, 0, read, StandardCharsets.UTF_8);
                System.out.println("[probe] tunnel received: " + received);
                out.write("HELLO-BACK".getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(2000);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "mock-xray");
        tunnelThread.setDaemon(true);
        tunnelThread.start();

        try (Socket socket = open()) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            handshake(out);
            loginStart(out, "Probe");
            expectLoginSuccess(in);
            System.out.println("[probe] login success; starting the XMC landing");

            FrameCodec.write(out, new byte[]{LOGIN_ACKNOWLEDGED});
            List<PaddingTurn> turns = PaddingPreset26.full();
            Random random = new Random(42);
            for (int i = 0; i < turns.size(); i++) {
                PaddingTurn turn = turns.get(i);
                if (turn.direction() == PaddingTurn.Direction.CLIENT_TO_SERVER) {
                    clientWritePadding(out, turn, i == 0 ? 2 : 0, random);
                } else {
                    PaddingCodec.readTurn(in, turn, i == 0 ? 2 : 0);
                }
            }
            System.out.println("[probe] padding complete; relaying through xmc:data");

            sendCustomPayload(out, "xmc:data", "HELLO-XMC");
            long deadline = System.currentTimeMillis() + 15000;
            while (System.currentTimeMillis() < deadline) {
                FrameCodec.Frame frame = FrameCodec.read(in, 1 << 20);
                ByteArrayInputStream body = new ByteArrayInputStream(frame.body());
                int id = VarInts.read(body);
                if (!isCustomPayload(id)) {
                    System.out.println("[probe] skipped packet id " + id);
                    continue;
                }
                String channel = readString(body);
                byte[] data = body.readAllBytes();
                System.out.println("[probe] tunnel payload: channel=" + channel + " data="
                        + new String(data, StandardCharsets.UTF_8));
                if (!"xmc:data".equals(channel) || !new String(data, StandardCharsets.UTF_8).equals("HELLO-BACK")) {
                    throw new IllegalStateException("unexpected tunnel payload");
                }
                break;
            }
        } finally {
            tunnelServer.close();
        }
        System.out.println("[probe] XMC landing OK");
    }

    // ------------------------------------------------------------ vanilla path

    private static void vanillaLanding() throws Exception {
        try (Socket socket = open()) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            handshake(out);
            loginStart(out, "VanillaProbe");
            expectLoginSuccess(in);
            System.out.println("[probe] login success; continuing as an ordinary client");

            FrameCodec.write(out, new byte[]{LOGIN_ACKNOWLEDGED});
            // Any valid configuration-state packet: the mod must hand the
            // connection back to vanilla.
            sendCustomPayload(out, "probe:hello", "x");

            long deadline = System.currentTimeMillis() + 30000;
            while (System.currentTimeMillis() < deadline) {
                FrameCodec.Frame frame = FrameCodec.read(in, 1 << 22);
                ByteArrayInputStream body = new ByteArrayInputStream(frame.body());
                int id = VarInts.read(body);
                if (id == CONFIGURATION_CLIENTBOUND_DISCONNECT) {
                    String reason = body.available() > 0 ? readString(body) : "<no reason>";
                    throw new IllegalStateException("server disconnected the ordinary client during configuration: "
                            + reason);
                }
                System.out.println("[probe] configuration packet id=" + id + " size=" + frame.body().length);
                if (id == CONFIGURATION_CLIENTBOUND_KEEP_ALIVE) {
                    long aliveId = readLong(body);
                    ByteArrayOutputStream answer = new ByteArrayOutputStream();
                    VarInts.write(answer, CONFIGURATION_SERVERBOUND_KEEP_ALIVE);
                    writeLong(answer, aliveId);
                    FrameCodec.write(out, answer.toByteArray());
                    continue;
                }
                if (id == CONFIGURATION_CLIENTBOUND_PING) {
                    ByteArrayOutputStream answer = new ByteArrayOutputStream();
                    VarInts.write(answer, CONFIGURATION_SERVERBOUND_PONG);
                    answer.write(body.readNBytes(4));
                    FrameCodec.write(out, answer.toByteArray());
                    continue;
                }
                if (id == CONFIGURATION_CLIENTBOUND_SELECT_KNOWN_PACKS) {
                    ByteArrayOutputStream answer = new ByteArrayOutputStream();
                    VarInts.write(answer, CONFIGURATION_SERVERBOUND_SELECT_KNOWN_PACKS);
                    VarInts.write(answer, 0);
                    FrameCodec.write(out, answer.toByteArray());
                    continue;
                }
                if (id == CONFIGURATION_CLIENTBOUND_FINISH) {
                    FrameCodec.write(out, new byte[]{CONFIGURATION_SERVERBOUND_FINISH});
                    break;
                }
            }

            FrameCodec.Frame play = FrameCodec.read(in, 1 << 22);
            int playId = VarInts.read(new ByteArrayInputStream(play.body()));
            System.out.println("[probe] first play-state packet id=" + playId);
        }
        System.out.println("[probe] vanilla landing OK (reached the Play stage)");
    }

    // ------------------------------------------------------------------ shared

    private static Socket open() throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(HOST, SERVER_PORT), 5000);
        socket.setSoTimeout(30000);
        socket.setTcpNoDelay(true);
        return socket;
    }

    private static void handshake(DataOutputStream out) throws Exception {
        ByteArrayOutputStream handshake = new ByteArrayOutputStream();
        VarInts.write(handshake, 0x00);
        VarInts.write(handshake, PROTOCOL);
        writeString(handshake, HOST);
        handshake.write((SERVER_PORT >> 8) & 0xFF);
        handshake.write(SERVER_PORT & 0xFF);
        VarInts.write(handshake, 2);
        FrameCodec.write(out, handshake.toByteArray());
    }

    private static void loginStart(DataOutputStream out, String username) throws Exception {
        ByteArrayOutputStream loginStart = new ByteArrayOutputStream();
        VarInts.write(loginStart, 0x00);
        writeString(loginStart, username);
        writeLong(loginStart, PROFILE_UUID.getMostSignificantBits());
        writeLong(loginStart, PROFILE_UUID.getLeastSignificantBits());
        FrameCodec.write(out, loginStart.toByteArray());
    }

    private static void expectLoginSuccess(DataInputStream in) throws Exception {
        FrameCodec.Frame frame = FrameCodec.read(in, 1 << 20);
        ByteArrayInputStream body = new ByteArrayInputStream(frame.body());
        int id = VarInts.read(body);
        if (id != LOGIN_SUCCESS) {
            throw new IllegalStateException("expected login success, got packet id " + id
                    + " (the dev server needs online-mode=false and network-compression-threshold=-1)");
        }
    }

    private static boolean isCustomPayload(int id) {
        return id == CONFIGURATION_CLIENTBOUND_CUSTOM_PAYLOAD;
    }

    private static void sendCustomPayload(DataOutputStream out, String channel, String data) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        VarInts.write(body, CONFIGURATION_SERVERBOUND_CUSTOM_PAYLOAD);
        writeString(body, channel);
        body.write(data.getBytes(StandardCharsets.UTF_8));
        FrameCodec.write(out, body.toByteArray());
    }

    private static void clientWritePadding(DataOutputStream out, PaddingTurn turn, int prefix, Random random)
            throws Exception {
        int target;
        if (!turn.variants().isEmpty()) {
            target = turn.variants().get(random.nextInt(turn.variants().size())).totalLength();
        } else {
            target = turn.minLength() + random.nextInt(turn.maxLength() - turn.minLength() + 1);
        }
        int recordLength = target - prefix;
        VarInts.write(out, recordLength);
        int headerLength = VarInts.size(recordLength);
        out.write(new byte[recordLength - headerLength]);
        out.flush();
    }

    private static void writeString(OutputStream out, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        VarInts.write(out, bytes.length);
        out.write(bytes);
    }

    private static String readString(InputStream in) throws Exception {
        int length = VarInts.read(in);
        byte[] bytes = in.readNBytes(length);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeLong(OutputStream out, long value) throws Exception {
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) (value >>> shift) & 0xFF);
        }
    }

    private static long readLong(InputStream in) throws Exception {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (in.read() & 0xFF);
        }
        return value;
    }
}

package xmc.probe;

import xmc.core.padding.PaddingFrames;
import xmc.core.padding.PaddingPreset26;
import xmc.core.padding.PaddingTurn;
import xmc.core.protocol.FrameCodec;
import xmc.core.protocol.VarInts;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Developer-only integration probe against a running dev server.
 *
 * <pre>
 * ./gradlew probe          # XMC: Login Ack + padding + xmc:data relay
 * ./gradlew probeVanilla   # ordinary client (offline mode): must reach Play
 * ./gradlew probeOnline    # ordinary client (online mode, real Yggdrasil login)
 * </pre>
 *
 * <p>{@code probe} does not authenticate and needs an offline-mode dev server.
 * {@code probeOnline} performs the full online-mode login (Yggdrasil
 * authenticate + session join + encryption) and then the ordinary
 * configuration handshake, so it verifies the same path a vanilla client takes
 * against an online-mode server.
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
        if (args.length > 0 && args[0].equals("vanilla-online")) {
            vanillaOnline();
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
                if (turn.direction() != PaddingTurn.Direction.CLIENT_TO_SERVER) {
                    // Native padding: the server's turns are ordinary custom
                    // payloads that the client simply does not read.
                    continue;
                }
                PaddingFrames.writeTurn(data -> {
                    try {
                        writePacket(out, CONFIGURATION_SERVERBOUND_CUSTOM_PAYLOAD, "xmc:pad", data);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }, turn, i == 0 ? 2 : 0, Thread::sleep, random);
            }
            out.flush();
            System.out.println("[probe] padding complete; relaying through xmc:data");

            sendCustomPayload(out, "xmc:data", "HELLO-XMC");
            long deadline = System.currentTimeMillis() + 30000;
            while (System.currentTimeMillis() < deadline) {
                FrameCodec.Frame frame = FrameCodec.read(in, 1 << 20);
                ByteArrayInputStream body = new ByteArrayInputStream(frame.body());
                int id = VarInts.read(body);
                if (id == CONFIGURATION_CLIENTBOUND_KEEP_ALIVE) {
                    long aliveId = readLong(body);
                    ByteArrayOutputStream answer = new ByteArrayOutputStream();
                    VarInts.write(answer, CONFIGURATION_SERVERBOUND_KEEP_ALIVE);
                    writeLong(answer, aliveId);
                    FrameCodec.write(out, answer.toByteArray());
                    continue;
                }
                if (id != CONFIGURATION_CLIENTBOUND_CUSTOM_PAYLOAD) {
                    continue;
                }
                String channel = readString(body);
                if (!"xmc:data".equals(channel)) {
                    // xmc:pad: the startup padding is ordinary traffic.
                    continue;
                }
                byte[] data = body.readAllBytes();
                System.out.println("[probe] tunnel payload: channel=" + channel + " data="
                        + new String(data, StandardCharsets.UTF_8));
                if (!new String(data, StandardCharsets.UTF_8).equals("HELLO-BACK")) {
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
            configurationToPlay(in, out);
            FrameCodec.Frame play = FrameCodec.read(in, 1 << 22);
            int playId = VarInts.read(new ByteArrayInputStream(play.body()));
            System.out.println("[probe] first play-state packet id=" + playId);
        }
        System.out.println("[probe] vanilla landing OK (reached the Play stage)");
    }

    // ----------------------------------------------------- vanilla online path

    /**
     * Full online-mode login: Yggdrasil authenticate, encryption request,
     * session join, encrypted login, then the ordinary configuration phase.
     * Credentials come from XMC_TEST_EMAIL / XMC_TEST_PASSWORD / XMC_TEST_API.
     */
    private static void vanillaOnline() throws Exception {
        String email = env("XMC_TEST_EMAIL");
        String password = env("XMC_TEST_PASSWORD");
        String api = env("XMC_TEST_API");
        if (email == null || password == null) {
            throw new IllegalStateException(
                    "set XMC_TEST_EMAIL and XMC_TEST_PASSWORD (XMC_TEST_API defaults to "
                            + "http://127.0.0.1:8080/api/yggdrasil)");
        }

        JsonObject auth = new JsonObject();
        auth.addProperty("username", email);
        auth.addProperty("password", password);
        auth.addProperty("clientToken", "xmc-probe");
        JsonObject authenticated = postJson(api + "/authserver/authenticate", auth);
        String accessToken = authenticated.get("accessToken").getAsString();
        JsonObject profile = authenticated.getAsJsonObject("selectedProfile");
        String playerName = profile.get("name").getAsString();
        UUID playerUuid = uuidFromHex(profile.get("id").getAsString());
        System.out.println("[probe] authenticated as " + playerName + " (" + playerUuid + ")");

        try (Socket socket = open()) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            handshake(out);
            ByteArrayOutputStream loginStart = new ByteArrayOutputStream();
            VarInts.write(loginStart, 0x00);
            writeString(loginStart, playerName);
            writeLong(loginStart, playerUuid.getMostSignificantBits());
            writeLong(loginStart, playerUuid.getLeastSignificantBits());
            FrameCodec.write(out, loginStart.toByteArray());

            FrameCodec.Frame request = FrameCodec.read(in, 1 << 20);
            ByteArrayInputStream requestBody = new ByteArrayInputStream(request.body());
            int requestId = VarInts.read(requestBody);
            if (requestId == 0x03) {
                throw new IllegalStateException("server asked for login compression; set "
                        + "network-compression-threshold=-1");
            }
            if (requestId != 0x01) {
                throw new IllegalStateException("expected encryption request, got packet id " + requestId
                        + " (is online-mode enabled?)");
            }
            String serverId = readString(requestBody);
            byte[] publicKey = readBytes(requestBody);
            byte[] verifyToken = readBytes(requestBody);
            requestBody.readNBytes(1); // shouldAuthenticate

            byte[] secret = new byte[16];
            new SecureRandom().nextBytes(secret);
            PublicKey rsaKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(publicKey));
            Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsa.init(Cipher.ENCRYPT_MODE, rsaKey);
            byte[] encryptedSecret = rsa.doFinal(secret);
            byte[] encryptedToken = rsa.doFinal(verifyToken);

            String serverHash = minecraftServerId(serverId, secret, publicKey);
            JsonObject join = new JsonObject();
            join.addProperty("accessToken", accessToken);
            join.addProperty("selectedProfile", profile.get("id").getAsString());
            join.addProperty("serverId", serverHash);
            postJson(api + "/sessionserver/session/minecraft/join", join);
            System.out.println("[probe] joined the session (serverId hash " + serverHash + ")");

            ByteArrayOutputStream response = new ByteArrayOutputStream();
            VarInts.write(response, 0x01);
            writeBytes(response, encryptedSecret);
            writeBytes(response, encryptedToken);
            FrameCodec.write(out, response.toByteArray());

            Cipher decrypt = Cipher.getInstance("AES/CFB8/NoPadding");
            decrypt.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secret, "AES"),
                    new IvParameterSpec(secret));
            Cipher encrypt = Cipher.getInstance("AES/CFB8/NoPadding");
            encrypt.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secret, "AES"),
                    new IvParameterSpec(secret));
            in = new DataInputStream(new Cfb8InputStream(socket.getInputStream(), decrypt));
            out = new DataOutputStream(new Cfb8OutputStream(socket.getOutputStream(), encrypt));

            FrameCodec.Frame success = FrameCodec.read(in, 1 << 20);
            int successId = VarInts.read(new ByteArrayInputStream(success.body()));
            if (successId != LOGIN_SUCCESS) {
                throw new IllegalStateException("expected login success, got packet id " + successId);
            }
            System.out.println("[probe] login success; continuing as an ordinary online client");

            FrameCodec.write(out, new byte[]{LOGIN_ACKNOWLEDGED});
            sendCustomPayload(out, "probe:hello", "x");
            configurationToPlay(in, out);
        }
        System.out.println("[probe] online vanilla landing OK (reached the Play stage)");
    }

    private static void configurationToPlay(DataInputStream in, DataOutputStream out) throws Exception {
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

    private static JsonObject postJson(String url, JsonObject payload) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(url + " -> " + response.statusCode() + " " + response.body());
        }
        String body = response.body();
        if (body == null || body.isBlank()) {
            return new JsonObject();
        }
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private static String minecraftServerId(String serverId, byte[] secret, byte[] publicKey)
            throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(serverId.getBytes(StandardCharsets.UTF_8));
        digest.update(secret);
        digest.update(publicKey);
        return new BigInteger(digest.digest()).toString(16);
    }

    private static UUID uuidFromHex(String hex) {
        String plain = hex.replace("-", "");
        long most = Long.parseUnsignedLong(plain.substring(0, 16), 16);
        long least = Long.parseUnsignedLong(plain.substring(16), 16);
        return new UUID(most, least);
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            if (name.equals("XMC_TEST_API")) {
                return "http://127.0.0.1:8080/api/yggdrasil";
            }
            return null;
        }
        return value;
    }

    private static byte[] readBytes(InputStream in) throws Exception {
        int length = VarInts.read(in);
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new IllegalStateException("truncated byte array");
        }
        return bytes;
    }

    private static void writeBytes(OutputStream out, byte[] bytes) throws Exception {
        VarInts.write(out, bytes.length);
        out.write(bytes);
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

    private static void writePacket(OutputStream out, int packetId, String channel, byte[] data)
            throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream(data.length + 16);
        VarInts.write(body, packetId);
        writeString(body, channel);
        body.write(data);
        FrameCodec.write(out, body.toByteArray());
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

    // ----------------------------------------------------------- CFB8 streams

    /**
     * The JDK CipherInputStream can block on CFB8 while the underlying socket
     * already has bytes, so both directions do the cipher.update() themselves.
     */
    private static final class Cfb8InputStream extends InputStream {
        private final InputStream in;
        private final Cipher cipher;
        private final byte[] single = new byte[1];

        Cfb8InputStream(InputStream in, Cipher cipher) {
            this.in = in;
            this.cipher = cipher;
        }

        @Override
        public int read() throws java.io.IOException {
            int read = read(single, 0, 1);
            return read < 0 ? -1 : single[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws java.io.IOException {
            int read = in.read(buffer, offset, length);
            if (read <= 0) {
                return read;
            }
            try {
                cipher.update(buffer, offset, read, buffer, offset);
            } catch (Exception e) {
                throw new java.io.IOException("decrypt failed", e);
            }
            return read;
        }

        @Override
        public int available() throws java.io.IOException {
            return in.available();
        }
    }

    private static final class Cfb8OutputStream extends OutputStream {
        private final OutputStream out;
        private final Cipher cipher;
        private final byte[] single = new byte[1];

        Cfb8OutputStream(OutputStream out, Cipher cipher) {
            this.out = out;
            this.cipher = cipher;
        }

        @Override
        public void write(int value) throws java.io.IOException {
            single[0] = (byte) value;
            write(single, 0, 1);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws java.io.IOException {
            if (length == 0) {
                return;
            }
            byte[] encrypted = new byte[length];
            try {
                cipher.update(buffer, offset, length, encrypted, 0);
            } catch (Exception e) {
                throw new java.io.IOException("encrypt failed", e);
            }
            out.write(encrypted, 0, length);
            out.flush();
        }

        @Override
        public void flush() throws java.io.IOException {
            out.flush();
        }
    }
}

package xmc.probe;

import xmc.core.padding.PaddingCodec;
import xmc.core.padding.PaddingPreset26;
import xmc.core.padding.PaddingTurn;
import xmc.core.protocol.FrameCodec;
import xmc.core.protocol.VarInts;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
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
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Developer-only integration probe. It speaks the Xray XMC client protocol
 * against a running dev server and a mock local "Xray" TCP endpoint.
 *
 * <pre>
 * ./gradlew probe
 * </pre>
 */
public final class XmcProbe {
    private static final int PROTOCOL = 775;
    private static final String PASSWORD = "test-password-123";
    private static final String USERNAME = "Probe";
    private static final UUID PROFILE_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final String HOST = "127.0.0.1";
    private static final int SERVER_PORT = 25565;
    private static final int TUNNEL_PORT = 10086;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("vanilla")) {
            vanillaLogin();
            return;
        }

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

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, SERVER_PORT), 5000);
            socket.setSoTimeout(15000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            VarInts.write(handshake, 0x00);
            VarInts.write(handshake, PROTOCOL);
            writeString(handshake, HOST);
            handshake.write((SERVER_PORT >> 8) & 0xFF);
            handshake.write(SERVER_PORT & 0xFF);
            VarInts.write(handshake, 2);
            FrameCodec.write(out, handshake.toByteArray());

            ByteArrayOutputStream loginStart = new ByteArrayOutputStream();
            VarInts.write(loginStart, 0x00);
            writeString(loginStart, USERNAME);
            writeLong(loginStart, PROFILE_UUID.getMostSignificantBits());
            writeLong(loginStart, PROFILE_UUID.getLeastSignificantBits());
            FrameCodec.write(out, loginStart.toByteArray());

            FrameCodec.Frame hello = FrameCodec.read(in, 1 << 20);
            ByteArrayInputStream helloBody = new ByteArrayInputStream(hello.body());
            int helloId = VarInts.read(helloBody);
            String serverId = readString(helloBody);
            byte[] publicKeyBytes = helloBody.readNBytes(VarInts.read(helloBody));
            byte[] verifyToken = helloBody.readNBytes(VarInts.read(helloBody));
            boolean shouldAuthenticate = helloBody.read() != 0;
            System.out.println("[probe] encryption request id=" + helloId + " serverId='" + serverId
                    + "' token=" + verifyToken.length + " auth=" + shouldAuthenticate);

            PublicKey serverKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(publicKeyBytes));
            byte[] secret = new byte[16];
            new SecureRandom().nextBytes(secret);
            byte[] tokenWithPassword = concat(verifyToken, PASSWORD.getBytes(StandardCharsets.UTF_8));
            byte[] encryptedSecret = rsa(serverKey, secret);
            byte[] encryptedToken = rsa(serverKey, tokenWithPassword);

            ByteArrayOutputStream response = new ByteArrayOutputStream();
            VarInts.write(response, 0x01);
            writeByteArray(response, encryptedSecret);
            writeByteArray(response, encryptedToken);
            FrameCodec.write(out, response.toByteArray());

            Cipher encrypt = Cipher.getInstance("AES/CFB8/NoPadding");
            encrypt.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secret, "AES"), new IvParameterSpec(secret));
            Cipher decrypt = Cipher.getInstance("AES/CFB8/NoPadding");
            decrypt.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secret, "AES"), new IvParameterSpec(secret));

            DataInputStream secureIn = new DataInputStream(new EncryptedInputStream(socket.getInputStream(), decrypt));
            DataOutputStream secureOut = new DataOutputStream(new EncryptedOutputStream(socket.getOutputStream(), encrypt));

            byte[] first = new byte[8];
            int firstRead = secureIn.read(first, 0, first.length);
            System.out.println("[probe] first decrypted bytes: "
                    + java.util.HexFormat.of().formatHex(java.util.Arrays.copyOf(first, Math.max(firstRead, 0))));
            DataInputStream bufferedIn = new DataInputStream(new java.io.SequenceInputStream(
                    new ByteArrayInputStream(first, 0, Math.max(firstRead, 0)), secureIn));

            FrameCodec.Frame success = FrameCodec.read(bufferedIn, 1 << 20);
            ByteArrayInputStream successBody = new ByteArrayInputStream(success.body());
            int successId = VarInts.read(successBody);
            byte[] uuid = successBody.readNBytes(16);
            String name = readString(successBody);
            int properties = VarInts.read(successBody);
            for (int i = 0; i < properties; i++) {
                readString(successBody);
                readString(successBody);
                if (successBody.read() != 0) {
                    readString(successBody);
                }
            }
            System.out.println("[probe] login success id=" + successId + " name=" + name
                    + " uuid=" + new UUID(bytesToLong(uuid, 0), bytesToLong(uuid, 8)));

            FrameCodec.write(secureOut, new byte[]{0x03});
            secureOut.flush();

            List<PaddingTurn> turns = PaddingPreset26.full();
            System.out.println("[probe] starting padding at " + System.currentTimeMillis());
            Random random = new Random(42);
            for (int i = 0; i < turns.size(); i++) {
                PaddingTurn turn = turns.get(i);
                if (turn.direction() == PaddingTurn.Direction.CLIENT_TO_SERVER) {
                    int prefix = i == 0 ? 2 : 0;
                    clientWritePadding(secureOut, turn, prefix, random);
                } else {
                    PaddingCodec.readTurn(secureIn, turn, i == 0 ? 2 : 0);
                }
            }
            secureOut.flush();
            System.out.println("[probe] padding complete");

            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            VarInts.write(payload, 0x02);
            writeString(payload, "xmc:data");
            payload.write("HELLO-XMC".getBytes(StandardCharsets.UTF_8));
            FrameCodec.write(secureOut, payload.toByteArray());
            secureOut.flush();

            long deadline = System.currentTimeMillis() + 10000;
            while (System.currentTimeMillis() < deadline) {
                FrameCodec.Frame frame = FrameCodec.read(secureIn, 1 << 20);
                ByteArrayInputStream body = new ByteArrayInputStream(frame.body());
                int id = VarInts.read(body);
                if (id == 0x04) {
                    System.out.println("[probe] keep-alive");
                    continue;
                }
                if (id != 0x01) {
                    System.out.println("[probe] unexpected packet id " + id);
                    continue;
                }
                String channel = readString(body);
                byte[] data = body.readAllBytes();
                System.out.println("[probe] tunnel payload: channel=" + channel + " data="
                        + new String(data, StandardCharsets.UTF_8));
                break;
            }
        }
        tunnelServer.close();
        System.out.println("[probe] OK");
    }

    static final class EncryptedInputStream extends InputStream {
        private final InputStream in;
        private final Cipher cipher;
        private byte[] buffer = new byte[0];
        private int position;

        EncryptedInputStream(InputStream in, Cipher cipher) {
            this.in = in;
            this.cipher = cipher;
        }

        @Override
        public int read() throws java.io.IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read <= 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws java.io.IOException {
            if (length == 0) {
                return 0;
            }
            while (position >= buffer.length) {
                byte[] encrypted = new byte[512];
                int read = in.read(encrypted);
                if (read < 0) {
                    return -1;
                }
                buffer = cipher.update(encrypted, 0, read);
                position = 0;
                if (buffer == null) {
                    buffer = new byte[0];
                }
            }
            int count = Math.min(length, buffer.length - position);
            System.arraycopy(buffer, position, target, offset, count);
            position += count;
            return count;
        }
    }

    static final class EncryptedOutputStream extends OutputStream {
        private final OutputStream out;
        private final Cipher cipher;

        EncryptedOutputStream(OutputStream out, Cipher cipher) {
            this.out = out;
            this.cipher = cipher;
        }

        @Override
        public void write(int value) throws java.io.IOException {
            write(new byte[]{(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws java.io.IOException {
            byte[] encrypted = cipher.update(bytes, offset, length);
            if (encrypted != null && encrypted.length > 0) {
                out.write(encrypted);
            }
        }

        @Override
        public void flush() throws java.io.IOException {
            out.flush();
        }
    }

    /**
     * Connects like an ordinary offline-mode Minecraft client: a four-byte
     * verify token and no XMC password. The mod must complete the offline login
     * and hand the connection to the vanilla Configuration phase.
     */
    private static void vanillaLogin() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, SERVER_PORT), 5000);
            socket.setSoTimeout(10000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            VarInts.write(handshake, 0x00);
            VarInts.write(handshake, PROTOCOL);
            writeString(handshake, HOST);
            handshake.write((SERVER_PORT >> 8) & 0xFF);
            handshake.write(SERVER_PORT & 0xFF);
            VarInts.write(handshake, 2);
            FrameCodec.write(out, handshake.toByteArray());

            ByteArrayOutputStream loginStart = new ByteArrayOutputStream();
            VarInts.write(loginStart, 0x00);
            writeString(loginStart, "VanillaProbe");
            writeLong(loginStart, 0L);
            writeLong(loginStart, 0x1122334455667788L);
            FrameCodec.write(out, loginStart.toByteArray());

            FrameCodec.Frame hello = FrameCodec.read(in, 1 << 20);
            ByteArrayInputStream helloBody = new ByteArrayInputStream(hello.body());
            VarInts.read(helloBody);
            readString(helloBody);
            byte[] publicKeyBytes = helloBody.readNBytes(VarInts.read(helloBody));
            byte[] verifyToken = helloBody.readNBytes(VarInts.read(helloBody));
            helloBody.read();
            PublicKey serverKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(publicKeyBytes));

            byte[] secret = new byte[16];
            new SecureRandom().nextBytes(secret);
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            VarInts.write(response, 0x01);
            writeByteArray(response, rsa(serverKey, secret));
            writeByteArray(response, rsa(serverKey, verifyToken));
            FrameCodec.write(out, response.toByteArray());

            Cipher encrypt = Cipher.getInstance("AES/CFB8/NoPadding");
            encrypt.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secret, "AES"), new IvParameterSpec(secret));
            Cipher decrypt = Cipher.getInstance("AES/CFB8/NoPadding");
            decrypt.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secret, "AES"), new IvParameterSpec(secret));
            DataInputStream secureIn = new DataInputStream(new EncryptedInputStream(socket.getInputStream(), decrypt));
            DataOutputStream secureOut = new DataOutputStream(new EncryptedOutputStream(socket.getOutputStream(), encrypt));

            FrameCodec.Frame success = FrameCodec.read(secureIn, 1 << 20);
            ByteArrayInputStream successBody = new ByteArrayInputStream(success.body());
            int successId = VarInts.read(successBody);
            System.out.println("[probe] vanilla login success id=" + successId);
            FrameCodec.write(secureOut, new byte[]{0x03});
            secureOut.flush();

            FrameCodec.Frame configuration = FrameCodec.read(secureIn, 1 << 20);
            int configurationId = VarInts.read(new ByteArrayInputStream(configuration.body()));
            System.out.println("[probe] vanilla configuration packet id=" + configurationId);
            if (configurationId == 0x00) {
                throw new IllegalStateException("server disconnected during configuration");
            }
            System.out.println("[probe] vanilla OK");
        }
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

    private static byte[] rsa(PublicKey key, byte[] data) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return cipher.doFinal(data);
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

    private static void writeByteArray(OutputStream out, byte[] value) throws Exception {
        VarInts.write(out, value.length);
        out.write(value);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static long bytesToLong(byte[] bytes, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (bytes[offset + i] & 0xFF);
        }
        return value;
    }
}

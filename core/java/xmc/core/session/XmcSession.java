package xmc.core.session;

import xmc.config.XmcConfig;
import xmc.core.XmcMod;
import xmc.core.padding.PaddingCodec;
import xmc.core.padding.PaddingPreset26;
import xmc.core.padding.PaddingTurn;
import xmc.core.protocol.FrameCodec;
import xmc.core.protocol.VarInts;
import xmc.lib.net.RawConnection;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Server side of one XMC tunnel:
 *
 * <ol>
 *   <li>run the captured startup/play-join padding schedule (the Login
 *       Acknowledged packet itself was already consumed by vanilla),</li>
 *   <li>connect to the local Xray VLESS inbound and relay opaque bytes.</li>
 * </ol>
 *
 * The relay never parses VLESS; it only splits the byte stream into
 * {@code xmc:data} configuration payload packets (and answers keep-alives).
 */
public final class XmcSession implements Runnable {
    /** Wire length of the vanilla Login Acknowledged packet: VarInt(1) + packet id. */
    private static final int LOGIN_ACKNOWLEDGED_WIRE_LENGTH = 2;
    private static final int CONFIGURATION_CLIENTBOUND_CUSTOM_PAYLOAD = 0x01;
    private static final int CONFIGURATION_SERVERBOUND_CUSTOM_PAYLOAD = 0x02;
    private static final int CONFIGURATION_KEEP_ALIVE = 0x04;
    private static final int MAX_RAW_FRAME = (1 << 21) - 1;
    private static final int SOCKET_BUFFER = 16 * 1024;
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "xmc-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    private final RawConnection connection;
    private final XmcConfig config;
    private final String playerName;
    private final Consumer<XmcSession> onClose;
    private final Random random = new Random();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final PaddingCodec.Sleeper sleeper = Thread::sleep;

    private volatile Socket tunnel;
    private volatile boolean paddingDone;
    private long keepAliveId;

    public XmcSession(RawConnection connection, XmcConfig config, String playerName,
                      Consumer<XmcSession> onClose) {
        this.connection = connection;
        this.config = config;
        this.playerName = playerName;
        this.onClose = onClose;
    }

    public void start() {
        Thread thread = new Thread(this, "xmc-session " + connection.remoteAddress());
        thread.setDaemon(true);
        thread.start();
    }

    public void close() {
        stop();
    }

    @Override
    public void run() {
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(connection.inputStream(), SOCKET_BUFFER));
            DataOutputStream out = new DataOutputStream(connection.outputStream());
            ScheduledFuture<?> handshakeTimeout = WATCHDOG.schedule(() -> {
                if (!paddingDone) {
                    XmcMod.LOGGER.debug("XMC session {} timed out during handshake", connection.remoteAddress());
                    connection.close();
                }
            }, config.handshakeTimeoutSeconds(), TimeUnit.SECONDS);
            try {
                runPadding(in, out);
            } finally {
                handshakeTimeout.cancel(false);
            }
            paddingDone = true;
            Socket socket = TunnelConnector.connect(config.tunnel());
            tunnel = socket;
            runTunnel(in, out, socket);
        } catch (Throwable failure) {
            if (!stopped.get()) {
                if (failure instanceof IOException) {
                    XmcMod.LOGGER.debug("XMC session {} closed: {}", connection.remoteAddress(), failure.toString());
                } else {
                    XmcMod.LOGGER.warn("XMC session {} failed", connection.remoteAddress(), failure);
                }
            }
        } finally {
            closeQuietly(tunnel);
            connection.close();
            onClose.accept(this);
        }
    }

    private void runPadding(DataInputStream in, DataOutputStream out)
            throws IOException, InterruptedException {
        List<PaddingTurn> schedule = PaddingPreset26.serverSchedule(random);
        for (int index = 0; index < schedule.size(); index++) {
            PaddingTurn turn = schedule.get(index);
            // The first client padding turn counts the 2 wire bytes of the Login
            // Acknowledged packet as its prefix, exactly like Xray-core.
            int prefixLength = index == 0 ? LOGIN_ACKNOWLEDGED_WIRE_LENGTH : 0;
            if (turn.sendsFromServer()) {
                PaddingCodec.writeTurn(out, turn, prefixLength, sleeper, random);
            } else {
                PaddingCodec.readTurn(in, turn, prefixLength);
            }
        }
        out.flush();
        XmcMod.LOGGER.debug("XMC session {} padding complete ({} turns)", connection.remoteAddress(), schedule.size());
    }

    private void runTunnel(DataInputStream in, DataOutputStream out, Socket socket) throws IOException {
        socket.setSoTimeout(Math.max(1000, config.keepAliveSeconds() * 1000));
        InputStream socketIn = new BufferedInputStream(socket.getInputStream(), SOCKET_BUFFER);
        OutputStream socketOut = new BufferedOutputStream(socket.getOutputStream(), SOCKET_BUFFER);

        Thread writer = new Thread(() -> writerLoop(socketIn, out), "xmc-writer " + connection.remoteAddress());
        writer.setDaemon(true);
        writer.start();

        try {
            while (!stopped.get()) {
                FrameCodec.Frame frame = FrameCodec.read(in, MAX_RAW_FRAME);
                handleClientFrame(frame.body(), socketOut);
            }
        } finally {
            stop();
            writer.interrupt();
        }
    }

    private void writerLoop(InputStream socketIn, DataOutputStream out) {
        byte[] buffer = new byte[Math.min(config.maxPayloadBytes(), SOCKET_BUFFER)];
        try {
            while (!stopped.get()) {
                int read;
                try {
                    read = socketIn.read(buffer);
                } catch (SocketTimeoutException timeout) {
                    writeKeepAlive(out);
                    continue;
                }
                if (read < 0) {
                    return;
                }
                for (int offset = 0; offset < read; offset += config.maxPayloadBytes()) {
                    int count = Math.min(read - offset, config.maxPayloadBytes());
                    writeCustomPayload(out, buffer, offset, count);
                }
            }
        } catch (IOException failure) {
            XmcMod.LOGGER.warn("XMC tunnel writer {} ended: {}", connection.remoteAddress(), failure.toString());
        } finally {
            stop();
        }
    }

    private void handleClientFrame(byte[] body, OutputStream socketOut) throws IOException {
        ByteArrayInputStream input = new ByteArrayInputStream(body);
        int packetId = VarInts.read(input);
        if (packetId == CONFIGURATION_KEEP_ALIVE) {
            return;
        }
        if (packetId != CONFIGURATION_SERVERBOUND_CUSTOM_PAYLOAD) {
            return;
        }
        String channel = readString(input, 32_767);
        if (!config.channel().equals(channel)) {
            return;
        }
        byte[] payload = input.readAllBytes();
        if (payload.length == 0) {
            return;
        }
        socketOut.write(payload);
        socketOut.flush();
    }

    private void writeCustomPayload(DataOutputStream out, byte[] bytes, int offset, int length) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream(length + 16);
        VarInts.write(body, CONFIGURATION_CLIENTBOUND_CUSTOM_PAYLOAD);
        writeString(body, config.channel());
        body.write(bytes, offset, length);
        FrameCodec.write(out, body.toByteArray());
    }

    private void writeKeepAlive(DataOutputStream out) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream(9);
        VarInts.write(body, CONFIGURATION_KEEP_ALIVE);
        long id = ++keepAliveId;
        for (int shift = 56; shift >= 0; shift -= 8) {
            body.write((int) (id >>> shift) & 0xFF);
        }
        FrameCodec.write(out, body.toByteArray());
    }

    private void stop() {
        if (stopped.compareAndSet(false, true)) {
            closeQuietly(tunnel);
            connection.close();
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    private static String readString(InputStream in, int maximumBytes) throws IOException {
        int length = VarInts.read(in);
        if (length < 0 || length > maximumBytes) {
            throw new IOException("invalid string length: " + length);
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("truncated string");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(OutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        VarInts.write(out, bytes.length);
        out.write(bytes);
    }

    @Override
    public String toString() {
        return "XmcSession[" + playerName + " @ " + connection.remoteAddress() + "]";
    }
}

package xmc.core.session;

import net.minecraft.network.chat.Component;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import xmc.config.XmcConfig;
import xmc.core.XmcMod;
import xmc.core.padding.PaddingFrames;
import xmc.core.padding.PaddingPreset26;
import xmc.core.padding.PaddingTurn;
import xmc.lib.net.XmcNetworking;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * One XMC tunnel on the server side.
 *
 * <p>The connection already finished a vanilla login and is parked in the
 * configuration state, so this class never sees or writes any login packet. It
 * only:
 *
 * <ol>
 *   <li>sends the captured server-to-client startup padding, framed as ordinary
 *       custom payloads on the configured padding channel,</li>
 *   <li>connects to the local Xray VLESS inbound,</li>
 *   <li>relays opaque bytes in both directions through the configured data
 *       channel.</li>
 * </ol>
 *
 * <p>The client's own padding arrives on the padding channel and is dropped
 * by vanilla before it reaches the mod, so it is never read here.
 */
public final class XmcSession {
    private static final int MAX_PENDING_PAYLOADS = 256;
    private static final int SOCKET_BUFFER = 16 * 1024;

    private final ServerConfigurationPacketListenerImpl listener;
    private final XmcConfig config;
    private final String playerName;
    private final Consumer<XmcSession> onClose;
    private final Random random = new Random();
    private final BlockingQueue<byte[]> inbound = new LinkedBlockingQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final PaddingFrames.Sleeper sleeper = Thread::sleep;

    private volatile Socket tunnel;

    public XmcSession(ServerConfigurationPacketListenerImpl listener,
                      XmcConfig config, String playerName, Consumer<XmcSession> onClose) {
        this.listener = listener;
        this.config = config;
        this.playerName = playerName;
        this.onClose = onClose;
    }

    public void start() {
        Thread thread = new Thread(this::run, "xmc-session " + playerName);
        thread.setDaemon(true);
        thread.start();
    }

    /** Called on the server thread for every data-channel payload. */
    public void offer(byte[] data) {
        if (closed.get()) {
            return;
        }
        if (inbound.size() >= MAX_PENDING_PAYLOADS) {
            XmcMod.LOGGER.warn("XMC session {}: tunnel is not draining, closing", playerName);
            close();
            return;
        }
        inbound.add(data);
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Socket socket = tunnel;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
        try {
            onClose.accept(this);
        } catch (RuntimeException failure) {
            XmcMod.LOGGER.warn("XMC session {}: close callback failed", playerName, failure);
        }
        if (listener.isAcceptingMessages()) {
            listener.disconnect(Component.literal("XMC tunnel closed"));
        }
    }

    private void run() {
        try {
            sendStartupPadding();
            Socket socket = TunnelConnector.connect(config.tunnel());
            tunnel = socket;
            if (closed.get()) {
                socket.close();
                return;
            }
            XmcMod.LOGGER.info("XMC tunnel established for {}", playerName);
            startTunnelReader(socket);
            forwardInbound(socket);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable failure) {
            if (!closed.get()) {
                XmcMod.LOGGER.warn("XMC session {} failed", playerName, failure);
            }
        } finally {
            close();
        }
    }

    private void sendStartupPadding() throws InterruptedException {
        List<PaddingTurn> schedule = PaddingPreset26.serverSchedule(random);
        for (PaddingTurn turn : schedule) {
            if (!turn.sendsFromServer()) {
                // Client-to-server turns are ordinary Minecraft traffic handled by
                // vanilla; the mod must not consume them.
                continue;
            }
            PaddingFrames.writeTurn(this::sendPadding, turn, 0, sleeper, random);
        }
    }

    private void sendPadding(byte[] data) {
        if (!XmcNetworking.sendPadding(listener, data)) {
            throw new IllegalStateException("configuration listener stopped accepting messages");
        }
    }

    private void startTunnelReader(Socket socket) {
        Thread reader = new Thread(() -> readTunnel(socket), "xmc-reader " + playerName);
        reader.setDaemon(true);
        reader.start();
    }

    private void readTunnel(Socket socket) {
        byte[] buffer = new byte[Math.min(config.maxPayloadBytes(), SOCKET_BUFFER)];
        try {
            InputStream in = socket.getInputStream();
            while (!closed.get()) {
                int read = in.read(buffer);
                if (read < 0) {
                    return;
                }
                for (int offset = 0; offset < read; offset += config.maxPayloadBytes()) {
                    int count = Math.min(read - offset, config.maxPayloadBytes());
                    byte[] chunk = Arrays.copyOfRange(buffer, offset, offset + count);
                    if (!XmcNetworking.sendData(listener, chunk)) {
                        return;
                    }
                }
            }
        } catch (IOException failure) {
            if (!closed.get()) {
                XmcMod.LOGGER.debug("XMC tunnel reader {} ended: {}", playerName, failure.toString());
            }
        } finally {
            close();
        }
    }

    private void forwardInbound(Socket socket) throws IOException, InterruptedException {
        OutputStream out = new BufferedOutputStream(socket.getOutputStream(), SOCKET_BUFFER);
        while (!closed.get()) {
            byte[] data = inbound.poll(500, TimeUnit.MILLISECONDS);
            if (data == null) {
                continue;
            }
            out.write(data);
            if (inbound.isEmpty()) {
                out.flush();
            }
        }
    }

    @Override
    public String toString() {
        return "XmcSession[" + playerName + "]";
    }
}

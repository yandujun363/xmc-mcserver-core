package xmc.core.session;

import xmc.config.PrivateAddress;
import xmc.config.XmcConfig;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Opens the TCP connection to the configured tunnel endpoint.
 *
 * <p>The stream is opaque: the endpoint may speak any TCP protocol, VLESS
 * being only the lightest choice, so nothing here names one. Only private
 * addresses are accepted, both when the configuration is loaded and again
 * when the socket is created.
 */
public final class TunnelConnector {
    private TunnelConnector() {
    }

    public static Socket connect(XmcConfig.Tunnel tunnel) throws IOException {
        InetAddress address = PrivateAddress.resolvePrivate(tunnel.host());
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(address, tunnel.port()), tunnel.connectTimeoutMillis());
            socket.setTcpNoDelay(true);
            return socket;
        } catch (IOException failure) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // best effort
            }
            throw failure;
        }
    }
}

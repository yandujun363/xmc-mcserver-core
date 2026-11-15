package xmc.config;

import java.util.regex.Pattern;

/**
 * Validated configuration of the XMC server.
 *
 * <p>There is deliberately no credential or player list here: players are
 * authenticated, whitelisted and banned by vanilla Minecraft, and the mod only
 * recognises the XMC tunnel by the protocol shape of the connection.
 */
public record XmcConfig(
        boolean enabled,
        String channel,
        int keepAliveSeconds,
        int maxPayloadBytes,
        int handshakeTimeoutSeconds,
        int probeTimeoutMillis,
        Tunnel tunnel) {

    public static final String DEFAULT_CHANNEL = "xmc:data";
    public static final int DEFAULT_KEEP_ALIVE_SECONDS = 15;
    public static final int DEFAULT_MAX_PAYLOAD_BYTES = 24 * 1024;
    public static final int DEFAULT_HANDSHAKE_TIMEOUT_SECONDS = 120;
    public static final int DEFAULT_PROBE_TIMEOUT_MILLIS = 800;

    private static final Pattern CHANNEL = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public record Tunnel(String host, int port, int connectTimeoutMillis) {
    }

    public static XmcConfig disabled() {
        return new XmcConfig(false, DEFAULT_CHANNEL, DEFAULT_KEEP_ALIVE_SECONDS,
                DEFAULT_MAX_PAYLOAD_BYTES, DEFAULT_HANDSHAKE_TIMEOUT_SECONDS,
                DEFAULT_PROBE_TIMEOUT_MILLIS, new Tunnel("127.0.0.1", 0, 5000));
    }

    static void validate(XmcConfig config) {
        if (!config.enabled()) {
            return;
        }
        if (!CHANNEL.matcher(config.channel()).matches()) {
            throw new IllegalArgumentException("\"channel\" must be a Minecraft resource identifier, e.g. xmc:data");
        }
        if (config.keepAliveSeconds() < 1 || config.keepAliveSeconds() > 300) {
            throw new IllegalArgumentException("\"keepAliveSeconds\" must be between 1 and 300");
        }
        if (config.maxPayloadBytes() < 1024 || config.maxPayloadBytes() > 32 * 1024) {
            throw new IllegalArgumentException("\"maxPayloadBytes\" must be between 1024 and 32768");
        }
        if (config.handshakeTimeoutSeconds() < 1 || config.handshakeTimeoutSeconds() > 600) {
            throw new IllegalArgumentException("\"handshakeTimeoutSeconds\" must be between 1 and 600");
        }
        if (config.probeTimeoutMillis() < 100 || config.probeTimeoutMillis() > 5000) {
            throw new IllegalArgumentException("\"probeTimeoutMillis\" must be between 100 and 5000");
        }
        if (config.tunnel().port() < 1 || config.tunnel().port() > 65535) {
            throw new IllegalArgumentException("\"tunnel.port\" must be between 1 and 65535");
        }
        if (config.tunnel().connectTimeoutMillis() < 100 || config.tunnel().connectTimeoutMillis() > 60_000) {
            throw new IllegalArgumentException("\"tunnel.connectTimeoutMillis\" must be between 100 and 60000");
        }
        try {
            PrivateAddress.resolvePrivate(config.tunnel().host());
        } catch (Exception e) {
            throw new IllegalArgumentException("\"tunnel.host\" must resolve to a private address: " + e.getMessage(), e);
        }
    }
}

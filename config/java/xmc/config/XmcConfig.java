package xmc.config;

/**
 * Validated configuration of the XMC server.
 *
 * <p>There is deliberately no credential, player list or protocol switch here:
 * players are authenticated, whitelisted and banned by vanilla Minecraft, the
 * channel is fixed to the one Xray writes, and everything the mod adds happens
 * after a successful login.
 */
public record XmcConfig(
        boolean enabled,
        int maxPayloadBytes,
        Tunnel tunnel) {

    public static final int DEFAULT_MAX_PAYLOAD_BYTES = 24 * 1024;

    public record Tunnel(String host, int port, int connectTimeoutMillis) {
    }

    public static XmcConfig disabled() {
        return new XmcConfig(false, DEFAULT_MAX_PAYLOAD_BYTES, new Tunnel("127.0.0.1", 0, 5000));
    }

    static void validate(XmcConfig config) {
        if (!config.enabled()) {
            return;
        }
        if (config.maxPayloadBytes() < 1024 || config.maxPayloadBytes() > 32767) {
            throw new IllegalArgumentException("\"maxPayloadBytes\" must be between 1024 and 32767");
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

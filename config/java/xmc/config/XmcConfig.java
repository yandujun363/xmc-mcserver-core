package xmc.config;

/**
 * Validated configuration of the XMC server.
 *
 * <p>There is deliberately no credential, player list or protocol switch here:
 * players are authenticated, whitelisted and banned by vanilla Minecraft, and
 * everything the mod adds happens after a successful login.
 *
 * <p>The two configuration-state payload channels are configurable. Their
 * default values are the ones Xray writes, but a fixed channel name is also a
 * fingerprint on the wire, so a deployment can replace both without rebuilding
 * the mod.
 */
public record XmcConfig(
        boolean enabled,
        int maxPayloadBytes,
        Channels channels,
        Tunnel tunnel) {

    public static final int DEFAULT_MAX_PAYLOAD_BYTES = 24 * 1024;

    /**
     * Vanilla identifiers have no length limit of their own. Channel names are
     * written into every custom payload, so keep them short on purpose.
     */
    public static final int MAX_CHANNEL_ID_LENGTH = 128;

    /**
     * One custom payload channel, split into the two halves that
     * {@code Identifier.fromNamespaceAndPath} takes.
     *
     * <p>Both halves are validated with the same character rules vanilla uses,
     * so constructing the {@code ResourceLocation} during registration cannot throw.
     */
    public record Channel(String namespace, String path) {
        /** The full {@code namespace:path} id used in the configuration file. */
        public String id() {
            return namespace + ":" + path;
        }
    }

    /** The payload channel that carries tunnel bytes and the one used for padding. */
    public record Channels(Channel data, Channel padding) {
        public static final Channel DEFAULT_DATA = new Channel("xmc", "data");
        public static final Channel DEFAULT_PADDING = new Channel("xmc", "pad");

        public static Channels defaults() {
            return new Channels(DEFAULT_DATA, DEFAULT_PADDING);
        }
    }

    /**
     * The local TCP endpoint the tunnel bytes are piped into. Nothing here
     * names a protocol: any inbound that accepts a raw byte stream works.
     */
    public record Tunnel(String host, int port, int connectTimeoutMillis) {
    }

    public static XmcConfig disabled() {
        return new XmcConfig(false, DEFAULT_MAX_PAYLOAD_BYTES, Channels.defaults(),
                new Tunnel("127.0.0.1", 0, 5000));
    }

    static void validate(XmcConfig config) {
        // Channels are validated even when the mod is disabled: they are read
        // at mod initialization, before the flag takes effect, and a bad name
        // must fail the same way either way.
        validateChannels(config.channels());

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

    static void validateChannels(Channels channels) {
        if (channels == null) {
            throw new IllegalArgumentException("\"channels\" must be an object with \"data\" and \"padding\"");
        }
        validateChannel("\"channels.data\"", channels.data());
        validateChannel("\"channels.padding\"", channels.padding());
        if (channels.data().id().equals(channels.padding().id())) {
            throw new IllegalArgumentException(
                    "\"channels.data\" and \"channels.padding\" must be different channels");
        }
    }

    private static void validateChannel(String key, Channel channel) {
        if (channel == null) {
            throw new IllegalArgumentException(key + " must be a channel id like \"xmc:data\"");
        }
        String namespace = channel.namespace();
        String path = channel.path();
        if (namespace.isEmpty()) {
            throw new IllegalArgumentException(key
                    + " must start with a namespace, e.g. \"xmc:data\"");
        }
        if (namespace.equals("..") || !namespace.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException(key + " namespace \"" + namespace
                    + "\" is invalid: only a-z, 0-9, '_', '.' and '-' are allowed, and it cannot be \"..\"");
        }
        if (path.isEmpty()) {
            throw new IllegalArgumentException(key + " needs a path after ':', e.g. \"xmc:data\"");
        }
        if (!path.matches("[a-z0-9/._-]+")) {
            throw new IllegalArgumentException(key + " path \"" + path
                    + "\" is invalid: only a-z, 0-9, '_', '-', '.' and '/' are allowed");
        }
        if ("minecraft".equals(namespace)) {
            throw new IllegalArgumentException(key + " must not use the \"minecraft\" namespace: "
                    + "it belongs to vanilla payloads, so a custom payload registered there can collide "
                    + "with vanilla and fail at startup; use your own namespace, e.g. \"xmc\"");
        }
        if (channel.id().length() > MAX_CHANNEL_ID_LENGTH) {
            throw new IllegalArgumentException(key + " must be at most " + MAX_CHANNEL_ID_LENGTH
                    + " characters long");
        }
    }
}

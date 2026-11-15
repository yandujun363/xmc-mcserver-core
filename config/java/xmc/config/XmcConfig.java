package xmc.config;

import xmc.lib.model.McProfile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validated configuration of the XMC server.
 */
public record XmcConfig(
        boolean enabled,
        String password,
        String channel,
        int keepAliveSeconds,
        int maxPayloadBytes,
        int handshakeTimeoutSeconds,
        List<McProfile> profiles,
        Tunnel tunnel) {

    public static final String DEFAULT_CHANNEL = "xmc:data";
    public static final int DEFAULT_KEEP_ALIVE_SECONDS = 15;
    public static final int DEFAULT_MAX_PAYLOAD_BYTES = 24 * 1024;
    public static final int DEFAULT_HANDSHAKE_TIMEOUT_SECONDS = 120;

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern CHANNEL = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public XmcConfig {
        profiles = List.copyOf(profiles);
    }

    public record Tunnel(String host, int port, int connectTimeoutMillis) {
    }

    public static XmcConfig disabled() {
        return new XmcConfig(false, "", DEFAULT_CHANNEL, DEFAULT_KEEP_ALIVE_SECONDS,
                DEFAULT_MAX_PAYLOAD_BYTES, DEFAULT_HANDSHAKE_TIMEOUT_SECONDS, List.of(),
                new Tunnel("127.0.0.1", 0, 5000));
    }

    public byte[] passwordBytes() {
        return password.getBytes(StandardCharsets.UTF_8);
    }

    public Optional<McProfile> findProfile(String username, UUID uuid) {
        for (McProfile profile : profiles) {
            if (profile.username().equals(username) && profile.uuid().equals(uuid)) {
                return Optional.of(profile);
            }
        }
        return Optional.empty();
    }

    static void validate(XmcConfig config) {
        if (!config.enabled()) {
            return;
        }
        if (config.password().isEmpty()) {
            throw new IllegalArgumentException("\"password\" must not be empty when XMC is enabled");
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
        if (config.profiles().isEmpty()) {
            throw new IllegalArgumentException("\"profiles\" must contain at least one profile when XMC is enabled");
        }
        for (McProfile profile : config.profiles()) {
            if (!USERNAME.matcher(profile.username()).matches()) {
                throw new IllegalArgumentException("invalid profile username: " + profile.username());
            }
            if (profile.texturesValue() == null || profile.texturesValue().isEmpty()
                    || profile.texturesSignature() == null || profile.texturesSignature().isEmpty()) {
                throw new IllegalArgumentException("profile " + profile.username()
                        + " needs both texturesValue and texturesSignature");
            }
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

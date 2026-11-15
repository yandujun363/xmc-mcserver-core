package xmc.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Loads {@code config/xmc-mcserver-core.json}. When the file does not exist a
 * disabled example is written first, so installing the mod never opens a
 * listener accidentally.
 */
public final class XmcConfigLoader {
    public static final String FILE_NAME = "xmc-mcserver-core.json";

    private static final String DEFAULT_JSON = """
            {
              "enabled": false,
              "channel": "xmc:data",
              "keepAliveSeconds": 15,
              "maxPayloadBytes": 24576,
              "handshakeTimeoutSeconds": 120,
              "probeTimeoutMillis": 800,
              "tunnel": {
                "host": "127.0.0.1",
                "port": 10086,
                "connectTimeoutMillis": 5000
              }
            }
            """;

    private XmcConfigLoader() {
    }

    public static XmcConfig loadOrCreate(Path path) throws IOException {
        if (!Files.exists(path)) {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, DEFAULT_JSON, StandardCharsets.UTF_8);
            return XmcConfig.disabled();
        }

        String json = Files.readString(path, StandardCharsets.UTF_8);
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid JSON in " + path + ": " + e.getMessage(), e);
        }
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("configuration root must be an object: " + path);
        }

        XmcConfig config = parse(parsed.getAsJsonObject());
        XmcConfig.validate(config);
        return config;
    }

    private static XmcConfig parse(JsonObject root) {
        boolean enabled = bool(root, "enabled", false);
        String channel = string(root, "channel", XmcConfig.DEFAULT_CHANNEL).toLowerCase(Locale.ROOT);
        int keepAliveSeconds = integer(root, "keepAliveSeconds", XmcConfig.DEFAULT_KEEP_ALIVE_SECONDS);
        int maxPayloadBytes = integer(root, "maxPayloadBytes", XmcConfig.DEFAULT_MAX_PAYLOAD_BYTES);
        int handshakeTimeoutSeconds = integer(root, "handshakeTimeoutSeconds",
                XmcConfig.DEFAULT_HANDSHAKE_TIMEOUT_SECONDS);
        int probeTimeoutMillis = integer(root, "probeTimeoutMillis", XmcConfig.DEFAULT_PROBE_TIMEOUT_MILLIS);

        JsonObject tunnelObject = root.has("tunnel") && root.get("tunnel").isJsonObject()
                ? root.getAsJsonObject("tunnel")
                : new JsonObject();
        XmcConfig.Tunnel tunnel = new XmcConfig.Tunnel(
                string(tunnelObject, "host", "127.0.0.1"),
                integer(tunnelObject, "port", 0),
                integer(tunnelObject, "connectTimeoutMillis", 5000));

        return new XmcConfig(enabled, channel, keepAliveSeconds, maxPayloadBytes,
                handshakeTimeoutSeconds, probeTimeoutMillis, tunnel);
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("\"" + key + "\" must be a boolean");
        }
        return element.getAsBoolean();
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("\"" + key + "\" must be a string");
        }
        return element.getAsString();
    }

    private static int integer(JsonObject object, String key, int fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("\"" + key + "\" must be an integer");
        }
        try {
            return element.getAsInt();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("\"" + key + "\" must be an integer", e);
        }
    }
}

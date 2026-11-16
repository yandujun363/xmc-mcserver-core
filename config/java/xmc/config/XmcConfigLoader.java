package xmc.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
              "maxPayloadBytes": 24576,
              "channels": {
                "data": "xmc:data",
                "padding": "xmc:pad"
              },
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
        int maxPayloadBytes = integer(root, "maxPayloadBytes", XmcConfig.DEFAULT_MAX_PAYLOAD_BYTES);

        JsonObject channelsObject = root.has("channels") && root.get("channels").isJsonObject()
                ? root.getAsJsonObject("channels")
                : new JsonObject();
        XmcConfig.Channels channels = new XmcConfig.Channels(
                channel(channelsObject, "data", XmcConfig.Channels.DEFAULT_DATA),
                channel(channelsObject, "padding", XmcConfig.Channels.DEFAULT_PADDING));

        JsonObject tunnelObject = root.has("tunnel") && root.get("tunnel").isJsonObject()
                ? root.getAsJsonObject("tunnel")
                : new JsonObject();
        XmcConfig.Tunnel tunnel = new XmcConfig.Tunnel(
                string(tunnelObject, "host", "127.0.0.1"),
                integer(tunnelObject, "port", 0),
                integer(tunnelObject, "connectTimeoutMillis", 5000));

        return new XmcConfig(enabled, maxPayloadBytes, channels, tunnel);
    }

    private static XmcConfig.Channel channel(JsonObject object, String key, XmcConfig.Channel fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("\"channels." + key + "\" must be a string like \"xmc:data\"");
        }
        String value = element.getAsString();
        int separator = value.indexOf(':');
        if (separator <= 0 || separator != value.lastIndexOf(':')) {
            throw new IllegalArgumentException("\"channels." + key
                    + "\" must be a full channel id with exactly one ':' separator, e.g. \"xmc:data\"");
        }
        return new XmcConfig.Channel(value.substring(0, separator), value.substring(separator + 1));
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

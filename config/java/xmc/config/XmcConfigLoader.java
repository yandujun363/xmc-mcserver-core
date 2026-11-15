package xmc.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import xmc.lib.model.McProfile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

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
              "password": "",
              "channel": "xmc:data",
              "keepAliveSeconds": 15,
              "maxPayloadBytes": 24576,
              "handshakeTimeoutSeconds": 120,
              "profiles": [
                {
                  "username": "Dream",
                  "uuid": "00000000-0000-0000-0000-000000000000",
                  "texturesValue": "",
                  "texturesSignature": ""
                }
              ],
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
        String password = string(root, "password", "");
        String channel = string(root, "channel", XmcConfig.DEFAULT_CHANNEL).toLowerCase(Locale.ROOT);
        int keepAliveSeconds = integer(root, "keepAliveSeconds", XmcConfig.DEFAULT_KEEP_ALIVE_SECONDS);
        int maxPayloadBytes = integer(root, "maxPayloadBytes", XmcConfig.DEFAULT_MAX_PAYLOAD_BYTES);
        int handshakeTimeoutSeconds = integer(root, "handshakeTimeoutSeconds", XmcConfig.DEFAULT_HANDSHAKE_TIMEOUT_SECONDS);

        List<McProfile> profiles = new ArrayList<>();
        JsonElement profilesElement = root.get("profiles");
        if (profilesElement != null && profilesElement.isJsonArray()) {
            JsonArray array = profilesElement.getAsJsonArray();
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    throw new IllegalArgumentException("\"profiles\" entries must be objects");
                }
                JsonObject profile = element.getAsJsonObject();
                profiles.add(new McProfile(
                        string(profile, "username", ""),
                        uuid(string(profile, "uuid", "")),
                        nullableString(profile, "texturesValue"),
                        nullableString(profile, "texturesSignature")));
            }
        } else if (profilesElement != null && !profilesElement.isJsonNull()) {
            throw new IllegalArgumentException("\"profiles\" must be an array");
        }

        JsonObject tunnelObject = root.has("tunnel") && root.get("tunnel").isJsonObject()
                ? root.getAsJsonObject("tunnel")
                : new JsonObject();
        XmcConfig.Tunnel tunnel = new XmcConfig.Tunnel(
                string(tunnelObject, "host", "127.0.0.1"),
                integer(tunnelObject, "port", 0),
                integer(tunnelObject, "connectTimeoutMillis", 5000));

        return new XmcConfig(enabled, password, channel, keepAliveSeconds, maxPayloadBytes,
                handshakeTimeoutSeconds, profiles, tunnel);
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

    private static String nullableString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("\"" + key + "\" must be a string");
        }
        String value = element.getAsString();
        return value.isEmpty() ? null : value;
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

    static UUID uuid(String value) {
        String normalized = value.trim();
        if (normalized.length() == 32 && normalized.indexOf('-') < 0) {
            normalized = normalized.substring(0, 8) + "-" + normalized.substring(8, 12) + "-"
                    + normalized.substring(12, 16) + "-" + normalized.substring(16, 20) + "-"
                    + normalized.substring(20);
        }
        try {
            return UUID.fromString(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid UUID: " + value, e);
        }
    }
}

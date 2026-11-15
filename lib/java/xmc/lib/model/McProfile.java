package xmc.lib.model;

import java.util.Objects;
import java.util.UUID;

/**
 * A Minecraft login profile with the signed {@code textures} property.
 *
 * <p>XMC compares profiles by username and UUID on the server and compares the
 * signed textures on the client, so a server profile must carry exactly the
 * same texture value/signature pair as the matching client profile.
 */
public record McProfile(String username, UUID uuid, String texturesValue, String texturesSignature) {
    public McProfile {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(uuid, "uuid");
        if ((texturesValue == null) != (texturesSignature == null)) {
            throw new IllegalArgumentException("texturesValue and texturesSignature must be set together");
        }
    }

    public boolean hasTextures() {
        return texturesValue != null && !texturesValue.isEmpty();
    }
}

package xmc.lib.spi;

import xmc.lib.model.McProfile;
import xmc.lib.net.RawConnection;

import java.security.KeyPair;
import java.util.UUID;

/**
 * Implemented by the XMC core and installed through {@code XmcLoginHooks}.
 *
 * <p>All methods except {@link #startSession} are called on the Netty event
 * loop and must not block.
 */
public interface XmcLoginHandler {
    /** Whether XMC login interception is active for this server run. */
    boolean enabled();

    /**
     * RSA key pair derived from the configured password. The server replaces its
     * own key pair with this one so that both XMC and vanilla clients can
     * negotiate encryption against the same key.
     */
    KeyPair keyPair();

    /**
     * Inspects the decrypted verify token of a login key exchange.
     *
     * @param username    the login-start username
     * @param profileId   the login-start UUID
     * @param verifyToken the decrypted token, including the challenge prefix
     * @param challenge   the server challenge that was sent in the encryption request
     */
    LoginDecision decide(String username, UUID profileId, byte[] verifyToken, byte[] challenge);

    /** Starts the XMC session on a dedicated thread after the connection was taken over. */
    void startSession(RawConnection connection, McProfile profile);
}

package xmc.lib.spi;

import xmc.lib.net.RawConnection;

/**
 * Implemented by the XMC core and installed through {@code XmcLoginHooks}.
 *
 * <p>Everything before the login acknowledgement (encryption, authentication,
 * whitelist, Login Success) is done by vanilla; the core only decides how long
 * to wait for the first configuration-phase bytes and then runs the tunnel.
 */
public interface XmcLoginHandler {
    /** Whether XMC landing detection and the tunnel are active for this server run. */
    boolean enabled();

    /**
     * How long the landing probe waits for the first configuration-phase frame.
     * An XMC client never produces one, because its padding records are not valid
     * Minecraft frames, so the wait is what separates the two flows.
     */
    long probeTimeoutMillis();

    /** Starts the XMC session on a dedicated thread after the connection was taken over. */
    void startSession(RawConnection connection, String playerName);
}

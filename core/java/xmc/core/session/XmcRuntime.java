package xmc.core.session;

import xmc.config.XmcConfig;
import xmc.core.XmcMod;
import xmc.lib.XmcLoginHooks;
import xmc.lib.net.RawConnection;
import xmc.lib.spi.XmcLoginHandler;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * XMC core: owns the loaded configuration and tracks the active tunnels.
 *
 * <p>It takes no part in authentication: the player was already verified by
 * vanilla before the session starts.
 */
public final class XmcRuntime implements XmcLoginHandler {
    private final XmcConfig config;
    private final Set<XmcSession> sessions = ConcurrentHashMap.newKeySet();

    private XmcRuntime(XmcConfig config) {
        this.config = config;
    }

    public static XmcRuntime create(XmcConfig config) {
        return new XmcRuntime(config);
    }

    public XmcConfig config() {
        return config;
    }

    @Override
    public boolean enabled() {
        return config.enabled();
    }

    @Override
    public long probeTimeoutMillis() {
        return config.probeTimeoutMillis();
    }

    @Override
    public void startSession(RawConnection connection, String playerName) {
        XmcSession session = new XmcSession(connection, config, playerName, sessions::remove);
        sessions.add(session);
        XmcMod.LOGGER.info("XMC tunnel established for {} ({})", playerName, connection.remoteAddress());
        session.start();
    }

    public void closeAll() {
        for (XmcSession session : sessions) {
            session.close();
        }
        sessions.clear();
    }

    public void shutdown() {
        closeAll();
        XmcLoginHooks.uninstall();
    }
}

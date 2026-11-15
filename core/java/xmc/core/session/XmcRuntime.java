package xmc.core.session;

import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import xmc.config.XmcConfig;
import xmc.core.XmcMod;
import xmc.lib.net.XmcChannelHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * XMC core: owns the loaded configuration and tracks the active tunnels.
 *
 * <p>It takes no part in authentication: by the time a payload arrives vanilla
 * has already finished the login. A session is created lazily on the first
 * {@code xmc:data} payload, so an ordinary player never allocates one.
 */
public final class XmcRuntime implements XmcChannelHandler {
    private final XmcConfig config;
    private final Map<ServerConfigurationPacketListenerImpl, XmcSession> sessions = new ConcurrentHashMap<>();

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
    public void onData(ServerConfigurationPacketListenerImpl listener, byte[] data) {
        XmcSession session = sessions.get(listener);
        if (session == null) {
            String playerName = listener.getOwner().name();
            XmcSession created = new XmcSession(listener, config, playerName,
                    closed -> sessions.remove(listener, closed));
            session = sessions.putIfAbsent(listener, created);
            if (session == null) {
                session = created;
                XmcMod.LOGGER.info("XMC landing detected for {}", playerName);
                session.start();
            }
        }
        session.offer(data);
    }

    @Override
    public void onDisconnect(ServerConfigurationPacketListenerImpl listener) {
        XmcSession session = sessions.remove(listener);
        if (session != null) {
            session.close();
        }
    }

    public void closeAll() {
        for (XmcSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
    }

    public void shutdown() {
        closeAll();
    }
}

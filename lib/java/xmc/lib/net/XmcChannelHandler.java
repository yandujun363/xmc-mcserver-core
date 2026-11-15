package xmc.lib.net;

import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;

/**
 * Implemented by the XMC core and installed through {@link XmcNetworking}.
 *
 * <p>The mod has no say in login: by the time a payload arrives the player was
 * already authenticated, whitelisted and banned (or not) by vanilla. The handler
 * only ever sees configuration-state payloads.
 */
public interface XmcChannelHandler {
    /** A complete {@code xmc:data} payload arrived on the server thread. */
    void onData(ServerConfigurationPacketListenerImpl listener, byte[] data);

    /** The connection left the configuration state; release its tunnel. */
    void onDisconnect(ServerConfigurationPacketListenerImpl listener);
}

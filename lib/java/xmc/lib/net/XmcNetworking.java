package xmc.lib.net;

import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;

/**
 * Static bridge between the Fabric networking API and the XMC core.
 *
 * <p>Only the configuration state is used. That state is entered after a
 * successful vanilla login, so the mod never sees credentials, never sends a
 * Login Success and never touches the login pipeline. It registers the two
 * channels at startup and forwards payloads; the rest is vanilla.
 */
public final class XmcNetworking {
    private static volatile XmcChannelHandler handler;

    private XmcNetworking() {
    }

    public static void install(XmcChannelHandler newHandler) {
        handler = newHandler;
    }

    public static void uninstall() {
        handler = null;
    }

    /** Must run once, from {@code onInitialize}, before any connection exists. */
    public static void register() {
        XmcPayloads.register();

        ServerConfigurationNetworking.registerGlobalReceiver(XmcPayloads.DATA_TYPE, (payload, context) -> {
            XmcChannelHandler current = handler;
            if (current != null) {
                current.onData(context.packetListener(), payload.data());
            }
        });

        ServerConfigurationConnectionEvents.DISCONNECT.register((listener, server) -> {
            XmcChannelHandler current = handler;
            if (current != null) {
                current.onDisconnect(listener);
            }
        });
    }

    public static boolean sendData(ServerConfigurationPacketListenerImpl listener, byte[] data) {
        if (!listener.isAcceptingMessages()) {
            return false;
        }
        ServerConfigurationNetworking.send(listener, new XmcPayloads.Data(data));
        return true;
    }

    public static boolean sendPadding(ServerConfigurationPacketListenerImpl listener, byte[] data) {
        if (!listener.isAcceptingMessages()) {
            return false;
        }
        ServerConfigurationNetworking.send(listener, new XmcPayloads.Padding(data));
        return true;
    }
}

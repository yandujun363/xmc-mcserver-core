package xmc.lib.net;

import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/**
 * Replaces the login packet listener after takeover so that {@code Connection.tick()}
 * no longer runs the vanilla login state machine (its 600-tick slow-login kick).
 */
public final class NoopPacketListener implements PacketListener {
    @Override
    public PacketFlow flow() {
        return PacketFlow.SERVERBOUND;
    }

    @Override
    public ConnectionProtocol protocol() {
        return ConnectionProtocol.LOGIN;
    }

    @Override
    public void onDisconnect(DisconnectionDetails details) {
    }

    @Override
    public boolean isAcceptingMessages() {
        return true;
    }

    @Override
    public boolean shouldHandleMessage(Packet<?> packet) {
        return false;
    }
}

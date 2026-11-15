package xmc.lib.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xmc.lib.XmcLoginHooks;
import xmc.lib.net.LoginAckProbe;
import xmc.lib.spi.XmcLoginHandler;

/**
 * The only point where the mod meets the login: right after vanilla has finished
 * it (encryption, Yggdrasil or offline authentication, whitelist/ban checks and
 * Login Success are all vanilla's) and the client acknowledged it.
 *
 * <p>The connection is handed back to vanilla unless the client starts the XMC
 * padding schedule instead of the configuration phase; see {@link LoginAckProbe}.
 * No authentication decision is taken here.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPacketListenerImplMixin {
    @Unique
    private boolean xmc$replayingLoginAcknowledged;

    @Inject(method = "handleLoginAcknowledgement", at = @At("HEAD"), cancellable = true)
    private void xmc$interceptLoginAcknowledged(ServerboundLoginAcknowledgedPacket packet, CallbackInfo info) {
        if (xmc$replayingLoginAcknowledged) {
            return;
        }
        XmcLoginHandler handler = XmcLoginHooks.handler();
        if (handler == null || !handler.enabled()) {
            return;
        }

        ServerLoginPacketListenerImplAccess access = (ServerLoginPacketListenerImplAccess) (Object) this;
        Connection connection = access.xmc$connection();
        if (connection.isMemoryConnection()) {
            return;
        }

        Runnable vanillaReplay = () -> {
            xmc$replayingLoginAcknowledged = true;
            try {
                access.xmc$handleLoginAcknowledgement(packet);
            } finally {
                xmc$replayingLoginAcknowledged = false;
            }
        };
        if (LoginAckProbe.install(connection, handler, vanillaReplay, xmc$playerName(access))) {
            info.cancel();
        }
    }

    @Unique
    private static String xmc$playerName(ServerLoginPacketListenerImplAccess access) {
        GameProfile profile = access.xmc$authenticatedProfile();
        if (profile != null && profile.name() != null) {
            return profile.name();
        }
        String requested = access.xmc$requestedUsername();
        return requested != null ? requested : "<unknown>";
    }
}

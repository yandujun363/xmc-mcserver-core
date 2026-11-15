package xmc.lib.mixin;

import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Crypt;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xmc.lib.XmcLoginHooks;
import xmc.lib.mc.MinecraftLoginSupport;
import xmc.lib.net.RawConnection;
import xmc.lib.spi.LoginDecision;
import xmc.lib.spi.XmcLoginHandler;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;

/**
 * Login interception for XMC.
 *
 * <p>XMC starts exactly like a vanilla login: a handshake with protocol 775 and
 * intent 2, a login start and an encryption request/response. The only
 * difference is that the XMC client appends the pre-shared password to the
 * verify token. The mixin therefore decodes the token itself, and when a
 * password is present it terminates the vanilla login and hands the connection
 * to the XMC core. Ordinary clients and offline-mode servers keep their normal
 * login path.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPacketListenerImplMixin {
    @Unique
    private UUID xmc$profileId;

    /**
     * Vanilla only requests encryption for online-mode servers. XMC needs the
     * key exchange even on an offline-mode server, so force that branch while
     * XMC is active. Ordinary clients handle the encryption request fine; the
     * key handler below completes their offline login.
     */
    @Redirect(
            method = "handleHello",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;usesAuthentication()Z"))
    private boolean xmc$forceEncryption(MinecraftServer server) {
        return XmcLoginHooks.enabled() || server.usesAuthentication();
    }

    @Inject(method = "handleHello", at = @At("TAIL"))
    private void xmc$captureProfileId(ServerboundHelloPacket packet, CallbackInfo info) {
        xmc$profileId = packet.profileId();
    }

    @Inject(method = "handleKey", at = @At("HEAD"), cancellable = true)
    private void xmc$handleKey(ServerboundKeyPacket packet, CallbackInfo info) {
        XmcLoginHandler handler = XmcLoginHooks.handler();
        if (handler == null || !handler.enabled()) {
            return;
        }

        ServerLoginPacketListenerImpl self = (ServerLoginPacketListenerImpl) (Object) this;
        ServerLoginPacketListenerImplAccess access = (ServerLoginPacketListenerImplAccess) (Object) this;
        Connection connection = access.xmc$connection();
        byte[] challenge = access.xmc$challenge();

        byte[] verifyToken;
        try {
            verifyToken = Crypt.decryptUsingKey(
                    handler.keyPair().getPrivate(),
                    ((ServerboundKeyPacketAccess) (Object) packet).xmc$encryptedChallenge());
        } catch (Exception undecryptable) {
            // Not something the mod understands; let vanilla produce its own error.
            return;
        }

        boolean plainChallenge = verifyToken.length == challenge.length
                && MessageDigest.isEqual(verifyToken, challenge);
        if (plainChallenge) {
            if (!access.xmc$server().usesAuthentication()) {
                xmc$completeOfflineLogin(self, access, connection, packet, handler, info);
            }
            return;
        }

        // Anything that is not "challenge + password" is left to vanilla.
        if (verifyToken.length <= challenge.length
                || !MessageDigest.isEqual(Arrays.copyOf(verifyToken, challenge.length), challenge)) {
            return;
        }

        UUID profileId = xmc$profileId;
        String username = access.xmc$requestedUsername();
        if (profileId == null && username != null) {
            profileId = net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(username);
        }
        if (profileId == null) {
            return;
        }

        LoginDecision decision = handler.decide(username, profileId, verifyToken, challenge);
        if (decision instanceof LoginDecision.NotXmc) {
            return;
        }

        info.cancel();

        try {
            MinecraftLoginSupport.enableEncryption(connection, packet.getSecretKey(handler.keyPair().getPrivate()));
        } catch (Exception failure) {
            MinecraftLoginSupport.close(connection);
            return;
        }

        if (decision instanceof LoginDecision.Reject reject) {
            MinecraftLoginSupport.disconnectLogin(connection, switch (reject.reason()) {
                case AUTH_SERVERS_DOWN -> Component.translatable("multiplayer.disconnect.authservers_down");
                case NOT_WHITELISTED -> Component.literal("You are not white-listed on this server!");
            });
            return;
        }

        LoginDecision.Accept accept = (LoginDecision.Accept) decision;
        MinecraftLoginSupport.sendLoginSuccess(connection, accept.profile());
        RawConnection raw = MinecraftLoginSupport.takeOver(connection);
        try {
            handler.startSession(raw, accept.profile());
        } catch (RuntimeException failure) {
            raw.close();
        }
    }

    @Unique
    private void xmc$completeOfflineLogin(
            ServerLoginPacketListenerImpl self,
            ServerLoginPacketListenerImplAccess access,
            Connection connection,
            ServerboundKeyPacket packet,
            XmcLoginHandler handler,
            CallbackInfo info) {
        info.cancel();
        try {
            MinecraftLoginSupport.enableEncryption(connection, packet.getSecretKey(handler.keyPair().getPrivate()));
        } catch (Exception failure) {
            MinecraftLoginSupport.close(connection);
            return;
        }
        MinecraftLoginSupport.completeOfflineLogin(self, access.xmc$requestedUsername());
    }
}

package xmc.lib.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Accessors for the vanilla login state that the XMC landing probe needs. The
 * profile and requested name are only used for logging.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public interface ServerLoginPacketListenerImplAccess {
    @Accessor("connection")
    Connection xmc$connection();

    @Accessor("authenticatedProfile")
    GameProfile xmc$authenticatedProfile();

    @Accessor("requestedUsername")
    String xmc$requestedUsername();

    @Invoker("handleLoginAcknowledgement")
    void xmc$handleLoginAcknowledgement(ServerboundLoginAcknowledgedPacket packet);
}

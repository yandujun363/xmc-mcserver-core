package xmc.lib.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerLoginPacketListenerImpl.class)
public interface ServerLoginPacketListenerImplAccess {
    @Accessor("challenge")
    byte[] xmc$challenge();

    @Accessor("connection")
    Connection xmc$connection();

    @Accessor("server")
    MinecraftServer xmc$server();

    @Accessor("requestedUsername")
    String xmc$requestedUsername();

    @Invoker("startClientVerification")
    void xmc$startClientVerification(GameProfile profile);
}

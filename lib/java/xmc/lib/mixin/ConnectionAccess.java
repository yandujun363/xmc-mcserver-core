package xmc.lib.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Connection.class)
public interface ConnectionAccess {
    @Accessor("channel")
    Channel xmc$channel();

    @Accessor("packetListener")
    void xmc$setPacketListener(PacketListener listener);
}

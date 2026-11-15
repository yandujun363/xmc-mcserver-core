package xmc.lib.mixin;

import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerboundKeyPacket.class)
public interface ServerboundKeyPacketAccess {
    @Accessor("encryptedChallenge")
    byte[] xmc$encryptedChallenge();
}

package xmc.lib.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xmc.lib.XmcLoginHooks;

import java.security.KeyPair;

/**
 * Replaces the server RSA key pair with the password-derived pair while XMC is
 * active. Both the vanilla login flow and the XMC login flow then negotiate
 * against the same key, which is what the XMC client pins.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Inject(method = "getKeyPair", at = @At("HEAD"), cancellable = true)
    private void xmc$replaceKeyPair(CallbackInfoReturnable<KeyPair> info) {
        KeyPair keyPair = XmcLoginHooks.keyPairOrNull();
        if (keyPair != null) {
            info.setReturnValue(keyPair);
        }
    }
}

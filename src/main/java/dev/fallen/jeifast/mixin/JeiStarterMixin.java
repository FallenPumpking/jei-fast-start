package dev.fallen.jeifast.mixin;

import dev.fallen.jeifast.FastStackCache;
import mezz.jei.library.startup.JeiStarter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = JeiStarter.class, remap = false)
public abstract class JeiStarterMixin {
    @Inject(method = "start()V", at = @At("HEAD"))
    private void jeifast$startBegin(CallbackInfo ci) {
        FastStackCache.startBegin();
    }

    @Inject(method = "start()V", at = @At("RETURN"))
    private void jeifast$startEnd(CallbackInfo ci) {
        FastStackCache.startEnd();
    }
}

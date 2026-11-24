package dev.fallen.jeifast.mixin;

import dev.fallen.jeifast.FastStackCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Priority above the default 1000: mixins are applied in ascending priority and a later HEAD
 * callback runs after an earlier one. Async loaders such as Just Enough Threads cancel start() at
 * HEAD on the client thread and call it again on their own thread; running after them means only
 * the start that actually does the work is tracked, never the cancelled client-thread call.
 */
@Mixin(targets = "mezz.jei.library.startup.JeiStarter", remap = false, priority = 1100)
public abstract class JeiStarterMixin {
    @Inject(method = "start()V", at = @At("HEAD"))
    private void jeifast$startBegin(CallbackInfo ci) {
        FastStackCache.startBegin();
    }

    @Inject(method = "start()V", at = @At("RETURN"))
    private void jeifast$startEnd(CallbackInfo ci) {
        FastStackCache.startEnd();
    }

    /**
     * A start that is aborted by an exception never reaches RETURN. Just Enough Threads cancels a
     * background start this way when the player leaves the world, and calls stop() on the client
     * thread first. Must stay at HEAD: stop() returns early while JEI's running flag is false,
     * which it still is during a JET background start.
     */
    @Inject(method = "stop()V", at = @At("HEAD"))
    private void jeifast$stop(CallbackInfo ci) {
        FastStackCache.startAbort();
    }
}

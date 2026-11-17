package dev.fallen.jeifast.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collection;

/**
 * KubeJS's JEIEvents.hideItems scans JEI's whole ingredient list once per hide() call. That list
 * is a lazy view that rebuilds an ItemStack for every element on every pass, so a script with a
 * few hundred hide() calls rebuilds tens of millions of stacks. Copy the view into a plain list
 * once per event instead.
 */
@Pseudo
@Mixin(targets = "dev.latvian.mods.kubejs.integration.forge.jei.HideJEIEventJS", remap = false)
public abstract class KubeJsHideEventMixin {
    @Shadow
    @Final
    @Mutable
    private Collection<Object> allIngredients;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void jeifast$materialize(CallbackInfo ci) {
        this.allIngredients = new ArrayList<>(this.allIngredients);
    }
}

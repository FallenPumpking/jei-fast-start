package dev.fallen.jeifast.mixin;

import dev.fallen.jeifast.FastStackCache;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks JEI's TypedItemStack. JEI moved the class between packages during 15.x, so both names
 * are listed; JeiFastMixinPlugin only lets this apply when the members it needs are present.
 *
 * Rule for every injector here: method arguments are only captured at HEAD. At any later point
 * the compiler may have reused an argument's local slot for something else (JEI 15.74 does this
 * in create), and loading it there produces a VerifyError.
 */
@Mixin(
    targets = {
        "mezz.jei.common.ingredients.itemStacks.TypedItemStack",
        "mezz.jei.library.ingredients.itemStacks.TypedItemStack"
    },
    remap = false)
public abstract class TypedItemStackMixin {
    @Shadow
    protected abstract ItemStack createItemStackUncached();

    /** Serve getIngredient() from the fast table instead of the Guava cache. */
    @Inject(method = "getIngredient()Lnet/minecraft/world/item/ItemStack;", at = @At("HEAD"), cancellable = true)
    private void jeifast$getIngredient(CallbackInfoReturnable<ItemStack> cir) {
        FastStackCache.Entry entry = FastStackCache.find(this);
        ItemStack stack;
        if (entry == null) {
            stack = this.createItemStackUncached();
            FastStackCache.store(this, stack);
        } else {
            stack = entry.stack();
            if (FastStackCache.shouldVerify(entry)) {
                ItemStack rebuilt = this.createItemStackUncached();
                if (!FastStackCache.sameStack(stack, rebuilt)) {
                    FastStackCache.primingMismatch(stack, rebuilt);
                    FastStackCache.store(this, rebuilt);
                    stack = rebuilt;
                }
            }
        }
        cir.setReturnValue(stack);
    }

    /** Remember the source stack while it is still in its argument slot. */
    @Inject(method = "create(Lnet/minecraft/world/item/ItemStack;)Lmezz/jei/api/ingredients/ITypedIngredient;", at = @At("HEAD"))
    private static void jeifast$createHead(ItemStack source, CallbackInfoReturnable<Object> cir) {
        FastStackCache.createBegin(source);
    }

    /** Pair the new ingredient with the remembered source. Takes no arguments on purpose. */
    @Inject(method = "create(Lnet/minecraft/world/item/ItemStack;)Lmezz/jei/api/ingredients/ITypedIngredient;", at = @At("RETURN"))
    private static void jeifast$createReturn(CallbackInfoReturnable<Object> cir) {
        FastStackCache.createEnd(cir.getReturnValue());
    }
}

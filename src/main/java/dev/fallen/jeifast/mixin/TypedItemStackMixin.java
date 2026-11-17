package dev.fallen.jeifast.mixin;

import dev.fallen.jeifast.FastStackCache;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.common.ingredients.itemStacks.TypedItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = TypedItemStack.class, remap = false)
public abstract class TypedItemStackMixin {
    @Shadow
    protected abstract ItemStack createItemStackUncached();

    /** Serve getIngredient() from the fast table instead of the Guava cache. */
    @Inject(method = "getIngredient()Lnet/minecraft/world/item/ItemStack;", at = @At("HEAD"), cancellable = true)
    private void jeifast$getIngredient(CallbackInfoReturnable<ItemStack> cir) {
        ItemStack stack = FastStackCache.lookup(this);
        if (stack == null) {
            stack = this.createItemStackUncached();
            FastStackCache.store(this, stack);
        }
        cir.setReturnValue(stack);
    }

    /** During JEI startup, remember the source stack so it is not rebuilt right away. */
    @Inject(method = "create(Lnet/minecraft/world/item/ItemStack;)Lmezz/jei/api/ingredients/ITypedIngredient;", at = @At("RETURN"))
    private static void jeifast$prime(ItemStack source, CallbackInfoReturnable<ITypedIngredient<ItemStack>> cir) {
        // m_41613_ = ItemStack.getCount (SRG name; this mod is compiled without remapping)
        if (source.m_41613_() == 1) {
            FastStackCache.prime(cir.getReturnValue(), source);
        }
    }

    /**
     * create(ItemStack) copies the tag and then NormalizedTypedItemStack's constructor copies it
     * again. Drop the first copy on the count == 1 path.
     */
    @Redirect(
        method = "create(Lnet/minecraft/world/item/ItemStack;)Lmezz/jei/api/ingredients/ITypedIngredient;",
        at = @At(value = "INVOKE", ordinal = 0,
            target = "Lmezz/jei/common/ingredients/itemStacks/TypedItemStack;copyTag(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;"))
    private static CompoundTag jeifast$skipDoubleCopy(CompoundTag tag) {
        return tag;
    }
}

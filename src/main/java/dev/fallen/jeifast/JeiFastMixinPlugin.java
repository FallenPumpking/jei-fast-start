package dev.fallen.jeifast;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.util.List;
import java.util.Set;

/**
 * Decides per target class whether a mixin may apply, by reading the target's bytecode first.
 * A mixin is only applied when every member it touches exists with the expected shape, so a JEI
 * or KubeJS version with different internals leaves that class untouched instead of failing
 * while the class is being transformed.
 */
public final class JeiFastMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOG = LogManager.getLogger("jeifast");
    private static final String ITEM_STACK = "Lnet/minecraft/world/item/ItemStack;";

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String reason;
        try {
            ClassNode target = MixinService.getService().getBytecodeProvider().getClassNode(targetClassName);
            reason = missing(target, mixinClassName);
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Throwable t) {
            reason = "could not read the class (" + t + ")";
        }
        if (reason != null) {
            LOG.warn("Not hooking {}: {}. This version is not supported yet; it runs unmodified.",
                targetClassName, reason);
            return false;
        }
        return true;
    }

    private static String missing(ClassNode target, String mixinClassName) {
        if (mixinClassName.endsWith(".TypedItemStackMixin")) {
            if (!hasField(target, "CACHE", "Lcom/google/common/cache/LoadingCache;")) {
                return "no Guava CACHE field";
            }
            if (!hasMethod(target, "getIngredient", "()" + ITEM_STACK, false)) {
                return "no getIngredient()";
            }
            if (!hasMethod(target, "createItemStackUncached", "()" + ITEM_STACK, false)) {
                return "no createItemStackUncached()";
            }
            if (!hasMethod(target, "create", "(" + ITEM_STACK + ")Lmezz/jei/api/ingredients/ITypedIngredient;", true)) {
                return "no static create(ItemStack)";
            }
            return null;
        }
        if (mixinClassName.endsWith(".JeiStarterMixin")) {
            return hasMethod(target, "start", "()V", false) ? null : "no start()";
        }
        if (mixinClassName.endsWith(".KubeJsHideEventMixin")) {
            return hasField(target, "allIngredients", "Ljava/util/Collection;") ? null : "no allIngredients field";
        }
        return "unknown mixin " + mixinClassName;
    }

    private static boolean hasField(ClassNode target, String name, String desc) {
        for (FieldNode f : target.fields) {
            if (f.name.equals(name) && f.desc.equals(desc)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMethod(ClassNode target, String name, String desc, boolean isStatic) {
        for (MethodNode m : target.methods) {
            if (m.name.equals(name) && m.desc.equals(desc)
                && ((m.access & Opcodes.ACC_STATIC) != 0) == isStatic) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}

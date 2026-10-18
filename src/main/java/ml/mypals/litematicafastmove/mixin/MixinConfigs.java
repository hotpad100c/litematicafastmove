package ml.mypals.litematicafastmove.mixin;

import com.google.gson.JsonObject;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.malilib.config.ConfigUtils;
import ml.mypals.litematicafastmove.config.FastMoveConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

// This mod's config is read and written together with Litematica's, stored under the FastMove category in litematica.json
@Mixin(value = Configs.class, remap = false)
public abstract class MixinConfigs {

    @WrapOperation(method = "loadFromFile", at = @At(value = "INVOKE",
            target = "Lfi/dy/masa/malilib/config/ConfigUtils;readConfigBase(Lcom/google/gson/JsonObject;Ljava/lang/String;Ljava/util/List;)V"))
    private static void loadFastMoveConfigs(JsonObject root, String category, List<?> options, Operation<Void> original) {
        original.call(root, category, options);
        if ("Visuals".equals(category)) {
            ConfigUtils.readConfigBase(root, FastMoveConfig.CATEGORY, FastMoveConfig.OPTIONS);
        }
    }

    @WrapOperation(method = "saveToFile", at = @At(value = "INVOKE",
            target = "Lfi/dy/masa/malilib/config/ConfigUtils;writeConfigBase(Lcom/google/gson/JsonObject;Ljava/lang/String;Ljava/util/List;)V"))
    private static void saveFastMoveConfigs(JsonObject root, String category, List<?> options, Operation<Void> original) {
        original.call(root, category, options);
        if ("Visuals".equals(category)) {
            ConfigUtils.writeConfigBase(root, FastMoveConfig.CATEGORY, FastMoveConfig.OPTIONS);
        }
    }
}

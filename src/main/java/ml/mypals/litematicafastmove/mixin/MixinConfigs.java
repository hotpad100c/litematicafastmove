package ml.mypals.litematicafastmove.mixin;

import com.google.gson.JsonObject;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigBase;
import ml.mypals.litematicafastmove.config.FastMoveConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

// 本模组配置随 Litematica 一起读写，保存在 litematica.json 的 FastMove 分类下
//
// 0.18.3's classes carry no LocalVariableTable, so the {@code @Local JsonObject} capture of the 26.3 mixin
// cannot be used; the root object is taken from the fifth read/writeConfigBase call instead (Visuals).
@Mixin(value = Configs.class, remap = false)
public abstract class MixinConfigs {

    @WrapOperation(method = "loadFromFile", at = @At(value = "INVOKE", ordinal = 4,
            target = "Lfi/dy/masa/malilib/config/ConfigUtils;readConfigBase(Lcom/google/gson/JsonObject;Ljava/lang/String;Ljava/util/List;)V"))
    private static void loadFastMoveConfigs(JsonObject root, String category, List<? extends IConfigBase> options,
                                            Operation<Void> original) {
        original.call(root, category, options);
        ConfigUtils.readConfigBase(root, FastMoveConfig.CATEGORY, FastMoveConfig.OPTIONS);
    }

    @WrapOperation(method = "saveToFile", at = @At(value = "INVOKE", ordinal = 4,
            target = "Lfi/dy/masa/malilib/config/ConfigUtils;writeConfigBase(Lcom/google/gson/JsonObject;Ljava/lang/String;Ljava/util/List;)V"))
    private static void saveFastMoveConfigs(JsonObject root, String category, List<? extends IConfigBase> options,
                                            Operation<Void> original) {
        original.call(root, category, options);
        ConfigUtils.writeConfigBase(root, FastMoveConfig.CATEGORY, FastMoveConfig.OPTIONS);
    }
}

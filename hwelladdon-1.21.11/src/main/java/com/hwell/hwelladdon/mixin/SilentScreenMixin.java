package com.hwell.hwelladdon.mixin;

import com.hwell.hwelladdon.modules.HwellAutoBuyShop;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class SilentScreenMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void onSetScreen(Screen screen, CallbackInfo ci) {
        // Nếu đang mua, bật silent, và server chuẩn bị mở GUI (không phải túi đồ cá nhân) -> Chặn
        if (HwellAutoBuyShop.isBuying && HwellAutoBuyShop.silentMode 
            && screen instanceof HandledScreen && !(screen instanceof InventoryScreen)) {
            ci.cancel();
        }
    }
}
package com.hwell.hwelladdon.mixin;

import com.hwell.hwelladdon.modules.HwellGuiPlus;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.ingame.*;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
public class InventoryControlMixin {

    @Inject(method = "tickMovement", at = @At("HEAD"))
    private void onTickMovement(CallbackInfo ci) {
        HwellGuiPlus guiModule = Modules.get().get(HwellGuiPlus.class);
        if (guiModule == null || !guiModule.isActive()) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen == null) return;

        // Không áp dụng cho chat / edit screen
        if (mc.currentScreen instanceof ChatScreen
            || mc.currentScreen instanceof BookEditScreen
            || mc.currentScreen instanceof SignEditScreen
            || mc.currentScreen instanceof AnvilScreen) {
            return;
        }

        // Force press các phím di chuyển nếu đang giữ
        long window = mc.getWindow().getHandle();
        applyKey(mc.options.forwardKey, GLFW.GLFW_KEY_W, window);
        applyKey(mc.options.backKey, GLFW.GLFW_KEY_S, window);
        applyKey(mc.options.leftKey, GLFW.GLFW_KEY_A, window);
        applyKey(mc.options.rightKey, GLFW.GLFW_KEY_D, window);
        applyKey(mc.options.jumpKey, GLFW.GLFW_KEY_SPACE, window);
        applyKey(mc.options.sneakKey, GLFW.GLFW_KEY_LEFT_SHIFT, window);
        applyKey(mc.options.sprintKey, GLFW.GLFW_KEY_LEFT_CONTROL, window);
    }

    private void applyKey(KeyBinding key, int keyCode, long window) {
        boolean pressed = GLFW.glfwGetKey(window, keyCode) == GLFW.GLFW_PRESS;
        key.setPressed(pressed);
    }
}
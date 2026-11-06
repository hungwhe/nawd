package com.hwell.hwelladdon;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;

public class AutoEnableListener {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;

        // Đã xoá logic AutoBuyShopBase.checkAllAutoEnable(); vì feature này đã bỏ
    }
}
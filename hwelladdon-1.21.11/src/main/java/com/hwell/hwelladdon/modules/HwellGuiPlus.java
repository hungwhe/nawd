package com.hwell.hwelladdon.modules;

import com.hwell.hwelladdon.HwellAddon;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.screens.ModulesScreen;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;

public class HwellGuiPlus extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> sounds = sgGeneral.add(new BoolSetting.Builder()
        .name("sounds")
        .description("Phát âm thanh khi mở GUI.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> blur = sgGeneral.add(new BoolSetting.Builder()
        .name("blur")
        .description("Làm mờ nền khi mở GUI.")
        .defaultValue(true)
        .build()
    );

    public HwellGuiPlus() {
        super(HwellAddon.AUTO_SHOP, "hwell-gui+",
            "Phím tắt mở giao diện Meteor Client (Mô phỏng GUI của Night).");
    }

    @Override
    public void onActivate() {
        // Nếu người chơi chưa vào game thì không làm gì cả
        if (mc.player == null || mc.world == null) return;

        // Mở màn hình Modules của Meteor
        if (mc.currentScreen == null) {
            mc.setScreen(new ModulesScreen(GuiThemes.get()));
        }
    }
}
package com.hwell.hwelladdon.hud;

import com.hwell.hwelladdon.HwellAddon;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;

public class InventoryHud extends HudElement {
    public static final HudElementInfo<InventoryHud> INFO = new HudElementInfo<>(
        HwellAddon.HUD_GROUP,
        "inventory-hud",
        "Hiển thị inventory.",
        InventoryHud::new
    );

    private final MinecraftClient mc = MinecraftClient.getInstance();

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Kích thước ô item (1.0 = 16px).")
        .defaultValue(1.0)
        .range(0.5, 3.0)
        .sliderRange(0.5, 3.0)
        .build()
    );

    private final Setting<Boolean> showHotbar = sgGeneral.add(new BoolSetting.Builder()
        .name("show-hotbar")
        .description("Hiển thị hotbar.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showMainInv = sgGeneral.add(new BoolSetting.Builder()
        .name("show-main-inventory")
        .description("Hiển thị inventory chính.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> highlightSelected = sgGeneral.add(new BoolSetting.Builder()
        .name("highlight-selected")
        .description("Highlight slot đang cầm.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showSlotBackground = sgGeneral.add(new BoolSetting.Builder()
        .name("show-slot-background")
        .description("Hiển thị nền từng ô.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgGeneral.add(new ColorSetting.Builder()
        .name("background-color")
        .description("Màu nền tổng.")
        .defaultValue(new SettingColor(0, 0, 0, 100))
        .build()
    );

    private final Setting<SettingColor> slotColor = sgGeneral.add(new ColorSetting.Builder()
        .name("slot-color")
        .description("Màu nền từng ô.")
        .defaultValue(new SettingColor(30, 30, 30, 150))
        .build()
    );

    public InventoryHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        if (mc.player == null) return;

        double scaleValue = scale.get();
        double boxSize = 16.0 * scaleValue;
        double gap = 1.0 * scaleValue;
        double padding = 2.0 * scaleValue;

        int cols = 9;
        int rows = 0;
        if (showHotbar.get()) rows++;
        if (showMainInv.get()) rows += 3;

        if (rows == 0) {
            setSize(0, 0);
            return;
        }

        double totalWidth = cols * boxSize + (cols - 1) * gap + padding * 2;
        double totalHeight = rows * boxSize + (rows - 1) * gap + padding * 2;

        setSize(totalWidth, totalHeight);

        double startX = x;
        double startY = y;

        // Vẽ nền tổng
        renderer.quad(startX, startY, totalWidth, totalHeight, backgroundColor.get());

        double currentY = startY + padding;

        // === HOTBAR (0-8) ===
        if (showHotbar.get()) {
            for (int col = 0; col < 9; col++) {
                double drawX = startX + padding + col * (boxSize + gap);
                ItemStack stack = mc.player.getInventory().getStack(col);

                // Vẽ nền ô
                if (showSlotBackground.get()) {
                    renderer.quad(drawX, currentY, boxSize, boxSize, slotColor.get());
                }

                // Vẽ item + overlay (số lượng + độ bền)
                if (!stack.isEmpty()) {
                    renderer.item(stack, (int) drawX, (int) currentY, (float) scaleValue, true);
                }

                // Highlight slot đang chọn
                if (highlightSelected.get() && col == mc.player.getInventory().getSelectedSlot()) {
                    renderer.quad(drawX, currentY, boxSize, boxSize, new Color(255, 255, 255, 80));
                }
            }
            currentY += boxSize + gap;
        }

        // === INVENTORY CHÍNH (9-35) ===
        if (showMainInv.get()) {
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    int index = 9 + row * 9 + col;
                    double drawX = startX + padding + col * (boxSize + gap);
                    double drawY = currentY + row * (boxSize + gap);

                    ItemStack stack = mc.player.getInventory().getStack(index);

                    // Vẽ nền ô
                    if (showSlotBackground.get()) {
                        renderer.quad(drawX, drawY, boxSize, boxSize, slotColor.get());
                    }

                    // Vẽ item + overlay (số lượng + độ bền)
                    if (!stack.isEmpty()) {
                        renderer.item(stack, (int) drawX, (int) drawY, (float) scaleValue, true);
                    }
                }
            }
        }
    }
}
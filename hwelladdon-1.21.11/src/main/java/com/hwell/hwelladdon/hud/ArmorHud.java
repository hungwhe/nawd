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

public class ArmorHud extends HudElement {
    public static final HudElementInfo<ArmorHud> INFO = new HudElementInfo<>(
        HwellAddon.HUD_GROUP,
        "armor-hud",
        "Hiển thị giáp + độ bền.",
        ArmorHud::new
    );

    private final MinecraftClient mc = MinecraftClient.getInstance();

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Kích thước ô giáp (1.0 = 16px).")
        .defaultValue(1.0)
        .range(0.5, 3.0)
        .sliderRange(0.5, 3.0)
        .build()
    );

    private final Setting<Boolean> horizontal = sgGeneral.add(new BoolSetting.Builder()
        .name("horizontal")
        .description("Sắp xếp ngang.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showDurability = sgGeneral.add(new BoolSetting.Builder()
        .name("show-durability")
        .description("Hiển thị % độ bền (phía trên ô).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showDurabilityBar = sgGeneral.add(new BoolSetting.Builder()
        .name("show-durability-bar")
        .description("Hiển thị thanh độ bền.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showSlotBackground = sgGeneral.add(new BoolSetting.Builder()
        .name("show-slot-background")
        .description("Hiển thị nền ô.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> slotColor = sgGeneral.add(new ColorSetting.Builder()
        .name("slot-color")
        .description("Màu nền ô.")
        .defaultValue(new SettingColor(30, 30, 30, 180))
        .build()
    );

    public ArmorHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        if (mc.player == null) return;

        double scaleValue = scale.get();
        double boxSize = 16.0 * scaleValue;
        double gap = 2.0 * scaleValue;
        double padding = 2.0 * scaleValue;

        // Chừa chỗ trên cho text %
        double textSpace = showDurability.get() ? (10.0 * scaleValue) : 0;

        int cols = horizontal.get() ? 4 : 1;
        int rows = horizontal.get() ? 1 : 4;

        double totalWidth = cols * boxSize + (cols - 1) * gap + padding * 2;
        double totalHeight = rows * boxSize + (rows - 1) * gap + padding * 2 + textSpace;

        setSize(totalWidth, totalHeight);

        double startX = x;
        double startY = y + textSpace; // Dịch xuống để chừa chỗ trên

        for (int i = 0; i < 4; i++) {
            // Slot armor: 39 = helmet, 38 = chestplate, 37 = leggings, 36 = boots
            int armorSlot = 39 - i;
            ItemStack armor = mc.player.getInventory().getStack(armorSlot);

            int col = horizontal.get() ? i : 0;
            int row = horizontal.get() ? 0 : i;

            double drawX = startX + padding + col * (boxSize + gap);
            double drawY = startY + padding + row * (boxSize + gap);

            // Vẽ nền ô
            if (showSlotBackground.get()) {
                renderer.quad(drawX, drawY, boxSize, boxSize, slotColor.get());
            }

            if (!armor.isEmpty()) {
                // Vẽ item + overlay
                renderer.item(armor, (int) drawX, (int) drawY, (float) scaleValue, true);

                if (armor.isDamageable()) {
                    int maxDamage = armor.getMaxDamage();
                    int damage = armor.getDamage();
                    int remaining = maxDamage - damage;
                    float percent = (float) remaining / maxDamage;
                    int percentInt = (int) (percent * 100);

                    // === THANH ĐỘ BỀN (dưới ô) ===
                    if (showDurabilityBar.get()) {
                        double barHeight = 2.0 * scaleValue;
                        double barY = drawY + boxSize - barHeight;

                        // Nền đỏ
                        renderer.quad(drawX, barY, boxSize, barHeight, new Color(255, 0, 0, 200));

                        // Thanh xanh
                        double greenWidth = boxSize * percent;
                        if (greenWidth > 0) {
                            Color greenColor = getDurabilityColor(percent);
                            renderer.quad(drawX, barY, greenWidth, barHeight, greenColor);
                        }
                    }

                    // === SỐ % ĐỘ BỀN (TRÊN ô) ===
                    if (showDurability.get()) {
                        String text = percentInt + "%";
                        double textScale = 0.7 * scaleValue;
                        double textWidth = renderer.textWidth(text, false) * textScale;
                        double textHeight = renderer.textHeight(false) * textScale;

                        double textX = drawX + (boxSize - textWidth) / 2.0;
                        double textY = drawY - textHeight - 2.0; // Phía trên ô

                        renderer.text(text, textX, textY, new Color(255, 255, 255, 255), true, textScale);
                    }
                }
            }
        }
    }

    private Color getDurabilityColor(float percent) {
        if (percent > 0.5f) return new Color(0, 255, 0, 200);
        if (percent > 0.2f) return new Color(255, 255, 0, 200);
        return new Color(255, 100, 0, 200);
    }
}
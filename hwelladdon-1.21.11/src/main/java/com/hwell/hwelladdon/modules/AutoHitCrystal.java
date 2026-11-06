package com.hwell.hwelladdon.modules;

import com.hwell.hwelladdon.HwellAddon;
import meteordevelopment.meteorclient.events.entity.player.InteractBlockEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class AutoHitCrystal extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // ============================================================
    // SETTINGS
    // ============================================================
    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Khoảng cách tối đa để đập crystal.")
        .defaultValue(4.5)
        .range(1.0, 6.0)
        .sliderRange(1.0, 6.0)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay giữa các lần đập crystal (ms).")
        .defaultValue(0)
        .range(0, 1000)
        .sliderRange(0, 1000)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Tự động quay đầu về phía crystal trước khi đập.")
        .defaultValue(true)
        .build()
    );

    // ============================================================
    // STATE
    // ============================================================
    // Lưu vị trí block vừa click chuột phải với End Crystal (timestamp để dọn dẹp)
    private final Map<BlockPos, Long> placedPositions = new HashMap<>();
    private int cooldownTicks;

    public AutoHitCrystal() {
        super(HwellAddon.PVP_SUPER_LEGIT, "auto-hit-crystal",
            "Tự động đập crystal do mình đặt ra.");
    }

    @Override
    public void onActivate() {
        cooldownTicks = 0;
        placedPositions.clear();
    }

    @Override
    public void onDeactivate() {
        placedPositions.clear();
    }

    // ============================================================
    // TRACK: Ghi nhớ mỗi lần click chuột phải bằng End Crystal
    // ============================================================
    @EventHandler
    private void onInteractBlock(InteractBlockEvent event) {
        if (mc.player == null) return;

        // ✅ Track cả mainhand và offhand
        if (mc.player.getMainHandStack().getItem() == Items.END_CRYSTAL
            || mc.player.getOffHandStack().getItem() == Items.END_CRYSTAL) {

            BlockHitResult hit = event.result;
            BlockPos crystalPos = hit.getBlockPos().offset(hit.getSide());
            placedPositions.put(crystalPos, System.currentTimeMillis());
        }
    }

    // ============================================================
    // MAIN TICK
    // ============================================================
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        // Dọn dẹp vị trí đã đặt quá 3 giây (tránh memory leak)
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<BlockPos, Long>> iterator = placedPositions.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().getValue() > 3000) {
                iterator.remove();
            }
        }

        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }

        EndCrystalEntity targetCrystal = null;
        double bestDistance = range.get();

        // Quét crystal
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof EndCrystalEntity crystal)) continue;
            if (!crystal.isAlive()) continue;

            // ✅ Chỉ đập crystal do mình đặt
            if (!placedPositions.containsKey(crystal.getBlockPos())) continue;

            double dist = mc.player.distanceTo(crystal);
            if (dist <= bestDistance) {
                bestDistance = dist;
                targetCrystal = crystal;
            }
        }

        // Đập crystal
        if (targetCrystal != null) {
            if (rotate.get()) {
                float yaw = (float) Math.toDegrees(Math.atan2(
                    targetCrystal.getZ() - mc.player.getZ(),
                    targetCrystal.getX() - mc.player.getX())) - 90;
                float pitch = (float) -Math.toDegrees(Math.atan2(
                    targetCrystal.getY() + 0.5 - mc.player.getEyeY(),
                    Math.sqrt(
                        Math.pow(targetCrystal.getX() - mc.player.getX(), 2)
                        + Math.pow(targetCrystal.getZ() - mc.player.getZ(), 2))));
                Rotations.rotate(yaw, pitch);
            }

            mc.interactionManager.attackEntity(mc.player, targetCrystal);
            mc.player.swingHand(Hand.MAIN_HAND);

            cooldownTicks = getTicks();
        }
    }

    private int getTicks() {
        if (delay.get() <= 0) return 0;
        return Math.max(1, delay.get() / 50);
    }
}
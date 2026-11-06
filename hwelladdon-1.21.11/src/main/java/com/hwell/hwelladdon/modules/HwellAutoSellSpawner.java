package com.hwell.hwelladdon.modules;

import com.hwell.hwelladdon.HwellAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class HwellAutoSellSpawner extends Module {
    // ============================================================
    // HARDCODE
    // ============================================================
    private static final int SELL_SLOT = 50;              // ✅ Slot 50 thay vì 52
    private static final int WAIT_GUI_TIMEOUT = 40;
    private static final int SEARCH_RADIUS = 5;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSpeed = settings.createGroup("Speed");
    private final SettingGroup sgSafety = settings.createGroup("Safety");

    // ============================================================
    // GENERAL
    // ============================================================
    private final Setting<Boolean> noLimit = sgGeneral.add(new BoolSetting.Builder()
        .name("no-limit")
        .description("Chạy mãi (chỉ dừng khi hurt / totem / tắt tay).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> loopCount = sgGeneral.add(new IntSetting.Builder()
        .name("loop-count")
        .description("Số lần tối đa (1 - 1,000,000). Chỉ dùng khi no-limit OFF.")
        .defaultValue(10)
        .range(1, 1_000_000)
        .sliderRange(1, 100)
        .noSlider()
        .visible(() -> !noLimit.get())
        .build()
    );

    private final Setting<Integer> loopDelayMs = sgGeneral.add(new IntSetting.Builder()
        .name("loop-delay")
        .description("Thời gian giữa các loop (ms). 1 - 1,000,000.")
        .defaultValue(1000)
        .range(1, 1_000_000)
        .sliderRange(1, 10_000)
        .noSlider()
        .build()
    );

    // ============================================================
    // SPEED
    // ============================================================
    private final Setting<Integer> actionDelayMs = sgSpeed.add(new IntSetting.Builder()
        .name("action-delay")
        .description("Delay giữa các thao tác trong 1 loop (ms).")
        .defaultValue(100)
        .range(0, 5000)
        .sliderRange(0, 1000)
        .build()
    );

    private final Setting<Integer> maxCloseAttempts = sgSpeed.add(new IntSetting.Builder()
        .name("max-close-attempts")
        .description("Số packet close tối đa để đóng GUI (safety).")
        .defaultValue(20)
        .range(1, 100)
        .sliderRange(1, 50)
        .build()
    );

    // ============================================================
    // SAFETY
    // ============================================================
    private final Setting<Boolean> turnOffOnHurt = sgSafety.add(new BoolSetting.Builder()
        .name("turn-off-on-hurt")
        .description("Tắt khi bị đánh.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> turnOffOnTotemPop = sgSafety.add(new BoolSetting.Builder()
        .name("turn-off-on-totem-pop")
        .description("Tắt khi vỡ Totem.")
        .defaultValue(true)
        .build()
    );

    // ============================================================
    // STATE
    // ============================================================
    private enum State {
        SEARCHING, ROTATING, OPENING, WAITING_GUI,
        CLICKING_SELL, CLOSING, LOOP_DELAY
    }

    private State state = State.SEARCHING;
    private int timer = 0;
    private int currentLoop = 0;
    private int waitGuiTicks = 0;
    private int closeRemaining = 0;
    private boolean wasHoldingTotem = false;
    private BlockPos targetPos = null;

    public HwellAutoSellSpawner() {
        super(HwellAddon.FARM, "hwell-auto-sell-spawner",
            "Auto sell spawner: xoay → click spawner → click slot 50 → close.");
    }

    @Override
    public void onActivate() {
        state = State.SEARCHING;
        timer = 0;
        currentLoop = 0;
        waitGuiTicks = 0;
        closeRemaining = 0;
        wasHoldingTotem = hasTotem();
        targetPos = null;
    }

    @Override
    public void onDeactivate() {
        state = State.SEARCHING;
        if (mc.player != null && mc.player.currentScreenHandler != mc.player.playerScreenHandler) {
            mc.player.closeHandledScreen();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.interactionManager == null || mc.world == null) return;

        // ============================================================
        // SAFETY
        // ============================================================
        if (turnOffOnHurt.get() && mc.player.hurtTime > 0) {
            toggle();
            return;
        }

        boolean holdingTotem = hasTotem();
        if (turnOffOnTotemPop.get() && wasHoldingTotem && !holdingTotem) {
            toggle();
            return;
        }
        wasHoldingTotem = holdingTotem;

        // ============================================================
        // LOOP LIMIT
        // ============================================================
        if (!noLimit.get() && currentLoop >= loopCount.get() && state == State.SEARCHING) {
            toggle();
            return;
        }

        if (timer > 0) { timer--; return; }

        ScreenHandler handler = mc.player.currentScreenHandler;
        boolean guiOpen = handler != mc.player.playerScreenHandler;

        switch (state) {
            // ============================================================
            // 1. TÌM SPAWNER
            // ============================================================
            case SEARCHING -> {
                // Đảm bảo GUI đã đóng trước khi tìm spawner
                if (guiOpen) {
                    mc.player.closeHandledScreen();
                    timer = getActionTicks();
                    return;
                }

                targetPos = findSpawner(SEARCH_RADIUS);
                if (targetPos == null) {
                    if (noLimit.get()) {
                        // Không có spawner → chờ rồi tìm lại
                        timer = getLoopDelayTicks();
                        return;
                    }
                    toggle();
                    return;
                }
                state = State.ROTATING;
                timer = 1;
            }

            // ============================================================
            // 2. XOAY MẶT VỀ SPAWNER
            // ============================================================
            case ROTATING -> {
                if (targetPos == null) {
                    state = State.SEARCHING;
                    timer = getActionTicks();
                    return;
                }
                rotateTo(targetPos);
                state = State.OPENING;
                timer = getActionTicks();
            }

            // ============================================================
            // 3. CHUỘT PHẢI VÀO SPAWNER
            // ============================================================
            case OPENING -> {
                if (targetPos == null) {
                    state = State.SEARCHING;
                    timer = getActionTicks();
                    return;
                }
                interactBlock(targetPos);
                state = State.WAITING_GUI;
                waitGuiTicks = WAIT_GUI_TIMEOUT;
                timer = getActionTicks();
            }

            // ============================================================
            // 4. CHỜ GUI SPAWNER MỞ
            // ============================================================
            case WAITING_GUI -> {
                if (guiOpen) {
                    state = State.CLICKING_SELL;
                    timer = getActionTicks();
                } else {
                    if (--waitGuiTicks <= 0) {
                        // Timeout → loop mới
                        state = State.LOOP_DELAY;
                        timer = getLoopDelayTicks();
                    } else {
                        timer = 1;
                    }
                }
            }

            // ============================================================
            // 5. CLICK SLOT 50
            // ============================================================
            case CLICKING_SELL -> {
                if (!guiOpen) {
                    state = State.LOOP_DELAY;
                    timer = getLoopDelayTicks();
                    return;
                }

                // Click slot 50 nếu slot tồn tại (tránh exception)
                if (SELL_SLOT < handler.slots.size()) {
                    mc.interactionManager.clickSlot(
                        handler.syncId,
                        SELL_SLOT,
                        0,
                        SlotActionType.PICKUP,
                        mc.player
                    );
                }

                closeRemaining = maxCloseAttempts.get();
                state = State.CLOSING;
                timer = getActionTicks();
            }

            // ============================================================
            // 6. CLOSE PACKET ĐẾN KHI GUI ĐÓNG HẲN
            // ============================================================
            case CLOSING -> {
                // GUI đã đóng hẳn → loop tiếp
                if (!guiOpen) {
                    closeRemaining = 0;
                    state = State.LOOP_DELAY;
                    timer = getLoopDelayTicks();
                    return;
                }

                // Gửi close packet
                mc.player.closeHandledScreen();
                closeRemaining--;

                if (closeRemaining > 0) {
                    // Còn attempts → chờ tick sau thử lại
                    timer = getActionTicks();
                } else {
                    // Hết attempts mà GUI vẫn mở → tắt (safety)
                    toggle();
                }
            }

            // ============================================================
            // 7. CHỜ DELAY GIỮA CÁC LOOP
            // ============================================================
            case LOOP_DELAY -> {
                currentLoop++;
                state = State.SEARCHING;
                timer = 1;
            }
        }
    }

    // ============================================================
    // HELPERS
    // ============================================================
    private BlockPos findSpawner(int radius) {
        BlockPos playerPos = mc.player.getBlockPos();
        for (BlockPos pos : BlockPos.iterateOutwards(playerPos, radius, radius, radius)) {
            BlockState blockState = mc.world.getBlockState(pos);
            if (blockState.getBlock() == Blocks.SPAWNER
                || blockState.getBlock() == Blocks.TRIAL_SPAWNER) {
                return pos.toImmutable();
            }
        }
        return null;
    }

    private void rotateTo(BlockPos pos) {
        Vec3d center = Vec3d.ofCenter(pos);
        float yaw = (float) Math.toDegrees(Math.atan2(center.z - mc.player.getZ(), center.x - mc.player.getX())) - 90;
        float pitch = (float) -Math.toDegrees(Math.atan2(center.y - mc.player.getEyeY(),
                Math.sqrt(Math.pow(center.x - mc.player.getX(), 2) + Math.pow(center.z - mc.player.getZ(), 2))));
        Rotations.rotate(yaw, pitch);
    }

    private void interactBlock(BlockPos pos) {
        Vec3d center = Vec3d.ofCenter(pos);
        BlockHitResult hit = new BlockHitResult(center, Direction.UP, pos, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private boolean hasTotem() {
        if (mc.player == null) return false;
        return mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)
            || mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING);
    }

    private int getActionTicks() {
        return Math.max(1, actionDelayMs.get() / 50);
    }

    private int getLoopDelayTicks() {
        return Math.max(1, loopDelayMs.get() / 50);
    }
}
package com.hwell.hwelladdon.modules;

import com.hwell.hwelladdon.HwellAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class AnchorMarcoFast extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSwitch = settings.createGroup("Switch");

    // ============================================================
    // GENERAL
    // ============================================================
    private final Setting<Integer> anchorSlot = sgGeneral.add(new IntSetting.Builder()
        .name("anchor-slot").description("Slot hotbar chứa Respawn Anchor (1-9).")
        .defaultValue(1).range(1, 9).sliderRange(1, 9).build());
    private final Setting<Integer> glowstoneSlot = sgGeneral.add(new IntSetting.Builder()
        .name("glowstone-slot").description("Slot hotbar chứa Glowstone (1-9).")
        .defaultValue(2).range(1, 9).sliderRange(1, 9).build());
    private final Setting<Integer> totemSlot = sgGeneral.add(new IntSetting.Builder()
        .name("totem-slot").description("Slot hotbar chứa Totem (1-9).")
        .defaultValue(3).range(1, 9).sliderRange(1, 9).build());
    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay").description("Delay giữa các hành động (ms).")
        .defaultValue(0).range(0, 1000).sliderRange(0, 1000).build());

    // ============================================================
    // SWITCH
    // ============================================================
    private final Setting<Boolean> silentSwitch = sgSwitch.add(new BoolSetting.Builder()
        .name("silent-switch")
        .description("Đổi slot siêu nhanh, không hiện trên màn hình (chỉ server biết).")
        .defaultValue(false).build());

    private final Setting<Boolean> swapBack = sgSwitch.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Quay lại slot ban đầu sau khi combo xong.")
        .defaultValue(true).build());

    private final Setting<Boolean> customSwitchBack = sgSwitch.add(new BoolSetting.Builder()
        .name("custom-switch-back")
        .description("Sau khi combo xong chuyển về slot tuỳ chọn (1-9) thay vì slot ban đầu.")
        .defaultValue(false).build());

    private final Setting<Integer> switchBackSlot = sgSwitch.add(new IntSetting.Builder()
        .name("switch-back-slot")
        .description("Slot muốn chuyển về sau khi combo xong (1-9).")
        .defaultValue(1).range(1, 9).sliderRange(1, 9)
        .visible(customSwitchBack::get)
        .build());

    // ============================================================
    // STATE
    // ============================================================
    private enum State { IDLE, EXTINGUISH_FIRE, PLACE_ANCHOR, CHARGE_GLOWSTONE, DETONATE_TOTEM, SWAP_BACK }

    private State state = State.IDLE;
    private int timer = 0;
    private int originalSlot = -1;
    private BlockPos targetPos = null;
    private boolean wasUsePressed = false;

    private static boolean isRunning = false;
    public static boolean isRunningCombo() { return isRunning; }

    public AnchorMarcoFast() {
        super(HwellAddon.PVP_SUPER_LEGIT, "anchor-marco-fast",
            "Tự động nạp Glowstone và kích nổ Anchor bằng Totem.");
    }

    @Override
    public void onActivate() {
        state = State.IDLE;
        timer = 0;
        originalSlot = -1;
        targetPos = null;
        wasUsePressed = false;
        isRunning = false;
    }

    @Override
    public void onDeactivate() {
        isRunning = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        if (EasyDTap.isRunningCombo()) {
            isRunning = false;
            return;
        }

        if (state == State.IDLE) {
            isRunning = false;
            boolean isUsePressed = mc.options.useKey.isPressed();

            if (isUsePressed && !wasUsePressed) {
                if (mc.player.getMainHandStack().getItem() == Items.RESPAWN_ANCHOR) {
                    if (mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.BLOCK) {
                        BlockHitResult hit = (BlockHitResult) mc.crosshairTarget;

                        BlockPos clickedPos = hit.getBlockPos();
                        BlockState clickedState = mc.world.getBlockState(clickedPos);
                        Direction side = hit.getSide();

                        originalSlot = mc.player.getInventory().getSelectedSlot();

                        if (clickedState.getBlock() == Blocks.FIRE || clickedState.getBlock() == Blocks.SOUL_FIRE) {
                            targetPos = clickedPos;
                            state = State.EXTINGUISH_FIRE;
                            isRunning = true;
                            timer = 1;
                        } else if (clickedState.getBlock() instanceof RespawnAnchorBlock) {
                            targetPos = clickedPos;
                            state = State.CHARGE_GLOWSTONE;
                            isRunning = true;
                            timer = getTicks();
                        } else {
                            if (!clickedState.isSolidBlock(mc.world, clickedPos)) {
                                targetPos = clickedPos;
                            } else {
                                targetPos = clickedPos.offset(side);
                            }
                            state = State.CHARGE_GLOWSTONE;
                            isRunning = true;
                            timer = getTicks();
                        }
                    }
                }
            }
            wasUsePressed = isUsePressed;
        } else {
            isRunning = true;
            if (timer > 0) {
                timer--;
                return;
            }

            switch (state) {
                case EXTINGUISH_FIRE -> {
                    BlockState st = mc.world.getBlockState(targetPos);
                    if (st.getBlock() == Blocks.FIRE || st.getBlock() == Blocks.SOUL_FIRE) {
                        mc.interactionManager.attackBlock(targetPos, Direction.UP);
                        mc.player.swingHand(Hand.MAIN_HAND);
                    }
                    state = State.PLACE_ANCHOR;
                    timer = 1;
                }
                case PLACE_ANCHOR -> {
                    BlockState st = mc.world.getBlockState(targetPos);
                    if (st.isAir()) {
                        Vec3d hitVec = Vec3d.ofCenter(targetPos).add(0, 0.5, 0);
                        BlockHitResult placeHit = new BlockHitResult(hitVec, Direction.UP, targetPos, false);
                        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, placeHit);
                        mc.player.swingHand(Hand.MAIN_HAND);
                    }
                    state = State.CHARGE_GLOWSTONE;
                    timer = 1;
                }
                case CHARGE_GLOWSTONE -> {
                    if (targetPos != null) {
                        int slot = glowstoneSlot.get() - 1;
                        doSwap(slot);
                        clickBlock(targetPos);
                        // ✅ Silent: khôi phục ngay để giữ visual + state server-client sync
                        if (silentSwitch.get() && originalSlot != -1) {
                            silentSwap(originalSlot);
                        }
                        state = State.DETONATE_TOTEM;
                        timer = getTicks();
                    }
                }
                case DETONATE_TOTEM -> {
                    if (targetPos != null) {
                        int slot = totemSlot.get() - 1;
                        doSwap(slot);
                        clickBlock(targetPos);
                        if (silentSwitch.get() && originalSlot != -1) {
                            silentSwap(originalSlot);
                        }
                        state = State.SWAP_BACK;
                        timer = getTicks();
                    }
                }
                case SWAP_BACK -> {
                    // Đảm bảo server sync nếu silent
                    if (silentSwitch.get() && originalSlot != -1) {
                        silentSwap(originalSlot);
                    }

                    // Xác định slot cuối cùng
                    int endSlot = -1;
                    if (customSwitchBack.get()) {
                        endSlot = switchBackSlot.get() - 1;
                    } else if (swapBack.get() && originalSlot != -1) {
                        endSlot = originalSlot;
                    }

                    // Nếu slot cuối khác slot hiện tại của client → swap visible
                    if (endSlot != -1 && endSlot != mc.player.getInventory().getSelectedSlot()) {
                        InvUtils.swap(endSlot, false);
                    }

                    state = State.IDLE;
                    isRunning = false;
                    targetPos = null;
                    originalSlot = -1;
                }
            }
        }
    }

    // ✅ Swap bình thường hoặc silent tuỳ setting
    private void doSwap(int slot) {
        if (silentSwitch.get()) {
            silentSwap(slot);
        } else {
            InvUtils.swap(slot, false);
        }
    }

    // ✅ Silent swap: chỉ gửi packet, không thay đổi visual client
    private void silentSwap(int slot) {
        if (mc.getNetworkHandler() != null && mc.player != null) {
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        }
    }

    private void clickBlock(BlockPos pos) {
        Vec3d hitVec = Vec3d.ofCenter(pos).add(0, 0.5, 0);
        BlockHitResult placeHit = new BlockHitResult(hitVec, Direction.UP, pos, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, placeHit);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private int getTicks() {
        if (delay.get() <= 0) return 0;
        return Math.max(1, delay.get() / 50);
    }
}
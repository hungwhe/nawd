package com.hwell.hwelladdon.modules;

import com.hwell.hwelladdon.HwellAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class EasyDTap extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFilter = settings.createGroup("Filter");
    private final SettingGroup sgSwitch = settings.createGroup("Switch");

    // ============================================================
    // GENERAL
    // ============================================================
    private final Setting<Integer> obiSlot = sgGeneral.add(new IntSetting.Builder()
        .name("obsidian-slot").description("Slot hotbar chứa Obsidian (1-9).")
        .defaultValue(1).range(1, 9).sliderRange(1, 9).build());
    private final Setting<Integer> crystalSlot = sgGeneral.add(new IntSetting.Builder()
        .name("crystal-slot").description("Slot hotbar chứa End Crystal (1-9).")
        .defaultValue(2).range(1, 9).sliderRange(1, 9).build());
    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay").description("Delay giữa các hành động (ms).")
        .defaultValue(0).range(0, 1000).sliderRange(0, 1000).build());

    // ============================================================
    // FILTER
    // ============================================================
    private final Setting<Boolean> notWorkWithBlock = sgFilter.add(new BoolSetting.Builder()
        .name("not-work-with-block")
        .description("Không chạy khi đang cầm block (chỉ item như kiếm, búa, food… mới chạy).")
        .defaultValue(false).build());

    private final Setting<Boolean> obsOnly = sgFilter.add(new BoolSetting.Builder()
        .name("obs-only")
        .description("Chỉ chạy khi cầm Obsidian (các block khác bị bỏ qua, item vẫn chạy).")
        .defaultValue(false).build());

    // ============================================================
    // SWITCH
    // ============================================================
    private final Setting<Boolean> silentSwitch = sgSwitch.add(new BoolSetting.Builder()
        .name("silent-switch")
        .description("Đổi slot siêu nhanh, không hiện trên màn hình (chỉ server biết).")
        .defaultValue(false).build());

    private final Setting<Boolean> swapBack = sgSwitch.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Quay lại slot ban đầu sau khi đặt xong.")
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
    private enum State { IDLE, PLACING_OBSIDIAN, PLACING_CRYSTAL, SWAP_BACK }

    private State state = State.IDLE;
    private int timer = 0;
    private int originalSlot = -1;
    private BlockPos targetPos = null;
    private Direction targetSide = null;
    private boolean wasUsePressed = false;

    private static boolean isRunning = false;
    public static boolean isRunningCombo() { return isRunning; }

    public EasyDTap() {
        super(HwellAddon.PVP_SUPER_LEGIT, "easy-dtap",
            "Tự động đặt obsidian và crystal khi click chuột phải.");
    }

    @Override
    public void onActivate() {
        state = State.IDLE;
        timer = 0;
        originalSlot = -1;
        targetPos = null;
        targetSide = null;
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

        if (AnchorMarcoFast.isRunningCombo()) {
            isRunning = false;
            return;
        }

        if (state == State.IDLE) {
            isRunning = false;
            boolean isUsePressed = mc.options.useKey.isPressed();
            boolean wasPressed = wasUsePressed;
            wasUsePressed = isUsePressed;

            if (!isUsePressed || wasPressed) return;
            if (mc.crosshairTarget == null || mc.crosshairTarget.getType() != HitResult.Type.BLOCK) return;

            BlockHitResult hit = (BlockHitResult) mc.crosshairTarget;
            Item mainItem = mc.player.getMainHandStack().getItem();

            // Skip item dành riêng cho AnchorMarcoFast
            if (mainItem == Items.RESPAWN_ANCHOR || mainItem == Items.GLOWSTONE) {
                return;
            }

            // Filter 1: Not-work-with-block
            if (notWorkWithBlock.get() && mainItem instanceof BlockItem) {
                return;
            }

            // Filter 2: Obs-only
            if (obsOnly.get() && mainItem instanceof BlockItem && mainItem != Items.OBSIDIAN) {
                return;
            }

            boolean holdingCrystal = mainItem == Items.END_CRYSTAL;
            boolean clickingObsidian = mc.world.getBlockState(hit.getBlockPos()).getBlock() == Blocks.OBSIDIAN
                                    || mc.world.getBlockState(hit.getBlockPos()).getBlock() == Blocks.BEDROCK;

            if (holdingCrystal && clickingObsidian) {
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                mc.player.swingHand(Hand.MAIN_HAND);
            } else {
                if (!mc.player.getMainHandStack().isEmpty()) {
                    targetPos = hit.getBlockPos();
                    targetSide = hit.getSide();
                    originalSlot = mc.player.getInventory().getSelectedSlot();

                    doSwap(obiSlot.get() - 1);
                    state = State.PLACING_OBSIDIAN;
                    isRunning = true;
                    timer = getTicks();
                }
            }
        } else {
            isRunning = true;
            if (timer > 0) {
                timer--;
                return;
            }

            switch (state) {
                case PLACING_OBSIDIAN -> {
                    if (targetPos != null && targetSide != null) {
                        Vec3d hitVec = Vec3d.ofCenter(targetPos).add(Vec3d.of(targetSide.getVector()).multiply(0.5));
                        BlockHitResult placeHit = new BlockHitResult(hitVec, targetSide, targetPos, false);
                        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, placeHit);
                        mc.player.swingHand(Hand.MAIN_HAND);

                        // Silent: khôi phục ngay sau khi đặt obsidian
                        if (silentSwitch.get() && originalSlot != -1) {
                            silentSwap(originalSlot);
                        }

                        doSwap(crystalSlot.get() - 1);
                        state = State.PLACING_CRYSTAL;
                        timer = getTicks();
                    }
                }
                case PLACING_CRYSTAL -> {
                    if (targetPos != null && targetSide != null) {
                        BlockPos obsidianPos = targetPos.offset(targetSide);
                        Vec3d hitVec = Vec3d.ofCenter(obsidianPos);
                        BlockHitResult placeHit = new BlockHitResult(hitVec, Direction.UP, obsidianPos, false);
                        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, placeHit);
                        mc.player.swingHand(Hand.MAIN_HAND);

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
                    targetSide = null;
                    originalSlot = -1;
                }
            }
        }
    }

    // ✅ Swap thường hoặc silent tuỳ setting
    private void doSwap(int slot) {
        if (silentSwitch.get()) {
            silentSwap(slot);
        } else {
            InvUtils.swap(slot, false);
        }
    }

    // ✅ Silent swap: chỉ gửi packet, không đổi visual client
    private void silentSwap(int slot) {
        if (mc.getNetworkHandler() != null && mc.player != null) {
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        }
    }

    private int getTicks() {
        if (delay.get() <= 0) return 0;
        return Math.max(1, delay.get() / 50);
    }
}
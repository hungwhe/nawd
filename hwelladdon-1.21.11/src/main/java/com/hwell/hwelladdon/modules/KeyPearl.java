package com.hwell.hwelladdon.modules;

import com.hwell.hwelladdon.HwellAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;

public class KeyPearl extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // ============================================================
    // SETTINGS
    // ============================================================
    private final Setting<Keybind> triggerKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("trigger-key")
        .description("Phím để ném pearl (không dùng keybind toggle của Meteor).")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Integer> pearlSlot = sgGeneral.add(new IntSetting.Builder()
        .name("pearl-slot")
        .description("Slot hotbar chứa Ender Pearl (1-9).")
        .defaultValue(1).range(1, 9).sliderRange(1, 9).build()
    );

    private final Setting<Boolean> silentSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("silent-switch")
        .description("Đổi slot bằng packet, không hiện trên màn hình.")
        .defaultValue(true).build()
    );

    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Quay lại slot ban đầu sau khi ném.")
        .defaultValue(true).build()
    );

    // ============================================================
    // STATE
    // ============================================================
    private enum State { IDLE, SWAP_THROW, THROW, SWAP_BACK, FINISH }

    private State state = State.IDLE;
    private int timer = 0;
    private int originalSlot = -1;
    private boolean wasPressed = false;

    public KeyPearl() {
        super(HwellAddon.PVP_SUPER_LEGIT, "key-pearl",
            "Ấn phím trigger để ném Ender Pearl (silent switch tức thì).");
    }

    @Override
    public void onActivate() {
        state = State.IDLE;
        timer = 0;
        originalSlot = -1;
        wasPressed = false;
    }

    @Override
    public void onDeactivate() {
        state = State.IDLE;
        timer = 0;
        originalSlot = -1;
        wasPressed = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.interactionManager == null || mc.world == null) return;

        // ============================================================
        // IDLE: chờ phím trigger
        // ============================================================
        if (state == State.IDLE) {
            Keybind key = triggerKey.get();
            // ✅ Fix: bỏ check isNone() — nếu chưa bind, isPressed() tự trả false
            boolean isPressed = key != null && key.isPressed();

            if (isPressed && !wasPressed) {
                int target = pearlSlot.get() - 1;
                if (mc.player.getInventory().getStack(target).getItem() != Items.ENDER_PEARL) {
                    info("Không có Ender Pearl ở slot " + pearlSlot.get() + ".");
                    wasPressed = true;
                    return;
                }

                originalSlot = mc.player.getInventory().getSelectedSlot();
                state = State.SWAP_THROW;
                timer = 0;
            }
            wasPressed = isPressed;
            return;
        }

        // ============================================================
        // ĐANG CHẠY
        // ============================================================
        if (timer > 0) { timer--; return; }

        switch (state) {
            case SWAP_THROW -> {
                int target = pearlSlot.get() - 1;

                if (silentSwitch.get()) {
                    if (mc.getNetworkHandler() != null) {
                        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(target));
                    }
                } else {
                    InvUtils.swap(target, false);
                }

                state = State.THROW;
                timer = 1;
            }
            case THROW -> {
                int target = pearlSlot.get() - 1;

                if (silentSwitch.get()) {
                    // ✅ Fix: dùng setSelectedSlot() thay vì gán trực tiếp
                    int prev = mc.player.getInventory().getSelectedSlot();
                    mc.player.getInventory().setSelectedSlot(target);
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                    mc.player.getInventory().setSelectedSlot(prev);
                } else {
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                }
                mc.player.swingHand(Hand.MAIN_HAND);

                state = State.SWAP_BACK;
                timer = 1;
            }
            case SWAP_BACK -> {
                if (swapBack.get() && originalSlot != -1) {
                    if (silentSwitch.get()) {
                        if (mc.getNetworkHandler() != null) {
                            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(originalSlot));
                        }
                    } else {
                        InvUtils.swap(originalSlot, false);
                    }
                }
                state = State.FINISH;
                timer = 1;
            }
            case FINISH -> {
                state = State.IDLE;
                timer = 0;
                originalSlot = -1;
                wasPressed = true;
            }
        }
    }
}
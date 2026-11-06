package com.hwell.hwelladdon.modules;

import com.hwell.hwelladdon.HwellAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

public class LegitHoverTotem extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // ============================================================
    // SETTINGS
    // ============================================================
    private final Setting<Integer> totemSlot = sgGeneral.add(new IntSetting.Builder()
        .name("totem-slot")
        .description("Slot hotbar (1-9) dùng để chứa Totem.")
        .defaultValue(1)
        .range(1, 9)
        .sliderRange(1, 9)
        .build()
    );

    private final Setting<Integer> openDelay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Thời gian chờ (ms) trước khi mở inventory. Để 0 = nhanh nhất.")
        .defaultValue(0)
        .range(0, 1000)
        .sliderRange(0, 1000)
        .build()
    );

    private final Setting<Boolean> turnOffNoTotem = sgGeneral.add(new BoolSetting.Builder()
        .name("turn-off-no-totem")
        .description("Tắt module khi không còn Totem nào trong inventory.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> turnOffOnDeath = sgGeneral.add(new BoolSetting.Builder()
        .name("turn-off-on-death")
        .description("Tắt module khi người chơi chết.")
        .defaultValue(true)
        .build()
    );

    // ============================================================
    // STATE — tối ưu chỉ 3 state
    // ============================================================
    private enum State { IDLE, OPENING, SWAPPING }

    private State state = State.IDLE;
    private int timer = 0;

    public LegitHoverTotem() {
        super(HwellAddon.PVP_SUPER_LEGIT, "legit-hover-totem",
            "Tự động mở inventory để lấy Totem xuống hotbar (tối ưu tốc độ).");
    }

    @Override
    public void onActivate() {
        state = State.IDLE;
        timer = 0;
    }

    @Override
    public void onDeactivate() {
        state = State.IDLE;
        timer = 0;
        // Đóng inv nếu module đang trong quá trình thao tác
        if (mc.currentScreen instanceof InventoryScreen && mc.player != null) {
            mc.player.closeHandledScreen();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.interactionManager == null) return;

        // Tắt khi chết
        if (turnOffOnDeath.get() && isDead()) {
            info("Đã tắt do chết.");
            toggle();
            return;
        }

        int targetIndex = totemSlot.get() - 1;
        ItemStack currentStack = mc.player.getInventory().getStack(targetIndex);
        boolean slotHasTotem = currentStack.getItem() == Items.TOTEM_OF_UNDYING;

        // Slot đã có totem → reset state
        if (slotHasTotem) {
            if (state == State.IDLE) return;
            // Nếu đang SWAPPING mà đã có totem → swap thành công, reset
            state = State.IDLE;
            timer = 0;
            return;
        }

        // Có GUI khác đang mở (chest, shop…) → chờ
        if (mc.currentScreen != null && !(mc.currentScreen instanceof InventoryScreen)) {
            return;
        }

        // Tắt khi không còn totem trong inv
        if (state == State.IDLE && !hasTotemToRefill()) {
            if (turnOffNoTotem.get()) {
                info("Không còn Totem nào trong inventory, tắt module.");
                toggle();
            }
            return;
        }

        if (timer > 0) {
            timer--;
            return;
        }

        switch (state) {
            case IDLE -> {
                // ✅ Nếu inv đã mở sẵn → swap NGAY, không cần OPENING
                if (mc.currentScreen instanceof InventoryScreen) {
                    swapTotem(targetIndex);
                    state = State.IDLE;
                    timer = Math.max(2, getTicks()); // ít nhất 2 tick để server sync
                } else if (hasTotemToRefill()) {
                    // Mở inv mới — không chờ timer, qua tick sau mở ngay
                    state = State.OPENING;
                    timer = 0;
                }
            }
            case OPENING -> {
                // Mở inventory
                if (!(mc.currentScreen instanceof InventoryScreen)) {
                    mc.setScreen(new InventoryScreen(mc.player));
                }
                // Chuyển sang SWAPPING — tick sau swap + close ngay
                state = State.SWAPPING;
                timer = 0;
            }
            case SWAPPING -> {
                // ✅ Swap + close cùng tick — cực nhanh
                swapTotem(targetIndex);
                if (mc.currentScreen instanceof InventoryScreen) {
                    mc.player.closeHandledScreen();
                }
                state = State.IDLE;
                // ✅ 2 tick tối thiểu để server sync slot state (tránh swap lặp)
                timer = Math.max(2, getTicks());
            }
        }
    }

    // Swap totem từ main inv (9-35) xuống hotbar slot
    private void swapTotem(int targetIndex) {
        ScreenHandler handler = mc.player.playerScreenHandler;
        int foundSlot = -1;

        for (int i = 9; i <= 35; i++) {
            if (handler.getSlot(i).getStack().getItem() == Items.TOTEM_OF_UNDYING) {
                foundSlot = i;
                break;
            }
        }

        if (foundSlot != -1) {
            mc.interactionManager.clickSlot(
                handler.syncId,
                foundSlot,
                targetIndex,
                SlotActionType.SWAP,
                mc.player
            );
        }
    }

    private boolean hasTotemToRefill() {
        if (mc.player == null) return false;
        for (int i = 9; i <= 35; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.TOTEM_OF_UNDYING) {
                return true;
            }
        }
        return false;
    }

    private boolean isDead() {
        if (mc.player == null) return false;
        if (mc.currentScreen instanceof DeathScreen) return true;
        return mc.player.isDead() || mc.player.getHealth() <= 0;
    }

    // ✅ Chỉ dùng cho delay trước khi mở inv — không phải delay nội bộ state
    private int getTicks() {
        if (openDelay.get() <= 0) return 0;
        return Math.max(1, openDelay.get() / 50);
    }
}
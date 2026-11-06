package com.hwell.hwelladdon.modules;

import com.hwell.hwelladdon.HwellAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.util.List;

public class HwellAutoFillOrder extends Module {
    // ============================================================
    // HARDCODE
    // ============================================================
    private static final int SUBMIT_SLOT = 16;
    private static final int REFRESH_SLOT = 53;
    private static final int MAX_REFRESH_ATTEMPTS = 10;
    private static final int WAIT_GUI_TIMEOUT = 40;
    private static final int IDLE_DELAY = 20;
    private static final int PICKUP_SYNC_TICKS = 2;
    private static final int FINAL_CLOSE_COUNT = 40;
    private static final int VERIFY_SYNC_TICKS = 2;
    private static final int MAX_CLICK_RETRIES = 3;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSpeed = settings.createGroup("Speed");
    private final SettingGroup sgSafety = settings.createGroup("Safety");

    public enum FillMethod {
        SHIFT_CLICK,
        PICKUP_ALL
    }

    // ============================================================
    // GENERAL
    // ============================================================
    private final Setting<FillMethod> fillMethod = sgGeneral.add(new EnumSetting.Builder<FillMethod>()
        .name("fill-method")
        .description("SHIFT_CLICK: batch QUICK_MOVE (nhanh).\nPICKUP_ALL: gom cursor rồi thả.")
        .defaultValue(FillMethod.SHIFT_CLICK)
        .build()
    );

    private final Setting<String> targetString = sgGeneral.add(new StringSetting.Builder()
        .name("target")
        .description("Target cho lệnh /order. VD 'sand' → /order sand")
        .defaultValue("sand")
        .build()
    );

    private final Setting<List<Item>> itemList = sgGeneral.add(new ItemListSetting.Builder()
        .name("items")
        .description("Item ID cần dò trong GUI và inv.")
        .defaultValue(List.of(Items.SAND))
        .build()
    );

    private final Setting<Boolean> continuous = sgGeneral.add(new BoolSetting.Builder()
        .name("continuous")
        .description("Bật = chạy mãi.\nTắt = dừng sau loop-count lần.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> loopCount = sgGeneral.add(new IntSetting.Builder()
        .name("loop-count")
        .description("Số lần tối đa (1 - 1,000,000). Chỉ dùng khi continuous OFF.")
        .defaultValue(10)
        .range(1, 1_000_000)
        .sliderRange(1, 100)
        .noSlider()
        .visible(() -> !continuous.get())
        .build()
    );

    // ============================================================
    // SPEED
    // ============================================================
    private final Setting<Integer> delayMs = sgSpeed.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay giữa các thao tác (ms).")
        .defaultValue(100)
        .range(0, 5000)
        .sliderRange(0, 1000)
        .build()
    );

    private final Setting<Integer> stacksPerTick = sgSpeed.add(new IntSetting.Builder()
        .name("stacks-per-tick")
        .description("Số stack move mỗi tick.")
        .defaultValue(9)
        .range(1, 36)
        .sliderRange(1, 36)
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
        SENDING_CMD,
        WAITING_GUI,
        FINDING_TARGET,
        VERIFY_CLICK,
        FILLING,
        SUBMIT_CLOSE_1,
        SUBMIT_CLICK,
        SUBMIT_CLOSE_FINAL,
        LOOP_BACK
    }

    private State state = State.SENDING_CMD;
    private int timer = 0;
    private int waitGuiTicks = 0;
    private int currentLoop = 0;
    private int refreshAttempts = 0;
    private int closeRemaining = 0;
    private int clickRetries = 0;
    private int clickedSlot = -1;
    private boolean wasHoldingTotem = false;

    public HwellAutoFillOrder() {
        super(HwellAddon.FARM, "hwell-auto-fill-order",
            "Auto fill order: /order target → tìm item GUI → fill inv → submit.");
    }

    @Override
    public void onActivate() {
        state = State.SENDING_CMD;
        timer = 0;
        waitGuiTicks = 0;
        currentLoop = 0;
        refreshAttempts = 0;
        closeRemaining = 0;
        clickRetries = 0;
        clickedSlot = -1;
        wasHoldingTotem = hasTotem();
    }

    @Override
    public void onDeactivate() {
        state = State.SENDING_CMD;
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
        if (!continuous.get() && currentLoop >= loopCount.get() && state == State.SENDING_CMD) {
            toggle();
            return;
        }

        if (timer > 0) { timer--; return; }

        ScreenHandler handler = mc.player.currentScreenHandler;
        boolean guiOpen = handler != mc.player.playerScreenHandler;

        switch (state) {
            // ============================================================
            // 1. GỬI /order {target}
            // ============================================================
            case SENDING_CMD -> {
                // Safety: nếu GUI vẫn mở (close fail lần trước) → force close trước
                if (guiOpen) {
                    mc.player.closeHandledScreen();
                    timer = getTicks();
                    return;
                }

                if (!hasItemInInventory()) {
                    if (continuous.get()) {
                        timer = IDLE_DELAY;
                        return;
                    } else {
                        toggle();
                        return;
                    }
                }
                mc.player.networkHandler.sendChatCommand("order " + targetString.get());
                state = State.WAITING_GUI;
                waitGuiTicks = WAIT_GUI_TIMEOUT;
                refreshAttempts = 0;
                clickRetries = 0;
                timer = getTicks();
            }

            // ============================================================
            // 2. CHỜ GUI MỞ
            // ============================================================
            case WAITING_GUI -> {
                if (guiOpen) {
                    state = State.FINDING_TARGET;
                    timer = getTicks();
                } else {
                    if (--waitGuiTicks <= 0) {
                        toggle();
                    } else {
                        timer = 1;
                    }
                }
            }

            // ============================================================
            // 3. DÒ GUI — chỉ scan GUI order (không inv)
            //    Thứ tự slot 0 → N-1 = trái→phải, trên→dưới
            //    Không thấy → click slot 53 (refresh) → dò lại
            // ============================================================
            case FINDING_TARGET -> {
                if (!guiOpen) {
                    state = State.LOOP_BACK;
                    timer = getTicks();
                    return;
                }

                int slot = findItemInGui(handler);
                if (slot != -1) {
                    // ✅ Tìm thấy → click và lưu slot để verify
                    clickedSlot = slot;
                    mc.interactionManager.clickSlot(
                        handler.syncId, slot, 0, SlotActionType.PICKUP, mc.player
                    );
                    refreshAttempts = 0;
                    state = State.VERIFY_CLICK;
                    timer = VERIFY_SYNC_TICKS;
                } else {
                    // ❌ Không thấy → click slot 53 refresh
                    if (refreshAttempts < MAX_REFRESH_ATTEMPTS) {
                        refreshAttempts++;
                        mc.interactionManager.clickSlot(
                            handler.syncId, REFRESH_SLOT, 0, SlotActionType.PICKUP, mc.player
                        );
                        timer = getTicks();
                    } else {
                        // Hết attempts → báo log
                        msg("không có item trong order");
                        refreshAttempts = 0;
                        state = State.LOOP_BACK;
                        timer = getTicks();
                    }
                }
            }

            // ============================================================
            // 4. VERIFY CLICK — chờ server sync rồi kiểm tra
            //    Click thành công → FILLING
            //    Click fail → retry max 3 lần → báo log
            // ============================================================
            case VERIFY_CLICK -> {
                if (!guiOpen) {
                    state = State.LOOP_BACK;
                    timer = getTicks();
                    return;
                }

                ItemStack cursor = mc.player.currentScreenHandler.getCursorStack();
                boolean cursorHasItem = !cursor.isEmpty() && shouldFillItem(cursor.getItem());
                boolean slotChanged = clickedSlot >= 0
                    && clickedSlot < handler.slots.size()
                    && handler.getSlot(clickedSlot).getStack().isEmpty();

                if (cursorHasItem || slotChanged) {
                    // ✅ Click thành công → fill
                    clickRetries = 0;
                    state = State.FILLING;
                    timer = getTicks();
                } else {
                    // ❌ Click fail → retry
                    clickRetries++;
                    if (clickRetries < MAX_CLICK_RETRIES) {
                        int slot = findItemInGui(handler);
                        if (slot != -1) {
                            clickedSlot = slot;
                            mc.interactionManager.clickSlot(
                                handler.syncId, slot, 0, SlotActionType.PICKUP, mc.player
                            );
                            timer = VERIFY_SYNC_TICKS;
                        } else {
                            clickRetries = 0;
                            state = State.FINDING_TARGET;
                            timer = getTicks();
                        }
                    } else {
                        msg("click item thất bại sau " + MAX_CLICK_RETRIES + " lần");
                        clickRetries = 0;
                        state = State.LOOP_BACK;
                        timer = getTicks();
                    }
                }
            }

            // ============================================================
            // 5. FILL ITEM INV → GUI
            // ============================================================
            case FILLING -> {
                if (!guiOpen) {
                    state = State.SUBMIT_CLOSE_1;
                    timer = getTicks();
                    return;
                }

                if (fillMethod.get() == FillMethod.SHIFT_CLICK) {
                    int moved = fillBatchShiftClick(handler);
                    if (moved > 0) {
                        timer = getTicks();
                        return;
                    }
                    state = State.SUBMIT_CLOSE_1;
                    timer = getTicks();
                } else {
                    int result = fillBatchPickupAll(handler);
                    if (result == 1) {
                        timer = getTicks();
                        return;
                    }
                    state = State.SUBMIT_CLOSE_1;
                    timer = getTicks();
                }
            }

            // ============================================================
            // 6. CLOSE LẦN 1 (trước click slot 16)
            // ============================================================
            case SUBMIT_CLOSE_1 -> {
                if (guiOpen) mc.player.closeHandledScreen();
                state = State.SUBMIT_CLICK;
                timer = getTicks();
            }

            // ============================================================
            // 7. CLICK SLOT 16
            // ============================================================
            case SUBMIT_CLICK -> {
                if (mc.player.currentScreenHandler != null) {
                    mc.interactionManager.clickSlot(
                        mc.player.currentScreenHandler.syncId,
                        SUBMIT_SLOT, 0, SlotActionType.PICKUP, mc.player
                    );
                }
                closeRemaining = FINAL_CLOSE_COUNT;
                state = State.SUBMIT_CLOSE_FINAL;
                timer = getTicks();
            }

            // ============================================================
            // 8. CLOSE đến khi GUI đóng hẳn
            // ============================================================
            case SUBMIT_CLOSE_FINAL -> {
                // ✅ GUI đã đóng hẳn → loop
                if (!guiOpen) {
                    closeRemaining = 0;
                    state = State.LOOP_BACK;
                    timer = getTicks();
                    return;
                }

                // GUI vẫn mở → gửi close packet
                mc.player.closeHandledScreen();
                closeRemaining--;

                if (closeRemaining > 0) {
                    timer = getTicks();
                } else {
                    msg("GUI không đóng sau " + FINAL_CLOSE_COUNT + " lần");
                    state = State.LOOP_BACK;
                    timer = getTicks();
                }
            }

            // ============================================================
            // 9. LOOP
            // ============================================================
            case LOOP_BACK -> {
                currentLoop++;
                clickedSlot = -1;
                clickRetries = 0;
                state = State.SENDING_CMD;
                timer = getTicks();
            }
        }
    }

    // ============================================================
    // FILL BATCH — SHIFT_CLICK
    // ============================================================
    private int fillBatchShiftClick(ScreenHandler handler) {
        int containerSlots = handler.slots.size() - 36;
        int budget = stacksPerTick.get();
        int moved = 0;

        for (int i = containerSlots; i < handler.slots.size() && moved < budget; i++) {
            if (firstEmptyGuiSlot(handler) == -1) break;

            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty() || !shouldFillItem(stack.getItem())) continue;

            mc.interactionManager.clickSlot(
                handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player
            );
            moved++;
        }

        return moved;
    }

    // ============================================================
    // FILL BATCH — PICKUP_ALL
    // ============================================================
    private int fillBatchPickupAll(ScreenHandler handler) {
        ItemStack cursor = mc.player.currentScreenHandler.getCursorStack();

        if (cursor.isEmpty()) {
            int playerSlot = findItemInInventory(handler);
            if (playerSlot == -1) return 0;

            mc.interactionManager.clickSlot(handler.syncId, playerSlot, 0,
                SlotActionType.PICKUP, mc.player);
            mc.interactionManager.clickSlot(handler.syncId, playerSlot, 0,
                SlotActionType.PICKUP_ALL, mc.player);

            timer = PICKUP_SYNC_TICKS;
            return 1;
        }

        int moved = 0;
        int budget = stacksPerTick.get();
        while (moved < budget) {
            ItemStack cur = mc.player.currentScreenHandler.getCursorStack();
            if (cur.isEmpty()) break;

            int emptyGuiSlot = firstEmptyGuiSlot(handler);
            if (emptyGuiSlot == -1) {
                int backSlot = findEmptyInvSlot(handler);
                if (backSlot != -1) {
                    mc.interactionManager.clickSlot(handler.syncId, backSlot, 0,
                        SlotActionType.PICKUP, mc.player);
                }
                return 0;
            }

            mc.interactionManager.clickSlot(handler.syncId, emptyGuiSlot, 0,
                SlotActionType.PICKUP, mc.player);
            moved++;
        }

        return 1;
    }

    // ============================================================
    // HELPERS
    // ============================================================
    private void msg(String s) {
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("§c[hwelladdon] §6" + s), false);
        }
    }

    /**
     * Scan item ID trong GUI order (KHÔNG scan inv).
     * Thứ tự slot: 0 → containerSlots-1 = trái→phải, trên→dưới
     * Bỏ qua slot 53 (nút refresh).
     */
    private int findItemInGui(ScreenHandler handler) {
        int containerSlots = handler.slots.size() - 36;
        for (int i = 0; i < containerSlots; i++) {
            if (i == REFRESH_SLOT) continue;
            ItemStack stack = handler.getSlot(i).getStack();
            if (!stack.isEmpty() && shouldFillItem(stack.getItem())) return i;
        }
        return -1;
    }

    private int findItemInInventory(ScreenHandler handler) {
        int playerInvStart = handler.slots.size() - 36;
        for (int i = playerInvStart; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (!stack.isEmpty() && shouldFillItem(stack.getItem())) return i;
        }
        return -1;
    }

    private int firstEmptyGuiSlot(ScreenHandler handler) {
        int containerSlots = handler.slots.size() - 36;
        for (int i = 0; i < containerSlots; i++) {
            if (handler.getSlot(i).getStack().isEmpty()) return i;
        }
        return -1;
    }

    private int findEmptyInvSlot(ScreenHandler handler) {
        int playerInvStart = handler.slots.size() - 36;
        for (int i = playerInvStart; i < handler.slots.size(); i++) {
            if (handler.getSlot(i).getStack().isEmpty()) return i;
        }
        return -1;
    }

    private boolean hasItemInInventory() {
        if (mc.player == null) return false;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && shouldFillItem(stack.getItem())) return true;
        }
        return false;
    }

    private boolean shouldFillItem(Item item) {
        return itemList.get().contains(item);
    }

    private boolean hasTotem() {
        if (mc.player == null) return false;
        return mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)
            || mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING);
    }

    private int getTicks() {
        return Math.max(1, delayMs.get() / 50);
    }
}
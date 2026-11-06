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

import java.util.List;

public class HwellAutoSell extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSafety = settings.createGroup("Safety");

    // ============================================================
    // SELL MODE
    // ============================================================
    public enum SellMode {
        Whitelist,   // Chỉ bán item trong list
        Blacklist    // Bán mọi thứ trừ item trong list
    }

    public enum SellMethod {
        SHIFT_CLICK,   // Batch QUICK_MOVE (giống Glazed)
        PICKUP_ALL     // Gom cursor rồi thả (vanilla shift+double-click)
    }

    private final Setting<SellMethod> sellMethod = sgGeneral.add(new EnumSetting.Builder<SellMethod>()
        .name("sell-method")
        .description("SHIFT_CLICK: batch QUICK_MOVE (như Glazed).\nPICKUP_ALL: gom hết vào cursor rồi thả (double-click).")
        .defaultValue(SellMethod.SHIFT_CLICK)
        .build()
    );

    private final Setting<SellMode> mode = sgGeneral.add(new EnumSetting.Builder<SellMode>()
        .name("mode")
        .description("Whitelist: chỉ bán item trong list.\nBlacklist: bán mọi thứ trừ item trong list.")
        .defaultValue(SellMode.Whitelist)
        .build()
    );

    private final Setting<List<Item>> itemList = sgGeneral.add(new ItemListSetting.Builder()
        .name("items")
        .description("Danh sách item cần bán (hoặc cấm bán nếu Blacklist).")
        .defaultValue(List.of(Items.DIRT))
        .build()
    );

    // ============================================================
    // SPEED
    // ============================================================
    private final Setting<Integer> stacksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("stacks-per-tick")
        .description("Số stack xử lý mỗi tick.\n36 = bán cả inv trong 1 tick (như Glazed).\n5-10 = an toàn hơn.")
        .defaultValue(36)
        .range(1, 36)
        .sliderRange(1, 36)
        .build()
    );

    private final Setting<Integer> delayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks giữa các batch. 0 = bán liên tục.")
        .defaultValue(0)
        .range(0, 20)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Integer> openDelay = sgGeneral.add(new IntSetting.Builder()
        .name("open-delay")
        .description("Ticks chờ GUI mở sau khi gõ /sell.")
        .defaultValue(10)
        .range(1, 40)
        .sliderRange(1, 40)
        .build()
    );

    // ============================================================
    // PICKUP_ALL SPECIFIC
    // ============================================================
    private final Setting<Integer> pickupAllButton = sgGeneral.add(new IntSetting.Builder()
        .name("pickup-all-button")
        .description("Button cho PICKUP_ALL. Vanilla=0. Server custom có thể cần 1, 9...")
        .defaultValue(0)
        .range(0, 9)
        .sliderRange(0, 9)
        .visible(() -> sellMethod.get() == SellMethod.PICKUP_ALL)
        .build()
    );

    private final Setting<Integer> pickupAllSyncTicks = sgGeneral.add(new IntSetting.Builder()
        .name("pickup-all-sync-ticks")
        .description("Tick chờ server sync cursor sau PICKUP_ALL (mạng lag → 3-5).")
        .defaultValue(2)
        .range(1, 10)
        .sliderRange(1, 5)
        .visible(() -> sellMethod.get() == SellMethod.PICKUP_ALL)
        .build()
    );

    // ============================================================
    // LOOP
    // ============================================================
    private final Setting<Boolean> continuous = sgGeneral.add(new BoolSetting.Builder()
        .name("continuous")
        .description("Bật = chạy mãi (chỉ dừng khi hurt/totem/hết item/tắt tay).\nTắt = chạy loop-count lần rồi dừng.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> loopCount = sgGeneral.add(new IntSetting.Builder()
        .name("loop-count")
        .description("Số lần tối đa gõ /sell (dùng khi continuous OFF).")
        .defaultValue(10)
        .range(1, 1_000_000)
        .sliderRange(1, 100)
        .noSlider()
        .visible(() -> !continuous.get())
        .build()
    );

    private final Setting<Integer> idleDelay = sgGeneral.add(new IntSetting.Builder()
        .name("idle-delay")
        .description("Ticks chờ khi không còn item (continuous mode).")
        .defaultValue(20)
        .range(1, 200)
        .sliderRange(1, 200)
        .visible(continuous::get)
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

    private final Setting<Boolean> turnOffNoItem = sgSafety.add(new BoolSetting.Builder()
        .name("turn-off-no-item")
        .description("Tắt khi hết item (khi continuous OFF).")
        .defaultValue(true)
        .visible(() -> !continuous.get())
        .build()
    );

    private final Setting<String> sellCommand = sgSafety.add(new StringSetting.Builder()
        .name("sell-command")
        .description("Lệnh mở GUI bán.")
        .defaultValue("/sell")
        .build()
    );

    // ============================================================
    // STATE
    // ============================================================
    private enum State {
        SENDING_CMD, WAITING_GUI,
        SELLING,          // SHIFT_CLICK mode
        PICKUP_COLLECT,   // PICKUP_ALL - gom cursor
        PICKUP_WAIT,      // PICKUP_ALL - chờ sync
        PICKUP_DROP,      // PICKUP_ALL - thả vào GUI
        CLOSING
    }

    private State state = State.SENDING_CMD;
    private int timer = 0;
    private int currentLoop = 0;
    private int waitGuiTicks = 0;
    private boolean wasHoldingTotem = false;
    private boolean lastLoopGuiWasFull = false;
    private int soldInCurrentLoop = 0;

    public HwellAutoSell() {
        super(HwellAddon.FARM, "hwell-auto-sell",
            "Auto sell (SHIFT_CLICK batch hoặc PICKUP_ALL như shift+double-click).");
    }

    @Override
    public void onActivate() {
        state = State.SENDING_CMD;
        timer = 0;
        currentLoop = 0;
        waitGuiTicks = 0;
        wasHoldingTotem = hasTotem();
        lastLoopGuiWasFull = false;
        soldInCurrentLoop = 0;
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
        if (mc.player == null || mc.interactionManager == null) return;

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

        if (timer > 0) {
            timer--;
            return;
        }

        ScreenHandler handler = mc.player.currentScreenHandler;
        boolean guiOpen = handler != mc.player.playerScreenHandler;

        switch (state) {
            case SENDING_CMD -> {
                // Không có GUI + continuous + không có item → chờ
                if (!guiOpen && continuous.get() && !hasSellableInInventory()) {
                    timer = idleDelay.get();
                    return;
                }

                if (!guiOpen) {
                    currentLoop++;
                    soldInCurrentLoop = 0;
                    mc.player.networkHandler.sendChatCommand(sellCommand.get().replace("/", ""));
                    state = State.WAITING_GUI;
                    waitGuiTicks = openDelay.get();
                    timer = 1;
                } else {
                    // GUI đã mở sẵn
                    currentLoop++;
                    soldInCurrentLoop = 0;
                    state = (sellMethod.get() == SellMethod.PICKUP_ALL)
                        ? State.PICKUP_COLLECT : State.SELLING;
                    timer = 1;
                }
            }

            case WAITING_GUI -> {
                if (guiOpen) {
                    state = (sellMethod.get() == SellMethod.PICKUP_ALL)
                        ? State.PICKUP_COLLECT : State.SELLING;
                    timer = 1;
                } else {
                    if (--waitGuiTicks <= 0) {
                        // GUI không mở → thử lại
                        state = State.SENDING_CMD;
                        timer = 20;
                    } else {
                        timer = 1;
                    }
                }
            }

            // ============================================================
            // PICKUP_ALL: Bước 1 — gom cursor
            // ============================================================
            case PICKUP_COLLECT -> {
                if (!guiOpen) {
                    state = State.CLOSING;
                    timer = 1;
                    return;
                }

                int playerSlot = findSellableInInventory(handler);
                if (playerSlot == -1) {
                    // Hết item
                    lastLoopGuiWasFull = false;
                    state = State.CLOSING;
                    timer = 1;
                    return;
                }

                // Chỉ gửi 1 packet PICKUP_ALL - server tự scan inv
                mc.interactionManager.clickSlot(
                    handler.syncId,
                    playerSlot,
                    pickupAllButton.get(),
                    SlotActionType.PICKUP_ALL,
                    mc.player
                );

                state = State.PICKUP_WAIT;
                timer = pickupAllSyncTicks.get();
            }

            // ============================================================
            // PICKUP_ALL: Bước 2 — chờ sync
            // ============================================================
            case PICKUP_WAIT -> {
                if (!guiOpen) {
                    state = State.CLOSING;
                    timer = 1;
                    return;
                }

                ItemStack cursor = mc.player.currentScreenHandler.getCursorStack();
                if (cursor.isEmpty()) {
                    // PICKUP_ALL không gom được → fallback SHIFT_CLICK
                    state = State.SELLING;
                    timer = 1;
                    return;
                }

                state = State.PICKUP_DROP;
                timer = 1;
            }

            // ============================================================
            // PICKUP_ALL: Bước 3 — thả nhiều stack/tick
            // ============================================================
            case PICKUP_DROP -> {
                if (!guiOpen) {
                    state = State.CLOSING;
                    timer = 1;
                    return;
                }

                int clicks = 0;
                while (clicks < stacksPerTick.get()) {
                    ItemStack cursor = mc.player.currentScreenHandler.getCursorStack();

                    if (cursor.isEmpty()) {
                        // Hết cursor → gom tiếp hoặc close
                        if (findSellableInInventory(handler) != -1) {
                            state = State.PICKUP_COLLECT;
                        } else {
                            lastLoopGuiWasFull = false;
                            state = State.CLOSING;
                        }
                        timer = delayTicks.get() + 1;
                        return;
                    }

                    int emptyGuiSlot = firstEmptyGuiSlot(handler);
                    if (emptyGuiSlot == -1) {
                        // GUI đầy → đưa cursor về inv
                        int backSlot = findEmptyInvSlot(handler);
                        if (backSlot != -1) {
                            mc.interactionManager.clickSlot(handler.syncId, backSlot, 0,
                                SlotActionType.PICKUP, mc.player);
                        }
                        lastLoopGuiWasFull = true;
                        state = State.CLOSING;
                        timer = 1;
                        return;
                    }

                    int moved = Math.min(64, cursor.getCount());
                    mc.interactionManager.clickSlot(handler.syncId, emptyGuiSlot, 0,
                        SlotActionType.PICKUP, mc.player);
                    soldInCurrentLoop += moved;
                    clicks++;
                }

                timer = delayTicks.get() + 1;
            }

            // ============================================================
            // SHIFT_CLICK: batch QUICK_MOVE
            // ============================================================
            case SELLING -> {
                if (!guiOpen) {
                    lastLoopGuiWasFull = false;
                    state = State.CLOSING;
                    timer = 1;
                    return;
                }

                int moved = sellBatch(handler);

                if (moved > 0) {
                    timer = delayTicks.get() + 1;
                    return;
                }

                // Không bán được gì → check GUI đầy hay hết item
                if (firstEmptyGuiSlot(handler) == -1) {
                    lastLoopGuiWasFull = hasSellableInInventory();
                } else {
                    lastLoopGuiWasFull = false;
                }
                state = State.CLOSING;
                timer = 1;
            }

            case CLOSING -> {
                // Đưa cursor về inv nếu còn
                if (!mc.player.currentScreenHandler.getCursorStack().isEmpty() && guiOpen) {
                    int emptySlot = findEmptyInvSlot(handler);
                    if (emptySlot != -1) {
                        mc.interactionManager.clickSlot(handler.syncId, emptySlot, 0,
                            SlotActionType.PICKUP, mc.player);
                    }
                }
                if (guiOpen) mc.player.closeHandledScreen();

                if (lastLoopGuiWasFull) {
                    // Còn item → loop tiếp
                    state = State.SENDING_CMD;
                    timer = delayTicks.get() + 1;
                } else {
                    // Hết item
                    if (continuous.get()) {
                        state = State.SENDING_CMD;
                        timer = idleDelay.get();
                    } else {
                        toggle();
                    }
                }
            }
        }
    }

    // ============================================================
    // BATCH SELL (SHIFT_CLICK)
    // ============================================================
    private int sellBatch(ScreenHandler handler) {
        int containerSlots = handler.slots.size() - 36;
        int budget = stacksPerTick.get();
        int moved = 0;

        for (int slot = containerSlots; slot < handler.slots.size() && moved < budget; slot++) {
            if (firstEmptyGuiSlot(handler) == -1) break;

            ItemStack stack = handler.getSlot(slot).getStack();
            if (stack.isEmpty() || !shouldSellItem(stack.getItem())) continue;

            mc.interactionManager.clickSlot(
                handler.syncId,
                slot,
                0,
                SlotActionType.QUICK_MOVE,
                mc.player
            );
            soldInCurrentLoop += stack.getCount();
            moved++;
        }

        return moved;
    }

    // ============================================================
    // HELPERS
    // ============================================================
    private int findSellableInInventory(ScreenHandler handler) {
        int playerInvStart = handler.slots.size() - 36;
        for (int i = playerInvStart; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (!stack.isEmpty() && shouldSellItem(stack.getItem())) return i;
        }
        return -1;
    }

    private int firstEmptyGuiSlot(ScreenHandler handler) {
        int guiSlots = handler.slots.size() - 36;
        for (int i = 0; i < guiSlots; i++) {
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

    private boolean hasSellableInInventory() {
        if (mc.player == null) return false;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (!stack.isEmpty() && shouldSellItem(stack.getItem())) return true;
        }
        return false;
    }

    private boolean shouldSellItem(Item item) {
        List<Item> selectedItems = itemList.get();
        if (mode.get() == SellMode.Whitelist) {
            return selectedItems.contains(item);
        } else {
            return !selectedItems.contains(item);
        }
    }

    private boolean hasTotem() {
        if (mc.player == null) return false;
        return mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)
            || mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING);
    }
}
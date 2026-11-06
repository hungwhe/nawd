package com.hwell.hwelladdon.modules;

import com.hwell.hwelladdon.HwellAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class HwellAutoBuyShop extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTotem = settings.createGroup("Totem");
    private final SettingGroup sgCrystal = settings.createGroup("Crystal");
    private final SettingGroup sgExp = settings.createGroup("Exp");
    private final SettingGroup sgObsidian = settings.createGroup("Obsidian");
    private final SettingGroup sgPearl = settings.createGroup("Ender Pearl");
    private final SettingGroup sgDelay = settings.createGroup("Delay");

    // ✅ Thêm setting Silent
    private final Setting<Boolean> silentBuy = sgGeneral.add(new BoolSetting.Builder()
        .name("silent-buy")
        .description("Mua đồ mà không hiện GUI (Mixin sẽ ẩn GUI).")
        .defaultValue(false)
        .build()
    );

    private final Setting<ClickMode> clickMode = sgGeneral.add(new EnumSetting.Builder<ClickMode>()
        .name("click-mode")
        .description("PICKUP = click chuột trái. QUICK_MOVE = shift-click.")
        .defaultValue(ClickMode.PICKUP)
        .build()
    );

    // Cấu hình Slot
    private static final int SLOT_ITEM_TOTEM = 13;
    private static final int SLOT_ITEM_CRYSTAL = 10;
    private static final int SLOT_ITEM_EXP = 15;
    private static final int SLOT_ITEM_OBSIDIAN = 9;
    private static final int SLOT_ITEM_ENDER_PEARL = 14;
    private static final int SLOT_SET_1 = 15;
    private static final int SLOT_SET_11 = 16;
    private static final int SLOT_CONFIRM = 23;
    private static final int STEP_TIMEOUT_TICKS = 100;

    public enum ClickMode { PICKUP, QUICK_MOVE }
    public enum ShopMode { Totem, Crystal, Exp, Obsidian, EnderPearl }
    private enum State { IDLE, WAIT_SHOP, CLICK_ITEM, DECIDE, SET_11, CONFIRM }

    // Cờ báo hiệu cho Mixin (GrimBypass & SilentScreen)
    public static boolean isBuying = false;
    public static boolean silentMode = false;

    private final Setting<Keybind> totemKey = sgTotem.add(new KeybindSetting.Builder()
        .name("totem-key").defaultValue(Keybind.none()).build());
    private final Setting<Integer> totemAmount = sgTotem.add(new IntSetting.Builder()
        .name("totem-amount").defaultValue(1).range(1, 10).sliderRange(1, 10).build());

    private final Setting<Keybind> crystalKey = sgCrystal.add(new KeybindSetting.Builder()
        .name("crystal-key").defaultValue(Keybind.none()).build());
    private final Setting<Integer> crystalStacks = sgCrystal.add(new IntSetting.Builder()
        .name("crystal-stacks").defaultValue(1).range(1, 10).sliderRange(1, 10).build());

    private final Setting<Keybind> expKey = sgExp.add(new KeybindSetting.Builder()
        .name("exp-key").defaultValue(Keybind.none()).build());
    private final Setting<Integer> expStacks = sgExp.add(new IntSetting.Builder()
        .name("exp-stacks").defaultValue(1).range(1, 10).sliderRange(1, 10).build());

    private final Setting<Keybind> obsidianKey = sgObsidian.add(new KeybindSetting.Builder()
        .name("obsidian-key").defaultValue(Keybind.none()).build());
    private final Setting<Integer> obsidianStacks = sgObsidian.add(new IntSetting.Builder()
        .name("obsidian-stacks").defaultValue(1).range(1, 10).sliderRange(1, 10).build());

    private final Setting<Keybind> pearlKey = sgPearl.add(new KeybindSetting.Builder()
        .name("ender-pearl-key").defaultValue(Keybind.none()).build());
    private final Setting<Integer> pearlStacks = sgPearl.add(new IntSetting.Builder()
        .name("ender-pearl-stacks").defaultValue(1).range(1, 10).sliderRange(1, 10).build());

    private final Setting<Integer> actionDelay = sgDelay.add(new IntSetting.Builder()
        .name("action-delay").defaultValue(8).range(0, 20).sliderRange(0, 20).build());

    private final Setting<String> shopCommand = sgDelay.add(new StringSetting.Builder()
        .name("shop-command").defaultValue("shop open gear").build());

    private State state = State.IDLE;
    private final Queue<PurchaseOrder> orderQueue = new ConcurrentLinkedQueue<>();
    private PurchaseOrder currentOrder;
    private int ticks;
    private int timeoutTicks;
    private boolean quantitySet;
    private boolean wasKeyPressed;

    public HwellAutoBuyShop() {
        super(HwellAddon.AUTO_SHOP, "hwell-auto-buy-shop", "Tự mua đồ bằng phím (Tích hợp Silent Buy).");
    }

    @Override
    public void onActivate() { reset(); }

    @Override
    public void onDeactivate() {
        reset();
        if (mc.player != null && mc.player.currentScreenHandler != mc.player.playerScreenHandler) {
            mc.player.closeHandledScreen();
        }
    }

    private void reset() {
        state = State.IDLE;
        isBuying = false;
        silentMode = false;
        orderQueue.clear();
        currentOrder = null;
        ticks = 0;
        timeoutTicks = 0;
        quantitySet = false;
        wasKeyPressed = false;
    }

    public static class PurchaseOrder {
        public final ShopMode mode;
        public int remaining;
        public PurchaseOrder(ShopMode mode, int remaining) { this.mode = mode; this.remaining = remaining; }
    }

    public synchronized void enqueueOrder(ShopMode mode, int amount) {
        if (mc.player == null || mc.getNetworkHandler() == null || amount <= 0) return;
        orderQueue.add(new PurchaseOrder(mode, amount));
        if (state == State.IDLE) {
            currentOrder = orderQueue.poll();
            ticks = 0; timeoutTicks = 0; quantitySet = false;
            sendShopCommand();
            state = State.WAIT_SHOP;
        }
    }

    private void sendShopCommand() {
        if (mc.getNetworkHandler() != null) {
            // Cập nhật cờ Silent trước khi mở Shop
            silentMode = silentBuy.get();
            isBuying = true; // Bật cờ báo hiệu đang mua
            
            String cmd = shopCommand.get().replace("/", "");
            mc.getNetworkHandler().sendChatCommand(cmd);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) {
            if (state != State.IDLE) reset();
            return;
        }

        checkKeys();
        if (state == State.IDLE) {
            isBuying = false;
            silentMode = false;
            return;
        }

        if (ticks < actionDelay.get()) { ticks++; return; }
        ticks = 0;

        // ✅ Sửa lại logic: Dùng ScreenHandler thay vì currentScreen để check GUI (hoạt động cả khi bị ẩn)
        boolean shopOpen = mc.player.currentScreenHandler != mc.player.playerScreenHandler;
        
        if (state != State.WAIT_SHOP && !shopOpen) {
            reset(); 
            return;
        }

        switch (state) {
            case WAIT_SHOP -> {
                if (shopOpen) state = State.CLICK_ITEM;
                else {
                    if (++timeoutTicks > STEP_TIMEOUT_TICKS) reset();
                    else if (timeoutTicks % 20 == 0) sendShopCommand();
                }
            }
            case CLICK_ITEM -> {
                if (currentOrder == null) { state = State.DECIDE; return; }
                int slot = switch (currentOrder.mode) {
                    case Totem -> SLOT_ITEM_TOTEM;
                    case Crystal -> SLOT_ITEM_CRYSTAL;
                    case Exp -> SLOT_ITEM_EXP;
                    case Obsidian -> SLOT_ITEM_OBSIDIAN;
                    case EnderPearl -> SLOT_ITEM_ENDER_PEARL;
                };
                if (click(slot)) state = State.DECIDE;
                else reset();
            }
            case DECIDE -> {
                if (currentOrder == null || currentOrder.remaining <= 0) {
                    if (!orderQueue.isEmpty()) {
                        currentOrder = orderQueue.poll(); quantitySet = false;
                        state = State.CLICK_ITEM; 
                    } else finish();
                    return;
                }
                if (currentOrder.mode == ShopMode.Totem) state = State.CONFIRM;
                else if (!quantitySet) state = State.SET_11;
                else state = State.CONFIRM;
            }
            case SET_11 -> {
                int slot = currentOrder.mode == ShopMode.EnderPearl ? SLOT_SET_1 : SLOT_SET_11;
                if (click(slot)) { quantitySet = true; state = State.CONFIRM; }
                else reset();
            }
            case CONFIRM -> {
                if (click(SLOT_CONFIRM)) {
                    if (currentOrder != null) currentOrder.remaining--;
                    state = State.DECIDE;
                } else reset();
            }
        }
    }

    private void checkKeys() {
        ShopMode mode = null;
        int amount = 0;
        if (isKeyPressed(totemKey.get())) { mode = ShopMode.Totem; amount = totemAmount.get(); }
        else if (isKeyPressed(crystalKey.get())) { mode = ShopMode.Crystal; amount = crystalStacks.get(); }
        else if (isKeyPressed(expKey.get())) { mode = ShopMode.Exp; amount = expStacks.get(); }
        else if (isKeyPressed(obsidianKey.get())) { mode = ShopMode.Obsidian; amount = obsidianStacks.get(); }
        else if (isKeyPressed(pearlKey.get())) { mode = ShopMode.EnderPearl; amount = pearlStacks.get(); }

        if (mode != null && !wasKeyPressed) { enqueueOrder(mode, amount); wasKeyPressed = true; }
        else if (mode == null) { wasKeyPressed = false; }
    }

    private boolean isKeyPressed(Keybind key) { 
        return key != null && key.isSet() && key.isPressed(); 
    }

    private boolean click(int slot) {
        if (mc.player == null || mc.interactionManager == null) return false;
        if (slot < 0 || slot >= mc.player.currentScreenHandler.slots.size()) return false;

        SlotActionType action = (clickMode.get() == ClickMode.PICKUP) ? SlotActionType.PICKUP : SlotActionType.QUICK_MOVE;
        mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, slot, 0, action, mc.player);
        
        return true;
    }

    private void finish() {
        reset();
        if (mc.player != null) mc.player.closeHandledScreen();
        info("§a[Hwell Auto Buy Shop] Done.");
    }
}
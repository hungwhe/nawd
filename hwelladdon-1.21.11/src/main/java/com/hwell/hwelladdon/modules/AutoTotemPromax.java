package com.hwell.hwelladdon.modules;

import com.hwell.hwelladdon.HwellAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AutoTotemPromax extends Module {
    private static final int OFFHAND_BUTTON = 40;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> hotbarSlot = sgGeneral.add(
        new IntSetting.Builder()
            .name("hotbar-slot")
            .description("O hotbar muon tu dong bo sung Totem (1-9).")
            .defaultValue(9)
            .range(1, 9)
            .sliderRange(1, 9)
            .build()
    );

    private final Setting<Integer> opDelayMs = sgGeneral.add(
        new IntSetting.Builder()
            .name("op-delay-ms")
            .description("Thoi gian cho giua thao tac doi Totem va bo sung hotbar (ms).")
            .defaultValue(70)
            .range(0, 200)
            .sliderRange(0, 200)
            .build()
    );

    private final Setting<Boolean> freezeMove = sgGeneral.add(
        new BoolSetting.Builder()
            .name("freeze-move")
            .description("Dung di chuyen ngan trong luc chuyen Totem.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Integer> freezeDelayTicks = sgGeneral.add(
        new IntSetting.Builder()
            .name("freeze-delay-ticks")
            .description("So tick dung yen truoc khi chuyen Totem.")
            .defaultValue(2)
            .range(0, 10)
            .sliderRange(0, 10)
            .visible(freezeMove::get)
            .build()
    );

    private final Setting<Boolean> hideGui = sgGeneral.add(
        new BoolSetting.Builder()
            .name("hide-gui")
            .description("Chuyen Totem ma khong mo giao dien tui do.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> closeOtherGui = sgGeneral.add(
        new BoolSetting.Builder()
            .name("close-other-gui")
            .description("Dong giao dien ruong/container khac neu can chuyen Totem.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> logs = sgGeneral.add(
        new BoolSetting.Builder()
            .name("logs")
            .description("Hien thong bao khi chuyen Totem.")
            .defaultValue(false)
            .build()
    );

    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "hwell-auto-totem");
            thread.setDaemon(true);
            return thread;
        });

    private boolean busy;
    private boolean wasHoldingTotem;
    private boolean autoOpened;
    private int closeTicks;
    private boolean frozen;
    private int freezeElapsed;
    private int releaseTicks;
    private boolean opsStarted;

    public AutoTotemPromax() {
        super(
            HwellAddon.PVP_SUPER_LEGIT,
            "auto-totem",
            "Tu dong dua Totem vao tay phu va bo sung mot o hotbar khi co the."
        );
    }

    @Override
    public void onActivate() {
        busy = false;
        autoOpened = false;
        closeTicks = 0;
        frozen = false;
        freezeElapsed = 0;
        releaseTicks = 0;
        opsStarted = false;
        wasHoldingTotem = hasOffhandTotem();
    }

    @Override
    public void onDeactivate() {
        busy = false;
        if (frozen) restoreInput();
        frozen = false;
        closeTicks = 0;

        if (autoOpened && mc.currentScreen instanceof InventoryScreen) {
            mc.execute(() -> {
                if (mc.player != null && mc.currentScreen instanceof InventoryScreen) {
                    mc.player.closeHandledScreen();
                }
            });
        }
        autoOpened = false;
    }

    @EventHandler
    private void onTickPre(TickEvent.Pre event) {
        if (frozen) blockInput();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.interactionManager == null) return;

        if (closeTicks > 0) {
            closeTicks--;
            if (closeTicks == 0 && autoOpened && mc.currentScreen instanceof InventoryScreen) {
                mc.player.closeHandledScreen();
                if (logs.get()) info("Da dong giao dien tui do.");
                autoOpened = false;
            }
        }

        if (frozen) {
            blockInput();
            freezeElapsed++;

            if (!opsStarted && freezeElapsed >= freezeDelayTicks.get()) {
                opsStarted = true;
                scheduleOps();
            }

            if (opsStarted && !busy) {
                if (releaseTicks > 0) releaseTicks--;
                if (releaseTicks <= 0) {
                    frozen = false;
                    restoreInput();
                    if (logs.get()) info("Da chuyen Totem xong, co the di chuyen lai.");
                }
            }

            wasHoldingTotem = hasOffhandTotem();
            return;
        }

        boolean hasOffhand = hasOffhandTotem();

        // Tu nap ngay neu tay phu trong, ke ca luc vua bat module.
        if (!hasOffhand && !busy && hasTotemSomewhere()) {
            if (freezeMove.get()) startFrozenTransfer();
            else trigger();
        } else if (wasHoldingTotem && !hasOffhand && !busy && !hasTotemSomewhere() && logs.get()) {
            warning("Het Totem trong tui, khong the nap vao tay phu.");
        }

        wasHoldingTotem = hasOffhand;
    }

    private void trigger() {
        busy = true;
        if (logs.get()) info("Totem tay phu trong, dang chuyen Totem...");
        scheduleOps();
    }

    private void scheduleOps() {
        scheduler.schedule(
            () -> mc.execute(this::opEquipOffhand),
            0,
            TimeUnit.MILLISECONDS
        );
        scheduler.schedule(
            () -> mc.execute(this::opRefillHotbar),
            opDelayMs.get(),
            TimeUnit.MILLISECONDS
        );
    }

    private void startFrozenTransfer() {
        busy = true;
        frozen = true;
        freezeElapsed = 0;
        opsStarted = false;
        releaseTicks = 2;
        blockInput();
        if (logs.get()) info("Totem tay phu trong, tam dung di chuyen de chuyen Totem...");
    }

    private void blockInput() {
        if (mc.options == null) return;
        mc.options.forwardKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
    }

    private void restoreInput() {
        try {
            net.minecraft.client.option.KeyBinding.updatePressedStates();
        } catch (Throwable ignored) {
        }
    }

    private void opEquipOffhand() {
        try {
            if (mc.player == null || mc.interactionManager == null) return;
            if (hasOffhandTotem()) return;
            if (!prepareClick()) return;

            int slot = findTotemPreferHotbar();
            if (slot == -1) return;

            mc.interactionManager.clickSlot(
                menuContainerId(),
                toMenuSlot(slot),
                OFFHAND_BUTTON,
                SlotActionType.SWAP,
                mc.player
            );

            if (logs.get()) info("Da dua Totem tu o " + (slot + 1) + " vao tay phu.");
        } catch (Throwable error) {
            if (logs.get()) error("Loi khi chuyen Totem: " + error.getMessage());
        }
    }

    private void opRefillHotbar() {
        try {
            if (mc.player == null || mc.interactionManager == null) {
                finish();
                return;
            }
            if (!prepareClick()) {
                finish();
                return;
            }

            int targetSlot = hotbarSlot.get() - 1;
            if (mc.player.getInventory().getStack(targetSlot).isOf(Items.TOTEM_OF_UNDYING)) {
                finish();
                return;
            }

            int sourceSlot = findTotemInMain();
            if (sourceSlot == -1) {
                finish();
                return;
            }

            mc.interactionManager.clickSlot(
                menuContainerId(),
                sourceSlot,
                targetSlot,
                SlotActionType.SWAP,
                mc.player
            );

            if (logs.get()) info("Da bo sung Totem vao hotbar o " + hotbarSlot.get() + ".");
        } catch (Throwable error) {
            if (logs.get()) error("Loi khi bo sung Totem: " + error.getMessage());
        } finally {
            finish();
        }
    }

    private void finish() {
        busy = false;
        if (autoOpened) closeTicks = 2;
    }

    private boolean prepareClick() {
        if (mc.player == null) return false;

        if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) {
            if (!closeOtherGui.get()) {
                if (logs.get()) warning("Dang mo container khac, bo qua chuyen Totem.");
                return false;
            }

            mc.player.closeHandledScreen();
            if (logs.get()) info("Da dong giao dien khac de chuyen Totem.");
        }

        if (!hideGui.get() && mc.currentScreen == null) {
            mc.setScreen(new InventoryScreen(mc.player));
            autoOpened = true;
        }

        return true;
    }

    private int menuContainerId() {
        if (mc.player == null) return 0;
        return mc.player.currentScreenHandler.syncId;
    }

    private int toMenuSlot(int inventorySlot) {
        return inventorySlot < 9 ? inventorySlot + 36 : inventorySlot;
    }

    private int findTotemPreferHotbar() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) return i;
        }
        return findTotemInMain();
    }

    private int findTotemInMain() {
        for (int i = 9; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) return i;
        }
        return -1;
    }

    private boolean hasTotemSomewhere() {
        if (mc.player == null) return false;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) return true;
        }
        return false;
    }

    private boolean hasOffhandTotem() {
        return mc.player != null
            && mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING);
    }

    @Override
    public String getInfoString() {
        return hasOffhandTotem() ? "Co Totem" : "Thieu Totem";
    }
}

package com.hwell.hwelladdon.modules;

import com.hwell.hwelladdon.HwellAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.*;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

public class AutoEnchant extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // ============================================================
    // SETTINGS — 3 setting riêng để đảm bảo thứ tự phase
    // ============================================================
    private final Setting<Item> itemEnchant = sgGeneral.add(new ItemSetting.Builder()
        .name("item-enchant")
        .description("Chọn vật phẩm cần enchant.")
        .defaultValue(Items.DIAMOND_AXE)
        .build()
    );

    private final Setting<Set<RegistryKey<Enchantment>>> enchant1 = sgGeneral.add(new EnchantmentListSetting.Builder()
        .name("enchant-1")
        .description("Phù phép 1 (phase 1). Chỉ chọn 1 phù phép.")
        .defaultValue(Enchantments.MENDING)
        .build()
    );

    private final Setting<Set<RegistryKey<Enchantment>>> enchant2 = sgGeneral.add(new EnchantmentListSetting.Builder()
        .name("enchant-2")
        .description("Phù phép 2 (phase 2). Chỉ chọn 1 phù phép.")
        .defaultValue(Enchantments.UNBREAKING)
        .build()
    );

    private final Setting<Set<RegistryKey<Enchantment>>> enchant3 = sgGeneral.add(new EnchantmentListSetting.Builder()
        .name("enchant-3")
        .description("Phù phép 3 (phase 3). Chỉ chọn 1 phù phép.")
        .defaultValue(Enchantments.EFFICIENCY)
        .build()
    );

    private final Setting<Integer> batchAmount = sgGeneral.add(new IntSetting.Builder()
        .name("batch-amount").description("Số item lấy ra mỗi lần (1-7).")
        .defaultValue(4).range(1, 7).sliderRange(1, 7).build());

    private final Setting<Integer> batchCount = sgGeneral.add(new IntSetting.Builder()
        .name("batch-count").description("Số lần lặp lại quy trình (1-100).")
        .defaultValue(1).range(1, 100).sliderRange(1, 100).build());

    private final Setting<Integer> delayMs = sgGeneral.add(new IntSetting.Builder()
        .name("delay").description("Delay giữa các thao tác (ms).")
        .defaultValue(150).range(50, 1000).sliderRange(50, 1000).build());

    private final Setting<Boolean> silentMode = sgGeneral.add(new BoolSetting.Builder()
        .name("silent-mode")
        .description("Không hiện GUI khi enchant.")
        .defaultValue(false)
        .build());

    // ============================================================
    // STATE
    // ============================================================
    private enum State {
        SEARCHING_CHEST, OPENING_CHEST, WITHDRAWING,
        SEARCHING_ANVIL, OPENING_ANVIL, ENCHANTING, FINISHING
    }

    private State state = State.SEARCHING_CHEST;
    private int timer = 0;
    private int currentCycle = 1;
    private int totalCycles = 1;
    private int targetPerCycle = 1;
    private int enchantedThisCycle = 0;
    private int enchantPhase = 1;
    private int actionStep = 0;
    private int stuckTicks = 0;
    private BlockPos currentTargetPos = null;
    private final Set<BlockPos> visitedChests = new HashSet<>();

    public AutoEnchant() {
        super(HwellAddon.FARM, "Hwell Auto Enchant", "Tự động lấy đồ từ rương và enchant ở anvil.");
    }

    public boolean isSilentMode() { return silentMode.get(); }

    private RegistryKey<Enchantment> getKey(Setting<Set<RegistryKey<Enchantment>>> setting) {
        Set<RegistryKey<Enchantment>> s = setting.get();
        return (s == null || s.isEmpty()) ? null : s.iterator().next();
    }

    @Override
    public void onActivate() {
        totalCycles = batchCount.get();
        targetPerCycle = batchAmount.get();
        currentCycle = 1;
        enchantedThisCycle = 0;
        state = State.SEARCHING_CHEST;
        timer = 0;
        stuckTicks = 0;
        currentTargetPos = null;
        visitedChests.clear();
        enchantPhase = 1;
        actionStep = 0;

        if (mc.player != null) {
            // ✅ Feedback gốc của bạn
            mc.player.sendMessage(Text.literal("§c[hwelladdon] §6đã bật auto enchant made by hwell tư duy , logic , tối ưu về mọi mặt bọn ngu thích skid ý tưởng chan đi"), false);
        }
    }

    @Override
    public void onDeactivate() {
        state = State.SEARCHING_CHEST;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.interactionManager == null || mc.world == null) return;

        if ((state == State.ENCHANTING || state == State.OPENING_ANVIL) && currentTargetPos != null) {
            if (!(mc.world.getBlockState(currentTargetPos).getBlock() instanceof AnvilBlock)) {
                state = State.SEARCHING_ANVIL;
                actionStep = 0;
                stuckTicks = 0;
                timer = getTicks();
                return;
            }
        }

        if (isInterrupted()) {
            toggle();
            return;
        }

        if (timer > 0) { timer--; return; }

        ScreenHandler handler = mc.player.currentScreenHandler;
        boolean guiOpen = handler != mc.player.playerScreenHandler;

        switch (state) {
            case SEARCHING_CHEST -> {
                currentTargetPos = findNearbyUnvisitedChest(5);
                if (currentTargetPos != null) {
                    rotateTo(currentTargetPos);
                    state = State.OPENING_CHEST;
                    timer = getTicks();
                } else {
                    toggle();
                }
            }

            case OPENING_CHEST -> {
                if (!guiOpen) {
                    interactBlock(currentTargetPos);
                    timer = getTicks();
                } else {
                    state = State.WITHDRAWING;
                    timer = getTicks();
                }
            }

            case WITHDRAWING -> {
                if (guiOpen) {
                    int invItem = countInInventory(this::isTargetItem);
                    int invEc1 = countInInventory(this::isEc1Book);
                    int invEc2 = countInInventory(this::isEc2Book);
                    int invEc3 = countInInventory(this::isEc3Book);

                    if (invItem >= targetPerCycle && invEc1 >= targetPerCycle
                            && invEc2 >= targetPerCycle && invEc3 >= targetPerCycle) {
                        mc.player.closeHandledScreen();
                        state = State.SEARCHING_ANVIL;
                        timer = getTicks();
                        return;
                    }

                    boolean withdrew = false;
                    int chestSize = handler.slots.size() - 36;

                    for (int i = 0; i < chestSize; i++) {
                        ItemStack stack = handler.getSlot(i).getStack();
                        if (stack.isEmpty()) continue;

                        if (isTargetItem(stack) && invItem < targetPerCycle) {
                            mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                            withdrew = true; break;
                        } else if (isEc1Book(stack) && invEc1 < targetPerCycle) {
                            mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                            withdrew = true; break;
                        } else if (isEc2Book(stack) && invEc2 < targetPerCycle) {
                            mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                            withdrew = true; break;
                        } else if (isEc3Book(stack) && invEc3 < targetPerCycle) {
                            mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                            withdrew = true; break;
                        }
                    }

                    if (withdrew) {
                        timer = getTicks();
                    } else {
                        visitedChests.add(currentTargetPos);
                        mc.player.closeHandledScreen();
                        state = State.SEARCHING_CHEST;
                        timer = getTicks();
                    }
                } else {
                    state = State.SEARCHING_CHEST;
                    timer = getTicks();
                }
            }

            case SEARCHING_ANVIL -> {
                currentTargetPos = findNearbyBlock(AnvilBlock.class, 5);
                if (currentTargetPos != null) {
                    rotateTo(currentTargetPos);
                    state = State.OPENING_ANVIL;
                    timer = getTicks();
                } else {
                    timer = 40;
                }
            }

            case OPENING_ANVIL -> {
                if (!guiOpen) {
                    interactBlock(currentTargetPos);
                    timer = getTicks();
                } else {
                    state = State.ENCHANTING;
                    actionStep = 0;
                    stuckTicks = 0;
                    timer = getTicks();
                }
            }

            case ENCHANTING -> {
                if (!guiOpen) {
                    state = State.SEARCHING_ANVIL;
                    timer = getTicks();
                    return;
                }

                ItemStack slot0 = handler.getSlot(0).getStack();
                ItemStack slot1 = handler.getSlot(1).getStack();
                ItemStack slot2 = handler.getSlot(2).getStack();

                if (actionStep == 0) {
                    stuckTicks = 0;
                    if (slot0.isEmpty()) {
                        if (!moveItemToSlot(this::isTargetItem, 0)) {
                            msg("§chết item để enchant rồi.");
                            toggle(); return;
                        }
                        timer = getTicks();
                    } else if (isTargetItem(slot0)) {
                        RegistryKey<Enchantment> k1 = getKey(enchant1);
                        RegistryKey<Enchantment> k2 = getKey(enchant2);
                        RegistryKey<Enchantment> k3 = getKey(enchant3);

                        if (k1 != null && !hasEnchantment(slot0, k1)) {
                            enchantPhase = 1;
                        } else if (k2 != null && !hasEnchantment(slot0, k2)) {
                            enchantPhase = 2;
                        } else if (k3 != null && !hasEnchantment(slot0, k3)) {
                            enchantPhase = 3;
                        } else {
                            mc.interactionManager.clickSlot(handler.syncId, 0, 0, SlotActionType.THROW, mc.player);
                            finishCurrentItem();
                            return;
                        }
                        actionStep = 1;
                        stuckTicks = 0;
                        timer = getTicks();
                    } else {
                        mc.interactionManager.clickSlot(handler.syncId, 0, 0, SlotActionType.QUICK_MOVE, mc.player);
                        timer = getTicks();
                    }
                } else if (actionStep == 1) {
                    stuckTicks++;
                    if (stuckTicks > 60) {
                        msg("§ckhông tìm thấy sách phù phép phase " + enchantPhase + " trong inventory.");
                        toggle(); return;
                    }
                    Predicate<ItemStack> bookPredicate = getBookPredicate(enchantPhase);
                    if (bookPredicate.test(slot1)) {
                        actionStep = 2; stuckTicks = 0; timer = getTicks();
                    } else if (slot1.isEmpty()) {
                        moveItemToSlot(bookPredicate, 1);
                        timer = getTicks();
                    } else {
                        mc.interactionManager.clickSlot(handler.syncId, 1, 0, SlotActionType.QUICK_MOVE, mc.player);
                        timer = getTicks();
                    }
                } else if (actionStep == 2) {
                    if (!slot2.isEmpty()) {
                        actionStep = 3; stuckTicks = 0; timer = getTicks();
                    } else {
                        stuckTicks++;
                        if (stuckTicks > 40) {
                            msg("§cdcu exp thiếu kìa enchant cc j nữa");
                            toggle(); return;
                        }
                        timer = getTicks();
                    }
                } else if (actionStep == 3) {
                    if (!slot2.isEmpty()) {
                        mc.interactionManager.clickSlot(handler.syncId, 2, 0, SlotActionType.PICKUP, mc.player);
                        actionStep = 4; stuckTicks = 0; timer = 1;
                    } else {
                        actionStep = 2; timer = getTicks();
                    }
                } else if (actionStep == 4) {
                    stuckTicks++;
                    if (slot2.isEmpty()) {
                        mc.interactionManager.clickSlot(handler.syncId, 0, 0, SlotActionType.PICKUP, mc.player);
                        actionStep = 0; stuckTicks = 0; timer = getTicks();
                    } else {
                        if (stuckTicks > 20) {
                            msg("§cdcu exp thiếu kìa enchant cc j nữa");
                            toggle(); return;
                        }
                        actionStep = 3; timer = 40;
                    }
                }
            }

            case FINISHING -> {
                if (guiOpen) mc.player.closeHandledScreen();
                toggle();
            }
        }
    }

    private Predicate<ItemStack> getBookPredicate(int phase) {
        return switch (phase) {
            case 1 -> this::isEc1Book;
            case 2 -> this::isEc2Book;
            default -> this::isEc3Book;
        };
    }

    private void finishCurrentItem() {
        enchantedThisCycle++;

        // ✅ Feedback gốc của bạn
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("§c[hwelladdon] §6xog rồi đáy ku e (" + enchantedThisCycle + "/" + targetPerCycle + ")"), false);
        }

        if (enchantedThisCycle >= targetPerCycle) {
            if (currentCycle >= totalCycles) {
                state = State.FINISHING;
                if (mc.player != null) mc.player.closeHandledScreen();
                toggle();
            } else {
                currentCycle++;
                enchantedThisCycle = 0;
                if (mc.player != null) mc.player.closeHandledScreen();
                state = State.SEARCHING_CHEST;
                timer = getTicks();
            }
        } else {
            enchantPhase = 1;
            actionStep = 0;
            stuckTicks = 0;
            timer = getTicks();
        }
    }

    private void msg(String s) {
        if (mc.player != null) mc.player.sendMessage(Text.literal("§c[hwelladdon] " + s), false);
    }

    // ============================================================
    // HELPERS — dùng matchesKey để so sánh RegistryKey với RegistryEntry
    // ============================================================
    private boolean isTargetItem(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() == itemEnchant.get();
    }

    private boolean isEc1Book(ItemStack stack) { return isSpecificEnchantedBook(stack, getKey(enchant1)); }
    private boolean isEc2Book(ItemStack stack) { return isSpecificEnchantedBook(stack, getKey(enchant2)); }
    private boolean isEc3Book(ItemStack stack) { return isSpecificEnchantedBook(stack, getKey(enchant3)); }

    private boolean isSpecificEnchantedBook(ItemStack stack, RegistryKey<Enchantment> targetKey) {
        if (stack.isEmpty() || stack.getItem() != Items.ENCHANTED_BOOK || targetKey == null) return false;
        ItemEnchantmentsComponent enchantments = stack.get(DataComponentTypes.STORED_ENCHANTMENTS);
        if (enchantments == null) return false;
        for (RegistryEntry<Enchantment> entry : enchantments.getEnchantments()) {
            if (entry.matchesKey(targetKey)) return true;
        }
        return false;
    }

    private boolean hasEnchantment(ItemStack stack, RegistryKey<Enchantment> targetKey) {
        if (stack.isEmpty() || targetKey == null) return false;
        ItemEnchantmentsComponent enchantments = stack.get(DataComponentTypes.ENCHANTMENTS);
        if (enchantments == null) return false;
        for (RegistryEntry<Enchantment> entry : enchantments.getEnchantments()) {
            if (entry.matchesKey(targetKey)) return true;
        }
        return false;
    }

    private boolean moveItemToSlot(Predicate<ItemStack> predicate, int targetSlot) {
        ScreenHandler handler = mc.player.currentScreenHandler;
        int totalSlots = handler.slots.size();
        int playerInvStart = totalSlots - 36;

        for (int i = playerInvStart; i < totalSlots; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) continue;
            if (predicate.test(stack)) {
                mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.PICKUP, mc.player);
                mc.interactionManager.clickSlot(handler.syncId, targetSlot, 0, SlotActionType.PICKUP, mc.player);
                return true;
            }
        }
        return false;
    }

    private int countInInventory(Predicate<ItemStack> predicate) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (predicate.test(stack)) count += stack.getCount();
        }
        return count;
    }

    private BlockPos findNearbyUnvisitedChest(int radius) {
        BlockPos playerPos = mc.player.getBlockPos();
        for (BlockPos pos : BlockPos.iterateOutwards(playerPos, radius, radius, radius)) {
            Block block = mc.world.getBlockState(pos).getBlock();
            if (block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock) {
                BlockPos immutablePos = pos.toImmutable();
                if (!visitedChests.contains(immutablePos)) return immutablePos;
            }
        }
        return null;
    }

    private BlockPos findNearbyBlock(Class<? extends Block> blockClass, int radius) {
        BlockPos playerPos = mc.player.getBlockPos();
        for (BlockPos pos : BlockPos.iterateOutwards(playerPos, radius, radius, radius)) {
            if (blockClass.isInstance(mc.world.getBlockState(pos).getBlock())) return pos.toImmutable();
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
    }

    private boolean isInterrupted() {
        if (mc.player == null) return true;
        if (!isSilentMode() && mc.currentScreen == null && state == State.ENCHANTING) {
            if (currentTargetPos != null && mc.world.getBlockState(currentTargetPos).getBlock() instanceof AnvilBlock) {
                return true;
            }
        }
        if (mc.player.hurtTime > 0) return true;
        return false;
    }

    private int getTicks() {
        return Math.max(1, delayMs.get() / 50);
    }
}
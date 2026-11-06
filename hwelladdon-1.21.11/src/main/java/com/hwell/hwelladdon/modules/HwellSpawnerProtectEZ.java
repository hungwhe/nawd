package com.hwell.hwelladdon.modules;

import com.hwell.hwelladdon.HwellAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.AutoReconnect;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.*;

public class HwellSpawnerProtectEZ extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgWhitelist = settings.createGroup("Whitelist");

    // ============================================================
    // GENERAL
    // ============================================================
    private final Setting<Boolean> notifications = sgGeneral.add(new BoolSetting.Builder()
        .name("notifications")
        .description("Hiện thông báo chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> spawnerRange = sgGeneral.add(new IntSetting.Builder()
        .name("spawner-range")
        .description("Bán kính tìm spawner (block).")
        .defaultValue(16)
        .range(1, 50)
        .sliderRange(1, 50)
        .build()
    );

    private final Setting<Integer> emergencyDistance = sgGeneral.add(new IntSetting.Builder()
        .name("emergency-distance")
        .description("Khoảng cách (block) player khác đến gần → disconnect ngay (0 = tắt).")
        .defaultValue(7)
        .range(0, 20)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Integer> minDetectionRange = sgGeneral.add(new IntSetting.Builder()
        .name("min-detection-range")
        .description("Khoảng cách tối thiểu để phát hiện player.")
        .defaultValue(0)
        .range(0, 50)
        .sliderRange(0, 50)
        .build()
    );

    private final Setting<Integer> maxDetectionRange = sgGeneral.add(new IntSetting.Builder()
        .name("max-detection-range")
        .description("Khoảng cách tối đa để phát hiện player.")
        .defaultValue(50)
        .range(1, 100)
        .sliderRange(1, 100)
        .build()
    );

    private final Setting<Integer> spawnerCheckDelay = sgGeneral.add(new IntSetting.Builder()
        .name("spawner-check-delay-ms")
        .description("Delay (ms) xác nhận hết spawner.")
        .defaultValue(3000)
        .range(1000, 10000)
        .sliderRange(1000, 10000)
        .build()
    );

    private final Setting<Integer> spawnerTimeout = sgGeneral.add(new IntSetting.Builder()
        .name("spawner-timeout-ms")
        .description("Timeout (ms) đào 1 spawner, quá thì bỏ qua.")
        .defaultValue(4000)
        .range(4000, 30000)
        .sliderRange(4000, 30000)
        .build()
    );

    private final Setting<Boolean> depositToEChest = sgGeneral.add(new BoolSetting.Builder()
        .name("deposit-to-echest")
        .description("Deposit spawner vào ender chest.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<Item>> depositBlacklist = sgGeneral.add(new ItemListSetting.Builder()
        .name("deposit-blacklist")
        .description("Item không bao giờ deposit vào echest.")
        .defaultValue(Arrays.asList(
            Items.ENDER_PEARL,
            Items.END_CRYSTAL,
            Items.OBSIDIAN,
            Items.RESPAWN_ANCHOR,
            Items.GLOWSTONE,
            Items.TOTEM_OF_UNDYING))
        .visible(depositToEChest::get)
        .build()
    );

    // ============================================================
    // WHITELIST
    // ============================================================
    private final Setting<Boolean> enableWhitelist = sgWhitelist.add(new BoolSetting.Builder()
        .name("enable-whitelist")
        .description("Bật whitelist player (không trigger protect).")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<String>> whitelistPlayers = sgWhitelist.add(new StringListSetting.Builder()
        .name("whitelisted-players")
        .description("Danh sách tên player bỏ qua.")
        .defaultValue(new ArrayList<>())
        .visible(enableWhitelist::get)
        .build()
    );

    // ============================================================
    // STATE
    // ============================================================
    private enum State {
        IDLE,
        GOING_TO_SPAWNERS,
        GOING_TO_CHEST,
        OPENING_CHEST,
        DEPOSITING_ITEMS,
        DISCONNECTING
    }

    private State currentState = State.IDLE;
    private String detectedPlayer = "";
    private int tickCounter = 0;
    private int transferDelayCounter = 0;
    private int lastProcessedSlot = -1;
    private BlockPos currentTarget = null;
    private long noSpawnerStartTime = -1;
    private long currentTargetStartTime = -1;
    private BlockPos targetChest = null;
    private int chestOpenAttempts = 0;
    private float targetYaw, targetPitch;
    private boolean rotating = false;
    private final float ROTATION_SPEED = 8.0f;
    private final Set<BlockPos> invalidSpawners = new HashSet<>();

    public HwellSpawnerProtectEZ() {
        super(HwellAddon.FARM, "hwell-spawner-protect-ez",
            "Phát hiện player → đào spawner (silk touch) → deposit echest → disconnect.");
    }

    @Override
    public void onActivate() {
        resetState();
        if (notifications.get()) {
            info("Spawner Protect EZ đã bật. Cần: pickaxe silk touch + ender chest gần.");
        }
    }

    private void resetState() {
        currentState = State.IDLE;
        detectedPlayer = "";
        tickCounter = 0;
        transferDelayCounter = 0;
        lastProcessedSlot = -1;
        currentTarget = null;
        noSpawnerStartTime = -1;
        currentTargetStartTime = -1;
        targetChest = null;
        chestOpenAttempts = 0;
        rotating = false;
        invalidSpawners.clear();
    }

    private void disableAutoReconnectIfEnabled() {
        AutoReconnect autoReconnect = Modules.get().get(AutoReconnect.class);
        if (autoReconnect != null && autoReconnect.isActive()) {
            autoReconnect.toggle();
            if (notifications.get()) info("AutoReconnect đã tắt (tránh reconnect khi protect).");
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // ============================================================
        // XỬ LÝ ROTATION MƯỢT
        // ============================================================
        if (rotating) {
            float yaw = mc.player.getYaw();
            float pitch = mc.player.getPitch();
            float yawDiff = wrapDegrees(targetYaw - yaw);
            float pitchDiff = targetPitch - pitch;
            float newYaw = yaw + Math.signum(yawDiff) * Math.min(Math.abs(yawDiff), ROTATION_SPEED);
            float newPitch = pitch + Math.signum(pitchDiff) * Math.min(Math.abs(pitchDiff), ROTATION_SPEED);
            mc.player.setYaw(newYaw);
            mc.player.setPitch(newPitch);
            if (Math.abs(yawDiff) < 1f && Math.abs(pitchDiff) < 1f) {
                rotating = false;
            }
        }

        // Sneak khi đi tới spawner
        if (currentState == State.GOING_TO_SPAWNERS) {
            mc.options.sneakKey.setPressed(true);
        }

        tickCounter++;

        if (transferDelayCounter > 0) {
            transferDelayCounter--;
            return;
        }

        // ============================================================
        // STATE MACHINE
        // ============================================================
        switch (currentState) {
            case IDLE -> {
                if (checkEmergencyDisconnect()) return;
                checkForPlayers();
            }
            case GOING_TO_SPAWNERS -> handleGoingToSpawners();
            case GOING_TO_CHEST -> handleGoingToChest();
            case OPENING_CHEST -> handleOpeningChest();
            case DEPOSITING_ITEMS -> handleDepositingItems();
            case DISCONNECTING -> handleDisconnecting();
        }
    }

    private float wrapDegrees(float value) {
        value %= 360.0f;
        if (value >= 180.0f) value -= 360.0f;
        if (value < -180.0f) value += 360.0f;
        return value;
    }

    // ============================================================
    // PLAYER DETECTION
    // ============================================================
    private void checkForPlayers() {
        for (AbstractClientPlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;

            double distance = mc.player.distanceTo(player);
            if (distance < minDetectionRange.get() || distance > maxDetectionRange.get()) continue;

            String playerName = player.getName().getString();
            if (isPlayerWhitelisted(playerName)) continue;

            detectedPlayer = playerName;
            if (notifications.get()) {
                info("Phát hiện player: " + playerName + " ở " + String.format("%.1f", distance) + " block.");
            }
            disableAutoReconnectIfEnabled();
            currentState = State.GOING_TO_SPAWNERS;
            return;
        }
    }

    private boolean checkEmergencyDisconnect() {
        if (emergencyDistance.get() <= 0) return false;

        for (AbstractClientPlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            String playerName = player.getName().getString();
            if (isPlayerWhitelisted(playerName)) continue;

            double distance = mc.player.distanceTo(player);
            if (distance <= emergencyDistance.get()) {
                if (notifications.get()) {
                    info("EMERGENCY: " + playerName + " quá gần (" + String.format("%.1f", distance) + " block)!");
                }
                detectedPlayer = playerName;
                disableAutoReconnectIfEnabled();
                currentState = State.DISCONNECTING;
                return true;
            }
        }
        return false;
    }

    private boolean isPlayerWhitelisted(String playerName) {
        if (!enableWhitelist.get() || whitelistPlayers.get().isEmpty()) return false;
        return whitelistPlayers.get().stream().anyMatch(n -> n.equalsIgnoreCase(playerName));
    }

    // ============================================================
    // HELPER: Lấy vị trí player (tránh lỗi getPos rename giữa các version)
    // ============================================================
    private Vec3d getPlayerPos() {
        return new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
    }

    // ============================================================
    // MINING SPAWNERS
    // ============================================================
    private void handleGoingToSpawners() {
        mc.options.sneakKey.setPressed(true);

        // Kiểm tra target còn hợp lệ
        if (currentTarget != null && mc.world.getBlockState(currentTarget).getBlock() != Blocks.SPAWNER) {
            currentTarget = null;
            currentTargetStartTime = -1;
            stopBreaking();
            noSpawnerStartTime = -1;
            return;
        }

        // Tìm target mới
        if (currentTarget == null) {
            currentTarget = findNearestSpawner();

            if (currentTarget == null) {
                if (noSpawnerStartTime == -1) {
                    noSpawnerStartTime = System.currentTimeMillis();
                    return;
                }
                if (System.currentTimeMillis() - noSpawnerStartTime < spawnerCheckDelay.get()) {
                    return;
                }
                invalidSpawners.clear();
                stopBreaking();
                currentState = State.GOING_TO_CHEST;
                noSpawnerStartTime = -1;
                if (notifications.get()) info("Hết spawner, tới echest...");
                return;
            }
            currentTargetStartTime = System.currentTimeMillis();
        }
        noSpawnerStartTime = -1;

        // Timeout
        if (currentTargetStartTime != -1
            && System.currentTimeMillis() - currentTargetStartTime > spawnerTimeout.get()) {
            invalidSpawners.add(currentTarget);
            currentTarget = null;
            currentTargetStartTime = -1;
            stopBreaking();
            return;
        }

        // Xoay mặt
        Direction side = getExposedFaceSide(currentTarget);
        lookAtBlock(currentTarget, side);

        // Đào nếu crosshair vào spawner
        if (mc.crosshairTarget instanceof BlockHitResult hit
            && hit.getBlockPos().equals(currentTarget)) {

            FindItemResult pickaxe = findSilkTouchPickaxe();
            if (!pickaxe.found()) {
                stopBreaking();
                currentState = State.GOING_TO_CHEST;
                if (notifications.get()) info("Không có pickaxe silk touch → tới echest.");
                return;
            }

            InvUtils.swap(pickaxe.slot(), true);
            mc.options.attackKey.setPressed(true);
            mc.interactionManager.updateBlockBreakingProgress(currentTarget, hit.getSide());
        }
    }

    private BlockPos findNearestSpawner() {
        BlockPos playerPos = mc.player.getBlockPos();
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;
        double maxDistSq = spawnerRange.get() * spawnerRange.get();

        for (BlockPos pos : BlockPos.iterateOutwards(playerPos, spawnerRange.get(), spawnerRange.get(), spawnerRange.get())) {
            if (mc.world.getBlockState(pos).getBlock() != Blocks.SPAWNER) continue;
            if (invalidSpawners.contains(pos)) continue;

            double distSq = pos.getSquaredDistance(getPlayerPos());
            if (distSq > maxDistSq || distSq >= nearestDist) continue;

            nearestDist = distSq;
            nearest = pos.toImmutable();
        }
        return nearest;
    }

    private Direction getExposedFaceSide(BlockPos pos) {
        for (Direction side : Direction.values()) {
            BlockPos neighbor = pos.offset(side);
            if (mc.world.getBlockState(neighbor).isAir()
                || !mc.world.getBlockState(neighbor).isSolidBlock(mc.world, neighbor)) {
                return side;
            }
        }
        return Direction.UP;
    }

    private void lookAtBlock(BlockPos pos, Direction side) {
        Vec3d targetPos = Vec3d.ofCenter(pos).add(
            new Vec3d(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5));
        Vec3d eyePos = mc.player.getEyePos();
        Vec3d dir = targetPos.subtract(eyePos).normalize();

        targetYaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        targetPitch = (float) Math.toDegrees(-Math.asin(dir.y));
        rotating = true;
    }

    private FindItemResult findSilkTouchPickaxe() {
        return InvUtils.find(stack -> {
            if (stack.isEmpty() || !stack.isIn(ItemTags.PICKAXES)) return false;
            ItemEnchantmentsComponent ench = stack.get(DataComponentTypes.ENCHANTMENTS);
            if (ench == null) return false;
            for (RegistryEntry<Enchantment> entry : ench.getEnchantments()) {
                if (entry.matchesKey(Enchantments.SILK_TOUCH)) return true;
            }
            return false;
        });
    }

    private void stopBreaking() {
        mc.options.attackKey.setPressed(false);
    }

    // ============================================================
    // GO TO ENDER CHEST
    // ============================================================
    private void handleGoingToChest() {
        if (!depositToEChest.get()) {
            currentState = State.DISCONNECTING;
            return;
        }

        if (targetChest == null) {
            targetChest = findNearestEnderChest();
            if (targetChest == null) {
                if (notifications.get()) info("Không tìm thấy ender chest!");
                currentState = State.DISCONNECTING;
                return;
            }
        }

        // Đi tới echest
        Vec3d direction = Vec3d.ofCenter(targetChest).subtract(getPlayerPos()).normalize();
        double yaw = Math.toDegrees(Math.atan2(-direction.x, direction.z));
        mc.player.setYaw((float) yaw);
        mc.options.forwardKey.setPressed(true);

        // Đủ gần → mở
        if (mc.player.getBlockPos().getSquaredDistance(targetChest) <= 9) {
            mc.options.forwardKey.setPressed(false);
            currentState = State.OPENING_CHEST;
            chestOpenAttempts = 0;
        }

        if (tickCounter > 600) {
            currentState = State.DISCONNECTING;
        }
    }

    private BlockPos findNearestEnderChest() {
        BlockPos playerPos = mc.player.getBlockPos();
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.iterateOutwards(playerPos, 16, 8, 16)) {
            if (mc.world.getBlockState(pos).getBlock() == Blocks.ENDER_CHEST) {
                double dist = pos.getSquaredDistance(getPlayerPos());
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = pos.toImmutable();
                }
            }
        }
        return nearest;
    }

    // ============================================================
    // OPEN CHEST
    // ============================================================
    private void handleOpeningChest() {
        if (targetChest == null) {
            currentState = State.GOING_TO_CHEST;
            return;
        }

        mc.options.sneakKey.setPressed(false);
        mc.options.forwardKey.setPressed(false);

        if (chestOpenAttempts < 20) {
            lookAtBlock(targetChest, Direction.UP);
        }

        if (chestOpenAttempts % 5 == 0 && mc.interactionManager != null) {
            BlockHitResult hit = new BlockHitResult(
                Vec3d.ofCenter(targetChest), Direction.UP, targetChest, false);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        }

        chestOpenAttempts++;

        if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler) {
            currentState = State.DEPOSITING_ITEMS;
            lastProcessedSlot = -1;
            tickCounter = 0;
            if (notifications.get()) info("Đã mở ender chest!");
        }

        if (chestOpenAttempts > 200) {
            currentState = State.DISCONNECTING;
        }
    }

    // ============================================================
    // DEPOSIT ITEMS
    // ============================================================
    private void handleDepositingItems() {
        mc.options.sneakKey.setPressed(false);

        if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler)) {
            currentState = State.OPENING_CHEST;
            chestOpenAttempts = 0;
            return;
        }

        if (!hasItemsToDeposit()) {
            mc.player.closeHandledScreen();
            transferDelayCounter = 10;
            currentState = State.DISCONNECTING;
            if (notifications.get()) info("Đã deposit xong!");
            return;
        }

        transferItemsToChest(handler);

        if (tickCounter > 900) {
            currentState = State.DISCONNECTING;
        }
    }

    private boolean isVitalItem(ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() == Items.AIR) return true;
        if (depositBlacklist.get().contains(stack.getItem())) return true;
        if (stack.getItem() == Items.ENDER_CHEST) return true;
        return false;
    }

    private boolean hasItemsToDeposit() {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && !isVitalItem(stack)) return true;
        }
        return false;
    }

    private void transferItemsToChest(ScreenHandler handler) {
        int totalSlots = handler.slots.size();
        int chestSlots = totalSlots - 36;
        int playerInvStart = chestSlots;

        if (isChestFull(handler, chestSlots)) {
            if (notifications.get()) error("Echest đầy! Disconnect...");
            currentState = State.DISCONNECTING;
            return;
        }

        // Ưu tiên 1: spawner
        for (int i = 0; i < 36; i++) {
            int slotId = playerInvStart + i;
            ItemStack stack = handler.getSlot(slotId).getStack();
            if (!stack.isEmpty() && stack.getItem() == Items.SPAWNER) {
                depositSlot(handler, slotId);
                return;
            }
        }

        // Ưu tiên 2: non-vital
        for (int i = 0; i < 36; i++) {
            int slotId = playerInvStart + i;
            ItemStack stack = handler.getSlot(slotId).getStack();
            if (!stack.isEmpty() && !isVitalItem(stack)) {
                depositSlot(handler, slotId);
                return;
            }
        }

        if (lastProcessedSlot >= playerInvStart) {
            lastProcessedSlot = playerInvStart - 1;
            transferDelayCounter = 3;
        }
    }

    private void depositSlot(ScreenHandler handler, int slotId) {
        mc.interactionManager.clickSlot(
            handler.syncId, slotId, 0, SlotActionType.QUICK_MOVE, mc.player);
        lastProcessedSlot = slotId;
        transferDelayCounter = 2;
    }

    private boolean isChestFull(ScreenHandler handler, int chestSlots) {
        for (int i = 0; i < chestSlots; i++) {
            if (handler.getSlot(i).getStack().isEmpty()) return false;
        }
        return true;
    }

    // ============================================================
    // DISCONNECT
    // ============================================================
    private void handleDisconnecting() {
        mc.options.forwardKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
        mc.options.attackKey.setPressed(false);

        if (notifications.get()) {
            info("Disconnect do phát hiện: " + detectedPlayer);
        }

        if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().getConnection().disconnect(Text.literal(""));
        }

        toggle();
    }

    @Override
    public void onDeactivate() {
        stopBreaking();
        if (mc.options != null) {
            mc.options.forwardKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
            mc.options.sneakKey.setPressed(false);
            mc.options.attackKey.setPressed(false);
        }
    }
}
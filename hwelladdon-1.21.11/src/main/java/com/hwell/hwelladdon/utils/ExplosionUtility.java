package com.hwell.hwelladdon.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public class ExplosionUtility {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    public static float getAutoCrystalDamage(Vec3d crystalPos, PlayerEntity target, int predictTicks, boolean ignoreTerrain) {
        if (target == null || mc.world == null) return 0;

        Vec3d targetPos = PredictUtility.predictPosition(target, predictTicks);
        double dist = targetPos.distanceTo(crystalPos);
        if (dist > 12.0) return 0;

        Box box = target.getBoundingBox().offset(targetPos.subtract(target.getEntityPos()));
        double exposure = getExposure(crystalPos, box);

        double distFactor = 1.0 - (dist / 12.0);
        double rawDamage = (distFactor * distFactor + distFactor) / 2.0 * 7.0 * 12.0 + 1.0;
        rawDamage *= exposure;

        return getReduction(target, rawDamage);
    }

    public static float getSelfExplosionDamage(Vec3d crystalPos, int predictTicks, boolean ignoreTerrain) {
        if (mc.player == null) return 0;
        return getAutoCrystalDamage(crystalPos, mc.player, predictTicks, ignoreTerrain);
    }

    /**
     * Tính damage sau khi giảm bởi armor + resistance + absorption.
     * Dùng công thức đơn giản, KHÔNG dùng DamageUtil (API đã đổi trong 1.21.11).
     */
    private static float getReduction(PlayerEntity player, double damage) {
        // Resistance effect
        if (player.hasStatusEffect(StatusEffects.RESISTANCE)) {
            int resLevel = player.getStatusEffect(StatusEffects.RESISTANCE).getAmplifier() + 1;
            damage *= Math.max(0, 1.0 - resLevel * 0.2);
        }

        // Armor (Yarn 1.21.11: ARMOR thay vì GENERIC_ARMOR)
        double armor = 0;
        double toughness = 0;
        try {
            armor = player.getAttributeValue(EntityAttributes.ARMOR);
            toughness = player.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS);
        } catch (Throwable ignored) {
            // Fallback nếu attribute chưa có
        }

        // Công thức giảm damage của Minecraft (đơn giản hóa):
        // damage_reduction = armor - damage / (2 + toughness / 4)
        // damage_final = damage * (1 - damage_reduction / 25)
        double armorReduction = Math.max(
            armor / 5.0,
            armor - damage / (2.0 + toughness / 4.0)
        );
        armorReduction = Math.min(armorReduction, 20);

        damage = damage * (1.0 - armorReduction / 25.0);

        // Absorption (Yarn 1.21.11: getAbsorptionAmount vẫn có)
        double absorption = player.getAbsorptionAmount();
        damage -= absorption;

        return (float) Math.max(0, damage);
    }

    /**
     * Đếm protection enchant từ armor.
     * Yarn 1.21.11 đổi Enchantments.PROTECTION thành RegistryEntry — bỏ check này.
     */
    @SuppressWarnings("unused")
    private static int countProtection(PlayerEntity player) {
        int protection = 0;
        try {
            // Iterate armor slots 36-39
            for (int i = 36; i <= 39; i++) {
                ItemStack stack = player.getInventory().getStack(i);
                if (stack.isEmpty()) continue;
                // Bỏ check enchant vì API đổi
            }
        } catch (Throwable ignored) {}
        return Math.min(protection, 20);
    }

    private static float getExposure(Vec3d source, Box box) {
        if (mc.world == null) return 1.0f;

        double xDiff = box.maxX - box.minX;
        double yDiff = box.maxY - box.minY;
        double zDiff = box.maxZ - box.minZ;

        double xStep = 1.0 / (xDiff * 2.0 + 1.0);
        double yStep = 1.0 / (yDiff * 2.0 + 1.0);
        double zStep = 1.0 / (zDiff * 2.0 + 1.0);

        if (xStep < 0 || yStep < 0 || zStep < 0) return 0;

        int hits = 0;
        int total = 0;

        for (double x = 0; x <= 1.0; x += xStep) {
            for (double y = 0; y <= 1.0; y += yStep) {
                for (double z = 0; z <= 1.0; z += zStep) {
                    double px = MathHelper.lerp(x, box.minX, box.maxX);
                    double py = MathHelper.lerp(y, box.minY, box.maxY);
                    double pz = MathHelper.lerp(z, box.minZ, box.maxZ);

                    Vec3d point = new Vec3d(px, py, pz);

                    BlockHitResult hit = mc.world.raycast(new RaycastContext(
                        point, source,
                        RaycastContext.ShapeType.COLLIDER,
                        RaycastContext.FluidHandling.NONE,
                        mc.player
                    ));

                    if (hit == null || hit.getType() == HitResult.Type.MISS) hits++;
                    total++;
                }
            }
        }
        return total == 0 ? 0 : (float) hits / total;
    }
}
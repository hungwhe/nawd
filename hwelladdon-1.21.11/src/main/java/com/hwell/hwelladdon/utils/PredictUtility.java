package com.hwell.hwelladdon.utils;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

public class PredictUtility {
    public static Vec3d predictPosition(PlayerEntity player, int ticks) {
        if (ticks <= 0) return player.getEntityPos();

        Vec3d velocity = player.getVelocity();
        Vec3d current = player.getEntityPos();

        double drag = 0.91;
        double gravity = -0.08;
        double vy = velocity.y;

        double px = current.x;
        double py = current.y;
        double pz = current.z;

        for (int i = 0; i < ticks; i++) {
            px += velocity.x * Math.pow(drag, i + 1);
            pz += velocity.z * Math.pow(drag, i + 1);
            vy = (vy + gravity) * drag;
            py += vy;
        }
        return new Vec3d(px, py, pz);
    }

    public static Box predictBox(PlayerEntity player, int ticks) {
        if (ticks <= 0) return player.getBoundingBox();
        Vec3d predicted = predictPosition(player, ticks);
        Vec3d offset = predicted.subtract(player.getEntityPos());
        return player.getBoundingBox().offset(offset);
    }
}
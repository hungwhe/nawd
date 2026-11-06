package com.hwell.hwelladdon.utils;

import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class CrystalManager {
    private final Set<Integer> deadCrystals = new HashSet<>();
    private final Map<BlockPos, Long> awaitingPositions = new ConcurrentHashMap<>();
    private final Map<Integer, Long> deadTimestamps = new ConcurrentHashMap<>();

    private static final long DEAD_TIMEOUT = 2000;

    public void update() {
        long now = System.currentTimeMillis();
        deadTimestamps.entrySet().removeIf(e -> now - e.getValue() > DEAD_TIMEOUT);
        deadCrystals.removeIf(id -> !deadTimestamps.containsKey(id));
    }

    public void setDead(int id, long time) {
        deadCrystals.add(id);
        deadTimestamps.put(id, time);
    }

    public boolean isDead(int id) {
        return deadCrystals.contains(id);
    }

    public boolean isBlocked(int id) {
        return false;
    }

    public void addAwaitingPos(BlockPos pos) {
        awaitingPositions.put(pos, System.currentTimeMillis());
    }

    public boolean isPositionBlocked(BlockPos pos) {
        Long time = awaitingPositions.get(pos);
        if (time == null) return false;
        return System.currentTimeMillis() - time < 500;
    }

    public void confirmSpawn(BlockPos pos) {
        awaitingPositions.remove(pos);
    }

    public void onAttack(EndCrystalEntity crystal) {
        setDead(crystal.getId(), System.currentTimeMillis());
    }

    public void reset() {
        deadCrystals.clear();
        awaitingPositions.clear();
        deadTimestamps.clear();
    }
}
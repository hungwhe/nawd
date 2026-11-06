package com.hwell.hwelladdon.modules;

import com.hwell.hwelladdon.HwellAddon;
import meteordevelopment.meteorclient.events.world.ParticleEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.particle.ParticleTypes;

public class CrystalsOptimizer extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> noExplosionEmitter = sgGeneral.add(new BoolSetting.Builder()
        .name("no-explosion-emitter")
        .description("Tắt particle vụ nổ lớn (khói crystal).")
        .defaultValue(true).build());
    private final Setting<Boolean> noExplosion = sgGeneral.add(new BoolSetting.Builder()
        .name("no-explosion")
        .description("Tắt particle vụ nổ nhỏ.")
        .defaultValue(true).build());
    private final Setting<Boolean> noPoof = sgGeneral.add(new BoolSetting.Builder()
        .name("no-poof")
        .description("Tắt khói trắng.")
        .defaultValue(true).build());
    private final Setting<Boolean> noSmoke = sgGeneral.add(new BoolSetting.Builder()
        .name("no-smoke")
        .description("Tắt khói SMOKE.")
        .defaultValue(false).build());

    public CrystalsOptimizer() {
        super(HwellAddon.PVP_SUPER_LEGIT, "Hwell CrystalsOptimizer",
            "Tắt particle khi crystal nổ — không khói, không lag.");
    }

    @EventHandler
    private void onParticle(ParticleEvent event) {
        if (event.particle == null) return;
        if (noExplosionEmitter.get() && event.particle == ParticleTypes.EXPLOSION_EMITTER) { event.cancel(); return; }
        if (noExplosion.get() && event.particle == ParticleTypes.EXPLOSION) { event.cancel(); return; }
        if (noPoof.get() && event.particle == ParticleTypes.POOF) { event.cancel(); return; }
        if (noSmoke.get() && event.particle == ParticleTypes.SMOKE) { event.cancel(); }
    }
}

package com.hwell.hwelladdon;

import com.hwell.hwelladdon.hud.ArmorHud;
import com.hwell.hwelladdon.hud.InventoryHud;
import com.hwell.hwelladdon.modules.*;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.slf4j.Logger;

public class HwellAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();

    public static final Category AUTO_SHOP = new Category("HWELL AUTO SHOP",
        new ItemStack(Items.EMERALD));
    public static final Category FARM = new Category("HWELL FARM",
        new ItemStack(Items.DIAMOND_HOE));
    public static final Category PVP_SUPER_LEGIT = new Category("HWELL PVP Super Legit",
        new ItemStack(Items.NETHERITE_SWORD));
    public static final HudGroup HUD_GROUP = new HudGroup("HWELL");

    @Override
    public void onInitialize() {
        LOG.info("Initializing HwellAddon");

        // AUTO SHOP
        Modules.get().add(new HwellAutoBuyShop());
        Modules.get().add(new HwellGuiPlus());

        // FARM & ORDER
        Modules.get().add(new HwellDropOrder());
        Modules.get().add(new AutoEnchant());
        Modules.get().add(new HwellAutoSell());
        Modules.get().add(new HwellAutoFillOrder());
        Modules.get().add(new HwellAutoDropSpawner());
        Modules.get().add(new HwellAutoSellSpawner());
        Modules.get().add(new HwellSpawnerProtectEZ());

        // PVP SUPER LEGIT
        Modules.get().add(new EasyDTap());
        Modules.get().add(new AutoHitCrystal());
        Modules.get().add(new AnchorMarcoFast());
        Modules.get().add(new LegitHoverTotem());
        Modules.get().add(new AutoTotemPromax());
        Modules.get().add(new CrystalsOptimizer());
        Modules.get().add(new KeyPearl());

        // HUD
        Hud.get().register(InventoryHud.INFO);
        Hud.get().register(ArmorHud.INFO);
        LOG.info("Registered HUD elements");

        MeteorClient.EVENT_BUS.subscribe(new AutoEnableListener());
        LOG.info("AutoEnableListener registered!");
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(AUTO_SHOP);
        Modules.registerCategory(FARM);
        Modules.registerCategory(PVP_SUPER_LEGIT);
    }

    @Override
    public String getPackage() {
        return "com.hwell.hwelladdon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("hungwhe", "hwelladdon-1.21.11");
    }
}
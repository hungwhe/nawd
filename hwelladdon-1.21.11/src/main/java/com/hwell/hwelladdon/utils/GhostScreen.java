package com.hwell.hwelladdon.utils;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public class GhostScreen extends Screen {
    public GhostScreen() {
        super(Text.empty());
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public boolean shouldCloseOnEsc() { return false; }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {}

    @Override
    public void init() {}
}
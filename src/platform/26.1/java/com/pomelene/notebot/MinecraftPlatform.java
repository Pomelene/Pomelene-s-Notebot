// SPDX-License-Identifier: MIT
package com.pomelene.notebot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.Overlay;

/** Compile-time bridge for Minecraft 26.1 screen ownership. */
public final class MinecraftPlatform {
    private MinecraftPlatform() {}
    public static com.mojang.blaze3d.platform.InputConstants.Type keyboardType() { return com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM; }
    public static Screen screen(Minecraft client) { return client.screen; }
    public static void setScreen(Minecraft client, Screen screen) { client.setScreen(screen); }
    public static Overlay overlay(Minecraft client) { return client.getOverlay(); }
}

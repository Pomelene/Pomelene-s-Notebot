// SPDX-License-Identifier: MIT
package com.pomelene.notebot;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

public final class NotebotClient implements ClientModInitializer {
    private static NotebotController controller;
    public static NotebotController controller() { return controller; }
    @Override public void onInitializeClient() {
        Minecraft client = Minecraft.getInstance();
        controller = new NotebotController(client);
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> controller.shutdown());
        var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("pomelene_notebot", "controls"));
        KeyMapping menu = key("menu", InputConstants.KEY_N, category);
        KeyMapping play = key("play", InputConstants.KEY_F8, category);
        KeyMapping stop = key("stop", InputConstants.KEY_F9, category);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (stop.consumeClick()) controller.stop(true);
            while (menu.consumeClick()) {
                if (MinecraftPlatform.screen(mc) == null) MinecraftPlatform.setScreen(mc,new NotebotScreen(controller));
            }
            while (play.consumeClick()) {
                if (MinecraftPlatform.screen(mc) == null || controller.directPackets()) controller.togglePlayback();
            }
            controller.tick();
            controller.highlights().tick(mc,controller.report());
        });
    }
    private static KeyMapping key(String name, int code, KeyMapping.Category category) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.notebot." + name,
                MinecraftPlatform.keyboardType(), code, category));
    }
}

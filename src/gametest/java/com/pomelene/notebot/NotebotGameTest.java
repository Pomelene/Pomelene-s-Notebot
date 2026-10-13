// SPDX-License-Identifier: MIT
package com.pomelene.notebot;

import com.mojang.blaze3d.platform.InputConstants;
import com.pomelene.notebot.test.TestNoteEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NoteBlock;

public final class NotebotGameTest implements FabricClientGameTest {
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @Override public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            var server = world.getServer(); var connection = new TestConnection(context, world);
            connection.waitForChunksDownload();
            server.runCommand("fill -4 4 -4 4 4 4 minecraft:dirt");
            server.runCommand("fill -4 5 -4 4 8 4 minecraft:air");
            server.runCommand("tp @a 0.5 5 0.5");
            server.runCommand("gamemode survival @a");
            int[] pitches = {12, 14, 16, 17, 19};
            for (int i = 0; i < pitches.length; i++) server.runCommand("setblock " + (i - 2)
                    + " 5 -2 minecraft:note_block[instrument=harp,note=" + pitches[i] + "]");
            connection.waitForClientboundPackets();
            context.runOnClient(mc -> {
                require(mc.isWindowActive(), "Client test must have active window");
                NotebotClient.controller().load(NotebotClient.controller().directory().resolve("notebot-demo.nbs"));
            });
            context.waitFor(mc -> NotebotClient.controller().state() == NotebotController.State.READY, 200);
            context.runOnClient(mc -> {
                var bot = NotebotClient.controller(); bot.setScanRadius(6.0); bot.scan();
                require(bot.report().missing().isEmpty(), "Demo arrangement should match");
                bot.setScanRadius(1.0);
                require(!bot.report().missing().isEmpty(), "Reducing radius must remove out-of-radius candidates");
                bot.setScanRadius(16.0);
                require(bot.report().missing().isEmpty(), "Increasing radius must rescan and restore matches");
                bot.setScanRadius(Double.NaN);
                require(bot.scanRadius() == 6.0, "Invalid radius must recover to default");
                bot.setScanRadius(0.0); require(bot.scanRadius() == 1.0, "Radius must have lower bound");
                bot.setScanRadius(100.0); require(bot.scanRadius() == 64.0, "Radius must have upper bound");
                bot.setScanRadius(6.0);
                var restored = new NotebotController(mc);
                require(restored.scanRadius() == 6.0 && restored.foldOctaves() == bot.foldOctaves(),
                        "Saved radius must survive reload without changing octave preferences");
            });
            server.runCommand("setblock 7 5 0 minecraft:note_block[instrument=harp,note=12]");
            connection.waitForClientboundPackets();
            context.runOnClient(mc -> {
                var bot = NotebotClient.controller(); bot.setScanRadius(6.0); bot.scan();
                require(bot.report().usable().size() == 5 && bot.report().outOfRange() >= 1,
                        "Default radius must exclude the distant note");
                bot.setScanRadius(16.0);
                require(bot.report().usable().size() == 6, "Extended radius must include distant notes");
                bot.setScanRadius(6.0);
            });
            server.runCommand("setblock 7 5 0 minecraft:air");
            context.getInput().pressKey(KeyMapping.get("key.notebot.menu"));
            context.waitForScreen(NotebotScreen.class);
            context.waitTicks(3);
            context.runOnClient(mc -> clickRadius(MinecraftPlatform.screen(mc), "−"));
            require(context.computeOnClient(mc -> NotebotClient.controller().scanRadius()) == 5.5,
                    "GUI radius slider must reduce radius by half a block");
            context.waitTicks(1);
            context.runOnClient(mc -> clickRadius(MinecraftPlatform.screen(mc), "+"));
            require(context.computeOnClient(mc -> NotebotClient.controller().scanRadius()) == 6.0,
                    "GUI radius slider must increase radius by half a block");
            context.waitTicks(2); context.takeScreenshot("notebot-library");
            context.runOnClient(mc -> MinecraftPlatform.setScreen(mc,null));
            TestNoteEvents.received.set(0);
            context.getInput().pressKey(KeyMapping.get("key.notebot.play"));
            context.waitFor(mc -> NotebotClient.controller().state() == NotebotController.State.PLAYING, 30);
            context.waitFor(mc -> NotebotClient.controller().state() == NotebotController.State.READY, 150);
            connection.waitForServerboundPackets(); connection.waitForClientboundPackets();
            require(TestNoteEvents.received.get() >= 8, "Server should broadcast every demo note");
            server.runOnServer(s -> {
                for (int i = 0; i < pitches.length; i++) {
                    var state = connection.getServerLevel().getBlockState(new BlockPos(i - 2, 5, -2));
                    require(state.is(Blocks.NOTE_BLOCK), "Playing must not mine note blocks");
                    require(state.getValue(NoteBlock.NOTE) == pitches[i], "Playing must not retune blocks");
                }
            });
            // A wrong pitch is diagnosed and blocks playback.
            server.runCommand("setblock 0 5 -2 minecraft:note_block[instrument=harp,note=0]");
            connection.waitForClientboundPackets();
            context.runOnClient(mc -> {
                var bot = NotebotClient.controller(); bot.scan();
                require(bot.report().missing().size() == 1, "Wrong pitch must be diagnosed");
                bot.togglePlayback(); require(bot.state() != NotebotController.State.PLAYING, "Must refuse missing note");
                bot.tune(); require(bot.state() == NotebotController.State.TUNING, "Explicit tuning should start");
            });
            context.waitFor(mc -> NotebotClient.controller().state() == NotebotController.State.READY, 500);
            server.runOnServer(s -> require(connection.getServerLevel().getBlockState(new BlockPos(0, 5, -2))
                    .getValue(NoteBlock.NOTE) == 16, "Server must confirm target pitch after tuning"));
            context.runOnClient(mc -> {
                var bot = NotebotClient.controller(); bot.scan();
                require(bot.report().missing().isEmpty(), "Tuned arrangement should match"); bot.togglePlayback();
            });
            context.waitTicks(3);
            // Upper obstruction pauses a live song.
            server.runCommand("setblock 0 6 -2 minecraft:stone");
            connection.waitForClientboundPackets();
            context.waitFor(mc -> NotebotClient.controller().state() == NotebotController.State.PAUSED, 30);
            context.runOnClient(mc -> {
                var bot = NotebotClient.controller();
                require(bot.report().blocked() == 1, "Covered note block must be excluded");
            });
            server.runCommand("setblock 0 6 -2 minecraft:air");
            connection.waitForClientboundPackets();
            context.getInput().pressKey(KeyMapping.get("key.notebot.play"));
            context.waitFor(mc -> NotebotClient.controller().state() == NotebotController.State.PLAYING, 30);
            context.getInput().pressKey(KeyMapping.get("key.notebot.play"));
            context.waitFor(mc -> NotebotClient.controller().state() == NotebotController.State.PAUSED, 30);
            double pausedAt = context.computeOnClient(mc -> NotebotClient.controller().seconds());
            context.waitTicks(10);
            require(pausedAt == context.computeOnClient(mc -> NotebotClient.controller().seconds()), "Pause must freeze song time");
            context.getInput().pressKey(KeyMapping.get("key.notebot.play"));
            context.waitFor(mc -> NotebotClient.controller().state() == NotebotController.State.PLAYING, 30);
            context.getInput().pressKey(KeyMapping.get("key.notebot.stop"));
            context.waitFor(mc -> NotebotClient.controller().state() == NotebotController.State.READY, 30);
            require(context.computeOnClient(mc -> NotebotClient.controller().seconds()) == 0.0, "Stop must reset song time");
            // Visibility is deliberately not a requirement: only the top block and reach matter.
            server.runCommand("setblock 0 5 -1 minecraft:stone");
            connection.waitForClientboundPackets();
            context.runOnClient(mc -> {
                var bot = NotebotClient.controller(); bot.scan();
                require(bot.report().missing().isEmpty(), "Wall between player and block must not exclude notes");
            });
            server.runCommand("setblock 0 5 -1 minecraft:air");
            server.runCommand("give @a minecraft:diamond_axe");
            connection.waitForClientboundPackets();
            context.runOnClient(mc -> {
                var bot = NotebotClient.controller(); bot.togglePlayback();
                require(bot.state() != NotebotController.State.PLAYING, "Holding a tool must be refused");
            });
            server.runCommand("clear @a");
            server.runCommand("setblock -2 4 -2 minecraft:stone");
            connection.waitForClientboundPackets();
            context.runOnClient(mc -> {
                var bot = NotebotClient.controller(); bot.scan();
                require(bot.report().missing().size() == 1, "Wrong instrument must not match harp pitch");
                MinecraftPlatform.setScreen(mc,new ReportScreen(new NotebotScreen(bot), bot));
            });
            context.waitTicks(3); context.takeScreenshot("notebot-missing-instrument");
            server.runCommand("setblock -2 4 -2 minecraft:dirt");
            context.runOnClient(mc -> MinecraftPlatform.setScreen(mc,null));
            server.runCommand("tp @a 20.5 5 0.5");
            connection.waitForClientboundPackets();
            context.runOnClient(mc -> {
                var bot = NotebotClient.controller(); bot.scan();
                require(bot.report().matches().isEmpty(), "Distant blocks must not match");
            });
            server.runCommand("tp @a 0.5 5 0.5");
            server.runCommand("gamemode creative @a");
            connection.waitForClientboundPackets();
            context.runOnClient(mc -> {
                NotebotClient.controller().togglePlayback();
                require(NotebotClient.controller().state() != NotebotController.State.PLAYING, "Creative must be refused");
                MinecraftPlatform.setScreen(mc,new NotebotScreen(NotebotClient.controller()));
            });
            context.waitTicks(3); context.takeScreenshot("notebot-report");
            var languageReload = context.computeOnClient(mc -> {
                mc.options.languageCode = "zh_cn";
                mc.getLanguageManager().setSelected("zh_cn");
                return mc.reloadResourcePacks();
            });
            context.waitFor(mc -> languageReload.isDone(), 300);
            languageReload.join();
            context.waitFor(mc -> MinecraftPlatform.overlay(mc) == null, 100);
            context.waitTicks(5); context.takeScreenshot("notebot-chinese");
            context.runOnClient(mc -> { MinecraftPlatform.setScreen(mc,null); NotebotClient.controller().stop(false); });
        }
        testExtendedRange(context);
        if (System.getProperty("notebot.test.library") != null) testLegacyLibrary(context);
        if (System.getProperty("notebot.test.stage") != null) testMusicStage(context);
    }

    private void testExtendedRange(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            var server = world.getServer(); var connection = new TestConnection(context, world);
            connection.waitForChunksDownload();
            server.runCommand("fill -4 4 -4 4 4 4 minecraft:dirt");
            server.runCommand("fill 60 4 -1 65 4 1 minecraft:dirt");
            server.runCommand("tp @a 0.5 5 0.5");
            server.runCommand("gamemode survival @a");
            server.runCommand("attribute @p minecraft:block_interaction_range base set 64");
            int[] pitches = {12, 14, 16, 17, 19};
            for (int i = 0; i < pitches.length; i++) server.runCommand("setblock " + (60 + i)
                    + " 5 0 minecraft:note_block[note=" + pitches[i] + "]");
            server.runCommand("setblock 65 5 0 minecraft:note_block[note=12]");
            connection.waitForClientboundPackets(); context.waitTicks(5);
            context.runOnClient(mc -> {
                // Simulate a server that permits distant packets while the client retains vanilla reach.
                mc.player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_INTERACTION_RANGE)
                        .setBaseValue(4.5);
                NotebotClient.controller().load(NotebotClient.controller().directory().resolve("notebot-demo.nbs"));
            });
            context.waitFor(mc -> NotebotClient.controller().state() == NotebotController.State.READY, 200);
            context.runOnClient(mc -> {
                var bot = NotebotClient.controller(); bot.setScanRadius(64.0);
                require(bot.report().usable().size() == 5 && bot.report().missing().isEmpty(),
                        "64-block scan must include x=64 and exclude x=65");
                require(bot.report().outOfRange() >= 1, "Outside radius must be reported");
                require(new NotebotController(mc).scanRadius() == 64.0, "64-block setting must persist");
                MinecraftPlatform.setScreen(mc,new NotebotScreen(bot));
                bot.setScanRadius(63.5);
            });
            context.waitTicks(2);
            context.runOnClient(mc -> clickRadius(MinecraftPlatform.screen(mc), "+"));
            require(context.computeOnClient(mc -> NotebotClient.controller().scanRadius()) == 64.0,
                    "GUI must reach 64 blocks");
            context.waitTicks(2); context.takeScreenshot("notebot-radius64");
            context.runOnClient(mc -> MinecraftPlatform.setScreen(mc,null));
            server.runCommand("setblock 62 5 0 minecraft:note_block[note=0]");
            connection.waitForClientboundPackets();
            context.runOnClient(mc -> { var bot = NotebotClient.controller(); bot.scan(); bot.tune(); });
            context.waitFor(mc -> NotebotClient.controller().state() == NotebotController.State.READY, 500);
            server.runOnServer(s -> require(connection.getServerLevel().getBlockState(new BlockPos(62, 5, 0))
                    .getValue(NoteBlock.NOTE) == 16, "Distant tuning must receive server confirmation"));
            TestNoteEvents.received.set(0);
            context.runOnClient(mc -> { var bot = NotebotClient.controller(); bot.scan(); bot.togglePlayback(); });
            context.waitFor(mc -> NotebotClient.controller().state() == NotebotController.State.READY, 150);
            connection.waitForServerboundPackets(); connection.waitForClientboundPackets();
            require(TestNoteEvents.received.get() >= 8, "Server must play all distant demo notes");
            server.runOnServer(s -> {
                for (int x = 60; x <= 65; x++) require(connection.getServerLevel().getBlockState(new BlockPos(x, 5, 0))
                        .is(Blocks.NOTE_BLOCK), "Distant playback must retain note blocks");
            });
            context.runOnClient(mc -> {
                var bot = NotebotClient.controller(); bot.setScanRadius(6.0);
                require(bot.report().usable().isEmpty(), "Default radius must exclude distant arrangement");
            });
            System.out.println("RADIUS64_OK: boundary, persistence, GUI, server tuning and playback");
        }
    }

    private void testLegacyLibrary(ClientGameTestContext context) {
        var directory = java.nio.file.Path.of(System.getProperty("notebot.test.library"));
        String[] names = {"Fallen Down.nbs", "A Team - Theme.nbs", "Pokemon Battle Theme.nbs", "Feel Good.nbs"};
        int[] counts = {923, 1318, 4702, 841};
        for (int i = 0; i < names.length; i++) {
            final String name = names[i]; final int expected = counts[i];
            context.runOnClient(mc -> NotebotClient.controller().load(directory.resolve(name)));
            context.waitFor(mc -> NotebotClient.controller().state() == NotebotController.State.READY, 200);
            context.runOnClient(mc -> {
                var song = NotebotClient.controller().song();
                require(song.notes().size() == expected, "Legacy loading must retain notes: " + name);
                require(!song.name().isBlank() && song.tempo() > 0, "Legacy metadata must load");
                System.out.println("LEGACY_LOADED: " + name + " notes=" + song.notes().size());
            });
        }
        context.runOnClient(mc -> NotebotClient.controller().load(directory.resolve("test_greatfairyfountain.nbs")));
        context.waitFor(mc -> NotebotClient.controller().state() == NotebotController.State.IDLE, 200);
        context.runOnClient(mc -> require(NotebotClient.controller().status().getString().contains("schematic"),
                "Misnamed schematic must show the correct file-type diagnosis"));
    }

    private void testMusicStage(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            var server = world.getServer(); var connection = new TestConnection(context, world);
            connection.waitForChunksDownload();
            com.google.gson.JsonObject fixture;
            try (var reader = java.nio.file.Files.newBufferedReader(java.nio.file.Path.of(System.getProperty("notebot.test.stage")))) {
                fixture = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
            } catch (java.io.IOException e) { throw new RuntimeException(e); }
            server.runCommand("fill 29 19 29 41 32 41 minecraft:air");
            // The supplied schematic has sand at its bottom; support it as a built stage would be.
            server.runCommand("fill 30 19 30 40 19 40 minecraft:dirt");
            server.runOnServer(s -> {
                for (var element : fixture.getAsJsonArray("blocks")) {
                    var block = element.getAsJsonObject(); var pos = block.getAsJsonArray("pos");
                    var state = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(
                            net.minecraft.resources.Identifier.parse(block.get("Name").getAsString())).defaultBlockState();
                    if (block.has("Properties")) for (var entry : block.getAsJsonObject("Properties").entrySet()) {
                        var property = state.getBlock().getStateDefinition().getProperty(entry.getKey());
                        require(property != null, "Unknown fixture property");
                        state = withProperty(state, property, entry.getValue().getAsString());
                    }
                    connection.getServerLevel().setBlock(new BlockPos(30 + pos.get(0).getAsInt(),
                            20 + pos.get(1).getAsInt(), 30 + pos.get(2).getAsInt()), state, 2);
                }
            });
            server.runCommand("tp @a 35.5 25.5 35.5");
            server.runCommand("gamemode survival @a");
            connection.waitForClientboundPackets();
            context.waitTicks(5); // Allow teleport/attribute updates to settle before the boundary assertion.
            context.runOnClient(mc -> {
                var bot = NotebotClient.controller(); bot.setScanRadius(6.0); bot.scan();
                require(bot.report().usable().size() == 388, "All 388 stage notes must be in server range from the central slab");
                long strict = bot.report().usable().stream().filter(c -> mc.player.isWithinBlockInteractionRange(c.pos(), 0)).count();
                require(strict < 388, "Regression fixture must exercise the additional server allowance");
                System.out.println("STAGE_SCAN: old=" + strict + ", fixed=" + bot.report().usable().size());
                bot.setScanRadius(4.5);
                require(bot.report().usable().size() < 388, "Smaller user radius must narrow actual stage scan");
                bot.setScanRadius(6.0);
                require(bot.report().usable().size() == 388, "Restoring radius must include the whole stage");
                bot.load(java.nio.file.Path.of(System.getProperty("notebot.test.song", bot.directory().resolve("notebot-demo.nbs").toString())));
            });
            context.waitFor(mc -> NotebotClient.controller().state() == NotebotController.State.READY, 200);
            context.runOnClient(mc -> {
                var bot = NotebotClient.controller();
                require(bot.plan().playable(), "User song should use supported vanilla notes");
                bot.tune();
            });
            context.waitFor(mc -> NotebotClient.controller().state() == NotebotController.State.READY, 10000);
            context.runOnClient(mc -> {
                var bot = NotebotClient.controller(); bot.scan();
                require(bot.report().missing().isEmpty(), "Actual user stage must accommodate the whole user song after tuning");
                System.out.println("STAGE_TUNED: matched=" + bot.report().matches().size() + "/" + bot.plan().required().size());
            });
            connection.waitForClientboundPackets(); TestNoteEvents.received.set(0);
            context.runOnClient(mc -> NotebotClient.controller().togglePlayback());
            context.waitFor(mc -> TestNoteEvents.received.get() >= 5, 300);
            context.runOnClient(mc -> {
                require(NotebotClient.controller().state() == NotebotController.State.PLAYING, "Stage playback should stay active");
                NotebotClient.controller().stop(false);
            });
            server.runOnServer(s -> {
                int noteCount = 0;
                for (BlockPos pos : BlockPos.betweenClosed(30,20,30,40,31,40))
                    if (connection.getServerLevel().getBlockState(pos).is(Blocks.NOTE_BLOCK)) noteCount++;
                require(noteCount == 388, "Music stage must retain all 388 note blocks after tuning and playback");
            });
            context.runOnClient(mc -> MinecraftPlatform.setScreen(mc,new NotebotScreen(NotebotClient.controller())));
            context.waitTicks(5); context.takeScreenshot("notebot-actual-music-stage");
            context.runOnClient(mc -> MinecraftPlatform.setScreen(mc,null));
        }
    }

    private static <T extends Comparable<T>> net.minecraft.world.level.block.state.BlockState withProperty(
            net.minecraft.world.level.block.state.BlockState state,
            net.minecraft.world.level.block.state.properties.Property<T> property, String value) {
        return state.setValue(property, property.getValue(value).orElseThrow());
    }

    private static void clickRadius(net.minecraft.client.gui.screens.Screen screen, String label) {
        ((NotebotScreen)screen).selectTab(NotebotScreen.Tab.SETTINGS);
        var slider = screen.children().stream()
                .filter(element -> element instanceof net.minecraft.client.gui.components.AbstractSliderButton)
                .map(element -> (net.minecraft.client.gui.components.AbstractSliderButton) element).findFirst().orElseThrow();
        require(slider.active && slider.visible, "Radius slider must be usable");
        double target = NotebotClient.controller().scanRadius() + (label.equals("+") ? 0.5 : -0.5);
        double fraction = (target - 1) / 63;
        var event = new net.minecraft.client.input.MouseButtonEvent(slider.getX() + 4 + fraction * (slider.getWidth() - 8),
                slider.getY() + slider.getHeight() / 2.0, new net.minecraft.client.input.MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
        require(screen.mouseClicked(event, false), "Radius widget must handle mouse clicks");
        screen.mouseReleased(event);
    }
}

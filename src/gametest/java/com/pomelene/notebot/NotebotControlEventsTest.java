// SPDX-License-Identifier: MIT
package com.pomelene.notebot;

import com.pomelene.notebot.test.TestNoteEvents;
import java.nio.file.Files;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Confirms that control markers schedule real note-block packets without sounding themselves. */
public final class NotebotControlEventsTest implements FabricClientGameTest {
    private static void require(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
    @Override public void runTest(ClientGameTestContext context) {
        try(var world=context.worldBuilder().create()) {
            var server=world.getServer(); var connection=world.getConnection();
            connection.waitForChunksDownload();
            server.runCommand("fill -3 4 -3 3 4 3 minecraft:dirt");
            server.runCommand("fill -3 5 -3 3 8 3 minecraft:air");
            server.runCommand("tp @a 0.5 5 0.5"); server.runCommand("gamemode survival @a");
            server.runCommand("setblock 0 5 -2 minecraft:note_block[instrument=harp,note=12]");
            server.runCommand("setblock 1 5 -2 minecraft:note_block[instrument=harp,note=14]");
            connection.waitForClientboundPackets();
            var fixture=context.computeOnClient(mc -> NotebotClient.controller().directory().resolve("control-events-test.nbs"));
            try(var input=getClass().getResourceAsStream("/notebot-control-events.nbs")) {
                if(input==null)throw new IllegalStateException("Missing synthetic control fixture");
                Files.copy(input,fixture,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                context.runOnClient(mc -> {
                    var bot=NotebotClient.controller(); bot.stop(false);
                    if(!bot.originalSpeed())bot.toggleSpeedMode();
                    bot.setScanRadius(6); bot.load(fixture);
                });
                context.waitFor(mc -> NotebotClient.controller().state()==NotebotController.State.READY,200);
                context.runOnClient(mc -> {
                    var bot=NotebotClient.controller(); bot.scan();
                    require(bot.song().events().size()==6,"All controls must be classified");
                    require(bot.plan().required().size()==2 && bot.plan().playable(),"Only musical pitches may require blocks");
                    require(bot.durationSeconds()==2.2,"Tempo map must affect displayed duration");
                    require(bot.plan().frames().keySet().equals(java.util.Set.of(0,8,12,28)),"Expected nonuniform musical onsets");
                    if(!bot.directPackets())bot.toggleDirectPackets();
                    if(bot.repeat())bot.toggleRepeat();
                    TestNoteEvents.received.set(0); bot.togglePlayback();
                    require(bot.state()==NotebotController.State.PLAYING,"Control score must play without conversion dialog");
                });
                context.waitFor(mc -> NotebotClient.controller().state()==NotebotController.State.READY,100);
                connection.waitForClientboundPackets();
                require(TestNoteEvents.received.get()==4,"Only four real notes should reach the client; no event may sound");
                context.runOnClient(mc -> {
                    var bot=NotebotClient.controller(); require(bot.seconds()==2.2,"Playback must finish at the integrated time");
                    bot.seek(0.5); require(bot.seconds()==1.1,"Seeking uses the integrated song duration");
                    bot.setCustomTempo(20); bot.toggleSpeedMode();
                });
                context.waitFor(mc -> NotebotClient.controller().state()!=NotebotController.State.LOADING,200);
                context.runOnClient(mc -> {
                    var bot=NotebotClient.controller();
                    require(bot.durationSeconds()==1.1,"User tempo must scale all automatic tempo changes");
                    require(Math.abs(bot.seconds()-0.55)<0.051,"Changing speed must preserve musical position");
                    bot.toggleSpeedMode();
                });
                context.waitFor(mc -> NotebotClient.controller().state()!=NotebotController.State.LOADING,200);
                context.runOnClient(mc -> NotebotClient.controller().stop(false));
            } finally { Files.deleteIfExists(fixture); }
            System.out.println("CONTROL_EVENTS_OK: real playback, tempo map, ignored markers, duration, seek and custom speed");
        } catch(java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
    }
}

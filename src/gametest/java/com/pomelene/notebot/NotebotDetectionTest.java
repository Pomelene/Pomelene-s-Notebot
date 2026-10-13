package com.pomelene.notebot;

import com.pomelene.notebot.song.NoteKey;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import java.util.*;

/** Real client/server regression on a disposable 20-instrument stage. */
public final class NotebotDetectionTest implements FabricClientGameTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static Button find(net.minecraft.client.gui.screens.Screen screen,String key) {
        String label=NotebotController.text(key).getString();
        return screen.children().stream().filter(c->c instanceof Button b&&b.visible&&b.getMessage().getString().equals(label))
                .map(c->(Button)c).findFirst().orElseThrow();
    }
    private static void click(net.minecraft.client.gui.screens.Screen screen,Button button) {
        var e=new net.minecraft.client.input.MouseButtonEvent(button.getX()+button.getWidth()/2.0,button.getY()+10,
                new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT,0));
        screen.mouseClicked(e,false);screen.mouseReleased(e);
    }
    @Override public void runTest(ClientGameTestContext context) {
        try(var world=context.worldBuilder().create()) {
            var server=world.getServer();var connection=new TestConnection(context, world);connection.waitForChunksDownload();
            server.runCommand("fill -14 4 -8 14 8 8 minecraft:air");
            server.runCommand("fill -14 3 -8 14 3 8 minecraft:stone");
            server.runCommand("fill -14 4 -8 14 4 8 minecraft:dirt");
            server.runCommand("tp @a 0.5 5 0.5");server.runCommand("gamemode survival @a");
            String[] materials={"dirt","oak_planks","stone","sand","glass","white_wool","clay","gold_block","packed_ice","bone_block","iron_block","soul_sand","pumpkin","emerald_block","hay_block","glowstone","copper_block","exposed_copper","weathered_copper","oxidized_copper"};
            for(int i=0;i<20;i++){
                int x=i%5-2,z=i/5+2;
                server.runCommand("setblock "+x+" 4 "+z+" minecraft:"+materials[i]);
                server.runCommand("setblock "+x+" 5 "+z+" minecraft:note_block[note="+i+"]");
            }
            server.runCommand("setblock -2 5 -2 minecraft:note_block[note=0]");
            server.runCommand("setblock -1 5 -2 minecraft:note_block[note=0]");
            server.runCommand("setblock -3 5 0 minecraft:note_block[note=12]");
            server.runCommand("setblock -3 6 0 minecraft:stone");
            server.runCommand("setblock 11 5 0 minecraft:note_block[note=12]");
            server.runCommand("setblock 3 5 -2 minecraft:note_block[instrument=zombie,note=0]");
            connection.waitForClientboundPackets();context.waitTicks(10);
            context.runOnClient(mc->{
                var report=BlockScanner.scan(mc,Set.of(new NoteKey(0,0),new NoteKey(0,12)),10);
                check(report.inWorld(),"Report must know it has a world");
                for(int i=0;i<20;i++)check(report.instrument(i).boxes()>0,"Missing instrument "+i);
                var harp=report.instrument(0);
                check(harp.boxes()==3&&harp.pitches().size()==1,"Duplicates count as boxes, not pitch coverage");
                check(harp.missing().equals(Set.of(12)),"Covered and distant pitches must not satisfy song requirements");
                check(harp.missingScale().size()==24,"Full scale requires all 25 distinct pitches");
                check(report.blocked()==1&&report.outOfRange()==1&&report.unsupported()==1,"Exclusion reasons must be exact");
                check(report.excluded().stream().anyMatch(e->e.pos().equals(new BlockPos(-3,5,0))&&e.reason()==BlockScanner.Reason.BLOCKED&&!e.detail().isBlank()),"Covered box needs position and covering block");
                var h=new ScanHighlights();h.toggle(mc);
                List<BlockScanner.Candidate> many=new ArrayList<>();
                for(int i=0;i<400;i++)many.add(new BlockScanner.Candidate(new BlockPos(i,5,0),new NoteKey(0,0)));
                var large=new BlockScanner.Report(many,Map.of(),Set.of(),1,0,0,
                        List.of(new BlockScanner.Excluded(new BlockPos(-3,5,0),0,12,BlockScanner.Reason.BLOCKED,"stone")),0,true);
                check(h.marks(large).size()==ScanHighlights.LIMIT,"Highlight count must be bounded on large stages");
                check(h.marks(large).getFirst().color()==0xFFFF5555,"Excluded boxes must remain visible when highlight cap is reached");
                h.select(0,ScanHighlights.Filter.BLOCKED);
                check(h.marks(report).size()==1&&h.marks(report).getFirst().pos().equals(new BlockPos(-3,5,0)),"Filter highlights wrong boxes");
                h.select(-3,ScanHighlights.Filter.ALL);check(h.marks(report).size()==1,"Unsupported instrument category needs highlighting");
                h.select(-1,ScanHighlights.Filter.ALL);h.locate(mc,new BlockPos(11,5,0));
                check(h.marks(report).size()==1&&h.marks(report).getFirst().color()==0xFFFFAA33,"Locate must highlight only selected far box");
                h.tick(mc,report);h.toggle(mc);check(h.marks(report).isEmpty(),"Turning highlights off must clear marks");
                NotebotClient.controller().setScanRadius(10);NotebotClient.controller().scan();
                mc.options.guiScale().set(2);mc.getWindow().setWindowed(854,480);
            });
            var reload=context.computeOnClient(mc->{mc.options.languageCode="zh_cn";mc.getLanguageManager().setSelected("zh_cn");return mc.reloadResourcePacks();});
            context.waitFor(mc->reload.isDone()&&MinecraftPlatform.overlay(mc)==null,300);
            context.runOnClient(mc->MinecraftPlatform.setScreen(mc,new ReportScreen(new NotebotScreen(NotebotClient.controller()),NotebotClient.controller())));
            context.waitTicks(5);context.runOnClient(mc->NotebotUiSmokeTest.check(MinecraftPlatform.screen(mc)));context.takeScreenshot("detection-20-instruments-overview");
            context.runOnClient(mc->click(MinecraftPlatform.screen(mc),find(MinecraftPlatform.screen(mc),"positions_all")));
            context.waitTicks(3);context.runOnClient(mc->NotebotUiSmokeTest.check(MinecraftPlatform.screen(mc)));context.takeScreenshot("detection-all-positions");
            context.runOnClient(mc->click(MinecraftPlatform.screen(mc),find(MinecraftPlatform.screen(mc),"filter.all")));
            context.runOnClient(mc->click(MinecraftPlatform.screen(mc),find(MinecraftPlatform.screen(mc),"filter.usable")));
            context.waitTicks(3);context.takeScreenshot("detection-covered-filter");
            context.runOnClient(mc->{
                var s=MinecraftPlatform.screen(mc);var b=s.children().stream().filter(c->c instanceof Button w&&w.visible&&w.getY()==134).map(c->(Button)c).findFirst().orElseThrow();
                click(s,b);check(MinecraftPlatform.screen(mc)==null,"Locate must return to world");check(NotebotClient.controller().highlights().enabled(),"Locate must enable highlights");
                mc.player.setYRot(90);mc.player.setXRot(15);
            });
            context.waitTicks(8);context.takeScreenshot("detection-world-highlight");
            context.runOnClient(mc->{mc.getWindow().setWindowed(1200,800);MinecraftPlatform.setScreen(mc,new ReportScreen(new NotebotScreen(NotebotClient.controller()),NotebotClient.controller()));});
            context.waitTicks(5);context.runOnClient(mc->NotebotUiSmokeTest.check(MinecraftPlatform.screen(mc)));context.takeScreenshot("detection-large-overview");
            context.runOnClient(mc->{
                var s=MinecraftPlatform.screen(mc);var b=s.children().stream().filter(c->c instanceof Button w&&w.visible&&w.getY()==86).map(c->(Button)c).findFirst().orElseThrow();click(s,b);
            });
            context.waitTicks(3);context.runOnClient(mc->NotebotUiSmokeTest.check(MinecraftPlatform.screen(mc)));context.takeScreenshot("detection-harp-pitches");
            context.runOnClient(mc->MinecraftPlatform.setScreen(mc,null));
        }
        context.waitTicks(5);
        context.runOnClient(mc->check(!NotebotClient.controller().highlights().enabled(),"World disconnect must disable highlights"));
        System.out.println("DETECTION_OK: 20 instruments, duplicates, pitches, exclusions, filters, locate, world reset, small/large UI");
    }
}

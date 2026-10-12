package com.pomelene.notebot;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import java.util.*;

/** UI-only regression: no connection to user servers and no edits to user worlds. */
public final class NotebotUiSmokeTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        context.runOnClient(mc -> {mc.options.guiScale().set(2);mc.getWindow().setWindowed(854,480);});
        context.waitTicks(8);
        for(var tab:NotebotScreen.Tab.values()) {
            context.runOnClient(mc -> {var screen=new NotebotScreen(NotebotClient.controller());mc.gui.setScreen(screen);screen.selectTab(tab);});
            context.waitTicks(8);context.runOnClient(mc -> check(mc.gui.screen()));
            context.takeScreenshot("rebuild-small-"+tab.name().toLowerCase(Locale.ROOT));
        }
        context.runOnClient(mc -> {var screen=new NotebotScreen(NotebotClient.controller());mc.gui.setScreen(screen);
            var input=(EditBox)screen.children().stream().filter(c -> c instanceof EditBox).findFirst().orElseThrow();input.setValue("senbonzakura");});
        context.waitTicks(15);
        context.runOnClient(mc -> mc.getWindow().setWindowed(1200,800));context.waitTicks(10);
        context.runOnClient(mc -> check(mc.gui.screen()));context.takeScreenshot("rebuild-large-search");
        context.runOnClient(mc -> mc.gui.setScreen(new FolderScreen(mc.gui.screen(),NotebotClient.controller())));
        context.waitTicks(3);context.runOnClient(mc -> {check(mc.gui.screen());mc.gui.screen().onClose();});
        context.waitTicks(3);context.runOnClient(mc -> check(mc.gui.screen()));
        context.runOnClient(mc -> NotebotClient.controller().load(NotebotClient.controller().directory().resolve("notebot-demo.nbs")));
        context.waitFor(mc -> NotebotClient.controller().song()!=null,200);
        context.runOnClient(mc -> {
            var parent=new NotebotScreen(NotebotClient.controller());
            var compatibility=new CompatibilityScreen(parent,NotebotClient.controller());
            mc.gui.setScreen(compatibility);mc.gui.setScreen(new PreferenceScreen(compatibility));
        });
        context.waitTicks(3);context.runOnClient(mc -> check(mc.gui.screen()));context.takeScreenshot("rebuild-preferences");
        context.runOnClient(mc -> {
            mc.gui.setScreen(new NotebotScreen(NotebotClient.controller()));
            var button=mc.gui.screen().children().stream().filter(c -> c instanceof net.minecraft.client.gui.components.Button b
                    && b.getMessage().getString().equals(NotebotController.text("open_folder").getString()))
                    .map(c -> (net.minecraft.client.gui.components.Button)c).findFirst().orElseThrow();
            var event=new net.minecraft.client.input.MouseButtonEvent(button.getX()+button.getWidth()/2.0,
                    button.getY()+button.getHeight()/2.0,new net.minecraft.client.input.MouseButtonInfo(
                    com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT,0));
            mc.gui.screen().mouseClicked(event,false);mc.gui.screen().mouseReleased(event);
        });
        context.waitFor(mc -> NotebotClient.controller().folderMessage().getString().equals(NotebotController.text("folder_requested").getString()),160);
        context.waitTicks(10);context.runOnClient(mc -> check(mc.gui.screen()));
        System.out.println("FOLDER_OPEN_UI_OK: click returned, file manager requested, client ticks continued");
        var reload=context.computeOnClient(mc -> {mc.options.languageCode="zh_cn";mc.getLanguageManager().setSelected("zh_cn");return mc.reloadResourcePacks();});
        context.waitFor(mc -> reload.isDone()&&mc.gui.overlay()==null,300);
        context.runOnClient(mc -> {mc.getWindow().setWindowed(854,480);mc.gui.setScreen(new NotebotScreen(NotebotClient.controller()));});
        context.waitTicks(8);context.runOnClient(mc -> check(mc.gui.screen()));context.takeScreenshot("rebuild-chinese-library");
        context.runOnClient(mc -> {((NotebotScreen)mc.gui.screen()).selectTab(NotebotScreen.Tab.PLAYBACK);});
        context.waitTicks(3);context.takeScreenshot("rebuild-chinese-playback");
        context.runOnClient(mc -> {((NotebotScreen)mc.gui.screen()).selectTab(NotebotScreen.Tab.SETTINGS);check(mc.gui.screen());});
        context.waitTicks(3);context.takeScreenshot("columns-chinese-settings");
        if(System.getProperty("notebot.test.song")!=null) {
            context.runOnClient(mc -> NotebotClient.controller().load(java.nio.file.Path.of(System.getProperty("notebot.test.song"))));
            context.waitForScreen(CompatibilityScreen.class);
            context.runOnClient(mc -> {
                var song=NotebotClient.controller().song();var plan=NotebotClient.controller().plan();
                if(song.version()!=6||song.notes().size()!=8976||!plan.problems().contains("custom:20")||plan.problems().contains("custom:18"))
                    throw new AssertionError("V6 actual song must load and preserve native copper classification");
            });
            context.waitTicks(3);context.takeScreenshot("nbs6-petal-dance-compatibility");
            System.out.println("NBS6_CLIENT_LOADED: Petal Dance, 8976 audible notes, native copper and custom Firework");
        }
        context.runOnClient(mc -> mc.gui.setScreen(null));
        System.out.println("REBUILD_UI_OK: tabs, resize, search, return, preferences, Chinese");
    }
    static void check(net.minecraft.client.gui.screens.Screen screen) {
        var widgets=screen.children().stream().filter(e -> e instanceof AbstractWidget)
                .map(e -> (AbstractWidget)e).filter(w -> w.visible).toList();
        for(var w:widgets)if(w.getX()<0||w.getY()<0||w.getX()+w.getWidth()>screen.width||w.getY()+w.getHeight()>screen.height)
            throw new AssertionError("Widget outside screen: "+w.getMessage().getString());
        for(int i=0;i<widgets.size();i++)for(int j=i+1;j<widgets.size();j++) {
            var a=widgets.get(i);var b=widgets.get(j);
            if(a.getX()<b.getX()+b.getWidth()&&b.getX()<a.getX()+a.getWidth()
                    &&a.getY()<b.getY()+b.getHeight()&&b.getY()<a.getY()+a.getHeight())
                throw new AssertionError("Overlapping widgets: "+a.getMessage().getString()+" / "+b.getMessage().getString());
        }
    }
}

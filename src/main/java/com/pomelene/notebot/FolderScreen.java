package com.pomelene.notebot;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

public final class FolderScreen extends Screen {
    private final Screen parent;
    private final NotebotController controller;
    private net.minecraft.network.chat.Component error=net.minecraft.network.chat.Component.empty();
    public FolderScreen(Screen parent, NotebotController controller) {
        super(NotebotController.text("new_folder")); this.parent=parent; this.controller=controller;
    }
    @Override protected void init() {
        int x=width/2-120, y=height/2-20;
        EditBox name=addRenderableWidget(new EditBox(font,x,y,240,20,title)); name.setMaxLength(100);
        Button create=addRenderableWidget(Button.builder(NotebotController.text("create_folder"),b -> {
            if(controller.createFolder(name.getValue().strip()))minecraft.gui.setScreen(parent);
            else error=controller.status();
        }).bounds(x,y+28,118,20).build()); create.active=false;
        name.setResponder(value -> create.active=!value.isBlank());
        addRenderableWidget(Button.builder(NotebotController.text("back"),b -> onClose()).bounds(x+122,y+28,118,20).build());
        setInitialFocus(name);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta) {
        super.extractRenderState(g,mx,my,delta); g.centeredText(font,title,width/2,height/2-45,0xFFFFFFFF);
        g.textWithWordWrap(font,error,width/2-120,height/2+36,240,0xFFFF7777);
    }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}

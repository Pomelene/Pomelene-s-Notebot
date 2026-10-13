package com.pomelene.notebot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

/** Persistent tempo in NBS ticks/second, distinct from tuning packets/second. */
public final class SpeedScreen extends Screen {
    private final Screen parent;
    private final NotebotController controller;
    private EditBox input;
    private Button apply;
    public SpeedScreen(Screen parent,NotebotController controller) {
        super(NotebotController.text("speed_settings"));this.parent=parent;this.controller=controller;
    }
    @Override protected void init() {
        int left=width/2-120;
        addRenderableWidget(Button.builder(modeLabel(),b -> {
            controller.toggleSpeedMode();b.setMessage(modeLabel());
        }).bounds(left,55,240,20).build());
        input=addRenderableWidget(new EditBox(font,left,85,240,20,NotebotController.text("custom_tempo")));
        input.setMaxLength(10);input.setValue(Double.toString(controller.customTempo()));
        apply=addRenderableWidget(Button.builder(NotebotController.text("speed_apply"),b -> {
            controller.setCustomTempo(Double.parseDouble(input.getValue().trim()));MinecraftPlatform.setScreen(minecraft,parent);
        }).bounds(left,height-50,118,20).build());
        input.setResponder(v -> {
            boolean valid;
            try {double n=Double.parseDouble(v.trim());valid=Double.isFinite(n)&&n>=0.1&&n<=200;}
            catch(NumberFormatException e) {valid=false;}
            apply.active=valid;input.setTextColor(valid?0xFFFFFFFF:0xFFFF7777);
        });
        addRenderableWidget(Button.builder(NotebotController.text("back"),b -> onClose())
            .bounds(left+122,height-50,118,20).build());
    }
    private net.minecraft.network.chat.Component modeLabel() {
        return NotebotController.text(controller.originalSpeed()?"speed_original":"speed_custom");
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta) {
        super.extractRenderState(g,mx,my,delta);g.centeredText(font,title,width/2,15,0xFFFFFFFF);
        g.textWithWordWrap(font,NotebotController.text("speed_hint"),width/2-120,115,240,0xFFDDDDDD);
    }
    @Override public boolean isPauseScreen() {return false;}
    @Override public void onClose() {MinecraftPlatform.setScreen(minecraft,parent);}
}

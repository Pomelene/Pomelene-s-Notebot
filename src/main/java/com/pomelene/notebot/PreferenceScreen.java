package com.pomelene.notebot;

import com.pomelene.notebot.song.SmartAssignment;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;

public final class PreferenceScreen extends Screen {
    private final CompatibilityScreen parent;
    public PreferenceScreen(CompatibilityScreen parent){super(NotebotController.text("smart_preference_title"));this.parent=parent;}
    @Override protected void init(){
        int w=Math.min(420,width-32),x=(width-w)/2;
        for(var preference:SmartAssignment.Preference.values()){
            var button=addRenderableWidget(Button.builder(NotebotController.text("smart_preference_"+preference.key()),b -> parent.choosePreference(preference))
                    .bounds(x,38+preference.ordinal()*26,w,22).build());
            button.setTooltip(Tooltip.create(NotebotController.text("smart_preference_desc_"+preference.key())));
        }
        addRenderableWidget(Button.builder(NotebotController.text("back"),b -> onClose()).bounds(x,height-28,w,20).build());
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta){
        super.extractRenderState(g,mx,my,delta);g.centeredText(font,title,width/2,14,0xFFFFFFFF);
    }
    @Override public void onClose(){minecraft.gui.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}

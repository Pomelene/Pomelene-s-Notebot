package com.pomelene.notebot;

import com.pomelene.notebot.song.CompatibilityOptions;
import com.pomelene.notebot.song.SmartAssignment;
import com.pomelene.notebot.song.NbsSong;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Per-load decisions: never carry one song's custom instrument IDs into another. */
public final class CompatibilityScreen extends Screen {
    private final Screen parent;
    private final NotebotController controller;
    private final List<Integer> ids;
    private final Map<Integer,Integer> bindings = new HashMap<>();
    private final Set<Integer> manual = new HashSet<>();
    private Map<Integer,SmartAssignment.Suggestion> suggestions = Map.of();
    private boolean analyzing, closed;
    private Component analysisMessage = Component.empty();
    private int analysisRequest;
    private final boolean detuned;
    private CompatibilityOptions.Detune policy = CompatibilityOptions.Detune.NEAREST;
    private boolean fold;
    private int page, rows, left, panelWidth;
    private Button apply;
    public CompatibilityScreen(Screen parent, NotebotController controller) {
        super(NotebotController.text("compat_title")); this.parent = parent; this.controller = controller;
        ids = controller.plan().problems().stream().filter(p -> p.startsWith("custom:"))
            .map(p -> Integer.parseInt(p.substring(7))).sorted().toList();
        detuned = controller.plan().problems().stream().anyMatch(p -> p.startsWith("detune:"));
        fold = controller.foldOctaves();
    }
    @Override protected void init() {
        panelWidth = Math.min(520,width-24); left = (width-panelWidth)/2;
        rows = Math.max(1,(height-218)/24); page = Math.min(page,Math.max(0,(ids.size()-1)/rows));
        Button smart = addRenderableWidget(Button.builder(NotebotController.text(analyzing?"smart_working":"smart_assign"),b -> analyze())
            .bounds(left,54,panelWidth*3/5-3,20).build());
        smart.active=!analyzing&&!ids.isEmpty();
        Button preference=addRenderableWidget(Button.builder(NotebotController.text("smart_preference",
                NotebotController.text("smart_preference_"+controller.assignmentPreference().key())),b -> MinecraftPlatform.setScreen(minecraft,new PreferenceScreen(this)))
                .bounds(left+panelWidth*3/5+1,54,panelWidth*2/5-1,20).build());
        preference.active=!analyzing;
        preference.setTooltip(Tooltip.create(NotebotController.text("smart_preference_tip")));
        for (int row=0; row<rows && page*rows+row<ids.size(); row++) {
            int id = ids.get(page*rows+row);
            Button binding = addRenderableWidget(Button.builder(bindingLabel(id), b -> {
                manual.add(id); bindings.put(id,(bindings.getOrDefault(id,-1)+1)%20);
                b.setMessage(bindingLabel(id)); b.setTooltip(null); updateApply();
            }).bounds(left,94+row*24,panelWidth,20).build());
            if(suggestions.containsKey(id)&&!manual.contains(id))binding.setTooltip(Tooltip.create(explanation(id)));
        }
        int y = height-120;
        addRenderableWidget(Button.builder(NotebotController.text("previous"),b -> {page=Math.max(0,page-1);rebuildWidgets();})
            .bounds(left,y,panelWidth/2-2,20).build());
        addRenderableWidget(Button.builder(NotebotController.text("next"),b -> {page=Math.min(Math.max(0,(ids.size()-1)/rows),page+1);rebuildWidgets();})
            .bounds(left+panelWidth/2+2,y,panelWidth/2-2,20).build());
        if (detuned) addRenderableWidget(Button.builder(policyLabel(),b -> {
            policy = policy==CompatibilityOptions.Detune.NEAREST?CompatibilityOptions.Detune.SKIP:CompatibilityOptions.Detune.NEAREST;
            b.setMessage(policyLabel());
        }).bounds(left,y+24,panelWidth,20).build());
        addRenderableWidget(Button.builder(NotebotController.text(fold?"fold_on":"fold_off"),b -> {
            fold=!fold;b.setMessage(NotebotController.text(fold?"fold_on":"fold_off"));
        }).bounds(left,y+48,panelWidth,20).build());
        apply=addRenderableWidget(Button.builder(NotebotController.text("compat_apply"),b -> {
            controller.applyCompatibility(new CompatibilityOptions(bindings,detuned?policy:CompatibilityOptions.Detune.REJECT),fold);
            MinecraftPlatform.setScreen(minecraft,parent);
        }).bounds(left,height-44,panelWidth/2-2,20).build());
        addRenderableWidget(Button.builder(NotebotController.text("compat_cancel"),b -> onClose())
            .bounds(left+panelWidth/2+2,height-44,panelWidth/2-2,20).build());
        updateApply();
    }
    private void analyze() {
        if(analyzing)return;
        analyzing=true;analysisMessage=NotebotController.text("smart_working");
        int request=++analysisRequest; NbsSong source=controller.song();
        var client=net.minecraft.client.Minecraft.getInstance();
        rebuildWidgets();
        controller.suggestInstruments(source).whenComplete((result,error) -> client.execute(() -> {
            if(closed||request!=analysisRequest||controller.song()!=source)return;
            analyzing=false;
            if(error!=null)analysisMessage=NotebotController.text("smart_failed");
            else {
                suggestions=result.suggestions();
                for(int id:ids)if(!manual.contains(id)&&suggestions.containsKey(id))
                    bindings.put(id,suggestions.get(id).instrument());
                analysisMessage=NotebotController.text("smart_summary",NotebotController.text("smart_style_"+result.style()),
                    NotebotController.text("smart_complexity_"+result.complexity()));
            }
            if(MinecraftPlatform.screen(client)==this)rebuildWidgets();
        }));
    }
    public void choosePreference(SmartAssignment.Preference preference){
        controller.setAssignmentPreference(preference);suggestions=Map.of();
        bindings.keySet().removeIf(id -> !manual.contains(id));
        MinecraftPlatform.setScreen(minecraft,this);analyze();
    }
    private Component explanation(int id) {
        var suggestion=suggestions.get(id);
        Component details=NotebotController.text("smart_explain",NotebotController.text("smart_role_"+suggestion.role()),
            NotebotController.text("smart_confidence_"+suggestion.confidence()));
        var result=details.copy();
        for(String reason:suggestion.reasons())result.append("\n").append(NotebotController.text("smart_reason_"+reason));
        return result;
    }
    private void updateApply() { apply.active=ids.stream().allMatch(bindings::containsKey); }
    private Component bindingLabel(int id) {
        String name=controller.song().customInstruments().getOrDefault(id,"#"+id);
        Component label=NotebotController.text("compat_binding",name+" (#"+id+")",
            bindings.containsKey(id)?NotebotController.instrumentName(bindings.get(id)):NotebotController.text("compat_unbound"));
        if(suggestions.containsKey(id)&&!manual.contains(id))return label.copy().append(" · ")
            .append(NotebotController.text("smart_confidence_"+suggestions.get(id).confidence()));
        return label;
    }
    private Component policyLabel() { return NotebotController.text(policy==CompatibilityOptions.Detune.NEAREST?"compat_nearest":"compat_skip"); }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta) {
        super.extractRenderState(g,mx,my,delta);
        g.centeredText(font,title,width/2,12,0xFFFFFFFF);
        g.textWithWordWrap(font,NotebotController.text("compat_hint",page+1,Math.max(1,(ids.size()+rows-1)/rows)),left,30,panelWidth,0xFFFFDD88);
        g.text(font,font.plainSubstrByWidth(analysisMessage.getString(),panelWidth),left,79,0xFF88DDCC);
    }
    @Override public void onClose() { closed=true;++analysisRequest;MinecraftPlatform.setScreen(minecraft,parent); }
    @Override public boolean isPauseScreen() { return false; }
}

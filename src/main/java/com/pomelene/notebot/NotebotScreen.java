package com.pomelene.notebot;

import com.pomelene.notebot.song.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import java.nio.file.Path;
import java.util.*;
import java.util.function.*;

/** Rebuilt from the 1.1.1 controller contract. Widgets are updated only after initialization completes. */
public final class NotebotScreen extends Screen {
    public enum Tab { PLAYBACK, SETTINGS }
    private final NotebotController controller;
    private Tab tab=Tab.PLAYBACK;
    private boolean ready;
    private int left,panelWidth,libraryWidth,rightLeft,rightWidth,page,searchDelay;
    private String pendingSearch;
    private EditBox search;
    private SongLibrary.Snapshot displayed;
    private final List<Button> entries=new ArrayList<>();
    private Button previous,next,up,play,tune,fullTune,compatibility,fold,direct,loop,openFolder;
    private ProgressSlider progress;
    private final List<NumericSetting> settings=new ArrayList<>();
    public NotebotScreen(NotebotController controller){super(text("title"));this.controller=controller;}
    private static Component text(String key,Object...args){return NotebotController.text(key,args);}
    public void selectTab(Tab selected){commitEdits();tab=selected;rebuildWidgets();}
    @Override protected void init(){
        ready=false;entries.clear();settings.clear();displayed=null;
        panelWidth=Math.min(720,width-24);left=(width-panelWidth)/2;
        rightWidth=(panelWidth-12)/3;libraryWidth=panelWidth-12-rightWidth;
        rightLeft=left+libraryWidth+12;
        for(Tab target:Tab.values()){
            int x=rightLeft+target.ordinal()*(rightWidth+4)/2;
            Button button=button("tab_"+target.name().toLowerCase(Locale.ROOT),x,30,(rightWidth-4)/2,()->selectTab(target));
            button.active=target!=tab;
        }
        initLibrary();
        if(tab==Tab.PLAYBACK)initPlayback();else initSettings();
        button("close",left,height-27,panelWidth,this::onClose);
        ready=true;updateWidgets();
    }
    private Button button(String key,int x,int y,int w,Runnable action){
        return addRenderableWidget(Button.builder(text(key),b->{commitEdits();action.run();}).bounds(x,y,w,20).build());
    }
    private void initLibrary(){
        int gap=4,cell=(libraryWidth-8)/3;
        up=button("parent_folder",left,48,cell,()->navigate(controller.library().snapshot().directory().getParent()));
        button("library_root",left+cell+gap,48,cell,()->navigate(controller.library().root()));
        button("new_folder",left+2*(cell+gap),48,libraryWidth-2*(cell+gap),()->MinecraftPlatform.setScreen(minecraft,new FolderScreen(this,controller)));
        openFolder=button("open_folder",left,72,cell,controller::openFolder);
        openFolder.setTooltip(Tooltip.create(text("folder_open_tip")));
        button("copy_folder_path",left+cell+gap,72,cell,controller::copyFolderPath);
        button("refresh",left+2*(cell+gap),72,libraryWidth-2*(cell+gap),controller.library()::refresh);
        search=addRenderableWidget(new EditBox(font,left,96,libraryWidth,20,text("search")));
        search.setMaxLength(100);search.setHint(text("search_hint"));
        search.setValue(controller.library().snapshot().query());
        search.setResponder(value->{if(ready){pendingSearch=value;searchDelay=6;page=0;}});
        int rows=Math.max(1,(height-64-120)/22);
        for(int slot=0;slot<rows;slot++){
            final int index=slot;
            entries.add(addRenderableWidget(Button.builder(Component.empty(),b->{
                var view=controller.library().snapshot();int selected=page*entries.size()+index;
                if(view.loading()||selected>=view.entries().size())return;
                var entry=view.entries().get(selected);
                if(entry.folder())navigate(entry.path());
                else {controller.load(entry.path());if(MinecraftPlatform.screen(minecraft)==this)selectTab(Tab.PLAYBACK);}
            }).bounds(left,120+slot*22,libraryWidth,20).build()));
        }
        previous=button("previous",left,height-53,60,()->{page--;displayed=null;});
        next=button("next",left+libraryWidth-60,height-53,60,()->{page++;displayed=null;});
    }
    private void navigate(Path path){
        if(path==null)return;pendingSearch=null;page=0;controller.clearFolderMessage();controller.library().request(path,"");
        search.setValue("");pendingSearch=null;displayed=null;
    }
    private void initPlayback(){
        progress=addRenderableWidget(new ProgressSlider(rightLeft,84,rightWidth));
        int half=(rightWidth-4)/2;
        play=button("play_button",rightLeft,112,half,controller::togglePlayback);
        button("stop_button",rightLeft+half+4,112,half,()->controller.stop(true));
        button("scan",rightLeft,136,half,controller::scan);
        tune=button("tune",rightLeft+half+4,136,half,controller::tune);tune.setTooltip(Tooltip.create(text("tune_tip")));
        fullTune=button("compact_full_scale",rightLeft,160,half,controller::tuneFullScale);
        fullTune.setTooltip(Tooltip.create(text("full_scale_tip")));
        button("details",rightLeft+half+4,160,half,()->MinecraftPlatform.setScreen(minecraft,new ReportScreen(this,controller)));
        button("compact_speed",rightLeft,184,half,()->MinecraftPlatform.setScreen(minecraft,new SpeedScreen(this,controller)));
        compatibility=button("compact_compat",rightLeft+half+4,184,half,()->MinecraftPlatform.setScreen(minecraft,new CompatibilityScreen(this,controller)));
    }
    private boolean radiusEditable(){var s=controller.state();return s!=NotebotController.State.LOADING&&s!=NotebotController.State.TUNING&&s!=NotebotController.State.PLAYING;}
    private void initSettings(){
        settings.add(new NumericSetting("radius",78,1,64,controller::scanRadius,controller::setScanRadius,this::radiusEditable));
        settings.add(new NumericSetting("tuning_rate",126,1,80,controller::tuningRate,controller::setTuningRate,()->true));
        int half=(rightWidth-4)/2;
        fold=button("compact_fold_off",rightLeft,154,half,controller::toggleFold);fold.setTooltip(Tooltip.create(text("fold_tip")));
        loop=button("compact_loop_off",rightLeft+half+4,154,half,controller::toggleRepeat);
        direct=button("compact_direct_off",rightLeft,178,half,controller::toggleDirectPackets);
        direct.setTooltip(Tooltip.create(text("direct_packets_tip")));
        button("compact_speed",rightLeft+half+4,178,half,()->MinecraftPlatform.setScreen(minecraft,new SpeedScreen(this,controller)));
    }
    private static String number(double n){return java.math.BigDecimal.valueOf(n).setScale(4,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();}
    private final class NumericSetting{
        final String key;final DoubleSupplier getter;final DoubleConsumer setter;final BooleanSupplier editable;
        final EditBox input;final SettingSlider slider;Double pending;int delay;boolean syncing;
        NumericSetting(String key,int y,double min,double max,DoubleSupplier getter,DoubleConsumer setter,BooleanSupplier editable){
            this.key=key;this.getter=getter;this.setter=setter;this.editable=editable;
            input=addRenderableWidget(new EditBox(font,rightLeft+rightWidth-46,y,46,20,text(key)));
            input.setMaxLength(8);input.setValue(number(getter.getAsDouble()));
            slider=addRenderableWidget(new SettingSlider(rightLeft,y,rightWidth-52,min,max));
            slider.setTooltip(Tooltip.create(text(key.equals("radius")?"radius_tip":"tuning_rate_tip")));
            input.setResponder(value->{if(!ready||syncing)return;try{
                double parsed=Double.parseDouble(value.strip());
                if(!Double.isFinite(parsed)||parsed<min||parsed>max)throw new NumberFormatException();
                pending=parsed;delay=8;slider.show(parsed);input.setTextColor(0xFFFFFFFF);
            }catch(NumberFormatException ignored){pending=null;input.setTextColor(0xFFFF7777);}});
        }
        void commit(){if(pending!=null&&editable.getAsBoolean()){double value=pending;pending=null;setter.accept(value);}}
        void tick(){boolean active=editable.getAsBoolean();slider.active=active;input.active=active;input.setEditable(active);
            if(!active)pending=null;else if(pending!=null&&--delay<=0)commit();}
        private final class SettingSlider extends AbstractSliderButton{
            final double min,max;
            SettingSlider(int x,int y,int w,double min,double max){super(x,y,w,20,Component.empty(),(getter.getAsDouble()-min)/(max-min));this.min=min;this.max=max;updateMessage();}
            void show(double n){value=(n-min)/(max-min);updateMessage();}
            @Override protected void updateMessage(){setMessage(Component.literal(number(min+value*(max-min))));}
            @Override protected void applyValue(){double n=Math.round((min+value*(max-min))*2)/2.0;
                pending=n;delay=8;show(n);syncing=true;input.setValue(number(n));input.setTextColor(0xFFFFFFFF);syncing=false;}
            @Override public void onRelease(MouseButtonEvent event){super.onRelease(event);commit();}
        }
    }
    private final class ProgressSlider extends AbstractSliderButton{
        boolean dragging;
        ProgressSlider(int x,int y,int w){super(x,y,w,20,Component.empty(),controller.progress());updateMessage();}
        void sync(){active=controller.plan()!=null&&controller.plan().playable()&&radiusEditable();
            if(controller.state()==NotebotController.State.PLAYING)active=true;
            if(!dragging){value=controller.progress();updateMessage();}}
        @Override protected void updateMessage(){setMessage(text("compact_progress",String.format(Locale.ROOT,"%.1f",value*controller.durationSeconds()),String.format(Locale.ROOT,"%.1f",controller.durationSeconds())));}
        @Override protected void applyValue(){controller.seek(value);updateMessage();}
        @Override public void onClick(MouseButtonEvent event,boolean doubleClick){dragging=true;super.onClick(event,doubleClick);}
        @Override public void onRelease(MouseButtonEvent event){super.onRelease(event);dragging=false;}
    }
    private void commitEdits(){if(!ready)return;for(var setting:settings)setting.commit();
        if(pendingSearch!=null){String query=pendingSearch;pendingSearch=null;controller.library().request(controller.library().snapshot().directory(),query);}}
    private void updateWidgets(){
        if(!ready)return;
        var state=controller.state();boolean busy=state==NotebotController.State.LOADING||state==NotebotController.State.TUNING;
        {
            openFolder.active=!controller.openingFolder();
            var view=controller.library().snapshot();if(view!=displayed){displayed=view;
            page=Math.max(0,Math.min(page,Math.max(0,(view.entries().size()-1)/entries.size())));
            up.active=!view.directory().equals(controller.library().root());previous.active=page>0;next.active=(page+1)*entries.size()<view.entries().size();
            for(int i=0;i<entries.size();i++){int index=page*entries.size()+i;Button row=entries.get(i);row.visible=index<view.entries().size();row.active=!view.loading();
                if(!row.visible)continue;var entry=view.entries().get(index);
                String label=entry.folder()?"[+] "+entry.title():entry.title();
                row.setMessage(Component.literal(font.plainSubstrByWidth(label,libraryWidth-16)));
                row.setTooltip(Tooltip.create(Component.literal(entry.title()+"\n"+view.directory().relativize(entry.path()))));}

            }
        }
        if(tab==Tab.PLAYBACK){
            boolean loaded=controller.plan()!=null&&controller.plan().playable();play.active=loaded&&!busy;tune.active=loaded&&!busy&&state!=NotebotController.State.PLAYING;
            fullTune.active=!busy&&state!=NotebotController.State.PLAYING;compatibility.active=controller.song()!=null&&!busy;
            play.setMessage(text(state==NotebotController.State.PLAYING?"pause_button":state==NotebotController.State.PAUSED?"resume_button":"play_button"));progress.sync();
        }else{
            for(var setting:settings)setting.tick();fold.active=radiusEditable();
            fold.setMessage(text(controller.foldOctaves()?"compact_fold_on":"compact_fold_off"));loop.setMessage(text(controller.repeat()?"compact_loop_on":"compact_loop_off"));direct.setMessage(text(controller.directPackets()?"compact_direct_on":"compact_direct_off"));
        }
    }
    @Override public void tick(){if(pendingSearch!=null&&--searchDelay<=0)commitEdits();updateWidgets();}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta){
        g.fill(left-6,26,left+libraryWidth+4,height-31,0xB5151E2B);
        g.fill(rightLeft-4,26,rightLeft+rightWidth+6,height-31,0xB5151E2B);
        super.extractRenderState(g,mx,my,delta);
        g.centeredText(font,title,width/2,10,0xFFFFFFFF);
        var view=controller.library().snapshot();String path=controller.library().root().relativize(view.directory()).toString();
        g.text(font,font.plainSubstrByWidth("songs / "+path,libraryWidth),left,33,0xFF99BDD9);
        if(view.loading()||!view.error().isEmpty()||view.entries().isEmpty())g.textWithWordWrap(font,
            view.loading()?text("library_indexing"):!view.error().isEmpty()?text("io_error",view.error()):text("library_no_results"),left,122,libraryWidth,0xFFFFDD99);
        Component message=controller.folderMessage();
        if(message.getString().isEmpty())message=text("library_count",view.entries().size(),page+1,Math.max(1,(view.entries().size()+entries.size()-1)/entries.size()));
        g.centeredText(font,font.plainSubstrByWidth(message.getString(),libraryWidth-128),left+libraryWidth/2,height-47,0xFFAAAAAA);
        if(tab==Tab.PLAYBACK){
            g.text(font,font.plainSubstrByWidth(controller.song()==null?text("idle").getString():controller.song().name(),rightWidth),rightLeft,58,0xFFFFFFFF);
            g.text(font,font.plainSubstrByWidth(controller.status().getString(),rightWidth),rightLeft,71,0xFFFFDD99);
        }else{
            g.text(font,font.plainSubstrByWidth(text("radius",number(controller.scanRadius())).getString(),rightWidth),rightLeft,62,0xFF99BDD9);
            g.text(font,font.plainSubstrByWidth(text("tuning_rate",number(controller.tuningRate())).getString(),rightWidth),rightLeft,110,0xFF99BDD9);
        }
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){commitEdits();MinecraftPlatform.setScreen(minecraft,null);}
    @Override public void onFilesDrop(List<Path> paths){controller.importFiles(paths);}
}

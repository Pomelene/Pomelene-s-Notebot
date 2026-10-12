// SPDX-License-Identifier: MIT
package com.pomelene.notebot;

import com.pomelene.notebot.song.NoteKey;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Instrument overview and position diagnostics share a compact, paged screen. */
public final class ReportScreen extends Screen {
    private record Row(Component label, Component tip, Runnable action) {}
    private final Screen parent;
    private final NotebotController controller;
    private int page, left, lineWidth, selected = -2;
    private ScanHighlights.Filter filter = ScanHighlights.Filter.ALL;
    private final List<Button> rows = new ArrayList<>();
    private List<Row> data = List.of();
    private BlockScanner.Report displayed;
    private Button previous, next, highlight, filterButton;
    public ReportScreen(Screen parent, NotebotController controller) {
        super(text("details")); this.parent = parent; this.controller = controller;
    }
    private static Component text(String key, Object...args) { return NotebotController.text(key,args); }
    private Button button(String key,int x,int y,int w,Runnable action) {
        return addRenderableWidget(Button.builder(text(key), b -> action.run()).bounds(x,y,w,20).build());
    }
    @Override protected void init() {
        rows.clear(); displayed=null;
        lineWidth=Math.min(720,width-32);left=(width-lineWidth)/2;
        int third=(lineWidth-8)/3;
        button("scan",left,58,third,controller::scan);
        highlight=button("highlight_off",left+third+4,58,third,()->controller.highlights().toggle(minecraft));
        highlight.setTooltip(Tooltip.create(text("highlight_tip",ScanHighlights.LIMIT)));
        if(selected==-2) button("positions_all",left+2*(third+4),58,lineWidth-2*(third+4),()->choose(-1));
        else button("instrument_overview",left+2*(third+4),58,lineWidth-2*(third+4),()->choose(-2));
        if(selected!=-2) {
            filterButton=button("filter.all",left,84,lineWidth,()->{
                filter=ScanHighlights.Filter.values()[(filter.ordinal()+1)%ScanHighlights.Filter.values().length];
                controller.highlights().select(selected,filter);page=0;displayed=null;refresh();
            });
            filterButton.setTooltip(Tooltip.create(text("filter_tip")));
        }
        int start=selected==-2?86:134;
        int count=Math.max(1,(height-60-start)/22);
        for(int i=0;i<count;i++) {
            int slot=i;
            rows.add(addRenderableWidget(Button.builder(Component.empty(),b->{int index=page*rows.size()+slot;if(index<data.size())data.get(index).action().run();})
                    .bounds(left,start+22*i,lineWidth,20).build()));
        }
        previous=button("previous",left,height-54,60,()->{page--;displayed=null;});
        next=button("next",left+lineWidth-60,height-54,60,()->{page++;displayed=null;});
        button("back",left,height-28,lineWidth,this::onClose);
        rows.forEach(row -> row.visible=false);
        refresh();
    }
    private void choose(int index) {
        selected=index;page=0;filter=ScanHighlights.Filter.ALL;
        controller.highlights().select(index==-2?-1:index,filter);rebuildWidgets();
    }
    private static String pitches(Set<Integer> values) {
        if(values.isEmpty())return "—";
        StringJoiner result=new StringJoiner(", ");values.forEach(n->result.add(n.toString()));return result.toString();
    }
    private List<Row> overview(BlockScanner.Report report) {
        List<Row> result=new ArrayList<>();
        if(controller.plan()!=null) for(String problem:controller.plan().problems()) {
            String[] parts=problem.split(":",2);Component label=text("problem."+parts[0],parts[1]);
            result.add(new Row(label,label,()->minecraft.gui.setScreen(new CompatibilityScreen(this,controller))));
        }
        for(int index=0;index<NoteKey.NAMES.length;index++) {
            int instrument=index;var summary=report.instrument(index);
            Component label=text("instrument_summary",NotebotController.instrumentName(index),summary.boxes(),summary.pitches().size(),
                    summary.required().size()-summary.missing().size(),summary.required().size(),summary.excluded().size());
            Component tip=label.copy().append("\n").append(text("song_missing",pitches(summary.missing())))
                    .append("\n").append(text("scale_missing",pitches(summary.missingScale())))
                    .append("\n").append(text("scale_box_count",Math.max(0,25-summary.boxes())));
            result.add(new Row(label,tip,()->choose(instrument)));
        }
        long unknown=report.excluded().stream().filter(e->e.instrument()<0).count();
        if(unknown>0)result.add(new Row(text("unknown_instruments",unknown),text("unknown_instruments",unknown),()->choose(-3)));
        return result;
    }
    private void locate(BlockPos pos) {
        controller.highlights().locate(minecraft,pos);
        minecraft.gui.setScreen(null);
        if(minecraft.player!=null)minecraft.player.sendSystemMessage(text("highlight_position",pos.getX(),pos.getY(),pos.getZ()));
    }
    private boolean instrumentMatches(int index) { return selected==-1 || (selected==-3?index<0:selected==index); }
    private List<Row> positions(BlockScanner.Report report) {
        List<Row> result=new ArrayList<>();
        if(filter==ScanHighlights.Filter.ALL || filter==ScanHighlights.Filter.USABLE) for(var c:report.usable()) {
            if(!instrumentMatches(c.key().instrument()))continue;
            boolean matched=report.matches().containsValue(c.pos());
            Component label=position(c.pos(),c.key().instrument(),c.key().pitch(),text(matched?"box_matched":"box_usable"));
            result.add(new Row(label,label.copy().append("\n").append(text("locate_tip")),()->locate(c.pos())));
        }
        for(var e:report.excluded()) {
            if(!instrumentMatches(e.instrument()) || (filter!=ScanHighlights.Filter.ALL && !filter.name().equals(e.reason().name())))continue;
            Component label=position(e.pos(),e.instrument(),e.pitch(),text("reason."+e.reason().name().toLowerCase(Locale.ROOT)));
            Component tip=label.copy().append(e.detail().isEmpty()?"":"\n"+e.detail()).append("\n").append(text("locate_tip"));
            result.add(new Row(label,tip,()->locate(e.pos())));
        }
        return result;
    }
    private Component position(BlockPos pos,int instrument,int pitch,Component state) {
        return text("box_position",pos.getX(),pos.getY(),pos.getZ(),
                instrument<0?text("unknown_instrument"):NotebotController.instrumentName(instrument),pitch,state);
    }
    private void refresh() {
        var report=controller.report();
        highlight.setMessage(text(controller.highlights().enabled()?"highlight_on":"highlight_off"));
        highlight.active=report!=null&&report.inWorld();
        if(selected!=-2)filterButton.setMessage(text("filter."+filter.name().toLowerCase(Locale.ROOT)));
        if(report==displayed)return;displayed=report;
        data=report==null?List.of():selected==-2?overview(report):positions(report);
        int pages=Math.max(1,(data.size()+rows.size()-1)/rows.size());page=Math.max(0,Math.min(page,pages-1));
        previous.active=page>0;next.active=page+1<pages;
        for(int i=0;i<rows.size();i++) {
            int index=page*rows.size()+i;var button=rows.get(i);button.visible=index<data.size();
            if(button.visible){var row=data.get(index);button.setMessage(Component.literal(font.plainSubstrByWidth(row.label().getString(),lineWidth-16)));button.setTooltip(Tooltip.create(row.tip()));}
        }
    }
    @Override public void tick(){refresh();}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta) {
        super.extractRenderState(g,mx,my,delta);
        g.centeredText(font,title,width/2,12,0xFFFFFFFF);
        var report=controller.report();
        if(report!=null) {
            Component summary=report.inWorld()?text("scan_summary",report.usable().size(),report.matches().size(),report.matches().size()+report.missing().size()):text("need_world");
            g.text(font,font.plainSubstrByWidth(summary.getString(),lineWidth),left,30,0xFFDDDDDD);
            Component coverage=report.unloadedChunks()>0?text("scan_unloaded",report.unloadedChunks()):text("scan_scope",String.format(Locale.ROOT,"%.1f",controller.scanRadius()),report.blocked(),report.outOfRange(),report.unsupported());
            g.text(font,font.plainSubstrByWidth(coverage.getString(),lineWidth),left,43,0xFFFFDD99);
            if(selected>=0) {
                var info=report.instrument(selected);
                g.text(font,font.plainSubstrByWidth(text("song_missing",pitches(info.missing())).getString(),lineWidth),left,110,0xFFFFDD99);
                g.text(font,font.plainSubstrByWidth(text("scale_missing",pitches(info.missingScale())).getString(),lineWidth),left,122,0xFF99BDD9);
                filterButton.setTooltip(Tooltip.create(text("song_missing",pitches(info.missing())).copy().append("\n").append(text("scale_missing",pitches(info.missingScale()))).append("\n").append(text("filter_tip"))));
            } else if(selected!=-2)g.text(font,text("position_hint"),left,112,0xFF99BDD9);
        }
        if(data.isEmpty())g.text(font,text("scan_no_positions"),left,selected==-2?90:136,0xFFAAAAAA);
        int pages=Math.max(1,(data.size()+rows.size()-1)/rows.size());
        g.centeredText(font,(page+1)+" / "+pages,width/2,height-48,0xFFAAAAAA);
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){minecraft.gui.setScreen(parent);}
}

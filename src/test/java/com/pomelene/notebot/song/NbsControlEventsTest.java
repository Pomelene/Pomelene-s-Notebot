// SPDX-License-Identifier: MIT
package com.pomelene.notebot.song;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class NbsControlEventsTest {
    private static final class Writer extends ByteArrayOutputStream {
        void byteValue(int n) { write(n); }
        void shortValue(int n) { write(n); write(n >> 8); }
        void string(String text) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            for (int i=0;i<4;i++) write(bytes.length >> (i*8));
            writeBytes(bytes);
        }
    }
    private static NbsSong.Note note(int tick,int layer,int instrument,int key,int velocity,int pan,int pitch) {
        return new NbsSong.Note(tick,layer,instrument,key,velocity,pitch,pan);
    }
    private static byte[] file(int version,int vanilla,List<NbsSong.Note> notes,List<String> custom,int[] status,int[] volume) {
        var w = new Writer();
        w.shortValue(0); w.byteValue(version); w.byteValue(vanilla); w.shortValue(16); w.shortValue(status.length);
        for (String text : List.of("Control fixture","","","")) w.string(text);
        w.shortValue(1000); for (int i=0;i<23;i++) w.byteValue(0); w.string("");
        w.byteValue(1); w.byteValue(2); w.shortValue(6);
        int tick=-1;
        for (int start=0;start<notes.size();) {
            int current=notes.get(start).tick(),layer=-1;
            w.shortValue(current-tick); tick=current;
            do {
                var n=notes.get(start++); w.shortValue(n.layer()-layer); layer=n.layer();
                w.byteValue(n.instrument()); w.byteValue(n.key()); w.byteValue(n.velocity()); w.byteValue(n.panning()); w.shortValue(n.cents());
            } while(start<notes.size() && notes.get(start).tick()==current);
            w.shortValue(0);
        }
        w.shortValue(0);
        for(int i=0;i<status.length;i++) {
            w.string("Layer "+i); w.byteValue(status[i]); w.byteValue(volume[i]); w.byteValue(100);
        }
        w.byteValue(custom.size());
        for(String name : custom) { w.string(name); w.string("untrusted-resource.ogg"); w.byteValue(45); w.byteValue(0); }
        return w.toByteArray();
    }
    private static NbsSong tempoSong() throws IOException {
        return NbsReader.decode(file(6,20,List.of(
            note(0,0,0,45,100,100,0), note(0,1,20,0,0,100,150),
            note(4,0,0,47,100,100,0), note(4,1,20,0,0,100,-300),
            note(8,0,0,45,100,100,0), note(8,1,20,0,0,100,75),
            note(12,0,0,47,100,100,0)),List.of("Tempo Changer"),new int[]{0,1},new int[]{100,0}),"f");
    }
    @ParameterizedTest @ValueSource(ints={4,5,6})
    void allKnownMarkersAreSeparatedBeforePitchVolumeOrSoloChecks(int version) throws Exception {
        int vanilla=version==6?20:16;
        List<String> names=List.of("Tempo Changer","Sound Stopper","Toggle Rainbow","Change Color to #12ABef",
                "Toggle Background Accent","Show Save Popup","change color to #invalid");
        var notes=new ArrayList<NbsSong.Note>(); notes.add(note(0,0,0,45,100,100,0));
        for(int i=0;i<names.size();i++) notes.add(note(0,i+1,vanilla+i,255,255,255,i==0?150:32767));
        int[] status=new int[8],volume=new int[8]; status[0]=2;volume[0]=100;
        var song=NbsReader.decode(file(version,vanilla,notes,names,status,volume),"f");
        assertEquals(1,song.notes().size()); assertEquals(7,song.events().size());
        assertEquals(255,song.events().get(1).raw().velocity());
        assertEquals(255,song.events().get(1).raw().panning());
        assertEquals(32767,song.events().get(1).raw().cents());
        var plan=PlaybackPlan.compile(song,false);
        assertTrue(plan.playable()); assertEquals(Set.of(new NoteKey(0,12)),plan.required());
        assertTrue(plan.problems().isEmpty()); assertTrue(SmartAssignment.analyze(song).suggestions().isEmpty());
    }
    @Test void tempoMapControlsOnsetsDurationAndLoopPoint() throws Exception {
        var song=tempoSong(); var plan=PlaybackPlan.compile(song,false);
        assertEquals(Set.of(0,8,12,28),plan.frames().keySet());
        assertEquals(44,plan.duration()); assertEquals(10,plan.loopStart());
        assertEquals(4,song.notes().size()); assertEquals(3,song.events().size());
        assertEquals(16,song.length());
    }
    @Test void customSpeedScalesEveryTempoSegmentAndPositionMappingRoundTrips() throws Exception {
        var song=tempoSong(); var plan=PlaybackPlan.compile(song,false,CompatibilityOptions.STRICT,20);
        assertEquals(Set.of(0,4,6,14),plan.frames().keySet());
        assertEquals(22,plan.duration()); assertEquals(5,plan.loopStart());
        var original=PlaybackPlan.compile(song,false);
        for(double tick:new double[]{0,1.25,4,6,8,12,15.5}) {
            assertEquals(tick,plan.timeline().sourceTick(plan.timeline().gameTick(tick)),1e-9);
            assertEquals(original.timeline().gameTick(tick)/2,plan.timeline().gameTick(tick),1e-9);
        }
    }
    @Test void pauseSeekAndFiniteLoopsUseIntegratedTime() throws Exception {
        var song=tempoSong(); var plan=PlaybackPlan.compile(song,false); var clock=new PlaybackClock();
        clock.seek(plan,28); assertEquals(List.of(new NoteKey(0,14)),clock.current(plan));
        assertEquals(28,clock.tick()); assertEquals(28,clock.tick());
        for(int i=28;i<44;i++)clock.advance(plan,true,1);
        assertEquals(10,clock.tick()); assertFalse(clock.finished());
        for(int i=10;i<44;i++)clock.advance(plan,true,1);
        assertTrue(clock.finished()); clock.reset(); assertEquals(0,clock.tick());
    }
    @Test void zeroTempoDoesNotFreezeAndLastMarkerAtSameTickWins() throws Exception {
        var song=NbsReader.decode(file(6,20,List.of(
            note(0,0,0,45,100,100,0),note(0,1,20,0,0,100,300),note(0,2,20,0,0,100,-150),
            note(4,1,20,0,0,100,0)),List.of("Tempo Changer"),new int[]{0,0,0},new int[]{100,0,0}),"f");
        var plan=PlaybackPlan.compile(song,false);
        assertEquals(32,plan.duration()); assertEquals(3,song.events().size());
    }
    @Test void fractionalTempoAndSmallestSignedPitchAreNotRoundedOrOverflowed() throws Exception {
        var song=NbsReader.decode(file(6,20,List.of(note(0,0,0,45,100,100,0),note(0,1,20,0,0,100,151)),
            List.of("Tempo Changer"),new int[]{0,0},new int[]{100,0}),"f");
        assertEquals(320/(151/15.0),PlaybackPlan.compile(song,false).timeline().gameTick(16),1e-9);
        var raw=note(0,0,20,0,0,100,-32768);
        assertEquals(32768/15.0,new NbsControlEvent(NbsControlEvent.Type.TEMPO,"Tempo Changer",raw).tempo(),1e-9);
    }
    @Test void unrelatedCustomNamesAreNotGuessedAndInvalidNormalNotesStillFail() throws Exception {
        for(String name:List.of("tempo changer","Tempo Changer Piano","My Sound Stopper","Rainbow synth")) {
            var song=NbsReader.decode(file(6,20,List.of(note(0,0,20,45,100,100,0)),List.of(name),new int[]{0},new int[]{100}),"f");
            assertTrue(song.events().isEmpty()); assertTrue(PlaybackPlan.compile(song,false).problems().contains("custom:20"));
        }
        assertThrows(IOException.class,()->NbsReader.decode(file(6,20,List.of(note(0,0,0,45,255,255,0)),List.of(),new int[]{0},new int[]{100}),"f"));
    }
    @Test void soloSelectsMusicButDoesNotDisableSilentTempoLayerOrLockedNotes() throws Exception {
        var song=NbsReader.decode(file(6,20,List.of(note(0,0,0,45,100,100,0),note(0,1,1,45,100,100,0),note(0,2,20,0,0,100,300)),
            List.of("Tempo Changer"),new int[]{2,1,0},new int[]{100,100,0}),"f");
        assertEquals(1,song.notes().size()); assertEquals(1,song.events().size());
        assertEquals(16,PlaybackPlan.compile(song,false).duration());
    }
}

package com.pomelene.notebot.song;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NbsVersionSixTest {
    private NbsSong song(int version,int count,int instrument,Map<Integer,String> custom) {
        return new NbsSong("v6","",version,count,10,1,false,0,0,
                List.of(new NbsSong.Note(0,0,instrument,45,100,0)),custom);
    }
    @Test void allFourCopperInstrumentsAreNativeOnlyInV6WithSuitableHeader() {
        for(int id=16;id<20;id++) {
            var modern=song(6,20,id,Map.of());
            var plan=PlaybackPlan.compile(modern,false);
            assertTrue(plan.playable());assertEquals(Set.of(new NoteKey(id,12)),plan.required());
            assertFalse(SmartAssignment.analyze(modern).suggestions().containsKey(id));
            assertFalse(song(5,16,id,Map.of(id,"Legacy custom")).isVanillaInstrument(id));
            assertFalse(song(6,16,id,Map.of(id,"Preserved custom")).isVanillaInstrument(id));
            assertFalse(PlaybackPlan.compile(song(6,16,id,Map.of(id,"Preserved custom")),false).playable());
        }
    }
    @Test void v6CustomDefinitionsStartAtHeaderCountAndStillRequireBinding() {
        var source=song(6,20,20,Map.of(20,"Firework"));
        assertFalse(source.isVanillaInstrument(20));
        assertTrue(PlaybackPlan.compile(source,false).problems().contains("custom:20"));
        var mapped=PlaybackPlan.compile(source,false,new CompatibilityOptions(Map.of(20,3),CompatibilityOptions.Detune.REJECT),10);
        assertEquals(Set.of(new NoteKey(3,12)),mapped.required());assertTrue(mapped.playable());
        assertTrue(SmartAssignment.analyze(source).suggestions().containsKey(20));
    }
    @Test void v6HeaderLayersAndSignedPitchSurviveGzip() throws Exception {
        byte[] bytes=NbsReaderTest.fixture(6,true,true);
        var raw=NbsReader.decode(bytes,"test");
        assertEquals(6,raw.version());assertEquals(20,raw.vanillaInstruments());
        assertEquals(2,raw.notes().size());assertEquals("Layer 1",raw.metadata().layerNames().get(1));
        assertEquals(0,raw.metadata().layerVolumes().get(1));
        var output=new ByteArrayOutputStream();try(var gzip=new java.util.zip.GZIPOutputStream(output)){gzip.write(bytes);}
        assertEquals(raw,NbsReader.decode(output.toByteArray(),"test"));
        var unmuted=NbsReader.decode(NbsReaderTest.fixture(6,true,false),"test");
        assertEquals(-100,unmuted.notes().get(1).cents());
    }
    @Test void v6StillRejectsEveryTruncatedMandatoryPrefix() {
        byte[] bytes=NbsReaderTest.fixture(6,false,false);
        for(int n=0;n<bytes.length;n++) {
            byte[] truncated=Arrays.copyOf(bytes,n);
            assertThrows(IOException.class,()->NbsReader.decode(truncated,"test"),"length="+n);
        }
    }
    @Test void binaryV6PreservesCopperAndCustomNamesWithoutMixingTheirIds() throws Exception {
        byte[] data=NbsReaderTest.fixture(6,true,false);
        var buffer=java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int position=8;
        for(int i=0;i<4;i++)position+=4+buffer.getInt(position);
        position+=2+23;position+=4+buffer.getInt(position);position+=4;
        data[position+4]=18; // Tick and layer deltas precede the instrument byte.
        data[position+12]=20; // Second same-tick note: one delta and six note bytes later.
        var output=new ByteArrayOutputStream();output.write(data,0,data.length-1);output.write(1);
        try(var writer=new DataOutputStream(output)) {
            for(String text:List.of("Firework","minecraft/fireworks/blast_far1.ogg")) {
                byte[] bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                writer.writeInt(Integer.reverseBytes(bytes.length));writer.write(bytes);
            }
            writer.writeByte(45);writer.writeByte(0);
        }
        var loaded=NbsReader.decode(output.toByteArray(),"test");
        assertEquals(18,loaded.notes().getFirst().instrument());assertEquals(20,loaded.notes().get(1).instrument());
        assertEquals("Firework",loaded.customInstruments().get(20));
        assertEquals("minecraft/fireworks/blast_far1.ogg",loaded.metadata().soundFiles().get(20));
        assertEquals(Set.of("custom:20"),new HashSet<>(PlaybackPlan.compile(loaded,false).problems()));
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="notebot.test.nbs6",matches=".+")
    void actualPetalDanceReadsCompleteMetadataAndOnlyFlagsTrueCustomSound() throws Exception {
        var loaded=NbsReader.read(java.nio.file.Path.of(System.getProperty("notebot.test.nbs6")));
        assertEquals(6,loaded.version());assertEquals(20,loaded.vanillaInstruments());
        assertEquals("DELTARUNE ch. 5 - Petal Dance",loaded.name());
        assertEquals(8976,loaded.notes().size());assertEquals(166,loaded.metadata().layerVolumes().size());
        assertEquals(820,loaded.notes().stream().filter(n->n.instrument()==18).count());
        assertEquals("Firework",loaded.customInstruments().get(20));
        assertEquals(Set.of("custom:20"),new HashSet<>(PlaybackPlan.compile(loaded,true).problems()));
        var converted=PlaybackPlan.compile(loaded,true,new CompatibilityOptions(Map.of(20,3),CompatibilityOptions.Detune.NEAREST),loaded.tempo());
        assertTrue(converted.playable());assertTrue(converted.required().stream().anyMatch(n->n.instrument()==18));
    }
}

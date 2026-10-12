package com.pomelene.notebot.song;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LibraryAndAssignmentTest {
    @TempDir Path directory;
    @Test void indexesPinyinInitialsAndJapaneseKanjiReadings() throws Exception {
        SongSearch index=new SongSearch();
        Path chinese=Files.createFile(directory.resolve("月亮代表我的心.nbs"));
        var entry=index.entry(chinese,false);
        assertTrue(entry.matches("yue liang dai biao"));
        assertTrue(entry.matches("yldbwdx"));
        Path japanese=Files.createFile(directory.resolve("千本桜.nbs"));
        assertTrue(index.entry(japanese,false).matches("senbonzakura"));
        assertFalse(entry.matches("unrelated title"));
    }
    private NbsSong song(String name,List<NbsSong.Note> notes) {
        return new NbsSong("Test","",5,16,10,500,false,0,0,notes,Map.of(16,name));
    }
    @Test void repetitiveBassIsNotPercussionInAnyPreference() {
        var notes=new ArrayList<NbsSong.Note>();
        for(int i=0;i<40;i++) {
            notes.add(new NbsSong.Note(i*8,0,16,33,100,0));
            notes.add(new NbsSong.Note(i*8,1,0,57+i%6,100,0));
        }
        var source=song("Sub Bass",notes);
        for(var preference:SmartAssignment.Preference.values()) {
            var result=SmartAssignment.analyze(source,preference).suggestions().get(16);
            assertEquals("bass",result.role());
            assertTrue(Set.of(1,12,19).contains(result.instrument()));
        }
    }
    @Test void arpeggiatedAccompanimentKeepsHarmonicRole() {
        var notes=new ArrayList<NbsSong.Note>(); int[] pitches={45,49,52};
        for(int i=0;i<36;i++) {
            notes.add(new NbsSong.Note(i*4,0,16,pitches[i%3],100,0));
            notes.add(new NbsSong.Note(i*4,1,0,62+i%4,100,0));
        }
        var result=SmartAssignment.analyze(song("Arpeggio accompaniment",notes)).suggestions().get(16);
        assertEquals("chord",result.role());
        assertFalse(Set.of(2,3,4,11).contains(result.instrument()));
    }
    @Test void preferencesChangeTimbreWithoutChangingLeadRole() {
        var notes=new ArrayList<NbsSong.Note>();
        for(int i=0;i<40;i++)notes.add(new NbsSong.Note(i*4,0,16,48+i%8,100,0));
        var source=song("Vocal lead",notes);
        var piano=SmartAssignment.analyze(source,SmartAssignment.Preference.PIANO).suggestions().get(16);
        var electronic=SmartAssignment.analyze(source,SmartAssignment.Preference.ELECTRONIC).suggestions().get(16);
        assertEquals("melody",piano.role()); assertEquals("melody",electronic.role());
        assertEquals(15,piano.instrument());assertEquals(13,electronic.instrument());
        assertEquals(SmartAssignment.analyze(source),SmartAssignment.analyze(source));
    }
}

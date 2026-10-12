// SPDX-License-Identifier: MIT
package com.pomelene.notebot.song;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackTest {
    private static NbsSong song(double tempo, int length, NbsSong.Note... notes) {
        return new NbsSong("Test", "", 5, 16, tempo, length, false, 0, 0, List.of(notes));
    }
    private static NbsSong.Note note(int tick, int instrument, int key, int cents) {
        return new NbsSong.Note(tick, 0, instrument, key, 100, cents);
    }
    @Test void retainsTempoChordsAndDeduplicatesIdenticalNotes() {
        var plan = PlaybackPlan.compile(song(12.5, 11, note(0, 0, 45, 0), note(0, 0, 45, 0),
                note(0, 5, 47, 0), note(10, 0, 49, 0)), false);
        assertTrue(plan.playable()); assertEquals(2, plan.frames().get(0).size());
        assertEquals(List.of(new NoteKey(0, 16)), plan.frames().get(16));
        assertEquals(18, plan.duration()); assertEquals(3, plan.required().size());
    }
    @Test void fullSemitonesAreExactAndFineDetuningIsRejected() {
        var exact = PlaybackPlan.compile(song(10, 1, note(0, 0, 45, -100)), false);
        assertEquals(new NoteKey(0, 11), exact.required().iterator().next());
        var detuned = PlaybackPlan.compile(song(10, 1, note(0, 0, 45, 50)), false);
        assertFalse(detuned.playable()); assertTrue(detuned.problems().contains("detune:50"));
    }
    @Test void customInstrumentsAreNotSilentlyReplaced() {
        var plan = PlaybackPlan.compile(song(10, 1, note(0, 16, 45, 0)), true);
        assertFalse(plan.playable()); assertTrue(plan.problems().contains("custom:16"));
        var oldSong = new NbsSong("old", "", 0, 10, 10, 1, false, 0, 0, List.of(note(0, 10, 45, 0)));
        assertFalse(PlaybackPlan.compile(oldSong, false).playable());
    }
    @Test void octaveFoldingIsExplicitAndKeepsPitchClass() {
        var song = song(10, 1, note(0, 0, 21, 0), note(0, 5, 69, 0));
        assertFalse(PlaybackPlan.compile(song, false).playable());
        var folded = PlaybackPlan.compile(song, true);
        assertTrue(folded.playable()); assertEquals(2, folded.foldedNotes());
        assertTrue(folded.required().contains(new NoteKey(0, 0)));
        assertTrue(folded.required().contains(new NoteKey(5, 24)));
    }
    @Test void lastQuantizedNoteIsNeverDroppedAtHighTempo() {
        var plan = PlaybackPlan.compile(song(100, 2, note(1, 0, 45, 0)), false);
        assertTrue(plan.frames().lastKey() < plan.duration());
        var clock = new PlaybackClock(); assertEquals(1, clock.current(plan).size());
        clock.advance(plan, false, 0); assertTrue(clock.finished());
    }
    @Test void pauseFreezesTimeAndResetRestartsFromTickZero() {
        var plan = PlaybackPlan.compile(song(20, 3, note(0, 0, 45, 0), note(2, 0, 47, 0)), false);
        var clock = new PlaybackClock();
        assertEquals(new NoteKey(0, 12), clock.current(plan).getFirst());
        clock.advance(plan, false, 0); assertTrue(clock.current(plan).isEmpty());
        // No advance call while paused; repeated reads must not consume notes.
        assertEquals(1, clock.tick()); assertTrue(clock.current(plan).isEmpty());
        clock.advance(plan, false, 0); assertEquals(new NoteKey(0, 14), clock.current(plan).getFirst());
        clock.advance(plan, false, 0); assertTrue(clock.finished());
        clock.reset(); assertFalse(clock.finished()); assertEquals(0, clock.tick());
    }
    @Test void obeysLoopStartAndFiniteLoopCount() {
        var nbs = new NbsSong("Loop", "", 5, 16, 20, 4, true, 2, 2,
                List.of(note(0, 0, 45, 0), note(2, 0, 47, 0)));
        var plan = PlaybackPlan.compile(nbs, false); var clock = new PlaybackClock();
        for (int i = 0; i < 4; i++) clock.advance(plan, true, nbs.maxLoops());
        assertEquals(2, clock.tick()); assertFalse(clock.finished());
        for (int i = 0; i < 2; i++) clock.advance(plan, true, nbs.maxLoops());
        assertEquals(2, clock.tick()); assertFalse(clock.finished());
        for (int i = 0; i < 2; i++) clock.advance(plan, true, nbs.maxLoops());
        assertTrue(clock.finished());
    }
    @Test void nonLoopingFileDoesNotApplyInactiveLoopStart() {
        var nbs = new NbsSong("Loop disabled", "", 5, 16, 20, 4, false, 3, 3,
                List.of(note(0, 0, 45, 0)));
        assertEquals(0, PlaybackPlan.compile(nbs, false).loopStart());
    }
}

// SPDX-License-Identifier: MIT
package com.pomelene.notebot.song;

import java.util.*;

/** Quantize to the server's 20 ticks/second; identical chord notes are deduplicated. */
public record PlaybackPlan(NavigableMap<Integer, List<NoteKey>> frames, Set<NoteKey> required,
                           int duration, int loopStart, List<String> problems, int foldedNotes) {
    public PlaybackPlan {
        frames = Collections.unmodifiableNavigableMap(new TreeMap<>(frames));
        required = Collections.unmodifiableSet(new TreeSet<>(required));
        problems = List.copyOf(problems);
    }
    public boolean playable() { return problems.isEmpty() && !required.isEmpty(); }

    public static PlaybackPlan compile(NbsSong song, boolean foldOctaves) {
        return compile(song, foldOctaves, CompatibilityOptions.STRICT, song.tempo());
    }
    public static PlaybackPlan compile(NbsSong song, boolean foldOctaves, CompatibilityOptions options, double tempo) {
        if (!Double.isFinite(tempo) || tempo <= 0 || song.length() * 20.0 / tempo >= Integer.MAX_VALUE - 1)
            throw new IllegalArgumentException("Invalid or excessive playback duration");
        var grouped = new TreeMap<Integer, Set<NoteKey>>();
        var required = new TreeSet<NoteKey>();
        var problems = new LinkedHashSet<String>();
        int folded = 0;
        for (var note : song.notes()) {
            if (note.cents()%100!=0 && options.detune()==CompatibilityOptions.Detune.REJECT)
                problems.add("detune:"+note.cents());
            int instrument = note.instrument();
            boolean custom = !song.isVanillaInstrument(instrument);
            if (custom) {
                Integer bound = options.instruments().get(instrument);
                if (bound == null) { problems.add("custom:" + instrument); continue; }
                instrument = bound;
            }
            if (note.cents() % 100 != 0) {
                if (options.detune() == CompatibilityOptions.Detune.REJECT) {
                    problems.add("detune:" + note.cents()); continue;
                }
                if (options.detune() == CompatibilityOptions.Detune.SKIP) continue;
            }
            int pitch = note.key() - 33 + (custom ? song.metadata().baseKeys().getOrDefault(note.instrument(),45)-45 : 0) + (int) Math.round(note.cents() / 100.0);
            if (pitch < 0 || pitch > 24) {
                if (!foldOctaves) { problems.add("range:" + (pitch + 33)); continue; }
                while (pitch < 0) pitch += 12;
                while (pitch > 24) pitch -= 12;
                folded++;
            }
            NoteKey key = new NoteKey(instrument, pitch);
            required.add(key);
            int gameTick = quantize(note.tick(), tempo);
            grouped.computeIfAbsent(gameTick, ignored -> new TreeSet<>()).add(key);
        }
        var frames = new TreeMap<Integer, List<NoteKey>>();
        grouped.forEach((tick, keys) -> {
            if (keys.size() > 64) problems.add("dense:" + tick);
            frames.put(tick, List.copyOf(keys));
        });
        int duration = Math.max(1, (int) Math.ceil(song.length() * 20.0 / tempo));
        if (!frames.isEmpty()) duration = Math.max(duration, frames.lastKey() + 1);
        return new PlaybackPlan(frames, required, duration,
                song.loop() ? quantize(song.loopStart(), tempo) : 0, new ArrayList<>(problems), folded);
    }
    private static int quantize(int tick, double tempo) {
        return (int) Math.round(tick * 20.0 / tempo);
    }
}

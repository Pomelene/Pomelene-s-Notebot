package com.pomelene.notebot.song;
import java.util.Map;
public record CompatibilityOptions(Map<Integer,Integer> instruments, Detune detune) {
    public enum Detune { REJECT, NEAREST, SKIP }
    public static final CompatibilityOptions STRICT = new CompatibilityOptions(Map.of(), Detune.REJECT);
    public CompatibilityOptions {
        instruments = Map.copyOf(instruments);
        if (detune == null) throw new IllegalArgumentException("Missing detune policy");
        if (instruments.values().stream().anyMatch(i -> i < 0 || i >= NoteKey.NAMES.length))
            throw new IllegalArgumentException("Invalid instrument binding");
    }
}

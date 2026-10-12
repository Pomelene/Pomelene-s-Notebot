// SPDX-License-Identifier: MIT
package com.pomelene.notebot.song;

public record NoteKey(int instrument, int pitch) implements Comparable<NoteKey> {
    public static final String[] NAMES = {
        "harp", "bass", "basedrum", "snare", "hat", "guitar", "flute", "bell",
        "chime", "xylophone", "iron_xylophone", "cow_bell", "didgeridoo", "bit", "banjo", "pling",
        "trumpet", "trumpet_exposed", "trumpet_weathered", "trumpet_oxidized"
    };
    public NoteKey {
        if (instrument < 0 || instrument >= NAMES.length || pitch < 0 || pitch > 24)
            throw new IllegalArgumentException("Invalid vanilla note");
    }
    @Override public int compareTo(NoteKey other) {
        int order = Integer.compare(instrument, other.instrument);
        return order == 0 ? Integer.compare(pitch, other.pitch) : order;
    }
}

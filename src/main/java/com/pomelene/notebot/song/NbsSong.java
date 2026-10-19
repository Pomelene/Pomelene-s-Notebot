// SPDX-License-Identifier: MIT
package com.pomelene.notebot.song;

import java.util.List;

public record NbsSong(String name, String author, int version, int vanillaInstruments,
                      double tempo, int length, boolean loop, int maxLoops, int loopStart,
                      List<Note> notes, java.util.Map<Integer, String> customInstruments, Metadata metadata,
                      List<NbsControlEvent> events) {
    public NbsSong { notes = List.copyOf(notes); customInstruments = java.util.Map.copyOf(customInstruments); events = List.copyOf(events); }
    public NbsSong(String name, String author, int version, int vanillaInstruments, double tempo,
                   int length, boolean loop, int maxLoops, int loopStart, List<Note> notes,
                   java.util.Map<Integer,String> customInstruments, Metadata metadata) {
        this(name,author,version,vanillaInstruments,tempo,length,loop,maxLoops,loopStart,notes,customInstruments,metadata,List.of());
    }
    /** Header count matters: old custom IDs 16-19 must never silently become copper instruments. */
    public boolean isVanillaInstrument(int instrument) {
        int supported = version >= 6 ? NoteKey.NAMES.length : 16;
        return instrument >= 0 && instrument < vanillaInstruments && instrument < supported;
    }
    public record Metadata(java.util.Map<Integer,String> layerNames, java.util.Map<Integer,Integer> layerVolumes,
                           java.util.Map<Integer,String> soundFiles, java.util.Map<Integer,Integer> baseKeys) {
        public static final Metadata EMPTY = new Metadata(java.util.Map.of(),java.util.Map.of(),java.util.Map.of(),java.util.Map.of());
        public Metadata { layerNames=java.util.Map.copyOf(layerNames); layerVolumes=java.util.Map.copyOf(layerVolumes);
            soundFiles=java.util.Map.copyOf(soundFiles); baseKeys=java.util.Map.copyOf(baseKeys); }
    }
    public NbsSong(String name, String author, int version, int vanillaInstruments, double tempo,
                   int length, boolean loop, int maxLoops, int loopStart, List<Note> notes, java.util.Map<Integer,String> customInstruments) {
        this(name,author,version,vanillaInstruments,tempo,length,loop,maxLoops,loopStart,notes,customInstruments,Metadata.EMPTY);
    }
    public NbsSong(String name, String author, int version, int vanillaInstruments, double tempo,
                   int length, boolean loop, int maxLoops, int loopStart, List<Note> notes) {
        this(name, author, version, vanillaInstruments, tempo, length, loop, maxLoops, loopStart, notes, java.util.Map.of(), Metadata.EMPTY);
    }
    public record Note(int tick, int layer, int instrument, int key, int velocity, int cents, int panning) {
        public Note(int tick, int layer, int instrument, int key, int velocity, int cents) {
            this(tick,layer,instrument,key,velocity,cents,100);
        }
    }
}

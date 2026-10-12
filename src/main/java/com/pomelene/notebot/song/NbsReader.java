// SPDX-License-Identifier: MIT
package com.pomelene.notebot.song;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Independent implementation of https://noteblock.studio/nbs. */
public final class NbsReader {
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    private final ByteBuffer data;
    private NbsReader(byte[] bytes) { data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }

    public static NbsSong read(Path path) throws IOException {
        if (Files.size(path) > MAX_BYTES) throw new IOException("NBS exceeds 32 MiB");
        try (var input = Files.newInputStream(path)) {
            return decode(input.readNBytes(MAX_BYTES + 1), path.getFileName().toString());
        }
    }
    public static NbsSong decode(byte[] bytes, String fallbackName) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("NBS exceeds 32 MiB");
        // Some song collections wrap NBS data in gzip but retain the .nbs extension.
        if (bytes.length >= 3 && bytes[0] == (byte) 0x1f && bytes[1] == (byte) 0x8b && bytes[2] == 8) {
            try (var gzip = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(bytes))) {
                bytes = gzip.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) throw new IOException("Expanded NBS exceeds 32 MiB");
            }
        }
        if (bytes.length >= 15 && bytes[0] == 10 && bytes[1] == 0 && bytes[2] == 0
                && bytes[3] == 10 && bytes[4] == 0 && bytes[5] == 9
                && new String(bytes, 6, 9, StandardCharsets.US_ASCII).equals("Schematic"))
            throw new IOException("This file contains a Minecraft schematic, not an NBS song");
        try { return new NbsReader(bytes).parse(fallbackName); }
        catch (java.nio.BufferUnderflowException | IllegalArgumentException e) {
            throw new IOException("Invalid or truncated NBS: " + e.getMessage(), e);
        }
    }

    private NbsSong parse(String fallbackName) throws IOException {
        int length = u16();
        int version = 0, vanilla = 10;
        if (length == 0) {
            version = u8();
            if (version < 1 || version > 6) throw new IOException("Unsupported NBS version: " + version);
            vanilla = u8();
            if (vanilla == 0) throw new IOException("Invalid vanilla instrument count");
            if (version >= 3) length = u16();
        }
        int layers = u16();
        String name = string(), author = string();
        String originalAuthor = string();
        string(); // Description
        double tempo = u16() / 100.0;
        if (tempo <= 0) throw new IOException("Tempo must be positive");
        skip(3 + 5 * 4); // Editor metadata
        string(); // Imported filename
        boolean loop = false;
        int maxLoops = 0, loopStart = 0;
        if (version >= 4) { loop = u8() != 0; maxLoops = u8(); loopStart = u16(); }

        List<NbsSong.Note> notes = new ArrayList<>();
        int tick = -1, lastTick = -1, actualLayers = 0;
        while (true) {
            int jump = u16();
            if (jump == 0) break;
            tick += jump;
            if (tick > 10_000_000) throw new IOException("Song timeline is too long");
            int layer = -1;
            while (true) {
                int layerJump = u16();
                if (layerJump == 0) break;
                layer += layerJump;
                if (layer >= 65_536) throw new IOException("Too many note layers");
                // Some exporters leave the header at zero or understate the layers.
                // Keep their declared optional metadata layout, and recover note-only layers.
                actualLayers = Math.max(actualLayers, layer + 1);
                int instrument = u8(), key = u8();
                if (key > 87) throw new IOException("Invalid NBS key: " + key);
                int velocity = 100, cents = 0;
                if (version >= 4) {
                    velocity = u8();
                    int pan = u8();
                    cents = data.getShort();
                    if (velocity > 100 || pan > 200) throw new IOException("Invalid note volume/panning");
                }
                notes.add(new NbsSong.Note(tick, layer, instrument, key, velocity, cents));
                if (notes.size() > 2_000_000) throw new IOException("Too many notes");
                lastTick = tick;
            }
        }
        // The entire layer section may be omitted, but partial layer data is malformed.
        java.util.Map<Integer,String> layerNames = new java.util.TreeMap<>(), soundFiles = new java.util.TreeMap<>();
        java.util.Map<Integer,Integer> layerVolumes = new java.util.TreeMap<>(), baseKeys = new java.util.TreeMap<>();
        int[] volume = new int[Math.max(layers, actualLayers)];
        java.util.Arrays.fill(volume, 100);
        boolean countedNames = version == 0 && tryCountedLayerNames(actualLayers);
        if (!countedNames && data.hasRemaining()) {
            for (int i = 0; i < layers; i++) {
                layerNames.put(i,string());
                if (version >= 4) u8(); // Lock is an editor flag, not a mute flag.
                volume[i] = u8(); layerVolumes.put(i,volume[i]);
                if (volume[i] > 100) throw new IOException("Invalid layer volume");
                if (version >= 2 && u8() > 200) throw new IOException("Invalid layer panning");
            }
        }
        java.util.Map<Integer, String> customNames = new java.util.LinkedHashMap<>();
        if (!countedNames && data.hasRemaining()) {
            int custom = u8();
            for (int i = 0; i < custom; i++) {
                customNames.put(vanilla + i, string()); soundFiles.put(vanilla+i,string());
                int baseKey = u8(); baseKeys.put(vanilla+i,baseKey);
                if (baseKey > 87 || u8() > 1) throw new IOException("Invalid custom instrument");
            }
            // Classic editors often pad a complete file to 4/8/64 KiB. Never ignore
            // nonzero trailing bytes or use padding to repair truncated mandatory data.
            while (data.hasRemaining()) if (u8() != 0) throw new IOException("Unexpected trailing NBS data");
        }
        List<NbsSong.Note> audible = new ArrayList<>();
        for (var note : notes) {
            if (note.velocity() > 0 && volume[note.layer()] > 0) audible.add(note);
        }
        length = Math.max(length, lastTick + 1);
        if (length == 0 || audible.isEmpty()) throw new IOException("Song contains no audible notes");
        if (loop && loopStart >= length) throw new IOException("Loop start is outside song");
        return new NbsSong(name.isBlank() ? fallbackName : name,
                author.isBlank() ? originalAuthor : author, version, vanilla, tempo,
                length, loop, maxLoops, loopStart, audible, customNames, new NbsSong.Metadata(layerNames,layerVolumes,soundFiles,baseKeys));
    }
    private int u8() { return Byte.toUnsignedInt(data.get()); }
    private int u16() { return Short.toUnsignedInt(data.getShort()); }
    private void skip(int count) { data.position(data.position() + count); }
    /** Legacy converter variant: u16 count, names only (no volume fields), zero custom count. */
    private boolean tryCountedLayerNames(int actualLayers) {
        int start = data.position();
        boolean accepted = false;
        try {
            if (data.remaining() < 3) return false;
            int count = u16();
            if (count == 0 || count < actualLayers || (long) count * 4 + 1 > data.remaining()) return false;
            for (int i = 0; i < count; i++) string();
            if (u8() != 0) return false; // Custom definitions require the normal metadata layout.
            while (data.hasRemaining()) if (u8() != 0) return false;
            accepted = true;
            return true;
        } catch (IOException | java.nio.BufferUnderflowException | IllegalArgumentException e) {
            return false;
        } finally {
            if (!accepted) data.position(start);
        }
    }
    private String string() throws IOException {
        int size = data.getInt();
        if (size < 0 || size > 1_048_576 || size > data.remaining()) throw new IOException("Invalid string length");
        byte[] bytes = new byte[size];
        data.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}

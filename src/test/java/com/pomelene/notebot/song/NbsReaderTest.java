// SPDX-License-Identifier: MIT
package com.pomelene.notebot.song;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class NbsReaderTest {
    private static final class Writer extends ByteArrayOutputStream {
        void u8(int v) { write(v); }
        void u16(int v) { write(v); write(v >> 8); }
        void i32(int v) { for (int i = 0; i < 4; i++) write(v >> (8 * i)); }
        void string(String s) {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8); i32(bytes.length); writeBytes(bytes);
        }
    }
    static byte[] fixture(int version, boolean layerData, boolean muted) {
        Writer w = new Writer();
        if (version == 0) w.u16(9);
        else { w.u16(0); w.u8(version); w.u8(version >= 6 ? 20 : 16); if (version >= 3) w.u16(9); }
        w.u16(2);
        w.string("中文测试"); w.string("Author"); w.string(""); w.string("");
        w.u16(1250); // 12.5 NBS ticks/s.
        w.u8(0); w.u8(10); w.u8(4);
        for (int i = 0; i < 5; i++) w.i32(0);
        w.string("");
        if (version >= 4) { w.u8(1); w.u8(2); w.u16(4); }
        w.u16(1); // Tick zero.
        w.u16(1); w.u8(0); w.u8(45);
        if (version >= 4) { w.u8(100); w.u8(100); w.u16(0); }
        w.u16(1); w.u8(5); w.u8(47);
        if (version >= 4) { w.u8(80); w.u8(200); w.u16(-100); }
        w.u16(0);
        w.u16(9); // Tick nine, checks declared length isn't allowed to hide last note.
        w.u16(1); w.u8(0); w.u8(49);
        if (version >= 4) { w.u8(100); w.u8(100); w.u16(0); }
        w.u16(0); w.u16(0);
        if (layerData) {
            for (int i = 0; i < 2; i++) {
                w.string("Layer " + i);
                if (version >= 4) w.u8(1); // Locked layers still play.
                w.u8(muted && i == 1 ? 0 : 100);
                if (version >= 2) w.u8(100);
            }
            w.u8(0);
        }
        return w.toByteArray();
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
    void supportsEveryVersionAndOptionalLayers(int version) throws Exception {
        for (boolean layerData : new boolean[]{false, true}) {
            var song = NbsReader.decode(fixture(version, layerData, false), "fallback.nbs");
            assertEquals(version, song.version()); assertEquals("中文测试", song.name());
            assertEquals(12.5, song.tempo()); assertEquals(3, song.notes().size());
            assertEquals(10, song.length()); assertEquals(0, song.notes().getFirst().tick());
            assertEquals(9, song.notes().getLast().tick());
            if (version >= 4) {
                assertTrue(song.loop()); assertEquals(2, song.maxLoops()); assertEquals(4, song.loopStart());
                assertEquals(-100, song.notes().get(1).cents());
            }
        }
    }
    @Test void mutedLayersAreSkippedButLockedLayersAreNot() throws Exception {
        assertEquals(2, NbsReader.decode(fixture(5, true, true), "f").notes().size());
        assertEquals(3, NbsReader.decode(fixture(5, true, false), "f").notes().size());
    }
    @Test void rejectsEveryTruncatedMandatoryPrefix() {
        byte[] data = fixture(5, false, false);
        for (int length = 0; length < data.length; length++) {
            byte[] truncated = Arrays.copyOf(data, length);
            assertThrows(IOException.class, () -> NbsReader.decode(truncated, "f"), "length=" + length);
        }
    }
    @Test void rejectsPartialOptionalLayerSection() {
        byte[] data = fixture(5, true, false);
        assertThrows(IOException.class, () -> NbsReader.decode(Arrays.copyOf(data, data.length - 3), "f"));
    }
    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
    void acceptsZeroPaddingAfterCompleteOptionalSections(int version) throws Exception {
        byte[] original = fixture(version, true, false);
        byte[] padded = Arrays.copyOf(original, 8192);
        assertEquals(NbsReader.decode(original, "f"), NbsReader.decode(padded, "f"));
        padded[padded.length - 1] = 1;
        assertThrows(IOException.class, () -> NbsReader.decode(padded, "f"));
    }
    @Test void recoversClassicZeroAndUnderstatedLayerHeaders() throws Exception {
        for (int declared : new int[]{0, 1}) {
            byte[] data = fixture(0, false, false);
            data[2] = (byte) declared; data[3] = 0;
            var song = NbsReader.decode(data, "f");
            assertEquals(3, song.notes().size());
            assertEquals(1, song.notes().get(1).layer());
        }
        // Recover modern note-only layers without inventing extra metadata records.
        byte[] modern = fixture(5, false, false); modern[6] = 0; modern[7] = 0;
        assertEquals(3, NbsReader.decode(modern, "f").notes().size());
    }
    @Test void classicRecoveryPreservesDeclaredMutedLayersAndCustomClassification() throws Exception {
        byte[] data = fixture(0, true, true);
        var song = NbsReader.decode(Arrays.copyOf(data, 4096), "f");
        assertEquals(2, song.notes().size(), "Padding must not unmute real layer metadata");
        assertEquals(10, song.vanillaInstruments(), "Classic custom instrument boundary must not change");
    }
    @Test void gzipWrappedSongsRetainMetadataAndValidateIntegrityAndSize() throws Exception {
        byte[] raw = fixture(5, true, false);
        var output = new ByteArrayOutputStream();
        try (var gzip = new java.util.zip.GZIPOutputStream(output)) { gzip.write(raw); }
        byte[] compressed = output.toByteArray();
        assertEquals(NbsReader.decode(raw, "f"), NbsReader.decode(compressed, "f"));
        byte[] corrupt = compressed.clone(); corrupt[corrupt.length - 8] ^= 1;
        assertThrows(IOException.class, () -> NbsReader.decode(corrupt, "f"), "Invalid CRC must not be ignored");
        assertThrows(IOException.class, () -> NbsReader.decode(Arrays.copyOf(compressed, compressed.length - 4), "f"));
        output.reset();
        try (var gzip = new java.util.zip.GZIPOutputStream(output)) { gzip.write(new byte[NbsReader.MAX_BYTES + 1]); }
        assertThrows(IOException.class, () -> NbsReader.decode(output.toByteArray(), "f"), "Expansion must be bounded");
    }
    @Test void legacyCountedNamesMetadataIsRecognizedOnlyWhenComplete() throws Exception {
        Writer writer = new Writer(); writer.writeBytes(fixture(0, false, false));
        writer.u16(2); writer.string("OD Guitar"); writer.string("E.Bass (finger)"); writer.u8(0);
        byte[] alternate = writer.toByteArray();
        assertEquals(3, NbsReader.decode(alternate, "f").notes().size());
        assertEquals(3, NbsReader.decode(Arrays.copyOf(alternate, 4096), "f").notes().size());
        assertThrows(IOException.class, () -> NbsReader.decode(Arrays.copyOf(alternate, alternate.length - 3), "f"));
        byte[] nonzero = Arrays.copyOf(alternate, alternate.length + 1); nonzero[nonzero.length - 1] = 7;
        assertThrows(IOException.class, () -> NbsReader.decode(nonzero, "f"));
    }
    @Test void compressedSchematicGetsAnExplicitFileTypeError() throws Exception {
        byte[] nbt = new byte[]{10,0,0,10,0,9,'S','c','h','e','m','a','t','i','c',0,0};
        var output = new ByteArrayOutputStream();
        try (var gzip = new java.util.zip.GZIPOutputStream(output)) { gzip.write(nbt); }
        var error = assertThrows(IOException.class, () -> NbsReader.decode(output.toByteArray(), "f"));
        assertTrue(error.getMessage().contains("schematic"));
    }
    @Test void rejectsBadVersionsAndUnboundedStrings() {
        byte[] data = fixture(5, false, false); data[2] = 7;
        assertThrows(IOException.class, () -> NbsReader.decode(data, "f"));
        byte[] negative = fixture(5, false, false);
        Arrays.fill(negative, 8, 12, (byte) 0xff);
        assertThrows(IOException.class, () -> NbsReader.decode(negative, "f"));
        byte[] zeroTempo = fixture(5, false, false);
        // Locate tempo after four length-prefixed UTF-8 strings.
        int pos = 8;
        var buffer = java.nio.ByteBuffer.wrap(zeroTempo).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 4; i++) { int length = buffer.getInt(pos); pos += 4 + length; }
        zeroTempo[pos] = 0; zeroTempo[pos + 1] = 0;
        assertThrows(IOException.class, () -> NbsReader.decode(zeroTempo, "f"));
    }
}

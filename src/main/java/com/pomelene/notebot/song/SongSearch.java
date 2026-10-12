package com.pomelene.notebot.song;

import com.atilika.kuromoji.ipadic.Tokenizer;
import com.ibm.icu.text.Transliterator;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Built only on the library worker: dictionary loading never blocks rendering or playback. */
public final class SongSearch {
    private final Tokenizer japanese = new Tokenizer();
    private final Transliterator pinyin = Transliterator.getInstance("Han-Latin");
    private final Transliterator romaji = Transliterator.getInstance("Katakana-Latin; Hiragana-Latin");
    public record Entry(Path path, boolean folder, String title, List<String> aliases) {
        public Entry { aliases = List.copyOf(aliases); }
        public boolean matches(String query) {
            String wanted = normalize(query);
            return aliases.stream().anyMatch(alias -> alias.startsWith("@")
                    ? roman(alias.substring(1)).replace('z','s').contains(roman(wanted).replace('z','s'))
                    : alias.contains(wanted) || roman(alias).contains(roman(wanted)));
        }
    }
    public Entry entry(Path path, boolean folder) {
        String filename = path.getFileName().toString();
        String title = folder ? filename : title(path);
        Set<String> aliases = new LinkedHashSet<>();
        for (String source : List.of(filename, title)) {
            aliases.add(normalize(source));
            String chinese = pinyin.transliterate(source);
            aliases.add(normalize(chinese));
            aliases.add(normalize(Normalizer.normalize(chinese,Normalizer.Form.NFD).replace("u\u0308","v")));
            StringBuilder initials = new StringBuilder();
            for (String word : chinese.split("[^\\p{L}]+")) if (!word.isEmpty()) initials.append(word.charAt(0));
            aliases.add(normalize(initials.toString()));
            StringBuilder reading = new StringBuilder();
            for (var token : japanese.tokenize(source)) {
                String value = token.getReading();
                reading.append(value == null || value.equals("*") ? token.getSurface() : value);
            }
            String japaneseReading=normalize(romaji.transliterate(reading.toString()));
            aliases.add(japaneseReading);
            // Compound proper titles can voice s -> z even when the dictionary splits their words.
            aliases.add("@"+japaneseReading);
        }
        return new Entry(path, folder, title, new ArrayList<>(aliases));
    }
    public static String normalize(String value) {
        return Normalizer.normalize(Normalizer.normalize(value, Normalizer.Form.NFKC), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
    private static String roman(String value) {
        return value.replace("shi", "si").replace("chi", "ti").replace("tsu", "tu").replace("ji", "zi")
                .replace("ou", "o").replace("oo", "o").replace("uu", "u");
    }
    public static String title(Path file) {
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(file))) {
            raw.mark(2); int a = raw.read(), b = raw.read(); raw.reset();
            InputStream input = a == 0x1f && b == 0x8b ? new GZIPInputStream(raw) : raw;
            int length = u16(input);
            if (length == 0) { int version = input.read(); input.read(); if (version >= 3) u16(input); }
            u16(input);
            byte[] size = input.readNBytes(4); if (size.length != 4) throw new EOFException();
            int n = (size[0]&255) | (size[1]&255)<<8 | (size[2]&255)<<16 | (size[3]&255)<<24;
            if (n < 0 || n > 16384) throw new IOException("Invalid title size");
            byte[] text = input.readNBytes(n); if (text.length != n) throw new EOFException();
            String result = new String(text, StandardCharsets.UTF_8).strip();
            return result.isEmpty() ? file.getFileName().toString() : result;
        } catch (IOException e) { return file.getFileName().toString(); }
    }
    private static int u16(InputStream input) throws IOException {
        int a=input.read(), b=input.read(); if (a<0 || b<0) throw new EOFException(); return a | b<<8;
    }
}

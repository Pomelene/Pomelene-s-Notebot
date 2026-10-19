// SPDX-License-Identifier: MIT
package com.pomelene.notebot.song;

import java.util.Locale;

/** OpenNBS control markers reuse note fields; keep the original bytes separate from music. */
public record NbsControlEvent(Type type, String name, NbsSong.Note raw) {
    public enum Type { TEMPO, SOUND_STOPPER, RAINBOW, COLOR, BACKGROUND_ACCENT, SAVE_POPUP }

    public static Type classify(int version, String name) {
        if (version < 4 || name == null) return null;
        return switch (name) {
            case "Tempo Changer" -> Type.TEMPO;
            case "Sound Stopper" -> Type.SOUND_STOPPER;
            case "Toggle Rainbow" -> Type.RAINBOW;
            case "Toggle Background Accent" -> Type.BACKGROUND_ACCENT;
            case "Show Save Popup" -> Type.SAVE_POPUP;
            default -> name.toLowerCase(Locale.ROOT).startsWith("change color to #") ? Type.COLOR : null;
        };
    }
    /** Zero tempo is invalid: retain the marker but leave the previous tempo in effect. */
    public double tempo() { return Math.abs(raw.cents() / 15.0); }
}

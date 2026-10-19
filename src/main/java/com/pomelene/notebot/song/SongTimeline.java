// SPDX-License-Identifier: MIT
package com.pomelene.notebot.song;

import java.util.Arrays;
import java.util.TreeMap;

/** Piecewise tempo integration in server ticks. Custom tempo scales the entire tempo map. */
public final class SongTimeline {
    private final double[] source, game, rates;

    public SongTimeline(NbsSong song, double tempo) {
        if (!Double.isFinite(tempo) || tempo <= 0 || !Double.isFinite(song.tempo()) || song.tempo() <= 0)
            throw new IllegalArgumentException("Invalid playback tempo");
        var changes = new TreeMap<Integer,Double>();
        changes.put(0,song.tempo());
        // File order breaks ties at one tick deterministically (last marker wins).
        for (var event : song.events()) if (event.type() == NbsControlEvent.Type.TEMPO
                && event.raw().tick() >= 0 && event.tempo() > 0) changes.put(event.raw().tick(),event.tempo());
        source = new double[changes.size()]; game = new double[source.length]; rates = new double[source.length];
        int i = 0;
        for (var change : changes.entrySet()) {
            source[i] = change.getKey();
            if (i > 0) game[i] = game[i-1] + (source[i]-source[i-1])*rates[i-1];
            rates[i] = 20.0/change.getValue() * song.tempo()/tempo;
            i++;
        }
        if (!Double.isFinite(gameTick(song.length())) || gameTick(song.length()) >= Integer.MAX_VALUE-2)
            throw new IllegalArgumentException("Excessive playback duration");
    }
    private static int segment(double[] starts, double position) {
        int found = Arrays.binarySearch(starts,position);
        return found >= 0 ? found : Math.max(0,-found-2);
    }
    public double gameTick(double sourceTick) {
        int i = segment(source,sourceTick);
        return game[i] + (sourceTick-source[i])*rates[i];
    }
    public double sourceTick(double gameTick) {
        int i = segment(game,gameTick);
        return source[i] + (gameTick-game[i])/rates[i];
    }
    public int quantize(double sourceTick) { return (int)Math.round(gameTick(sourceTick)); }
}

// SPDX-License-Identifier: MIT
package com.pomelene.notebot.song;

import java.util.List;

/** Pauses never advance this clock; lag never causes unbounded catch-up bursts. */
public final class PlaybackClock {
    private int tick, repeats;
    private boolean finished;
    public void reset() { tick = 0; repeats = 0; finished = false; }
    public List<NoteKey> current(PlaybackPlan plan) { return plan.frames().getOrDefault(tick, List.of()); }
    public void advance(PlaybackPlan plan, boolean loop, int maxLoops) {
        if (++tick < plan.duration()) return;
        if (loop && (maxLoops == 0 || repeats < maxLoops)) {
            tick = Math.min(plan.loopStart(), plan.duration() - 1);
            repeats++;
        } else finished = true;
    }
    public void seek(PlaybackPlan plan, int target) {
        tick = Math.max(0, Math.min(plan.duration() - 1, target));
        finished = false; repeats = 0;
    }
    public int tick() { return tick; }
    public boolean finished() { return finished; }
}

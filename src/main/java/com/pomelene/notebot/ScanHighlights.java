// SPDX-License-Identifier: MIT
package com.pomelene.notebot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.GizmoStyle;
import java.util.*;

/** Client-only diagnostics. No block updates or network packets. */
public final class ScanHighlights {
    public static final int LIMIT = 256;
    public enum Filter { ALL, USABLE, BLOCKED, OUT_OF_RANGE, UNSUPPORTED }
    public record Mark(BlockPos pos, int color) {}
    private boolean enabled;
    private int instrument = -1;
    private Filter filter = Filter.ALL;
    private BlockPos selected;
    private ClientLevel level;
    public boolean enabled() { return enabled; }
    public void toggle(Minecraft mc) { enabled = !enabled; level = mc.level; }
    public void select(int instrument, Filter filter) { this.instrument = instrument; this.filter = filter; selected = null; }
    public void locate(Minecraft mc, BlockPos pos) { selected = pos.immutable(); enabled = true; level = mc.level; }
    public List<Mark> marks(BlockScanner.Report report) {
        if (!enabled || report == null || !report.inWorld()) return List.of();
        List<Mark> result = new ArrayList<>();
        for (var e : report.excluded()) {
            if (!accept(e.pos(), e.instrument()) || (filter != Filter.ALL && !filter.name().equals(e.reason().name()))) continue;
            result.add(new Mark(e.pos(), switch (e.reason()) {
                case BLOCKED -> 0xFFFF5555; case OUT_OF_RANGE -> 0xFFFFAA33; case UNSUPPORTED -> 0xFFCC77FF;
            }));
            if (result.size() >= LIMIT) break;
        }
        if (result.size() >= LIMIT) return List.copyOf(result);
        if (filter == Filter.ALL || filter == Filter.USABLE) for (var c : report.usable()) {
            if (accept(c.pos(), c.key().instrument())) result.add(new Mark(c.pos(),
                    report.matches().containsValue(c.pos()) ? 0xFF55FF99 : 0xFFFFFF55));
            if (result.size() >= LIMIT) return List.copyOf(result);
        }
        return List.copyOf(result);
    }
    private boolean accept(BlockPos pos, int index) {
        return (selected == null || selected.equals(pos)) && (instrument == -1 || (instrument == -3 ? index < 0 : instrument == index));
    }
    public void tick(Minecraft mc, BlockScanner.Report report) {
        if (!enabled) return;
        if (mc.level == null || mc.level != level || mc.player == null) { enabled = false; selected = null; return; }
        try (var ignored = mc.collectPerTickGizmos()) {
            for (var mark : marks(report)) {
                if (!mc.level.hasChunkAt(mark.pos()) || !mc.level.getBlockState(mark.pos()).is(net.minecraft.world.level.block.Blocks.NOTE_BLOCK)) continue;
                Gizmos.cuboid(mark.pos(), 0.003f, GizmoStyle.stroke(mark.color(), 2)).setAlwaysOnTop();
                if (selected != null) Gizmos.billboardTextOverBlock(
                        selected.getX()+", "+selected.getY()+", "+selected.getZ(), selected, 1, mark.color(), 0.02f).setAlwaysOnTop();
            }
        }
    }
}

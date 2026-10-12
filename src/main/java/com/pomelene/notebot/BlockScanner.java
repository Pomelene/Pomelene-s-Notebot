// SPDX-License-Identifier: MIT
package com.pomelene.notebot;

import com.pomelene.notebot.song.NoteKey;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.phys.Vec3;
import java.util.*;

public final class BlockScanner {
    public static final double DEFAULT_RADIUS = 6.0;
    public static final double MAX_RADIUS = 64.0;
    public static double boundedRadius(double value) {
        return Double.isFinite(value) ? Math.max(1.0, Math.min(MAX_RADIUS, value)) : DEFAULT_RADIUS;
    }
    // First 16 entries follow NBS ordering. Copper entries are internal scan/tuning IDs;
    // a legacy NBS custom-instrument ID must not automatically become a copper instrument.
    public static final NoteBlockInstrument[] INSTRUMENTS = {
        NoteBlockInstrument.HARP, NoteBlockInstrument.BASS, NoteBlockInstrument.BASEDRUM,
        NoteBlockInstrument.SNARE, NoteBlockInstrument.HAT, NoteBlockInstrument.GUITAR,
        NoteBlockInstrument.FLUTE, NoteBlockInstrument.BELL, NoteBlockInstrument.CHIME,
        NoteBlockInstrument.XYLOPHONE, NoteBlockInstrument.IRON_XYLOPHONE,
        NoteBlockInstrument.COW_BELL, NoteBlockInstrument.DIDGERIDOO,
        NoteBlockInstrument.BIT, NoteBlockInstrument.BANJO, NoteBlockInstrument.PLING,
        NoteBlockInstrument.TRUMPET, NoteBlockInstrument.TRUMPET_EXPOSED,
        NoteBlockInstrument.TRUMPET_WEATHERED, NoteBlockInstrument.TRUMPET_OXIDIZED
    };
    public record Candidate(BlockPos pos, NoteKey key) {}
    public enum Reason { BLOCKED, OUT_OF_RANGE, UNSUPPORTED }
    /** A single primary exclusion, using the same priority as playback eligibility. */
    public record Excluded(BlockPos pos, int instrument, int pitch, Reason reason, String detail) {}
    public record InstrumentReport(int instrument, int boxes, Set<Integer> pitches,
                                   Set<Integer> required, Set<Integer> missing, List<Excluded> excluded) {
        public Set<Integer> missingScale() {
            Set<Integer> result = new TreeSet<>();
            for (int i = 0; i <= 24; i++) if (!pitches.contains(i)) result.add(i);
            return Collections.unmodifiableSet(result);
        }
    }
    public record Report(List<Candidate> usable, Map<NoteKey, BlockPos> matches,
                         Set<NoteKey> missing, int blocked, int outOfRange, int unsupported,
                         List<Excluded> excluded, int unloadedChunks, boolean inWorld) {
        public Report {
            usable = List.copyOf(usable); matches = Map.copyOf(matches);
            missing = Collections.unmodifiableSet(new TreeSet<>(missing));
            excluded = List.copyOf(excluded);
        }
        public InstrumentReport instrument(int index) {
            Set<Integer> pitches = new TreeSet<>(), required = new TreeSet<>(), absent = new TreeSet<>();
            int boxes = 0;
            for (var c : usable) if (c.key().instrument() == index) { boxes++; pitches.add(c.key().pitch()); }
            for (var key : matches.keySet()) if (key.instrument() == index) required.add(key.pitch());
            for (var key : missing) if (key.instrument() == index) { required.add(key.pitch()); absent.add(key.pitch()); }
            return new InstrumentReport(index, boxes, Collections.unmodifiableSet(pitches),
                    Collections.unmodifiableSet(required), Collections.unmodifiableSet(absent),
                    excluded.stream().filter(e -> e.instrument() == index).toList());
        }
    }
    private BlockScanner() {}

    public static Report scan(Minecraft client, Set<NoteKey> required) {
        return scan(client, required, DEFAULT_RADIUS);
    }

    public static Report scan(Minecraft client, Set<NoteKey> required, double scanRadius) {
        List<Candidate> usable = new ArrayList<>();
        List<Excluded> excluded = new ArrayList<>();
        int unloadedChunks = 0;
        scanRadius = boundedRadius(scanRadius);
        int blocked = 0, far = 0, unsupported = 0;
        if (client.level != null && client.player != null) {
            int radius = (int) Math.ceil(scanRadius) + 1;
            BlockPos origin = BlockPos.containing(client.player.getEyePosition());
            int minX = origin.getX() - radius, maxX = origin.getX() + radius;
            int minY = origin.getY() - radius, maxY = origin.getY() + radius;
            int minZ = origin.getZ() - radius, maxZ = origin.getZ() + radius;
            var cursor = new BlockPos.MutableBlockPos();
            // Skip unloaded chunks and palettes without note blocks instead of visiting
            // the entire 131-cubed region every second at the maximum radius.
            for (int cz = Math.floorDiv(minZ, 16); cz <= Math.floorDiv(maxZ, 16); cz++) {
                for (int cx = Math.floorDiv(minX, 16); cx <= Math.floorDiv(maxX, 16); cx++) {
                    var chunk = client.level.getChunkSource().getChunk(cx, cz,
                            net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
                    if (chunk == null) { unloadedChunks++; continue; }
                    var sections = chunk.getSections();
                    for (int index = 0; index < sections.length; index++) {
                        int bottom = chunk.getSectionYFromSectionIndex(index) * 16;
                        var section = sections[index];
                        if (bottom > maxY || bottom + 15 < minY
                                || !section.maybeHas(state -> state.is(Blocks.NOTE_BLOCK))) continue;
                        for (int y = Math.max(minY, bottom); y <= Math.min(maxY, bottom + 15); y++) {
                            for (int z = Math.max(minZ, cz * 16); z <= Math.min(maxZ, cz * 16 + 15); z++) {
                                for (int x = Math.max(minX, cx * 16); x <= Math.min(maxX, cx * 16 + 15); x++) {
                                    var state = section.getBlockState(x & 15, y & 15, z & 15);
                                    if (!state.is(Blocks.NOTE_BLOCK)) continue;
                                    cursor.set(x, y, z);
                                    int instrument = instrumentIndex(state.getValue(NoteBlock.INSTRUMENT));
                                    int pitch = state.getValue(NoteBlock.NOTE);
                                    if (!withinScanRadius(client, cursor, scanRadius)) {
                                        far++; excluded.add(new Excluded(cursor.immutable(), instrument, pitch, Reason.OUT_OF_RANGE, "")); continue;
                                    }
                                    var above = client.level.getBlockState(cursor.above());
                                    if (!above.isAir()) {
                                        blocked++; excluded.add(new Excluded(cursor.immutable(), instrument, pitch, Reason.BLOCKED,
                                                above.getBlock().getName().getString())); continue;
                                    }
                                    if (instrument < 0) {
                                        unsupported++; excluded.add(new Excluded(cursor.immutable(), instrument, pitch, Reason.UNSUPPORTED,
                                                state.getValue(NoteBlock.INSTRUMENT).getSerializedName())); continue;
                                    }
                                    usable.add(new Candidate(cursor.immutable(), new NoteKey(instrument, state.getValue(NoteBlock.NOTE))));
                                }
                            }
                        }
                    }
                }
            }
            Vec3 eye = client.player.getEyePosition();
            usable.sort(Comparator.comparingDouble(c -> Vec3.atCenterOf(c.pos()).distanceToSqr(eye)));
        }
        Map<NoteKey, BlockPos> matches = new TreeMap<>();
        for (var c : usable) if (required.contains(c.key())) matches.putIfAbsent(c.key(), c.pos());
        Set<NoteKey> missing = new TreeSet<>(required);
        missing.removeAll(matches.keySet());
        return new Report(usable, matches, missing, blocked, far, unsupported, excluded, unloadedChunks,
                client.level != null && client.player != null);
    }

    public static boolean accessible(Minecraft client, BlockPos pos, int instrument, double radius) {
        if (instrument < 0 || instrument >= INSTRUMENTS.length || client.level == null || client.player == null || !client.level.hasChunkAt(pos)
                || !withinScanRadius(client, pos, radius)) return false;
        var state = client.level.getBlockState(pos);
        return state.is(Blocks.NOTE_BLOCK) && state.getValue(NoteBlock.INSTRUMENT) == INSTRUMENTS[instrument]
                && client.level.getBlockState(pos.above()).isAir();
    }
    // Eye-to-block bounds, matching vanilla's interaction distance metric.
    public static boolean withinScanRadius(Minecraft client, BlockPos pos, double radius) {
        if (client.player == null) return false;
        Vec3 eye = client.player.getEyePosition();
        double dx = Math.max(0, Math.max(pos.getX() - eye.x, eye.x - pos.getX() - 1));
        double dy = Math.max(0, Math.max(pos.getY() - eye.y, eye.y - pos.getY() - 1));
        double dz = Math.max(0, Math.max(pos.getZ() - eye.z, eye.z - pos.getZ() - 1));
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }
    public static boolean matches(Minecraft client, BlockPos pos, NoteKey key, double radius) {
        return accessible(client, pos, key.instrument(), radius)
                && client.level.getBlockState(pos).getValue(NoteBlock.NOTE) == key.pitch();
    }
    public static int instrumentIndex(NoteBlockInstrument instrument) {
        for (int i = 0; i < INSTRUMENTS.length; i++) if (INSTRUMENTS[i] == instrument) return i;
        return -1;
    }
}

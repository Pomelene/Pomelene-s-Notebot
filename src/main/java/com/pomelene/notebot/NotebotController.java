// SPDX-License-Identifier: MIT
package com.pomelene.notebot;

import com.pomelene.notebot.mixin.GameModeInvoker;
import com.pomelene.notebot.song.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class NotebotController {
    public static final double MAX_TUNING_RATE = 80.0;
    private static final Logger LOG = LoggerFactory.getLogger("pomelene_notebot");
    private final Minecraft client;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Notebot song loader"); t.setDaemon(true); return t;
    });
    private final Path songsDirectory = FabricLoader.getInstance().getGameDir().resolve("notebot/songs");
    private final Path preferences = FabricLoader.getInstance().getConfigDir().resolve("pomelene-notebot.properties");
    private final SongLibrary library=new SongLibrary(songsDirectory);
    private final FolderOpener folderOpener=new FolderOpener();
    private Component folderMessage=Component.empty();
    private final PlaybackClock clock = new PlaybackClock();
    private final LinkedHashMap<NoteKey, BlockPos> tuning = new LinkedHashMap<>();
    private List<Path> files = List.of();
    private NbsSong song;
    private PlaybackPlan plan;
    private BlockScanner.Report report;
    private final ScanHighlights highlights=new ScanHighlights();
    public ScanHighlights highlights(){return highlights;}
    private Map<NoteKey, BlockPos> mapping = Map.of();
    private Map<NoteKey, BlockPos> fullScaleTargets = Map.of();
    private Component status = text("idle");
    private State state = State.IDLE;
    private ClientLevel sessionLevel;
    private int scanTicks, loadId, waitingTicks, expectedPitch = -1, tuningClicks;
    private boolean foldOctaves, repeat, directPackets;
    private boolean originalSpeed = true;
    private double customTempo = 20;
    private CompatibilityOptions compatibility = CompatibilityOptions.STRICT;
    private SmartAssignment.Preference assignmentPreference=SmartAssignment.Preference.BALANCED;
    private record LoadedSong(NbsSong song, PlaybackPlan plan) {}
    private double scanRadius = 6.0;
    private double tuningRate = 20.0, tuningBudget;
    public enum State { IDLE, LOADING, READY, PLAYING, PAUSED, TUNING }

    public NotebotController(Minecraft client) {
        this.client = client;
        try {
            Files.createDirectories(songsDirectory);
            Properties p = new Properties();
            if (Files.exists(preferences)) try (var input = Files.newInputStream(preferences)) { p.load(input); }
            originalSpeed = Boolean.parseBoolean(p.getProperty("originalSpeed", "true"));
            try {assignmentPreference=SmartAssignment.Preference.valueOf(p.getProperty("assignmentPreference","BALANCED"));}
            catch(IllegalArgumentException ignored){assignmentPreference=SmartAssignment.Preference.BALANCED;}
            try { customTempo = boundedTempo(Double.parseDouble(p.getProperty("customTempo", "20"))); }
            catch (NumberFormatException ignored) { customTempo = 20; }
            foldOctaves = Boolean.parseBoolean(p.getProperty("foldOctaves", "false"));
            directPackets = Boolean.parseBoolean(p.getProperty("directPackets", "false"));
            try { scanRadius = boundedRadius(Double.parseDouble(p.getProperty("scanRadius", "6.0"))); }
            catch (NumberFormatException ignored) { scanRadius = 6.0; }
            try { tuningRate = boundedTuningRate(Double.parseDouble(p.getProperty("tuningRate", "20.0"))); }
            catch (NumberFormatException ignored) { tuningRate = 20.0; }
            // Bundle a tiny original test melody, but do not overwrite user files.
            Path demo = songsDirectory.resolve("notebot-demo.nbs");
            if (!Files.exists(demo)) try (var input = getClass().getResourceAsStream("/notebot-demo.nbs")) {
                if (input != null) Files.copy(input, demo);
            }
        } catch (IOException e) { LOG.error("Cannot initialize song folder", e); status = text("io_error", e.getMessage()); }
        refreshFiles();
        library.refresh();
    }
    public static Component text(String key, Object... args) { return Component.translatable("notebot." + key, args); }
    public Path directory() { return songsDirectory; }
    public SongLibrary library(){return library;}
    public boolean openingFolder(){return folderOpener.busy();}
    public Component folderMessage(){return folderMessage;}
    public void clearFolderMessage(){folderMessage=Component.empty();}
    public void openFolder(){
        if(folderOpener.busy())return;
        Path path=library.snapshot().directory();folderMessage=text("folder_opening");
        folderOpener.open(path).whenComplete((unused,error)->client.execute(()->{
            if(error==null)folderMessage=text("folder_requested");
            else {
                Throwable cause=error instanceof CompletionException?error.getCause():error;
                folderMessage=text(cause instanceof TimeoutException?"folder_timeout":"folder_failed");
                LOG.warn("Could not launch file manager for {}",path,cause);
            }
        }));
    }
    public void copyFolderPath(){client.keyboardHandler.setClipboard(library.snapshot().directory().toString());folderMessage=text("folder_path_copied");}
    public void shutdown(){folderOpener.close();library.close();io.shutdownNow();}
    public boolean createFolder(String name) {
        try{library.createFolder(name);return true;}
        catch(IOException | InvalidPathException e){status=text("io_error",e.getMessage());return false;}
    }
    public List<Path> files() { return files; }
    public NbsSong song() { return song; }
    public PlaybackPlan plan() { return plan; }
    public BlockScanner.Report report() { return report; }
    public Component status() { return status; }
    public State state() { return state; }
    public boolean foldOctaves() { return foldOctaves; }
    public boolean repeat() { return repeat; }
    public boolean directPackets() { return directPackets; }
    public void toggleDirectPackets() { directPackets = !directPackets; savePreferences(); }
    public double scanRadius() { return scanRadius; }
    public double tuningRate() { return tuningRate; }
    private static double boundedTuningRate(double number) {
        return Double.isFinite(number) ? Math.max(1, Math.min(MAX_TUNING_RATE, number)) : 20;
    }
    public void setTuningRate(double number) {
        double updated = boundedTuningRate(number);
        if (updated == tuningRate) return;
        tuningRate = updated; tuningBudget = 0; savePreferences();
    }
    public boolean originalSpeed() { return originalSpeed; }
    public double customTempo() { return customTempo; }
    public double durationSeconds() { return plan == null ? 0 : plan.duration() / 20.0; }
    public double progress() { return plan == null ? 0 : clock.tick() / (double)Math.max(1,plan.duration()-1); }
    private static double boundedTempo(double v) { return Double.isFinite(v) ? Math.max(0.1,Math.min(200,v)) : 20; }
    public void toggleSpeedMode() { originalSpeed = !originalSpeed; savePreferences(); recompile(); }
    public void setCustomTempo(double value) {
        customTempo = boundedTempo(value); savePreferences(); if (!originalSpeed) recompile();
    }
    public void seek(double fraction) {
        if (plan == null || !plan.playable() || state == State.LOADING || state == State.TUNING || !Double.isFinite(fraction)) return;
        clock.seek(plan, (int)Math.round(Math.max(0,Math.min(1,fraction))*(plan.duration()-1)));
        if (state == State.READY) state = State.PAUSED;
    }
    public CompletableFuture<SmartAssignment.Result> suggestInstruments(NbsSong source) {
        var preference=assignmentPreference;
        return CompletableFuture.supplyAsync(() -> SmartAssignment.analyze(source,preference),io);
    }
    public SmartAssignment.Preference assignmentPreference(){return assignmentPreference;}
    public void setAssignmentPreference(SmartAssignment.Preference preference){assignmentPreference=preference;savePreferences();}
    public void applyCompatibility(CompatibilityOptions options, boolean fold) {
        compatibility = options; foldOctaves = fold; savePreferences(); recompile();
    }
    private void recompile() {
        if (song == null) return;
        double position = progress(); boolean resume = state == State.PLAYING;
        stop(false); int id = ++loadId; state = State.LOADING;
        NbsSong source = song; boolean fold = foldOctaves;
        CompatibilityOptions options = compatibility;
        double tempo = originalSpeed ? source.tempo() : customTempo;
        CompletableFuture.supplyAsync(() -> PlaybackPlan.compile(source,fold,options,tempo),io)
            .whenComplete((compiled,error) -> client.execute(() -> {
                if (id != loadId) return;
                if (error != null) { plan = null; state = State.READY; status = text("load_error",error.getMessage()); return; }
                plan = compiled; clock.seek(plan,(int)Math.round(position*(plan.duration()-1)));
                state = State.PAUSED; scan(); status = text(plan.playable()?"loaded":"unsupported_song",song.name());
                if (resume && plan.playable()) togglePlayback();
            }));
    }
    public double seconds() { return clock.tick() / 20.0; }
    public void refreshFiles() {
        try (var stream = Files.list(songsDirectory)) {
            files = stream.filter(Files::isRegularFile).filter(p -> isNbs(p))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT))).toList();
        } catch (IOException e) { status = text("io_error", e.getMessage()); }
    }
    private static boolean isNbs(Path path) { return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".nbs"); }

    public void load(Path path) {
        stop(false);
        int id = ++loadId;
        state = State.LOADING; status = text("loading", path.getFileName());
        compatibility = CompatibilityOptions.STRICT;
        boolean fold = foldOctaves, useOriginal = originalSpeed; double tempo = customTempo;
        CompletableFuture.supplyAsync(() -> {
            try {
                NbsSong loaded = NbsReader.read(path);
                return new LoadedSong(loaded,PlaybackPlan.compile(loaded,fold,CompatibilityOptions.STRICT,useOriginal?loaded.tempo():tempo));
            } catch (IOException e) { throw new CompletionException(e); }
        }, io).whenComplete((loaded, error) -> client.execute(() -> {
            if (id != loadId) return;
            if (error != null) {
                song = null; plan = null; report = null; state = State.IDLE;
                Throwable cause = error.getCause() == null ? error : error.getCause();
                status = text("load_error", cause.getMessage()); LOG.warn("NBS load failed: {}", path, cause);
            } else {
                song = loaded.song(); repeat = song.loop(); plan = loaded.plan();
                state = State.READY; clock.reset(); scan();
                status = text(plan.playable() ? "loaded" : "unsupported_song", song.name());
                if (!plan.playable()) MinecraftPlatform.setScreen(client,new CompatibilityScreen(MinecraftPlatform.screen(client),this));
            }
        }));
    }
    public void importFiles(List<Path> paths) {
        for (Path source : paths) {
            if (!isNbs(source)) continue;
            try {
                if (!Files.isRegularFile(source) || Files.size(source) > NbsReader.MAX_BYTES) throw new IOException("Invalid file size");
                NbsReader.read(source); // Validate before importing.
                Path target = library.snapshot().directory().resolve(source.getFileName());
                if (!source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) Files.copy(source, target);
                refreshFiles(); library.refresh(); load(target);
            } catch (IOException e) { status = text("io_error", e.getMessage()); }
        }
    }
    public void toggleFold() {
        foldOctaves = !foldOctaves; savePreferences(); recompile();
    }
    private static double boundedRadius(double value) {
        return BlockScanner.boundedRadius(value);
    }
    public void setScanRadius(double value) {
        double updated = boundedRadius(value);
        if (updated == scanRadius) return;
        stop(false); scanRadius = updated; savePreferences(); scan();
    }
    private void savePreferences() {
        Properties p = new Properties();
        try {
            Files.createDirectories(preferences.getParent());
            if (Files.exists(preferences)) try (var input = Files.newInputStream(preferences)) { p.load(input); }
            p.setProperty("foldOctaves", Boolean.toString(foldOctaves));
            p.setProperty("assignmentPreference",assignmentPreference.name());
            p.setProperty("directPackets", Boolean.toString(directPackets));
            p.setProperty("scanRadius", Double.toString(scanRadius));
            p.setProperty("tuningRate", Double.toString(tuningRate));
            p.setProperty("originalSpeed", Boolean.toString(originalSpeed));
            p.setProperty("customTempo", Double.toString(customTempo));
            try (var output = Files.newOutputStream(preferences)) { p.store(output, "Pomelene Notebot"); }
        } catch (IOException e) { status = text("io_error", e.getMessage()); }
    }
    public void toggleRepeat() { repeat = !repeat; }
    public void scan() { report = BlockScanner.scan(client, plan == null ? Set.of() : plan.required(), scanRadius); scanTicks = 0; }
    public void togglePlayback() {
        if (state == State.PLAYING) { state = State.PAUSED; status = text("paused"); return; }
        if (state == State.LOADING || state == State.TUNING) return;
        if (!checkWorld()) return;
        if (plan == null || !plan.playable()) { say("unsupported_song"); return; }
        scan();
        if (!report.missing().isEmpty()) { say("missing", report.missing().size()); return; }
        if (state != State.PAUSED) clock.reset();
        mapping = report.matches(); sessionLevel = client.level;
        state = State.PLAYING; status = text("playing", song.name());
    }
    private boolean checkWorld() {
        if (client.player == null || client.level == null || client.gameMode == null || client.getConnection() == null) {
            say("need_world"); return false;
        }
        // Left clicks in Creative destroy note blocks; Adventure may prohibit hitting them.
        if (client.gameMode.getPlayerMode() != GameType.SURVIVAL) { say("need_survival"); return false; }
        if (!client.player.isAlive()) { say("need_alive"); return false; }
        // Efficient tools can break a note block instantly even in Survival.
        if (!directPackets && !client.player.getMainHandItem().isEmpty()) { say("need_empty_hand"); return false; }
        return true;
    }
    public void stop(boolean announce) {
        ++loadId; clock.reset(); tuning.clear(); mapping = Map.of(); expectedPitch = -1;
        waitingTicks = 0; tuningBudget = 0;
        fullScaleTargets = Map.of();
        sessionLevel = null; state = song == null ? State.IDLE : State.READY;
        status = text("stopped"); if (announce) say("stopped");
    }

    /** Explicit button only: assign each required pitch a distinct same-instrument block. */
    public void tune() {
        if (state == State.LOADING || !checkWorld()) return;
        if (client.player.isShiftKeyDown()) { say("no_sneak_tuning"); return; }
        if (plan == null || !plan.playable()) { say("unsupported_song"); return; }
        stop(false); scan();
        Set<BlockPos> used = new HashSet<>(report.matches().values());
        var targets = new LinkedHashMap<NoteKey, BlockPos>();
        for (NoteKey key : report.missing()) {
            BlockScanner.Candidate best = null;
            for (var c : report.usable()) {
                if (c.key().instrument() != key.instrument() || used.contains(c.pos())) continue;
                if (best == null || tuningCost(c.key().pitch(), key.pitch()) < tuningCost(best.key().pitch(), key.pitch())) best = c;
            }
            if (best == null) { say("not_enough_blocks", instrumentName(key.instrument())); return; }
            targets.put(key, best.pos()); used.add(best.pos());
        }
        if (targets.isEmpty()) { status = text("ready"); return; }
        tuning.putAll(targets); sessionLevel = client.level;
        state = State.TUNING; tuningClicks = 0; tuningBudget = 1 - tuningRate / 20.0; status = text("tuning");
    }
    private static int tuningCost(int from, int to) { return (to - from + 25) % 25; }

    /** One explicit pass over every instrument with enough usable blocks for all 25 pitches. */
    public void tuneFullScale() {
        if (state == State.LOADING || !checkWorld()) return;
        if (client.player.isShiftKeyDown()) { say("no_sneak_tuning"); return; }
        stop(false); scan();
        var assigned = new LinkedHashMap<NoteKey, BlockPos>();
        for (int instrument = 0; instrument < BlockScanner.INSTRUMENTS.length; instrument++) {
            final int index = instrument;
            var candidates = report.usable().stream().filter(c -> c.key().instrument() == index).toList();
            if (candidates.size() < 25) continue;
            var used = new HashSet<BlockPos>();
            // Keep one already-correct block for each pitch before allocating duplicate pitches.
            for (var candidate : candidates) {
                if (assigned.putIfAbsent(candidate.key(), candidate.pos()) == null) used.add(candidate.pos());
            }
            for (int pitch = 0; pitch < 25; pitch++) {
                var key = new NoteKey(instrument, pitch);
                if (assigned.containsKey(key)) continue;
                BlockScanner.Candidate best = null;
                for (var candidate : candidates) {
                    if (used.contains(candidate.pos())) continue;
                    if (best == null || tuningCost(candidate.key().pitch(), pitch) < tuningCost(best.key().pitch(), pitch))
                        best = candidate;
                }
                if (best == null) throw new IllegalStateException("Full scale requires 25 distinct blocks");
                used.add(best.pos()); assigned.put(key, best.pos()); tuning.put(key, best.pos());
            }
        }
        int instruments = assigned.size() / 25;
        if (instruments == 0) { say("full_scale_none"); return; }
        if (tuning.isEmpty()) { status = text("full_scale_done", instruments); return; }
        fullScaleTargets = Map.copyOf(assigned); sessionLevel = client.level;
        state = State.TUNING; tuningClicks = 0; tuningBudget = 1 - tuningRate / 20.0;
        status = text("full_scale_tuning", instruments, tuning.size());
    }

    public void tick() {
        if (sessionLevel != null && (client.level != sessionLevel || client.player == null)) { stop(false); return; }
        if (++scanTicks >= 20 && state != State.LOADING) scan();
        if (state != State.PLAYING && state != State.TUNING) return;
        if (!client.player.isAlive() || client.gameMode == null || client.gameMode.getPlayerMode() != GameType.SURVIVAL) {
            stop(false); say("need_survival"); return;
        }
        if (!directPackets && !client.player.getMainHandItem().isEmpty()) {
            if (state == State.TUNING) stop(false);
            else state = State.PAUSED;
            say("need_empty_hand"); return;
        }
        if (client.isPaused()) return;
        var screen = MinecraftPlatform.screen(client);
        if (!directPackets && (!client.isWindowActive()
                || (screen != null && !(screen instanceof NotebotScreen) && !(screen instanceof ReportScreen)
                    && !(screen instanceof FolderScreen) && !(screen instanceof SpeedScreen)))) {
            if (state == State.PLAYING) { state = State.PAUSED; status = text("paused_ui"); }
            else { stop(false); status = text("tuning_interrupted"); }
            return;
        }
        if (state == State.TUNING) {
            if (client.player.isShiftKeyDown()) { stop(false); say("no_sneak_tuning"); return; }
            tickTuning(); return;
        }
        // Validate the whole arrangement before sending any note in this tick.
        for (var entry : mapping.entrySet()) {
            if (!BlockScanner.matches(client, entry.getValue(), entry.getKey(), scanRadius)) {
                state = State.PAUSED; scan(); say("arrangement_changed"); return;
            }
        }
        for (var note : clock.current(plan)) play(mapping.get(note));
        clock.advance(plan, repeat, song.loop() ? song.maxLoops() : 0);
        if (clock.finished()) { state = State.READY; sessionLevel = null; status = text("finished"); }
    }
    private void tickTuning() {
        // Rate limit actual right-click packets, with at most one outstanding batch.
        // Retain fractional credit for non-integer rates without accumulating a burst during lag.
        tuningBudget = Math.min(1 + tuningRate / 20.0, tuningBudget + tuningRate / 20.0);
        if (tuning.isEmpty()) {
            if (!fullScaleTargets.isEmpty()) {
                for (var entry : fullScaleTargets.entrySet()) {
                    if (!BlockScanner.matches(client, entry.getValue(), entry.getKey(), scanRadius)) {
                        stop(false); scan(); say("arrangement_changed"); return;
                    }
                }
            }
            int instruments = fullScaleTargets.size() / 25;
            fullScaleTargets = Map.of(); state = song == null ? State.IDLE : State.READY;
            sessionLevel = null; scan();
            status = instruments == 0 ? text("tuned") : text("full_scale_done", instruments);
            return;
        }
        var entry = tuning.entrySet().iterator().next();
        NoteKey key = entry.getKey(); BlockPos pos = entry.getValue();
        if (!BlockScanner.accessible(client, pos, key.instrument(), scanRadius)) { stop(false); say("arrangement_changed"); return; }
        int pitch = client.level.getBlockState(pos).getValue(NoteBlock.NOTE);
        if (expectedPitch >= 0) {
            if (pitch != expectedPitch) {
                if (++waitingTicks >= 60) { stop(false); say("tuning_timeout"); }
                return;
            }
            expectedPitch = -1;
            waitingTicks = 0;
        }
        if (pitch == key.pitch()) { tuning.remove(key); tuningClicks = 0; return; }
        if (tuningBudget + 1.0e-9 < 1) return;
        int clicks = Math.min(tuningCost(pitch, key.pitch()), Math.min((int) Math.floor(tuningBudget + 1.0e-9),
                (int) Math.ceil(tuningRate / 20.0)));
        tuningClicks += clicks;
        if (tuningClicks > 50) { stop(false); say("tuning_timeout"); return; }
        tuningBudget -= clicks;
        expectedPitch = (pitch + clicks) % 25; waitingTicks = 0;
        for (int i = 0; i < clicks; i++) {
            predict().notebot$startPrediction(client.level, sequence -> new ServerboundUseItemOnPacket(
                    InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false), sequence));
        }
    }
    private GameModeInvoker predict() { return (GameModeInvoker) client.gameMode; }
    private void play(BlockPos pos) {
        // Do not call startDestroyBlock: its local prediction would mine the block.
        predict().notebot$startPrediction(client.level, sequence -> new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP, sequence));
        client.getConnection().send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, pos, Direction.UP));
    }
    public static Component instrumentName(int instrument) { return text("instrument." + NoteKey.NAMES[instrument]); }
    public List<Component> details() {
        List<Component> lines = new ArrayList<>();
        if (plan != null) {
            for (String problem : plan.problems()) {
                String[] parts = problem.split(":", 2);
                lines.add(text("problem." + parts[0], parts[1]));
            }
            if (plan.foldedNotes() > 0) lines.add(text("folded", plan.foldedNotes()));
        }
        if (report != null) {
            lines.add(text("radius_details", String.format(Locale.ROOT, "%.1f", scanRadius)));
            lines.add(text("scan_summary", report.usable().size(), report.matches().size(), plan == null ? 0 : plan.required().size()));
            lines.add(text("scan_excluded", report.blocked(), report.outOfRange(), report.unsupported()));
            int[] counts = new int[BlockScanner.INSTRUMENTS.length], pitches = new int[BlockScanner.INSTRUMENTS.length];
            for (var candidate : report.usable()) {
                int index = candidate.key().instrument(); counts[index]++;
                pitches[index] |= 1 << candidate.key().pitch();
            }
            for (int index = 0; index < counts.length; index++) {
                if (counts[index] == 0) continue;
                lines.add(text("full_scale_coverage", instrumentName(index), counts[index],
                        Integer.bitCount(pitches[index]), text(counts[index] >= 25 ? "full_scale_enough" : "full_scale_short")));
            }
            for (NoteKey key : report.missing()) lines.add(text("missing_note", instrumentName(key.instrument()), key.pitch()));
        }
        return lines;
    }
    private void say(String key, Object... args) {
        status = text(key, args);
        if (client.player != null) client.player.sendSystemMessage(Component.literal("[Notebot] ").append(status));
    }
}

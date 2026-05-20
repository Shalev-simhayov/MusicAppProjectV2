package lyrify.FileHub;

import lyrify.APIinterface.ApiAggregator;
import lyrify.APIinterface.ApiResult;
import lyrify.FileInterface.FileInterface;
import lyrify.FileInterface.LyrifyException;
import lyrify.FileInterface.TrackMetadata;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

// Central hub for the Lyrify pipeline.
//
// Everything passes through here — the manager doesn't do the work itself,
// it coordinates between the layers and decides what happens next at each step:
//
//   1. Accept file         (FileInterface)
//   2. Scan existing tags  (FileInterface)
//   3. Query all APIs      (ApiAggregator)
//   4. Score results       (scorer — coming soon, stub for now)
//   5a. If score >= accept threshold  → write metadata, done
//   5b. If score >= review threshold  → ask user to confirm, then write
//   5c. If score < ai threshold       → run AI pipeline (Demucs + Whisper)
//   5d. Score AI result               → same accept/review/fail logic
//   5e. If still too low              → return a no-match warning, don't write
//
// Thresholds (all configurable via the Builder):
//   acceptThreshold  0.85  — auto-write without asking
//   reviewThreshold  0.60  — write only after user confirms
//   aiThreshold      0.60  — below this, skip to AI fallback
//                            (same default as reviewThreshold — anything needing
//                             review also triggers AI first)
public final class LyrifyManager {

    // ------------------------------------------------------------------
    // Thresholds
    // ------------------------------------------------------------------

    private final double acceptThreshold; // auto-write above this
    private final double reviewThreshold; // ask user between this and acceptThreshold
    private final double aiThreshold;     // send to AI below this

    // ------------------------------------------------------------------
    // Layer references — each is optional (null = that layer is disabled)
    // ------------------------------------------------------------------

    private final ApiAggregator apiAggregator;
    private final AiClient      aiClient;

    // ------------------------------------------------------------------
    // Constructor (private — use Builder)
    // ------------------------------------------------------------------

    private LyrifyManager(Builder b) {
        this.acceptThreshold = b.acceptThreshold;
        this.reviewThreshold = b.reviewThreshold;
        this.aiThreshold     = b.aiThreshold;
        this.apiAggregator   = b.apiAggregator;
        this.aiClient        = b.aiClient;
    }

    // ------------------------------------------------------------------
    // Core method — process a single track
    // ------------------------------------------------------------------

    // Run a single audio file through the full pipeline and return the result.
    // This is the main entry point — everything else is called from here.
    public PipelineResult process(String filePath) {

        log("=== Processing: " + filePath + " ===");

        // -- Step 1: validate and scan the file --
        Path path;
        TrackMetadata existing;
        try {
            path     = FileInterface.acceptPath(filePath);
            existing = FileInterface.scanMetadata(path);
            log("Existing tags: " + summarise(existing));
        } catch (LyrifyException e) {
            log("ERROR reading file: " + e.getMessage());
            return PipelineResult.noMatch(filePath, 0.0);
        }

        // -- Step 2: query all APIs --
        log("Querying APIs...");
        List<ApiResult> apiResults = apiAggregator.query(path, existing);
        ApiResult bestApi = ApiAggregator.best(apiResults);
        double apiScore = bestApi.isUsable() ? bestApi.confidence() : 0.0;
        log("Best API result: %s (score %.2f)".formatted(bestApi.source(), apiScore));

        // -- Step 3: decide what to do with the API score --

        // Score is good enough — handle directly
        if (apiScore >= aiThreshold) {
            return handleResult(path, bestApi.metadata(), apiScore,
                    PipelineResult.Stage.API, existing);
        }

        // Score is too low — try the AI fallback
        log("API score %.2f below AI threshold %.2f — running AI fallback..."
                .formatted(apiScore, aiThreshold));

        // -- Step 4: AI fallback --
        if (aiClient == null) {
            log("AI client not configured — skipping AI fallback.");
            return PipelineResult.noMatch(filePath, apiScore);
        }

        if (!aiClient.isServerUp()) {
            log("WARNING: AI server is not running. Start server.py and retry.");
            return PipelineResult.noMatch(filePath, apiScore);
        }

        TrackMetadata aiMeta = runAiFallback(path, existing);
        if (aiMeta == null) {
            log("AI fallback produced no result.");
            return PipelineResult.noMatch(filePath, apiScore);
        }

        // -- Step 5: re-query APIs with AI-transcribed lyrics --
        // Whisper gave us the lyric text — use it to search again with
        // better query terms than we had from the original bad tags
        log("Re-querying APIs with AI transcription...");
        List<ApiResult> aiApiResults = apiAggregator.query(aiMeta);
        ApiResult bestAiApi = ApiAggregator.best(aiApiResults);

        // Merge: prefer re-queried API metadata but keep AI lyrics if API has none
        TrackMetadata merged = merge(bestAiApi.isUsable() ? bestAiApi.metadata() : null, aiMeta);
        double aiScore = bestAiApi.isUsable() ? bestAiApi.confidence() : 0.0;

        log("AI pipeline result score: %.2f".formatted(aiScore));

        // -- Step 6: decide what to do with the AI score --
        if (!bestAiApi.isUsable() || aiScore < reviewThreshold) {
            log("AI score %.2f still too low — no accurate match found.".formatted(aiScore));
            return PipelineResult.noMatch(filePath, aiScore);
        }

        return handleResult(path, merged, aiScore, PipelineResult.Stage.AI, existing);
    }

    // ------------------------------------------------------------------
    // Batch processing
    // ------------------------------------------------------------------

    // Process every file in a directory and return one PipelineResult per track.
    // Backs up existing metadata before doing anything.
    public List<PipelineResult> processDirectory(String dirPath, boolean recursive) {
        log("=== Batch scan: " + dirPath + " ===");

        List<Path> files;
        try {
            Path root = FileInterface.acceptPath(dirPath);
            files = FileInterface.fileSearch(root, recursive);
            files = FileInterface.filterByMimeType(files);
        } catch (LyrifyException e) {
            log("ERROR scanning directory: " + e.getMessage());
            return List.of();
        }

        if (files.isEmpty()) {
            log("No audio files found in: " + dirPath);
            return List.of();
        }

        log("Found %d audio files. Backing up existing metadata...".formatted(files.size()));

        // Backup before touching anything
        try {
            Path backup = FileInterface.backupMetadata(files, null);
            log("Backup written: " + backup);
        } catch (LyrifyException e) {
            log("WARNING: backup failed — " + e.getMessage());
        }

        // Process each file and collect results
        return files.stream()
                .map(f -> process(f.toString()))
                .toList();
    }

    // ------------------------------------------------------------------
    // Internal: decide accept / review / reject and write if appropriate
    // ------------------------------------------------------------------

    // Given a metadata result and score, decide whether to auto-write,
    // ask for confirmation, or reject — then return a PipelineResult.
    private PipelineResult handleResult(Path path, TrackMetadata meta,
                                        double score, PipelineResult.Stage stage,
                                        TrackMetadata existing) {
        // Auto-accept — score is high enough, write without asking
        if (score >= acceptThreshold) {
            boolean written = writeMetadata(path, meta);
            return stage == PipelineResult.Stage.API
                    ? PipelineResult.fromApi(path.toString(), meta, score, false, written)
                    : PipelineResult.fromAi(path.toString(), meta, score, false, written);
        }

        // Review zone — score is acceptable but not confident enough to auto-write
        if (score >= reviewThreshold) {
            log("Score %.2f is in review zone [%.2f, %.2f) — asking for confirmation..."
                    .formatted(score, reviewThreshold, acceptThreshold));
            boolean confirmed = askUserConfirmation(path, existing, meta, score);
            boolean written   = confirmed && writeMetadata(path, meta);
            return stage == PipelineResult.Stage.API
                    ? PipelineResult.fromApi(path.toString(), meta, score, !confirmed, written)
                    : PipelineResult.fromAi(path.toString(), meta, score, !confirmed, written);
        }

        // Below review threshold — no write
        return PipelineResult.noMatch(path.toString(), score);
    }

    // ------------------------------------------------------------------
    // Internal: AI fallback — Demucs + Whisper
    // ------------------------------------------------------------------

    // Runs the AI pipeline and returns a TrackMetadata built from the
    // transcribed lyrics + detected language. Returns null on failure.
    private TrackMetadata runAiFallback(Path audioPath, TrackMetadata existing) {
        try {
            AiClient.PipelineAiResult ai = aiClient.runPipeline(
                    audioPath.toString(),
                    null // let Whisper auto-detect the language
            );

            if (ai.text() == null || ai.text().isBlank()) {
                log("Whisper returned empty transcription.");
                return null;
            }

            log("Whisper transcribed %d chars in language '%s'"
                    .formatted(ai.text().length(), ai.language()));

            // Build a TrackMetadata using the transcribed lyrics as a search hint.
            // We carry over any existing tags that weren't blank — they may still
            // be correct even if the full match scored poorly.
            return TrackMetadata.builder(audioPath.toString())
                    .title(existing.title())          // keep existing title hint
                    .artist(existing.artist())         // keep existing artist hint
                    .lyrics(ai.text())                 // NEW: from Whisper
                    .durationSeconds(ai.durationSeconds() > 0 ? ai.durationSeconds() : existing.durationSeconds())
                    .build();

        } catch (Exception e) {
            log("AI fallback error: " + e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Internal: merge two metadata objects
    // ------------------------------------------------------------------

    // Merge API metadata (preferred) with AI metadata (fallback for missing fields).
    // API metadata wins for every field it has — AI fills in the gaps.
    private static TrackMetadata merge(TrackMetadata api, TrackMetadata ai) {
        if (api == null) return ai;
        if (ai  == null) return api;

        // Use the filepath from whichever source has it
        String filepath = api.filepath() != null && !api.filepath().isBlank()
                ? api.filepath() : ai.filepath();

        return TrackMetadata.builder(filepath)
                .title(       firstNonNull(api.title(),        ai.title()))
                .artist(      firstNonNull(api.artist(),       ai.artist()))
                .album(       firstNonNull(api.album(),        ai.album()))
                .albumArtist( firstNonNull(api.albumArtist(),  ai.albumArtist()))
                .trackNumber( firstNonNull(api.trackNumber(),  ai.trackNumber()))
                .year(        firstNonNull(api.year(),         ai.year()))
                .genre(       firstNonNull(api.genre(),        ai.genre()))
                // Lyrics come from AI (Whisper) — API sources rarely return them
                .lyrics(      firstNonNull(ai.lyrics(),        api.lyrics()))
                .durationSeconds(firstNonNullObj(api.durationSeconds(), ai.durationSeconds()))
                .mimeType(    firstNonNull(api.mimeType(),     ai.mimeType()))
                .build();
    }

    // ------------------------------------------------------------------
    // Internal: write metadata to file
    // ------------------------------------------------------------------

    private boolean writeMetadata(Path path, TrackMetadata meta) {
        try {
            Map<String, String> updates = new java.util.LinkedHashMap<>();
            if (meta.title()       != null) updates.put("title",       meta.title());
            if (meta.artist()      != null) updates.put("artist",      meta.artist());
            if (meta.album()       != null) updates.put("album",       meta.album());
            if (meta.albumArtist() != null) updates.put("albumArtist", meta.albumArtist());
            if (meta.trackNumber() != null) updates.put("trackNumber", meta.trackNumber());
            if (meta.year()        != null) updates.put("year",        meta.year());
            if (meta.genre()       != null) updates.put("genre",       meta.genre());
            if (meta.lyrics()      != null) updates.put("lyrics",      meta.lyrics());
            FileInterface.modifyMetadata(path, updates, null);
            log("Metadata written to: " + path.getFileName());
            return true;
        } catch (LyrifyException e) {
            log("ERROR writing metadata: " + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Internal: ask user to confirm a review-zone result
    // ------------------------------------------------------------------

    // Prints a diff of existing vs proposed metadata and asks the user Y/N.
    // In a GUI this would be a dialog — for now it's a console prompt.
    private static boolean askUserConfirmation(Path path, TrackMetadata existing,
                                               TrackMetadata proposed, double score) {
        System.out.println();
        System.out.println("┌─ Review required ─────────────────────────────────────");
        System.out.println("│ File:  " + path.getFileName());
        System.out.printf( "│ Score: %.2f%n", score);
        System.out.println("│");
        System.out.println("│ Field          Current                →  Proposed");
        System.out.println("│ ─────────────────────────────────────────────────────");
        printDiffLine("title",       existing.title(),       proposed.title());
        printDiffLine("artist",      existing.artist(),      proposed.artist());
        printDiffLine("album",       existing.album(),       proposed.album());
        printDiffLine("year",        existing.year(),        proposed.year());
        printDiffLine("genre",       existing.genre(),       proposed.genre());
        printDiffLine("trackNumber", existing.trackNumber(), proposed.trackNumber());
        System.out.println("└───────────────────────────────────────────────────────");
        System.out.print("Apply these changes? [y/N]: ");

        try (Scanner sc = new Scanner(System.in)) {
            String input = sc.nextLine().strip().toLowerCase();
            return input.equals("y") || input.equals("yes");
        } catch (Exception e) {
            return false; // if we can't read input, default to safe no-write
        }
    }

    private static void printDiffLine(String field, String current, String proposed) {
        String cur  = current  != null ? current  : "(none)";
        String prop = proposed != null ? proposed : "(none)";
        if (!cur.equals(prop)) {
            System.out.printf("│ %-15s %-22s →  %s%n", field, truncate(cur, 22), prop);
        }
    }

    // ------------------------------------------------------------------
    // Tiny utilities
    // ------------------------------------------------------------------

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static Double firstNonNullObj(Double a, Double b) {
        return a != null ? a : b;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String summarise(TrackMetadata m) {
        return "'%s' by '%s' (%s)".formatted(
                m.title()  != null ? m.title()  : "?",
                m.artist() != null ? m.artist() : "?",
                m.year()   != null ? m.year()   : "?");
    }

    private static void log(String msg) {
        System.out.println("[Lyrify] " + msg);
    }

    // ------------------------------------------------------------------
    // Builder
    // ------------------------------------------------------------------

    public static final class Builder {
        private ApiAggregator apiAggregator;
        private AiClient      aiClient;
        private double        acceptThreshold = 0.85;
        private double        reviewThreshold = 0.60;
        private double        aiThreshold     = 0.60;

        // Required — at least one API must be configured
        public Builder apiAggregator(ApiAggregator a) { this.apiAggregator = a; return this; }

        // Optional — if not set, AI fallback is skipped
        public Builder aiClient(AiClient a)           { this.aiClient      = a; return this; }

        // Auto-write above this score (default 0.85)
        public Builder acceptThreshold(double t)      { this.acceptThreshold = t; return this; }

        // Ask user to confirm between reviewThreshold and acceptThreshold (default 0.60)
        public Builder reviewThreshold(double t)      { this.reviewThreshold = t; return this; }

        // Trigger AI fallback below this score (default 0.60)
        public Builder aiThreshold(double t)          { this.aiThreshold = t; return this; }

        public LyrifyManager build() {
            if (apiAggregator == null)
                throw new IllegalStateException("apiAggregator is required");
            if (reviewThreshold > acceptThreshold)
                throw new IllegalStateException("reviewThreshold must be <= acceptThreshold");
            return new LyrifyManager(this);
        }
    }
}
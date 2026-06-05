package lyrify.FileHub;

import lyrify.APIinterface.ApiAggregator;
import lyrify.APIinterface.ApiResult;
import lyrify.FileInterface.FileInterface;
import lyrify.FileInterface.LyrifyException;
import lyrify.FileInterface.TrackMetadata;
import lyrify.FileInterface.ScanResult;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

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
//   5c. If score < AI threshold       → run AI pipeline (Demucs + Whisper)
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

        // Skip if already processed by Lyrify and cache is enabled
        if (FileInterface.isAlreadyProcessed(path)) {
            log("Already processed by Lyrify — skipping: " + path.getFileName());
            return PipelineResult.cached(path.toString(), existing);
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
            // Fold all usable API results into one merged metadata object.
            // This collects: title/artist (AcoustID), year/trackNum (MusicBrainz),
            // lyrics (Genius) — whichever source has each field.
            TrackMetadata merged = bestApi.metadata();
            for (ApiResult r : apiResults) {
                if (r != bestApi && r.isUsable() && r.metadata() != null) {
                    merged = mergeAll(merged, r.metadata());
                    log("Merged lyrics: " + (merged.lyrics() != null ? merged.lyrics().substring(0, Math.min(50, merged.lyrics().length())) : "null"));
                }
            }
            return handleResult(path, merged, apiScore,
                    PipelineResult.Stage.API, existing);
        }

        // Score is too low — try the AI fallback
        // Score too low — try generating lyrics via AI
        log("API score %.2f below AI threshold %.2f — generating lyrics via AI..."
                .formatted(apiScore, aiThreshold));


        
        if (aiClient != null && aiClient.isServerUp()) {
            String langHint = detectLanguage(
                    existing.title(),
                    bestApi.isUsable() && bestApi.metadata() != null ? bestApi.metadata().title() : null
            );

            AiClient.PipelineAiResult ai = aiClient.runPipeline(path.toString(), langHint);
            log("AI result — hasText: " + ai.hasText() +
                    ", segments: " + ai.segments().size() +
                    ", duration: " + ai.durationSeconds());
            if (ai.hasText()) {
                log("First 100 chars: " + ai.text().substring(0, Math.min(100, ai.text().length())));
            }
            
            if (ai.hasText()) {
                log("Whisper transcribed %d chars in language '%s', %d segments"
                        .formatted(ai.text().length(), ai.language(), ai.segments().size()));

                try {
                    List<lyrify.FileInterface.LrcLine> lrcLines = new java.util.ArrayList<>();

                    if (!ai.segments().isEmpty()) {
                        // Use real Whisper timestamps — accurate to the second
                        for (AiClient.Segment seg : ai.segments()) {
                            int ms = (int)(seg.start() * 1000);
                            log("Segment: start=" + seg.start() + " ms=" + ms + " text=" + seg.text().substring(0, Math.min(20, seg.text().length())));
                            lrcLines.add(new lyrify.FileInterface.LrcLine(ms, seg.text()));
                        }
                    } else {
                        // Fallback: evenly space lines if no segments available
                        String[] lines = ai.text().split("\r\n");
                        double totalDur = ai.durationSeconds() > 0 ? ai.durationSeconds() : 180.0;
                        double step = totalDur / Math.max(lines.length, 1);
                        for (int i = 0; i < lines.length; i++) {
                            if (!lines[i].isBlank()) {
                                lrcLines.add(new lyrify.FileInterface.LrcLine(
                                        (int)(i * step * 1000), lines[i].strip()));
                            }
                        }
                    }

                    // Build metadata for LRC header — mark as AI generated
                    TrackMetadata lrcMeta = TrackMetadata.builder(path.toString())
                            .title(existing.title())
                            .artist(existing.artist())
                            .album(existing.album())
                            .lyrics("[AI-Generated by Lyrify — Language: " +
                                    (ai.language() != null ? ai.language() : "unknown") + "]\n"
                                    + ai.text())
                            .build();

                    log("Writing LRC with " + lrcLines.size() + " lines, first timestamp: " +
                            (lrcLines.isEmpty() ? "none" : lrcLines.getFirst().timestampMs()));
                    FileInterface.createLrcFile(path, lrcLines, lrcMeta, null);
                    log("AI-generated LRC file saved with " + lrcLines.size() + " timestamped lines.");
                } catch (Exception e) {
                    log("WARNING: LRC generation failed — " + e.getMessage());
                }

                // If we have a usable API result, attach the AI lyrics to it and return
                if (bestApi.isUsable()) {
                    TrackMetadata withLyrics = TrackMetadata.builder(path.toString())
                            .title(bestApi.metadata() != null ? bestApi.metadata().title() : null)
                            .artist(bestApi.metadata() != null ? bestApi.metadata().artist(): null)
                            .album(bestApi.metadata() != null ? bestApi.metadata().album() : null)
                            .albumArtist(bestApi.metadata() != null ? bestApi.metadata().albumArtist() : null)
                            .trackNumber(bestApi.metadata() != null ? bestApi.metadata().trackNumber() : null)
                            .year(bestApi.metadata() != null ? bestApi.metadata().year() : null)
                            .genre(bestApi.metadata() != null ? bestApi.metadata().genre() : null)
                            .lyrics(ai.text())
                            .durationSeconds(bestApi.metadata() != null ? bestApi.metadata().durationSeconds() : null)
                            .build();
                    return handleResult(path, withLyrics, apiScore,
                            PipelineResult.Stage.AI, existing);
                }
            } else {
                log("Whisper returned empty transcription.");
            }
        } else {
            log("WARNING: AI server is not running. Start server.py to enable lyric generation.");
        }

// Fall back to best API result even without lyrics
        if (bestApi.isUsable()) {
            log("Using API result without lyrics: %s (score %.2f)"
                    .formatted(bestApi.source(), apiScore));
            return handleResult(path, bestApi.metadata(), apiScore,
                    PipelineResult.Stage.API, existing);
        }

// Last resort: try existing tags
        if (existing.title() != null || existing.artist() != null) {
            log("Trying existing tags as search hint: " + summarise(existing));
            List<ApiResult> tagResults = apiAggregator.query(existing);
            ApiResult bestTag = ApiAggregator.best(tagResults);
            if (bestTag.isUsable()) {
                log("Found match using existing tags: %s (score %.2f)"
                        .formatted(bestTag.source(), bestTag.confidence()));
                return handleResult(path, bestTag.metadata(), bestTag.confidence(),
                        PipelineResult.Stage.API, existing);
            }
        }

        return PipelineResult.noMatch(filePath, apiScore);
    }

    // ------------------------------------------------------------------
    // Batch processing
    // ------------------------------------------------------------------

    // Process every file in a directory and return one PipelineResult per track.
    // Backs up existing metadata before doing anything.
    public List<PipelineResult> processDirectory(String dirPath, boolean recursive, boolean useCache, boolean doBackup) {
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

        log("Found %d audio files.".formatted(files.size()));

        if (doBackup) {
            try {
                Path backup = FileInterface.backupMetadata(files, null);
                log("Backup written: " + backup);
            } catch (LyrifyException e) {
                log("WARNING: backup failed — " + e.getMessage());
            }
        }

        // Process each file — cached files get a "previously scanned" result,
        // non-cached files go through the full pipeline
        List<ScanResult> scanResults;
        try {
            if (useCache) {
                scanResults = FileInterface.scanWithCache(files, Path.of(dirPath));
            } else {
                scanResults = files.stream()
                        .map(f -> ScanResult.of(f.toString(), null, false))
                        .toList();
            }
        } catch (LyrifyException e) {
            log("WARNING: cache unavailable — " + e.getMessage());
            scanResults = files.stream()
                    .map(f -> ScanResult.of(f.toString(), null, false))
                    .toList();
        }

        List<PipelineResult> results = scanResults.stream()
                .map(sr -> {
                    if (sr.fromCache()) {
                        log("Cache hit — skipping pipeline for: " + sr.path());
                        return PipelineResult.cached(sr.path(), sr.metadata());
                    }
                    return process(sr.path());
                })
                .toList();

        apiAggregator.shutdown();
        return results;
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
            // Rebuild metadata with the correct filepath before writing
            TrackMetadata finalMeta = TrackMetadata.builder(path.toString())
                    .title(meta.title()).artist(meta.artist()).album(meta.album())
                    .albumArtist(meta.albumArtist()).trackNumber(meta.trackNumber())
                    .year(meta.year()).genre(meta.genre()).lyrics(meta.lyrics())
                    .durationSeconds(meta.durationSeconds()).mimeType(meta.mimeType())
                    .build();
            boolean written = writeMetadata(path, finalMeta);
            return stage == PipelineResult.Stage.API
                    ? PipelineResult.fromApi(path.toString(), finalMeta, score, false, written)
                    : PipelineResult.fromAi(path.toString(), finalMeta, score, false, written);
        }

        // Review zone — score is acceptable but not confident enough to auto-write
        if (score >= reviewThreshold) {
            log("Score %.2f is in review zone [%.2f, %.2f) — asking for confirmation..."
                    .formatted(score, reviewThreshold, acceptThreshold));
            boolean confirmed = askUserConfirmation(path, existing, meta, score);
            TrackMetadata finalMeta = TrackMetadata.builder(path.toString())
                    .title(meta.title()).artist(meta.artist()).album(meta.album())
                    .albumArtist(meta.albumArtist()).trackNumber(meta.trackNumber())
                    .year(meta.year()).genre(meta.genre()).lyrics(meta.lyrics())
                    .durationSeconds(meta.durationSeconds()).mimeType(meta.mimeType())
                    .build();
            boolean written = confirmed && writeMetadata(path, finalMeta);
            return stage == PipelineResult.Stage.API
                    ? PipelineResult.fromApi(path.toString(), finalMeta, score, !confirmed, written)
                    : PipelineResult.fromAi(path.toString(), finalMeta, score, !confirmed, written);
        }

        // Below review threshold — no write, but if AI generated lyrics, keep them in result
        if (stage == PipelineResult.Stage.AI && meta != null && meta.lyrics() != null) {
            TrackMetadata finalMeta = TrackMetadata.builder(path.toString())
                    .title(meta.title()).artist(meta.artist()).album(meta.album())
                    .albumArtist(meta.albumArtist()).trackNumber(meta.trackNumber())
                    .year(meta.year()).genre(meta.genre()).lyrics(meta.lyrics())
                    .durationSeconds(meta.durationSeconds()).mimeType(meta.mimeType())
                    .build();
            return PipelineResult.fromAi(path.toString(), finalMeta, score, true, false);
        }
        // Below review threshold — but preserve lyrics if we found any
        if (meta != null && meta.lyrics() != null) {
            TrackMetadata finalMeta = TrackMetadata.builder(path.toString())
                    .title(meta.title()).artist(meta.artist()).album(meta.album())
                    .albumArtist(meta.albumArtist()).trackNumber(meta.trackNumber())
                    .year(meta.year()).genre(meta.genre()).lyrics(meta.lyrics())
                    .durationSeconds(meta.durationSeconds()).mimeType(meta.mimeType())
                    .build();
            return PipelineResult.fromApi(path.toString(), finalMeta, score, true, false);
        }
        return PipelineResult.noMatch(path.toString(), score);
    }

    // ------------------------------------------------------------------

    // Detect likely language from text using Unicode character blocks.
// Returns an ISO-639-1 code Whisper understands, or null for auto-detect.
    private static String detectLanguage(String... texts) {
        for (String text : texts) {
            if (text == null || text.isBlank()) continue;
            for (char c : text.toCharArray()) {
                Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
                if (block == Character.UnicodeBlock.HEBREW)                return "he";
                if (block == Character.UnicodeBlock.ARABIC)                return "ar";
                if (block == Character.UnicodeBlock.CYRILLIC)              return "ru";
                if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) return "zh";
                if (block == Character.UnicodeBlock.HIRAGANA
                        || block == Character.UnicodeBlock.KATAKANA)              return "ja";
                if (block == Character.UnicodeBlock.HANGUL_SYLLABLES)      return "ko";
                if (block == Character.UnicodeBlock.GREEK)                 return "el";
                if (block == Character.UnicodeBlock.THAI)                  return "th";
            }
        }
        return null; // null = let Whisper auto-detect
    }
    // ------------------------------------------------------------------
    // Internal: merge two metadata objects
    // ------------------------------------------------------------------




    // Merge two metadata objects, taking any non-null field from either source.
// Unlike merge(), this doesn't prefer one source over the other —
// it fills in any field that's missing from the primary with whatever the secondary has.
    private static TrackMetadata mergeAll(TrackMetadata primary, TrackMetadata secondary) {
        if (primary   == null) return secondary;
        if (secondary == null) return primary;

        String filepath = primary.filepath() != null && !primary.filepath().isBlank()
                ? primary.filepath() : secondary.filepath();

        return TrackMetadata.builder(filepath)
                .title(       firstNonNull(primary.title(),        secondary.title()))
                .artist(      firstNonNull(primary.artist(),       secondary.artist()))
                .album(       firstNonNull(primary.album(),        secondary.album()))
                .albumArtist( firstNonNull(primary.albumArtist(),  secondary.albumArtist()))
                .trackNumber( firstNonNull(primary.trackNumber(),  secondary.trackNumber()))
                .year(        firstNonNull(primary.year(),         secondary.year()))
                .genre(       firstNonNull(primary.genre(),        secondary.genre()))
                // Genius lyrics always win — they're the most accurate source
                .lyrics(      firstNonNull(secondary.lyrics(),     primary.lyrics()))
                .durationSeconds(firstNonNullObj(primary.durationSeconds(), secondary.durationSeconds()))
                .mimeType(    firstNonNull(primary.mimeType(),     secondary.mimeType()))
                .build();
    }

    // ------------------------------------------------------------------
    // Internal: write metadata to file
    // ------------------------------------------------------------------

    private boolean writeMetadata(Path path, TrackMetadata meta) {
        // Track the final path (may change if renamed)
        Path finalPath = path;
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

// Rename the file to match the new metadata if we have title + artist
            if (meta.title() != null && meta.artist() != null) {
                try {
                    String ext = path.getFileName().toString();
                    ext = ext.contains(".") ? ext.substring(ext.lastIndexOf('.')) : "";
                    String safeName = (meta.artist() + " - " + meta.title())
                            .replaceAll("[\\\\/:*?\"<>|]", "_")
                            .strip();
                    Path newPath = path.getParent().resolve(safeName + ext);
                    if (!newPath.equals(path)) {
                        java.nio.file.Files.move(path, newPath,
                                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                        log("Renamed: " + path.getFileName() + " → " + newPath.getFileName());
                        finalPath = newPath; // use new path for LRC
                    }
                } catch (Exception e) {
                    log("WARNING: rename failed — " + e.getMessage());
                }
            }


            // Write Lyrify processed tag using TXXX frame
            try {
                org.jaudiotagger.audio.AudioFile af =
                        org.jaudiotagger.audio.AudioFileIO.read(finalPath.toFile());
                org.jaudiotagger.tag.Tag tag = af.getTagOrCreateAndSetDefault();
                tag.getClass().getMethod("setField",
                                org.jaudiotagger.tag.FieldKey.class, String[].class)
                        .invoke(tag, org.jaudiotagger.tag.FieldKey.COMMENT,
                                new String[]{"Lyrify-processed"});
                af.commit();
                log("Lyrify processed tag written to: " + finalPath.getFileName());
            } catch (Exception e) {
                log("WARNING: could not write processed tag — " + e.getMessage());
            }

// Generate LRC file using the final (possibly renamed) path
            if (meta.lyrics() != null && !meta.lyrics().isBlank()) {
                try {
                    String[] lines = meta.lyrics().split("\r\n");
                    double totalDur = meta.durationSeconds() != null ? meta.durationSeconds() : 180.0;
                    double step = totalDur / Math.max(lines.length, 1);
                    List<lyrify.FileInterface.LrcLine> lrcLines = new java.util.ArrayList<>();
                    for (int i = 0; i < lines.length; i++) {
                        if (!lines[i].isBlank()) {
                            lrcLines.add(new lyrify.FileInterface.LrcLine(
                                    (int)(i * step * 1000), lines[i].strip()));
                        }
                    }
                    Path lrcPath = FileInterface.createLrcFile(finalPath, lrcLines, meta, null);
                    log("LRC file created: " + lrcPath.getFileName());
                } catch (Exception e) {
                    log("WARNING: LRC generation failed — " + e.getMessage());
                }
            }

            return true;
        } catch (LyrifyException e) {
            log("ERROR writing metadata: " + e.getMessage());
            log("ERROR cause: " + (e.getCause() != null ? e.getCause().getMessage() : "no cause"));
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
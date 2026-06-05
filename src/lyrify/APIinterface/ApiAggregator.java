package lyrify.APIinterface;

import lyrify.FileInterface.TrackMetadata;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

// Runs all enabled API clients and returns results ranked by confidence.
//
// Two-pass approach:
//   Pass 1 — AcoustID fingerprint identifies the song from audio alone.
//             If it returns a usable result, we extract its title/artist
//             and use them to enrich the second pass.
//   Pass 2 — MusicBrainz and Genius run in parallel.
//             They use the BEST available metadata — either from AcoustID
//             (pass 1) or from the file's existing tags — whichever has more.
//
// This means even files with zero tags get identified: AcoustID fingerprints
// the audio, then MusicBrainz and Genius fetch metadata and lyrics
// using the title/artist AcoustID discovered.

public final class ApiAggregator {

    // ------------------------------------------------------------------
    // Fields
    // ------------------------------------------------------------------

    // null means that source is disabled
    private final AcoustIdClient    acoustId;
    private final MusicBrainzClient musicBrainz;
    private final GeniusClient      genius;

    // If AcoustID returns confidence >= this, skip the other APIs entirely.
    // Set to > 1.0 to disable short-circuiting.
    private final double shortCircuitThreshold;

    private final ExecutorService executor;

    // ------------------------------------------------------------------
    // Constructor (private — use Builder)
    // ------------------------------------------------------------------

    private ApiAggregator(Builder b) {
        this.acoustId              = b.acoustId;
        this.musicBrainz           = b.musicBrainz;
        this.genius                = b.genius;
        this.shortCircuitThreshold = b.shortCircuitThreshold;
        this.executor              = Executors.newVirtualThreadPerTaskExecutor();
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------


     //Two-pass query:
    // Pass 1 — AcoustID fingerprint (audio-based, no tags needed)
    //Pass 2 — MusicBrainz / Genius using the best available
    // metadata (AcoustID result if usable, else existing tags)

    public List<ApiResult> query(Path audioFile, TrackMetadata existingMetadata) {
        List<ApiResult> results = new ArrayList<>();

        // -- Existing tag fields (may all be null for untagged files) --
        String existingTitle  = existingMetadata != null ? existingMetadata.title()           : null;
        String existingArtist = existingMetadata != null ? existingMetadata.artist()          : null;
        String existingAlbum  = existingMetadata != null ? existingMetadata.album()           : null;
        Double existingDur    = existingMetadata != null ? existingMetadata.durationSeconds() : null;

        // ── PASS 1: AcoustID fingerprint ─────────────────────────────────
        // Works from audio content alone — doesn't need any existing tags.
        // If it identifies the song, we use its title/artist in pass 2.

        String queryTitle  = existingTitle;
        String queryArtist = existingArtist;
        String queryAlbum  = existingAlbum;
        Double queryDur    = existingDur;

        if (acoustId != null && audioFile != null) {
            ApiResult acr = acoustId.identify(audioFile);
            results.add(acr);
            log(acr);

            if (acr.isUsable()) {
                // Short-circuit: AcoustID is confident enough
                if (acr.confidence() >= shortCircuitThreshold) {
                    System.out.printf("[Aggregator] Short-circuit: AcoustID confidence %.2f >= %.2f%n",
                            acr.confidence(), shortCircuitThreshold);

                    // Still run Genius for lyrics and MusicBrainz for year/genre/track#
                    // even on short-circuit — AcoustID doesn't return these fields
                    if (acr.metadata() != null) {
                        String scTitle  = acr.metadata().title();
                        String scArtist = acr.metadata().artist();

                        if (scTitle != null || scArtist != null) {
                            // MusicBrainz — year, genre, track number
                            if (musicBrainz != null) {
                                try {
                                    ApiResult mbResult = musicBrainz.search(
                                            scTitle, scArtist, acr.metadata().album(),
                                            acr.metadata().durationSeconds());
                                    if (mbResult.isUsable()) { results.add(mbResult); log(mbResult); }
                                } catch (Exception e) {
                                    System.err.println("[Aggregator] MusicBrainz short-circuit failed: " + e.getMessage());
                                }
                            }

                            // Genius — lyrics
                            if (genius != null) {
                                try {
                                    ApiResult geniusResult = genius.search(scTitle, scArtist);
                                    if (geniusResult.isUsable()) { results.add(geniusResult); log(geniusResult); }
                                } catch (Exception e) {
                                    System.err.println("[Aggregator] Genius short-circuit failed: " + e.getMessage());
                                }
                            }
                        }
                    }

                    return sorted(results);
                }

                // AcoustID found something but below short-circuit threshold —
                // use its metadata to enrich pass 2 queries.
                // We prefer AcoustID's identified title/artist over existing tags
                // because existing tags may be wrong (that's why we're scanning).
                TrackMetadata acrMeta = acr.metadata();
                if (acrMeta != null) {
                    if (acrMeta.title()  != null) queryTitle  = acrMeta.title();
                    if (acrMeta.artist() != null) queryArtist = acrMeta.artist();
                    if (acrMeta.album()  != null) queryAlbum  = acrMeta.album();
                    if (acrMeta.durationSeconds() != null) queryDur = acrMeta.durationSeconds();
                    System.out.printf("[Aggregator] Pass 2 will use AcoustID hint: '%s' by '%s'%n",
                            queryTitle, queryArtist);
                }
            }
        }

        // ── PASS 2: MusicBrainz / Genius in parallel ─────────────────────
        // Use the best available title/artist — from AcoustID if it found
        // something, otherwise from the file's existing tags.

        final String fTitle  = queryTitle;
        final String fArtist = queryArtist;
        final String fAlbum  = queryAlbum;
        final Double fDur    = queryDur;

        List<Callable<ApiResult>> tasks = new ArrayList<>();

        if (musicBrainz != null) {
            tasks.add(() -> musicBrainz.search(fTitle, fArtist, fAlbum, fDur));
        }
        if (genius != null) {
            tasks.add(() -> genius.search(fTitle, fArtist));
        }

        if (!tasks.isEmpty()) {
            try {
                List<Future<ApiResult>> futures = executor.invokeAll(
                        tasks, 30, TimeUnit.SECONDS);

                for (Future<ApiResult> future : futures) {
                    try {
                        ApiResult r = future.get();
                        results.add(r);
                        log(r);
                    } catch (Exception e) {
                        System.err.println("[Aggregator] Task failed: " + e.getMessage());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("[Aggregator] Interrupted while waiting for API responses");
            }
        }

        return sorted(results);
    }

    //Convenience overload — skips AcoustID and queries text-based APIs only.
    // Used when re-querying after AI transcription (no audio file needed).

    public List<ApiResult> query(TrackMetadata existingMetadata) {
        return query(null, existingMetadata);
    }

    //Return the single best ApiResult from a list, or an empty result
    // if the list is empty or contains no usable results.

    public static ApiResult best(List<ApiResult> results) {
        return results.stream()
                .filter(ApiResult::isUsable)
                .max(Comparator.comparingDouble(ApiResult::confidence))
                .orElse(ApiResult.empty("none"));
    }

    public void shutdown() {
        executor.shutdown();
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    // Usable results first (by confidence desc), errors/empties last
    private static List<ApiResult> sorted(List<ApiResult> results) {
        return results.stream()
                .sorted(Comparator
                        .comparingInt((ApiResult r) -> r.isUsable() ? 0 : 1)
                        .thenComparingDouble(ApiResult::confidence).reversed())
                .toList();
    }

    private static void log(ApiResult r) {
        if (r.isError()) {
            System.err.printf("[Aggregator] %s error: %s%n", r.source(), r.error());
        } else if (r.isEmpty()) {
            System.out.printf("[Aggregator] %s: no results%n", r.source());
        } else {
            System.out.printf("[Aggregator] %s: confidence=%.2f  %s — %s%n",
                    r.source(), r.confidence(),
                    r.metadata() != null ? r.metadata().artist() : "?",
                    r.metadata() != null ? r.metadata().title()  : "?");
        }
    }

    // ------------------------------------------------------------------
    // Builder
    // ------------------------------------------------------------------

    public static final class Builder {
        private AcoustIdClient    acoustId;
        private MusicBrainzClient musicBrainz;
        private GeniusClient      genius;
        private double            shortCircuitThreshold = 0.95;

        public Builder acoustId(AcoustIdClient c)       { this.acoustId     = c; return this; }
        public Builder musicBrainz(MusicBrainzClient c) { this.musicBrainz  = c; return this; }
        public Builder genius(GeniusClient c)           { this.genius        = c; return this; }

        // Set > 1.0 to disable short-circuiting. Default 0.95.
        public Builder shortCircuitThreshold(double t)  { this.shortCircuitThreshold = t; return this; }

        public ApiAggregator build() {
            if (acoustId == null && musicBrainz == null && genius == null)
                throw new IllegalStateException("At least one API client must be configured");
            return new ApiAggregator(this);
        }
    }
}
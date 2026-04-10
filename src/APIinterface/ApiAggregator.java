package APIinterface;

import lyrify.TrackMetadata;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Queries all enabled API sources and returns their results ranked by confidence.
 *
 * <h2>Role in the pipeline</h2>
 * <p>The aggregator sits between the file interface and the scoring system.
 * It doesn't decide which result is "correct" — that's the scorer's job.
 * It just:
 * <ol>
 *   <li>Fires off queries to all enabled APIs (in parallel where possible)</li>
 *   <li>Collects the results</li>
 *   <li>Returns them sorted by confidence, highest first</li>
 * </ol>
 *
 * <p>If the highest-confidence result across all APIs is still below the
 * configured {@code aiThreshold}, the pipeline will hand off to the AI
 * fallback layer instead.
 *
 * <h2>Parallelism</h2>
 * <p>Spotify and Genius requests are fired in parallel using a small thread
 * pool since they are independent of each other. AcoustID runs first
 * (synchronously) because its result is the most valuable and we want to
 * short-circuit early if it returns very high confidence — there's no point
 * querying the other APIs if AcoustID already found a perfect match.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * ApiAggregator agg = new ApiAggregator.Builder()
 *     .acoustId(acoustIdClient)
 *     .musicBrainz(musicBrainzClient)
 *     .spotify(spotifyClient)
 *     .genius(geniusClient)
 *     .shortCircuitThreshold(0.95)   // skip remaining APIs if one hits 95%+
 *     .build();
 *
 * List<ApiResult> results = agg.query(audioFile, existingMetadata);
 * }</pre>
 */
public final class ApiAggregator {

    // ------------------------------------------------------------------
    // Fields
    // ------------------------------------------------------------------

    /** Optional API clients — null means that source is disabled. */
    private final AcoustIdClient    acoustId;
    private final MusicBrainzClient musicBrainz;
    private final SpotifyClient     spotify;
    private final GeniusClient      genius;

    /**
     * If any single API returns confidence >= this value, we skip querying
     * the remaining APIs and return immediately.
     * Set to 1.1 to disable short-circuiting entirely.
     */
    private final double shortCircuitThreshold;

    /** Thread pool for parallel API calls. */
    private final ExecutorService executor;

    // ------------------------------------------------------------------
    // Constructor (private — use Builder)
    // ------------------------------------------------------------------

    private ApiAggregator(Builder b) {
        this.acoustId              = b.acoustId;
        this.musicBrainz           = b.musicBrainz;
        this.spotify               = b.spotify;
        this.genius                = b.genius;
        this.shortCircuitThreshold = b.shortCircuitThreshold;
        // Virtual threads (Java 21) — lightweight, one per task, no pooling overhead
        this.executor              = Executors.newVirtualThreadPerTaskExecutor();
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Query all enabled APIs for the given audio file and return results
     * sorted by confidence descending.
     *
     * <p>Usable results ({@link ApiResult#isUsable()}) appear first;
     * errors and empty responses are included at the end for logging purposes.
     *
     * @param audioFile        the audio file to identify (used by AcoustID)
     * @param existingMetadata any metadata already known about the file —
     *                         used as query hints for MusicBrainz, Spotify, Genius
     * @return list of {@link ApiResult}, sorted by confidence descending,
     *         never null, may be empty
     */
    public List<ApiResult> query(Path audioFile, TrackMetadata existingMetadata) {
        List<ApiResult> results = new ArrayList<>();

        // Convenience shortcuts into existing metadata (may be null)
        String title    = existingMetadata != null ? existingMetadata.title()    : null;
        String artist   = existingMetadata != null ? existingMetadata.artist()   : null;
        String album    = existingMetadata != null ? existingMetadata.album()    : null;
        Double duration = existingMetadata != null ? existingMetadata.durationSeconds() : null;

        // ---- Step 1: AcoustID first (synchronous) -------------------------
        // AcoustID is the most reliable source because it uses the actual audio
        // content rather than metadata. We run it first so we can short-circuit
        // if we get a very high-confidence match.
        if (acoustId != null && audioFile != null) {
            ApiResult acr = acoustId.identify(audioFile);
            results.add(acr);
            log(acr);

            if (acr.isUsable() && acr.confidence() >= shortCircuitThreshold) {
                System.out.printf("[Aggregator] Short-circuit: AcoustID confidence %.2f >= %.2f%n",
                        acr.confidence(), shortCircuitThreshold);
                return sorted(results);
            }
        }

        // ---- Step 2: MusicBrainz, Spotify, Genius in parallel -------------
        // These three are all metadata/lyrics lookups using text queries.
        // Running them in parallel saves time since they don't depend on
        // each other's results.
        List<Callable<ApiResult>> tasks = new ArrayList<>();

        if (musicBrainz != null && title != null) {
            tasks.add(() -> musicBrainz.search(title, artist, album, duration));
        }
        if (spotify != null && title != null) {
            tasks.add(() -> spotify.search(title, artist, album));
        }
        if (genius != null && title != null) {
            tasks.add(() -> genius.search(title, artist));
        }

        if (!tasks.isEmpty()) {
            try {
                // invokeAll blocks until all tasks complete or the timeout fires
                List<Future<ApiResult>> futures = executor.invokeAll(
                        tasks, 30, TimeUnit.SECONDS);

                for (Future<ApiResult> future : futures) {
                    try {
                        ApiResult r = future.get();
                        results.add(r);
                        log(r);
                    } catch (Exception e) {
                        // Individual task failed — log and continue
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

    /**
     * Convenience overload for when no audio file path is available —
     * skips AcoustID and queries the text-based APIs only.
     */
    public List<ApiResult> query(TrackMetadata existingMetadata) {
        return query(null, existingMetadata);
    }

    /**
     * Return the single best {@link ApiResult} from a list, or an empty
     * result if the list is empty or contains no usable results.
     *
     * <p>This is a utility method — the full scoring system will do more
     * sophisticated selection, but this is useful for simple cases or testing.
     */
    public static ApiResult best(List<ApiResult> results) {
        return results.stream()
                .filter(ApiResult::isUsable)
                .max(Comparator.comparingDouble(ApiResult::confidence))
                .orElse(ApiResult.empty("none"));
    }

    /** Shut down the thread pool — call this when the aggregator is no longer needed. */
    public void shutdown() {
        executor.shutdown();
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /** Sort results: usable results first (by confidence desc), errors/empties last. */
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
        private SpotifyClient     spotify;
        private GeniusClient      genius;
        private double            shortCircuitThreshold = 0.95;

        /** Enable AcoustID fingerprint lookup. */
        public Builder acoustId(AcoustIdClient c)       { this.acoustId     = c; return this; }
        /** Enable MusicBrainz text search. */
        public Builder musicBrainz(MusicBrainzClient c) { this.musicBrainz  = c; return this; }
        /** Enable Spotify search. */
        public Builder spotify(SpotifyClient c)         { this.spotify       = c; return this; }
        /** Enable Genius lyrics search. */
        public Builder genius(GeniusClient c)           { this.genius        = c; return this; }

        /**
         * If any API returns confidence >= this value, skip the remaining
         * APIs and return immediately. Default 0.95.
         * Set to a value > 1.0 to disable short-circuiting.
         */
        public Builder shortCircuitThreshold(double t)  { this.shortCircuitThreshold = t; return this; }

        public ApiAggregator build() {
            if (acoustId == null && musicBrainz == null && spotify == null && genius == null)
                throw new IllegalStateException("At least one API client must be configured");
            return new ApiAggregator(this);
        }
    }
}

package lyrify.FileHub;

import lyrify.FileInterface.TrackMetadata;

// Outcome of running a single track through the full Lyrify pipeline.
// Carries everything the caller needs to know: what was found, how confident,
// which stage produced the result, and whether user review is needed.
public record PipelineResult(
        String        filepath,      // absolute path of the track that was processed
        TrackMetadata metadata,      // best metadata found, or null if nothing usable
        double        finalScore,    // best confidence score achieved [0.0, 1.0]
        Stage         stage,         // which stage produced this result
        boolean       needsReview,   // true if score fell below the review threshold
        boolean       written,       // true if metadata was already written to the file
        String        note           // human-readable status message, never null
) {
    // Which stage of the pipeline produced the final result
    public enum Stage {
        API,        // one of the API clients found a good match
        AI,         // the Demucs + Whisper fallback produced the result
        CACHED,     //cached tag
        NONE        // nothing reached the acceptance threshold
    }

    // Convenience factories — named constructors make call sites readable

    public static PipelineResult fromApi(String filepath, TrackMetadata meta,
                                         double score, boolean needsReview, boolean written) {
        String note = needsReview
                ? "API match found but score %.2f is below review threshold — please confirm".formatted(score)
                : "Matched via API (score %.2f)".formatted(score);
        return new PipelineResult(filepath, meta, score, Stage.API, needsReview, written, note);
    }

    public static PipelineResult fromAi(String filepath, TrackMetadata meta,
                                        double score, boolean needsReview, boolean written) {
        String note = needsReview
                ? "AI result score %.2f is below review threshold — please confirm".formatted(score)
                : "Matched via AI fallback (score %.2f)".formatted(score);
        return new PipelineResult(filepath, meta, score, Stage.AI, needsReview, written, note);
    }

    public static PipelineResult noMatch(String filepath, double bestScore) {
        return new PipelineResult(filepath, null, bestScore, Stage.NONE, false, false,
                "No accurate match found (best score %.2f)".formatted(bestScore));
    }

    // File was found in cache — no changes made, shown in results as-is
    public static PipelineResult cached(String filepath, TrackMetadata metadata) {
        return new PipelineResult(
                filepath,
                metadata,
                1.0,           // score of 1.0 — we trust the cached data
                Stage.CACHED,  // new stage value
                false,         // doesn't need review
                false,         // nothing written
                "Previously scanned — no changes made"
        );
    }

    public boolean isUsable() { return metadata != null && stage != Stage.NONE; }
}
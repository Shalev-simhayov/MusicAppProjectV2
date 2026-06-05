package lyrify.APIinterface;

import lyrify.FileInterface.TrackMetadata;

// Shared result wrapper returned by every API client.
// Holds either a TrackMetadata + confidence score, or an error message.
// Always check isUsable() before accessing metadata().
public record ApiResult(
        String        source,
        TrackMetadata metadata,
        double        confidence,
        String        error,
        String        rawJson
) {
    // ------------------------------------------------------------------
    // Compact constructor — validate inputs on construction
    // ------------------------------------------------------------------
    public ApiResult {
        if (source == null || source.isBlank())
            throw new IllegalArgumentException("source must not be blank");
        if (confidence < 0.0 || confidence > 1.0)
            throw new IllegalArgumentException("confidence must be in [0.0, 1.0], got: " + confidence);
    }

    // ------------------------------------------------------------------
    // Factory methods — named factories make intent clear at the call site
    // ------------------------------------------------------------------

    public static ApiResult of(String source, TrackMetadata metadata,
                                double confidence, String rawJson) {
        return new ApiResult(source, metadata, confidence, null, rawJson);
    }

    public static ApiResult error(String source, String error) {
        return new ApiResult(source, null, 0.0, error, null);
    }

    // "No results found" is distinct from error — the API worked, there was just no match.
    // The scoring system treats these differently.
    public static ApiResult empty(String source) {
        return new ApiResult(source, null, 0.0, null, null);
    }

    // ------------------------------------------------------------------
    // State checks
    // ------------------------------------------------------------------

    // Confidence below 0.1 is too low to trust even if metadata is present
    public boolean isUsable() {
        return metadata != null && error == null && confidence >= 0.1;
    }

    public boolean isError() {
        return error != null;
    }

    public boolean isEmpty() {
        return metadata == null && error == null;
    }

    // ------------------------------------------------------------------
    // Utility
    // ------------------------------------------------------------------

    @Override
    public String toString() {
        return "ApiResult{source='%s', confidence=%.2f, usable=%b, error='%s'}"
                .formatted(source, confidence, isUsable(), error);
    }
}

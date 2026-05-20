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
    // Factory methods — same pattern as ScanResult, named factories make
    // the intent clear at the call site rather than passing raw nulls
    // ------------------------------------------------------------------

    /**
     * Factory for a successful API result.
     *
     * @param source     API name
     * @param metadata   parsed metadata
     * @param confidence match confidence [0.0, 1.0]
     * @param rawJson    raw JSON response for debugging
     */
    public static ApiResult of(String source, TrackMetadata metadata,
                                double confidence, String rawJson) {
        return new ApiResult(source, metadata, confidence, null, rawJson);
    }

    /**
     * Factory for a failed API result — network error, parse failure, etc.
     *
     * @param source API name
     * @param error  human-readable description of what went wrong
     */
    public static ApiResult error(String source, String error) {
        return new ApiResult(source, null, 0.0, error, null);
    }

    /**
     * Factory for a "no results found" response — the API responded
     * successfully but had nothing matching our query.
     *
     * <p>This is different from an error — the API worked fine, there
     * just wasn't a match. We record this distinction so the scoring
     * system can treat "API returned nothing" differently from
     * "API was unreachable".
     *
     * @param source API name
     */
    public static ApiResult empty(String source) {
        return new ApiResult(source, null, 0.0, null, null);
    }

    // ------------------------------------------------------------------
    // State checks
    // ------------------------------------------------------------------

    /**
     * Returns true if this result has metadata worth using.
     * A result with confidence below 0.1 is treated as not usable even
     * if metadata is technically present — it's too low to trust.
     */
    public boolean isUsable() {
        return metadata != null && error == null && confidence >= 0.1;
    }

    /**
     * Returns true if the API call itself failed (network error, auth
     * failure, parse error, etc.) as opposed to simply returning no results.
     */
    public boolean isError() {
        return error != null;
    }

    /**
     * Returns true if the API responded successfully but found nothing —
     * i.e. not an error, just no match.
     */
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

package FileInterface;

/**
 * Result of scanning a single audio file.
 * Either holds a populated {@link TrackMetadata} or an error message.
 * 
 * acts like casing that returns either data or error upon use of .isSuccess
 *
 * Always check {@link #isSuccess()} before accessing {@link #metadata} or {@link #error}.
 * Exactly one of the two will be non-null for any given instance.
 */
//immutable data object of the result
public record ScanResult(
        String        path,
        TrackMetadata metadata,
        boolean       fromCache,
        String        error
) {
    /** Convenience factory for a successful scan. */
    public static ScanResult of(String path, TrackMetadata meta, boolean fromCache) {
        return new ScanResult(path, meta, fromCache, null);
    }

    /** Convenience factory for a failed scan. */
    public static ScanResult error(String path, String error) {
        return new ScanResult(path, null, false, error);
    }

    public boolean isSuccess() { return error == null && metadata != null; }
}

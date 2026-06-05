package lyrify.FileInterface;

// Result of scanning a single audio file — holds either a TrackMetadata or an error.
// Always check isSuccess() before accessing metadata() or error().
public record ScanResult(
        String        path,
        TrackMetadata metadata,
        boolean       fromCache,
        String        error
) {
    // Convenience factory for a successful scan.
    public static ScanResult of(String path, TrackMetadata meta, boolean fromCache) {
        return new ScanResult(path, meta, fromCache, null);
    }

    // Convenience factory for a failed scan.
    public static ScanResult error(String path, String error) {
        return new ScanResult(path, null, false, error);
    }

    public boolean isSuccess() { return error == null && metadata != null; }
}

package APIinterface;

/**
 * Checked exception thrown by the API layer when a request cannot be
 * completed, even after retries.
 *
 * <p>Carries the {@code source} name (e.g. "MusicBrainz") so the caller
 * always knows which API was responsible for the failure — useful when
 * {@link ApiAggregator} is running multiple APIs and logging results.
 */
public final class ApiException extends Exception {

    /** The API that produced this exception (e.g. "Spotify", "Genius"). */
    private final String source;

    public ApiException(String source, String message) {
        super("[" + source + "] " + message);
        this.source = source;
    }

    public ApiException(String source, String message, Throwable cause) {
        super("[" + source + "] " + message, cause);
        this.source = source;
    }

    public String source() {
        return source;
    }
}

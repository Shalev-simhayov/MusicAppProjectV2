package lyrify.APIinterface;

// Checked exception for API layer failures.
// Always carries the source name (e.g. "Spotify") so you know which API failed.

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

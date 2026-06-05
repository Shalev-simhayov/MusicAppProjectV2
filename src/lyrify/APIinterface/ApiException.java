package lyrify.APIinterface;

// Checked exception for API layer failures.
// Always carries the source name (e.g. "MusicBrainz") so you know which API failed.

public final class ApiException extends Exception {

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

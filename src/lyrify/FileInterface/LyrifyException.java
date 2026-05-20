package lyrify.FileInterface;

/** Checked exception for all Lyrify file-interface errors. */
public final class LyrifyException extends Exception {

    public LyrifyException(String message) {
        super(message);
    }

    public LyrifyException(String message, Throwable cause) {
        super(message, cause);
    }
}

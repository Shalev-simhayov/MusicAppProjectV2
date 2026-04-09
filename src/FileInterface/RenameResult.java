package FileInterface;

/**
 * Outcome of a single file in a {@link FileInterface#batchRename} operation.
 *
 * @param originalPath  absolute path before rename
 * @param proposedPath  what the new path would be (null if skipped before planning)
 * @param renamed       true only when the file was actually moved on disk
 * @param skipped       true when the rename was intentionally not performed
 * @param reason        human-readable explanation when skipped or failed
 */
public record RenameResult(
        String  originalPath,
        String  proposedPath,
        boolean renamed,
        boolean skipped,
        String  reason
) {
    public static RenameResult renamed(String from, String to) {
        return new RenameResult(from, to, true, false, null);
    }

    public static RenameResult planned(String from, String to) {
        return new RenameResult(from, to, false, false, null);
    }

    public static RenameResult skipped(String from, String proposed, String reason) {
        return new RenameResult(from, proposed, false, true, reason);
    }
}

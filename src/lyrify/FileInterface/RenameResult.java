package lyrify.FileInterface;

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

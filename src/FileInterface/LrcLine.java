package FileInterface;

/**
 * A single timestamped lyric line for LRC file generation.
 *
 * @param timestampMs milliseconds from the start of the track
 * @param text        lyric text for this line
 */
public record LrcLine(int timestampMs, String text) implements Comparable<LrcLine> {

    public LrcLine {
        if (timestampMs < 0)
            throw new IllegalArgumentException("timestampMs must be >= 0");
        if (text == null) text = "";
    }

    /** Format as {@code [mm:ss.xx]} */
    public String timestampStr() {
        int mins  = timestampMs / 60_000;
        double secs = (timestampMs % 60_000) / 1000.0;
        return "[%02d:%05.2f]".formatted(mins, secs);
    }

    /** Full LRC line: {@code [mm:ss.xx]lyric text} */
    @Override
    public String toString() {
        return timestampStr() + text;
    }

    @Override
    public int compareTo(LrcLine other) {
        return Integer.compare(this.timestampMs, other.timestampMs);
    }
}

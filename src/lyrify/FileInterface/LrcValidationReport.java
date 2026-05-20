package lyrify.FileInterface;

import java.util.List;

/**
 * Report produced by {@link FileInterface#validateLrcSync}.
 *
 * @param valid     true when there are no errors (warnings are non-fatal)
 * @param lineCount number of timestamped lyric lines found
 * @param warnings  non-fatal issues (e.g. duplicate timestamps)
 * @param errors    fatal issues (e.g. non-monotonic order, exceeds duration)
 */
public record LrcValidationReport(
        boolean      valid,
        int          lineCount,
        List<String> warnings,
        List<String> errors
) {
    public LrcValidationReport {
        warnings = List.copyOf(warnings);
        errors   = List.copyOf(errors);
    }

    @Override
    public String toString() {
        return "LrcValidationReport{valid=%b, lines=%d, warnings=%d, errors=%d}"
                .formatted(valid, lineCount, warnings.size(), errors.size());
    }
}

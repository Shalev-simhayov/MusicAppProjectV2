package lyrify.FileInterface;

import java.util.List;

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

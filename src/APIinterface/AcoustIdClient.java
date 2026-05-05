package APIinterface;

import lyrify.TrackMetadata;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

// Identifies audio by fingerprint using AcoustID + Chromaprint (fpcalc).
// Most reliable source — works even if all existing tags are wrong.
// Requires fpcalc installed on PATH. Free API key from acoustid.org.

public final class AcoustIdClient {

    // ------------------------------------------------------------------
    // Constants
    // ------------------------------------------------------------------

    private static final String BASE_URL    = "https://api.acoustid.org/v2";
    private static final String SOURCE_NAME = "AcoustID";

    /**
     * AcoustID requires a free API key — register at https://acoustid.org/login
     * The key is passed as a query parameter on every request.
     */
    private final String apiKey;

    /** Shared HTTP client. */
    private final ApiClient http;

    // ------------------------------------------------------------------
    // Constructor
    // ------------------------------------------------------------------

    /**
     * @param http   shared {@link ApiClient} instance
     * @param apiKey your AcoustID API key (register free at acoustid.org)
     */
    public AcoustIdClient(ApiClient http, String apiKey) {
        if (apiKey == null || apiKey.isBlank())
            throw new IllegalArgumentException("AcoustID API key must not be blank");
        this.http   = http;
        this.apiKey = apiKey;
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Identify an audio file by its acoustic fingerprint.
     *
     * <p>This method:
     * <ol>
     *   <li>Runs {@code fpcalc} on the file to generate a fingerprint</li>
     *   <li>Sends the fingerprint to the AcoustID API</li>
     *   <li>Returns the best matching {@link TrackMetadata}</li>
     * </ol>
     *
     * @param audioFile path to the audio file to identify
     * @return {@link ApiResult} — always non-null
     */
    public ApiResult identify(Path audioFile) {
        // Step 1: generate fingerprint via fpcalc
        FingerprintResult fp;
        try {
            fp = fingerprint(audioFile);
        } catch (FingerprintException e) {
            return ApiResult.error(SOURCE_NAME, "fpcalc failed: " + e.getMessage());
        }

        // Step 2: look up the fingerprint
        return lookup(fp.fingerprint(), fp.duration());
    }

    /**
     * Look up a pre-computed fingerprint directly — useful if you already
     * have a fingerprint from a previous run (e.g. stored in the cache)
     * and want to avoid re-running {@code fpcalc}.
     *
     * @param fingerprint the raw Chromaprint fingerprint string
     * @param duration    audio duration in seconds
     * @return {@link ApiResult} — always non-null
     */
    public ApiResult lookup(String fingerprint, double duration) {
        String url     = buildUrl(fingerprint, duration);
        String rawJson;

        try {
            rawJson = http.get(SOURCE_NAME, url);
        } catch (ApiException e) {
            return ApiResult.error(SOURCE_NAME, e.getMessage());
        }

        return parse(rawJson, duration);
    }

    // ------------------------------------------------------------------
    // Fingerprint generation (fpcalc)
    // ------------------------------------------------------------------

    /**
     * Holds the output of a successful {@code fpcalc} run.
     *
     * @param fingerprint raw Chromaprint fingerprint string
     * @param duration    audio duration in seconds
     */
    public record FingerprintResult(String fingerprint, double duration) {}

    /**
     * Run {@code fpcalc} as an external process to generate a fingerprint.
     *
     * <p>{@code fpcalc} is part of Chromaprint and must be installed
     * separately. It outputs plain text in this format:
     * <pre>
     * DURATION=213
     * FINGERPRINT=AQADtMmybckm...
     * </pre>
     *
     * @throws FingerprintException if fpcalc is not found or returns an error
     */
    private static FingerprintResult fingerprint(Path audioFile) throws FingerprintException {
        ProcessBuilder pb = new ProcessBuilder("fpcalc", audioFile.toString());
        pb.redirectErrorStream(true); // merge stderr into stdout for easy reading

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new FingerprintException(
                    "Could not start fpcalc — is Chromaprint installed and on PATH? " + e.getMessage());
        }

        String output;
        try {
            // Read all output from the process
            output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new FingerprintException("fpcalc exited with code " + exitCode + ": " + output);
            }
        } catch (IOException | InterruptedException e) {
            throw new FingerprintException("Error reading fpcalc output: " + e.getMessage());
        }

        // Parse the two output lines
        String fingerprintValue = null;
        double durationValue    = 0;

        for (String line : output.lines().toList()) {
            if (line.startsWith("DURATION=")) {
                try {
                    durationValue = Double.parseDouble(line.substring("DURATION=".length()).strip());
                } catch (NumberFormatException e) {
                    throw new FingerprintException("Could not parse duration from fpcalc output");
                }
            } else if (line.startsWith("FINGERPRINT=")) {
                fingerprintValue = line.substring("FINGERPRINT=".length()).strip();
            }
        }

        if (fingerprintValue == null || fingerprintValue.isBlank()) {
            throw new FingerprintException("fpcalc did not return a fingerprint");
        }

        return new FingerprintResult(fingerprintValue, durationValue);
    }

    // ------------------------------------------------------------------
    // URL builder
    // ------------------------------------------------------------------

    /**
     * Build the AcoustID lookup URL.
     *
     * <p>We request {@code meta=recordings+releasegroups} so the response
     * includes track title, artist, and album info — without this flag
     * AcoustID only returns its internal recording IDs with no names.
     */
    private String buildUrl(String fingerprint, double duration) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client",      apiKey);
        params.put("meta",        "recordings+releasegroups+compress");
        params.put("duration",    String.valueOf((int) Math.round(duration)));
        params.put("fingerprint", fingerprint);
        return BASE_URL + "/lookup?" + ApiClient.buildQuery(params);
    }

    // ------------------------------------------------------------------
    // Response parser
    // ------------------------------------------------------------------

    /**
     * Parse the AcoustID JSON response into an {@link ApiResult}.
     *
     * <p>AcoustID response structure (simplified):
     * <pre>{@code
     * {
     *   "status": "ok",
     *   "results": [
     *     {
     *       "score": 0.921,           ← match confidence 0.0-1.0 (already our scale!)
     *       "recordings": [
     *         {
     *           "title": "Bohemian Rhapsody",
     *           "artists": [{ "name": "Queen" }],
     *           "releasegroups": [
     *             {
     *               "title": "A Night at the Opera",
     *               "type": "Album",
     *               "secondarytypes": []
     *             }
     *           ]
     *         }
     *       ]
     *     }
     *   ]
     * }
     * }</pre>
     */
    private static ApiResult parse(String rawJson, double duration) {
        JSONObject root;
        try {
            root = new JSONObject(rawJson);
        } catch (Exception e) {
            return ApiResult.error(SOURCE_NAME, "Failed to parse JSON: " + e.getMessage());
        }

        // Check the API-level status field first
        String status = root.optString("status", "");
        if (!"ok".equals(status)) {
            JSONObject errObj = root.optJSONObject("error");
            String msg = errObj != null ? errObj.optString("message", "unknown error") : "unknown error";
            return ApiResult.error(SOURCE_NAME, "API error: " + msg);
        }

        JSONArray results = root.optJSONArray("results");
        if (results == null || results.isEmpty()) {
            return ApiResult.empty(SOURCE_NAME);
        }

        // Top result
        JSONObject top        = results.getJSONObject(0);
        // AcoustID already gives confidence as 0.0-1.0 — no conversion needed
        double     confidence = top.optDouble("score", 0.0);

        JSONArray recordings = top.optJSONArray("recordings");
        if (recordings == null || recordings.isEmpty()) {
            // AcoustID matched a fingerprint but has no recording metadata for it
            return ApiResult.empty(SOURCE_NAME);
        }

        JSONObject recording = recordings.getJSONObject(0);
        String title  = recording.optString("title", null);
        String artist = parseArtists(recording.optJSONArray("artists"));
        String album  = parseReleaseGroup(recording.optJSONArray("releasegroups"));

        TrackMetadata metadata = TrackMetadata.builder("")
                .title(title)
                .artist(artist)
                .album(album)
                .durationSeconds(duration) // use duration from fpcalc, not API
                .build();

        return ApiResult.of(SOURCE_NAME, metadata, confidence, rawJson);
    }

    /** Extract a comma-joined artist name string from an AcoustID artists array. */
    private static String parseArtists(JSONArray artists) {
        if (artists == null || artists.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < artists.length(); i++) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(artists.getJSONObject(i).optString("name", ""));
        }
        String result = sb.toString().strip();
        return result.isEmpty() ? null : result;
    }

    /**
     * Pick the most relevant release group (album) from the list.
     * We prefer "Album" type over singles, EPs, compilations, etc.
     */
    private static String parseReleaseGroup(JSONArray groups) {
        if (groups == null || groups.isEmpty()) return null;

        // First pass: try to find a plain Album type
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.getJSONObject(i);
            if ("Album".equalsIgnoreCase(g.optString("type", ""))
                    && g.optJSONArray("secondarytypes") != null
                    && g.optJSONArray("secondarytypes").isEmpty()) {
                return g.optString("title", null);
            }
        }

        // Fallback: just return the first group's title
        return groups.getJSONObject(0).optString("title", null);
    }

    // ------------------------------------------------------------------
    // FingerprintException — internal only
    // ------------------------------------------------------------------

    /** Thrown when {@code fpcalc} cannot produce a fingerprint. */
    static final class FingerprintException extends Exception {
        FingerprintException(String message) { super(message); }
    }
}

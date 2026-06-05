package lyrify.APIinterface;

import lyrify.FileInterface.TrackMetadata;
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


    private final String apiKey;

    private final ApiClient http;

    // ------------------------------------------------------------------
    // Constructor
    // ------------------------------------------------------------------

    public AcoustIdClient(ApiClient http, String apiKey) {
        if (apiKey == null || apiKey.isBlank())
            throw new IllegalArgumentException("AcoustID API key must not be blank");
        this.http   = http;
        this.apiKey = apiKey;
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

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

    // Use this overload if you already have a fingerprint cached from a previous run —
    // skips the fpcalc subprocess entirely.
    public ApiResult lookup(String fingerprint, double duration) {
        try {
            String formBody = "client=" + ApiClient.encode(apiKey)
                    + "&meta=recordings+releasegroups"
                    + "&duration=" + (int) Math.round(duration)
                    + "&fingerprint=" + ApiClient.encode(fingerprint);
            System.out.println("[AcoustID DEBUG] formBody length: " + formBody.length());
            System.out.println("[AcoustID DEBUG] starts with: " + formBody.substring(0, Math.min(100, formBody.length())));
            String rawJson = http.post(SOURCE_NAME, BASE_URL + "/lookup", formBody);
            return parse(rawJson, duration);
        } catch (ApiException e) {
            return ApiResult.error(SOURCE_NAME, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Fingerprint generation (fpcalc)
    // ------------------------------------------------------------------

    public record FingerprintResult(String fingerprint, double duration) {}

    // fpcalc outputs plain text in this format:
    //   DURATION=213
    //   FINGERPRINT=AQADtMmybckm...
    private static FingerprintResult fingerprint(Path audioFile) throws FingerprintException {
        // Try to find fpcalc in common locations if not on PATH
        String fpcalc = resolveFpcalc();
        ProcessBuilder pb = new ProcessBuilder(fpcalc, audioFile.toString());
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


    private static String resolveFpcalc() {
        // 1. Check next to the jar (project root / app directory)
        String[] candidates = {
                System.getProperty("user.dir") + "\\fpcalc.exe",
                System.getProperty("user.dir") + "\\chromaprint-fpcalc-1.6.0-windows-x86_64\\fpcalc.exe",
                // Also try the app bundle location when running as exe
                System.getProperty("java.home") + "\\..\\fpcalc.exe",
        };
        for (String path : candidates) {
            if (new java.io.File(path).exists()) {
                return path;
            }
        }
        // 2. Fall back to PATH
        return "fpcalc";
    }
    // ------------------------------------------------------------------
    // URL builder
    // ------------------------------------------------------------------



    // ------------------------------------------------------------------
    // Response parser
    // ------------------------------------------------------------------

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

        TrackMetadata metadata = TrackMetadata.builder(null)
                .title(title)
                .artist(artist)
                .album(album)
                .durationSeconds(duration) // use duration from fpcalc, not API
                .build();

        return ApiResult.of(SOURCE_NAME, metadata, confidence, rawJson);
    }

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

    // Prefers "Album" type over singles, EPs, and compilations
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

    static final class FingerprintException extends Exception {
        FingerprintException(String message) { super(message); }
    }
}

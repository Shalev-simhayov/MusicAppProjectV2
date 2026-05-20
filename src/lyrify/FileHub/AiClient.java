package lyrify.FileHub;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

// Communicates with the Python AI server (server.py).
// The server runs Demucs (vocal separation) + Whisper (transcription)
// and returns the transcribed lyrics as plain text.
//
// Expected server endpoints:
//   GET  /ping              — health check, returns 200 if up
//   POST /pipeline?lang=xx  — runs Demucs + Whisper on the given file path
//                             body: plain-text absolute file path
//                             response: JSON { text, language, duration_seconds }
public final class AiClient {

    private final String baseUrl; // e.g. "http://localhost:5050"

    public AiClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    // Default constructor — assumes server is on localhost:5050
    public AiClient() {
        this("http://localhost:5050");
    }

    // ------------------------------------------------------------------
    // Health check
    // ------------------------------------------------------------------

    // Returns true if the Python server is reachable and responding.
    public boolean isServerUp() {
        try {
            URL url = URI.create(baseUrl + "/ping").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Main pipeline call
    // ------------------------------------------------------------------

    // Run Demucs + Whisper on the given audio file.
    // language: ISO-639-1 code (e.g. "en", "he") or null for auto-detect.
    // Returns a PipelineAiResult with the transcribed text, detected language,
    // and duration in seconds. Returns an empty result on failure.
    public PipelineAiResult runPipeline(String absoluteFilePath, String language) {
        try {
            String endpoint = baseUrl + "/pipeline" + (language != null ? "?lang=" + language : "");
            URL url = URI.create(endpoint).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(300_000); // 5 min — AI can be slow

            byte[] body = absoluteFilePath.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            conn.setRequestProperty("Content-Length", String.valueOf(body.length));
            conn.getOutputStream().write(body);

            int code = conn.getResponseCode();
            if (code != 200) {
                return PipelineAiResult.empty();
            }

            String json;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                json = br.lines().collect(Collectors.joining("\n"));
            }
            conn.disconnect();

            return PipelineAiResult.fromJson(json);

        } catch (Exception e) {
            System.out.println("[AiClient] Pipeline error: " + e.getMessage());
            return PipelineAiResult.empty();
        }
    }

    // ------------------------------------------------------------------
    // Result record
    // ------------------------------------------------------------------

    // Holds the output of one Demucs + Whisper run.
    public record PipelineAiResult(
            String text,            // transcribed lyrics (null if failed)
            String language,        // ISO-639-1 detected language (null if unknown)
            double durationSeconds  // audio duration in seconds (0 if unknown)
    ) {
        // Parse a minimal JSON response without pulling in a JSON library.
        // Expected shape: { "text": "...", "language": "en", "duration_seconds": 213.4 }
        static PipelineAiResult fromJson(String json) {
            String text     = extractString(json, "text");
            String language = extractString(json, "language");
            double duration = extractDouble(json, "duration_seconds");
            return new PipelineAiResult(text, language, duration);
        }

        static PipelineAiResult empty() {
            return new PipelineAiResult(null, null, 0.0);
        }

        // Minimal string extractor — reads "key": "value"
        private static String extractString(String json, String key) {
            String marker = "\"" + key + "\"";
            int idx = json.indexOf(marker);
            if (idx < 0) return null;
            int colon = json.indexOf(":", idx + marker.length());
            if (colon < 0) return null;
            int q1 = json.indexOf("\"", colon + 1);
            if (q1 < 0) return null;
            int q2 = json.indexOf("\"", q1 + 1);
            if (q2 < 0) return null;
            return json.substring(q1 + 1, q2);
        }

        // Minimal double extractor — reads "key": 123.4
        private static double extractDouble(String json, String key) {
            String marker = "\"" + key + "\"";
            int idx = json.indexOf(marker);
            if (idx < 0) return 0.0;
            int colon = json.indexOf(":", idx + marker.length());
            if (colon < 0) return 0.0;
            // read until comma, } or end
            int start = colon + 1;
            while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end))
                    || json.charAt(end) == '.' || json.charAt(end) == '-')) end++;
            try {
                return Double.parseDouble(json.substring(start, end));
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
    }
}

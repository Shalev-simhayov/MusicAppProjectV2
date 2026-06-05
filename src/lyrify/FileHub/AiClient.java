package lyrify.FileHub;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

// Communicates with the Python AI server (server.py).
// Uses two separate calls:
//   1. POST /separate — Demucs vocal separation (fast, ~10-30s)
//   2. POST /transcribe — Whisper transcription (slow, may time out)
// Splitting the calls means a Whisper timeout doesn't lose the Demucs result,
// and we can return partial data (e.g. duration) even if transcription fails.
public final class AiClient {

    private final String baseUrl;

    // Timeouts — separation is fast, transcription can be slow
    private static final int CONNECT_TIMEOUT_MS  = 5_000;
    private static final int SEPARATE_TIMEOUT_MS = 180_000;  // 3 min for Demucs
    private static final int TRANSCRIBE_TIMEOUT_MS = 600_000; // 10 min for Whisper medium

    public AiClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public AiClient() {
        this("http://localhost:8000");
    }

    // ------------------------------------------------------------------
    // Health check
    // ------------------------------------------------------------------

    public boolean isServerUp() {
        try {
            URL url = URI.create(baseUrl + "/health").toURL();
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
    // Two-step pipeline
    // ------------------------------------------------------------------

    // Run Demucs + Whisper on the given audio file.
    // Step 1: separate vocals (fast)
    // Step 2: transcribe vocal stem (slow — may time out, returns partial result)
    // Returns a PipelineAiResult — text may be null if transcription timed out,
    // but durationSeconds will still be set from the separation step.
    public PipelineAiResult runPipeline(String absoluteFilePath, String language) {

        // -- Step 1: Demucs vocal separation --
        System.out.println("[AiClient] Step 1: separating vocals...");
        String vocalPath;
        double duration;

        try {
            String sepJson = "{\"audio_path\": \""
                    + absoluteFilePath.replace("\\", "\\\\") + "\"}";
            String sepResponse = post("/separate", sepJson, SEPARATE_TIMEOUT_MS);

            if (sepResponse == null) {
                System.out.println("[AiClient] Separation failed — no response");
                return PipelineAiResult.empty();
            }

            // Check success
            if (sepResponse.contains("\"success\":false")) {
                String err = PipelineAiResult.extractString(sepResponse, "error");
                System.out.println("[AiClient] Separation failed: " + err);
                return PipelineAiResult.empty();
            }

            vocalPath = PipelineAiResult.extractString(sepResponse, "vocal_path");
            duration  = PipelineAiResult.extractDouble(sepResponse, "duration_seconds");

            if (vocalPath == null) {
                System.out.println("[AiClient] Separation returned no vocal path");
                return PipelineAiResult.empty();
            }

            System.out.println("[AiClient] Separation complete. Duration: " + duration + "s");

        } catch (Exception e) {
            System.out.println("[AiClient] Separation error: " + e.getMessage());
            return PipelineAiResult.empty();
        }

        // -- Step 2: Whisper transcription --
        System.out.println("[AiClient] Step 2: transcribing vocals...");
        try {
            String transcribeJson = "{\"audio_path\": \""
                    + vocalPath.replace("\\", "\\\\") + "\""
                    + (language != null ? ", \"language\": \"" + language + "\"" : "")
                    + "}";

            String transResponse = post("/transcribe", transcribeJson, TRANSCRIBE_TIMEOUT_MS);

            if (transResponse == null || transResponse.contains("\"success\":false")) {
                String err = transResponse != null
                        ? PipelineAiResult.extractString(transResponse, "error")
                        : "timeout";
                System.out.println("[AiClient] Transcription failed: " + err
                        + " — returning partial result with duration only");
                // Return partial result — we have duration from Demucs even if Whisper failed
                return new PipelineAiResult(null, null, duration, List.of());
            }

            String text     = PipelineAiResult.extractString(transResponse, "text");
            String lang     = PipelineAiResult.extractString(transResponse, "language");
            List<Segment> segments = PipelineAiResult.extractSegments(transResponse);

            System.out.println("[AiClient] Transcription complete. Language: " + lang
                    + ", chars: " + (text != null ? text.length() : 0)
                    + ", segments: " + segments.size());

            return new PipelineAiResult(text, lang, duration, segments);

        } catch (Exception e) {
            // Whisper timed out or errored — return partial result with duration
            System.out.println("[AiClient] Transcription error: " + e.getMessage()
                    + " — returning partial result");
            return new PipelineAiResult(null, null, duration, List.of());
        }
    }

    // ------------------------------------------------------------------
    // HTTP helper
    // ------------------------------------------------------------------

    // POST a JSON body to the given endpoint path, return the response body or null on error.
    private String post(String endpointPath, String jsonBody, int readTimeoutMs) {
        try {
            URL url = URI.create(baseUrl + endpointPath).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestProperty("Content-Type", "application/json");

            byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(body.length));
            conn.getOutputStream().write(body);

            int code = conn.getResponseCode();
            if (code != 200) {
                String err = "";
                if (conn.getErrorStream() != null) {
                    err = new BufferedReader(new InputStreamReader(
                            conn.getErrorStream(), StandardCharsets.UTF_8))
                            .lines().collect(Collectors.joining("\n"));
                }
                System.out.println("[AiClient] HTTP " + code + " from " + endpointPath + ": " + err);
                return null;
            }

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                return br.lines().collect(Collectors.joining("\n"));
            }

        } catch (java.net.SocketTimeoutException e) {
            System.out.println("[AiClient] Timeout on " + endpointPath);
            return null;
        } catch (Exception e) {
            System.out.println("[AiClient] Error on " + endpointPath + ": " + e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Result record
    // ------------------------------------------------------------------
    public record Segment(double start, double end, String text) {}

    public record PipelineAiResult(
            String text,
            String language,
            double durationSeconds,
            List<AiClient.Segment> segments
    ) {
        static PipelineAiResult empty() {
            return new PipelineAiResult(null, null, 0.0, java.util.List.of());
        }

        public boolean hasText() {
            return text != null && !text.isBlank();
        }

        // Made package-accessible so post() can use them during parsing
        static String extractString(String json, String key) {
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

        static double extractDouble(String json, String key) {
            String marker = "\"" + key + "\"";
            int idx = json.indexOf(marker);
            if (idx < 0) return 0.0;
            int colon = json.indexOf(":", idx + marker.length());
            if (colon < 0) return 0.0;
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

        static List<AiClient.Segment> extractSegments(String json) {
            List<AiClient.Segment> result = new java.util.ArrayList<>();
            String marker = "\"segments\"";
            int idx = json.indexOf(marker);
            if (idx < 0) return result;

            int arrStart = json.indexOf("[", idx);
            if (arrStart < 0) return result;

            // Walk through each segment object
            int pos = arrStart + 1;
            while (pos < json.length()) {
                int objStart = json.indexOf("{", pos);
                if (objStart < 0) break;

                // Find matching closing brace
                int depth = 1;
                int objEnd = objStart + 1;
                while (objEnd < json.length() && depth > 0) {
                    char c = json.charAt(objEnd);
                    if (c == '{') depth++;
                    else if (c == '}') depth--;
                    objEnd++;
                }

                String obj = json.substring(objStart, objEnd);
                double start = extractDouble(obj, "start");
                double end   = extractDouble(obj, "end");
                String text  = extractString(obj, "text");

                if (text != null && !text.isBlank()) {
                    result.add(new AiClient.Segment(start, end, text.strip()));
                }

                pos = objEnd;

                // Stop at end of segments array
                int nextBracket = json.indexOf("]", pos);
                int nextObj     = json.indexOf("{", pos);
                if (nextBracket >= 0 && (nextObj < 0 || nextBracket < nextObj)) break;
            }
            return result;
        }
    }
}
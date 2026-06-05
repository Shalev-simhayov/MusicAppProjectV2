package lyrify.APIinterface;

import lyrify.FileInterface.TrackMetadata;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

// Queries the MusicBrainz open music database for track metadata.
// No API key needed. Rate limited to 1 request/sec per their guidelines.

public final class MusicBrainzClient {

    // ------------------------------------------------------------------
    // Constants
    // ------------------------------------------------------------------

    private static final String BASE_URL    = "https://musicbrainz.org/ws/2";
    private static final String SOURCE_NAME = "MusicBrainz";

    // MusicBrainz requires this header — without it requests may be throttled or blocked
    private static final String USER_AGENT  = "Lyrify/1.0 (https://github.com/lyrify)";

    private static final int RESULT_LIMIT = 5;

    // ------------------------------------------------------------------
    // Fields
    // ------------------------------------------------------------------

    private final ApiClient http;

    // ------------------------------------------------------------------
    // Constructor
    // ------------------------------------------------------------------

    public MusicBrainzClient(ApiClient http) {
        this.http = http;
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    public ApiResult search(String title, String artist, String album, Double durationSeconds) {
        // Need at least a title or artist to produce a meaningful result
        if ((title == null || title.isBlank()) && (artist == null || artist.isBlank())) {
            return ApiResult.empty(SOURCE_NAME);
        }

        String query    = buildQuery(title, artist, album, durationSeconds);
        String url      = buildUrl(query);
        String rawJson;

        // Attempt the HTTP request — if it fails, wrap the exception in an
        // ApiResult so the pipeline can continue with the other APIs
        try {
            rawJson = http.get(SOURCE_NAME, url,
                    "User-Agent", USER_AGENT,
                    "Accept",     "application/json");  // ask for JSON, not XML
        } catch (ApiException e) {
            return ApiResult.error(SOURCE_NAME, e.getMessage());
        }

        // Parse the JSON response into an ApiResult
        return parse(rawJson);
    }

    // ------------------------------------------------------------------
    // Query builder
    // ------------------------------------------------------------------

    // Uses Lucene query syntax: recording:"title" AND artist:"name" AND dur:[lower TO upper]
    // Duration is searched within ±3 seconds to distinguish live vs studio versions
    private static String buildQuery(String title, String artist,
                                      String album, Double durationSeconds) {
        StringBuilder q = new StringBuilder();

        if (title != null && !title.isBlank()) {
            q.append("recording:\"").append(escapeLucene(title)).append("\"");
        }
        if (artist != null && !artist.isBlank()) {
            q.append(q.isEmpty() ? "" : " AND ").append("artist:\"").append(escapeLucene(artist)).append("\"");
        }
        if (album != null && !album.isBlank()) {
            q.append(" AND release:\"").append(escapeLucene(album)).append("\"");
        }

        // Duration: search within ±3 seconds of the known duration
        // MusicBrainz stores duration in milliseconds
        if (durationSeconds != null) {
            long durMs  = Math.round(durationSeconds * 1000);
            long lower  = durMs - 3000;
            long upper  = durMs + 3000;
            q.append(" AND dur:[").append(lower).append(" TO ").append(upper).append("]");
        }

        return q.toString();
    }

    private static String buildUrl(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("query", query);
        params.put("limit", String.valueOf(RESULT_LIMIT));
        params.put("fmt",   "json");  // without this MusicBrainz returns XML
        return BASE_URL + "/recording?" + ApiClient.buildQuery(params);
    }

    // ------------------------------------------------------------------
    // Response parser
    // ------------------------------------------------------------------

    private static ApiResult parse(String rawJson) {
        JSONObject root;
        try {
            root = new JSONObject(rawJson);
        } catch (Exception e) {
            return ApiResult.error(SOURCE_NAME, "Failed to parse JSON response: " + e.getMessage());
        }

        JSONArray recordings = root.optJSONArray("recordings");
        if (recordings == null || recordings.isEmpty()) {
            return ApiResult.empty(SOURCE_NAME);
        }

        // Take the top-ranked recording
        JSONObject top = recordings.getJSONObject(0);

        // MusicBrainz gives a 0-100 score — convert to our 0.0-1.0 scale
        int    mbScore   = top.optInt("score", 0);
        double confidence = mbScore / 100.0;

        // Parse title
        String title = top.optString("title", null);

        // Parse artist — MusicBrainz uses an array of "artist-credit" objects
        // because a track can have multiple artists (feat., collab., etc.)
        // We join them with " & " to produce a single readable string
        String artist = parseArtistCredit(top.optJSONArray("artist-credit"));

        // Parse release (album) info — again an array, we take the first entry
        String album = null;
        String year  = null;
        if (top.has("releases")) {
            JSONArray releases = top.optJSONArray("releases");
            if (releases != null && !releases.isEmpty()) {
                JSONObject release = releases.getJSONObject(0);
                album = release.optString("title", null);

                // Date is "YYYY-MM-DD" or just "YYYY" — we only want the year
                String date = release.optString("date", null);
                if (date != null && date.length() >= 4) {
                    year = date.substring(0, 4);
                }
            }
        }

        // Duration — MusicBrainz stores in milliseconds, we convert to seconds
        Double duration = null;
        if (top.has("length") && !top.isNull("length")) {
            duration = top.getLong("length") / 1000.0;
        }

        TrackMetadata metadata = TrackMetadata.builder(null) // filepath filled in by pipeline
                .title(title)
                .artist(artist)
                .album(album)
                .year(year)
                .durationSeconds(duration)
                .build();

        return ApiResult.of(SOURCE_NAME, metadata, confidence, rawJson);
    }

    // artist-credit is a mixed array: artist objects interleaved with join-phrase strings
    // e.g. [{"name": "Jay-Z"}, " feat. ", {"name": "Alicia Keys"}] → "Jay-Z feat. Alicia Keys"
    private static String parseArtistCredit(JSONArray credits) {
        if (credits == null || credits.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < credits.length(); i++) {
            Object item = credits.get(i);
            if (item instanceof JSONObject obj) {
                // Artist object — use "name" field (display name)
                String name = obj.optString("name", null);
                if (name != null) sb.append(name);
            } else if (item instanceof String joinPhrase) {
                // Plain join phrase like " & " or " feat. "
                sb.append(joinPhrase);
            }
        }

        String result = sb.toString().strip();
        return result.isEmpty() ? null : result;
    }

    // Characters like + - ! ( ) [ ] ^ " ~ * ? : \ / have special meaning in Lucene —
    // a title like "What's Going On?" would break the query without escaping
    private static String escapeLucene(String s) {
        // Characters with special meaning in Lucene: + - && || ! ( ) { } [ ] ^ " ~ * ? : \ /
        return s.replaceAll("([+\\-!(){}\\[\\]^\"~*?:\\\\/])", "\\\\$1");
    }
}

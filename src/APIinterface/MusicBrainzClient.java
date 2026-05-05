package APIinterface;

import lyrify.TrackMetadata;
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

    /**
     * User-Agent header MusicBrainz requires.
     * Format: "AppName/version (contact-url-or-email)"
     * Without this they may throttle or block the request.
     */
    private static final String USER_AGENT  = "Lyrify/1.0 (https://github.com/lyrify)";

    /**
     * How many candidate results to ask MusicBrainz for.
     * We only use the top one, but asking for a few lets us
     * potentially fall back if the top result is very low confidence.
     */
    private static final int RESULT_LIMIT = 5;

    // ------------------------------------------------------------------
    // Fields
    // ------------------------------------------------------------------

    /** Shared HTTP client — injected so it can be reused across all API clients. */
    private final ApiClient http;

    // ------------------------------------------------------------------
    // Constructor
    // ------------------------------------------------------------------

    /**
     * @param http the shared {@link ApiClient} instance — do not create a
     *             new one per API client, share a single instance across all
     */
    public MusicBrainzClient(ApiClient http) {
        this.http = http;
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Search MusicBrainz for a recording matching the given hints.
     *
     * <p>Any combination of parameters may be null — MusicBrainz will still
     * return results based on whatever is provided, just with lower confidence.
     * Providing at least {@code title} gives useful results; adding
     * {@code artist} and {@code durationSeconds} significantly improves accuracy.
     *
     * @param title           track title (most important field)
     * @param artist          performing artist
     * @param album           release/album name
     * @param durationSeconds audio duration — used to disambiguate recordings
     *                        that share a title (e.g. live vs studio versions)
     * @return {@link ApiResult} — always non-null; check {@link ApiResult#isUsable()}
     */
    public ApiResult search(String title, String artist, String album, Double durationSeconds) {

        // Need at least a title to search — without it the query is meaningless
        if (title == null || title.isBlank()) {
            return ApiResult.error(SOURCE_NAME, "Cannot search without a title");
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

    /**
     * Build a Lucene query string from available metadata fields.
     *
     * <p>MusicBrainz uses Lucene syntax for its search endpoint:
     * <ul>
     *   <li>{@code recording:"Bohemian Rhapsody"} — search by track title</li>
     *   <li>{@code artist:"Queen"}                — filter by artist name</li>
     *   <li>{@code release:"A Night at the Opera"} — filter by album</li>
     *   <li>{@code dur:[208000 TO 212000]}         — duration range in ms</li>
     * </ul>
     * Fields are combined with AND so all provided fields must match.
     */
    private static String buildQuery(String title, String artist,
                                      String album, Double durationSeconds) {
        StringBuilder q = new StringBuilder();

        // Title is always present (we checked above)
        q.append("recording:\"").append(escapeLucene(title)).append("\"");

        if (artist != null && !artist.isBlank()) {
            q.append(" AND artist:\"").append(escapeLucene(artist)).append("\"");
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

    /**
     * Assemble the full request URL from the query string.
     *
     * <p>Example output:
     * {@code https://musicbrainz.org/ws/2/recording?query=recording%3A%22Bohemian+...&limit=5&fmt=json}
     */
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

    /**
     * Parse the raw JSON response from MusicBrainz into an {@link ApiResult}.
     *
     * <p>MusicBrainz response structure (simplified):
     * <pre>{@code
     * {
     *   "recordings": [
     *     {
     *       "score": 98,               ← match confidence 0-100
     *       "title": "Bohemian Rhapsody",
     *       "length": 354000,          ← duration in milliseconds
     *       "artist-credit": [
     *         { "name": "Queen" }
     *       ],
     *       "releases": [
     *         {
     *           "title": "A Night at the Opera",
     *           "date": "1975-11-21",
     *           "track-count": 12
     *         }
     *       ]
     *     }
     *   ]
     * }
     * }</pre>
     */
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

        TrackMetadata metadata = TrackMetadata.builder("") // filepath filled in by pipeline
                .title(title)
                .artist(artist)
                .album(album)
                .year(year)
                .durationSeconds(duration)
                .build();

        return ApiResult.of(SOURCE_NAME, metadata, confidence, rawJson);
    }

    /**
     * Flatten a MusicBrainz {@code artist-credit} JSON array into a single
     * artist name string.
     *
     * <p>Each element in the array can be either an artist object or a plain
     * join phrase string (e.g. " feat. "). We concatenate them all together.
     *
     * <p>Example input:
     * <pre>{@code
     * [{"name": "Jay-Z"}, " feat. ", {"name": "Alicia Keys"}]
     * }</pre>
     * Result: {@code "Jay-Z feat. Alicia Keys"}
     */
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

    /**
     * Escape characters that have special meaning in Lucene query syntax.
     * Without this a title like "What's Going On?" would break the query.
     */
    private static String escapeLucene(String s) {
        // Characters with special meaning in Lucene: + - && || ! ( ) { } [ ] ^ " ~ * ? : \ /
        return s.replaceAll("([+\\-!(){}\\[\\]^\"~*?:\\\\/])", "\\\\$1");
    }
}

package APIinterface;

import lyrify.TrackMetadata;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

// Queries the Spotify Web API for track metadata.
// Uses OAuth Client Credentials flow — tokens are fetched and refreshed automatically.
// Needs a client ID + secret from developer.spotify.com.

public final class SpotifyClient {

    // ------------------------------------------------------------------
    // Constants
    // ------------------------------------------------------------------

    private static final String API_BASE      = "https://api.spotify.com/v1";
    private static final String TOKEN_URL     = "https://accounts.spotify.com/api/token";
    private static final String SOURCE_NAME   = "Spotify";

    /** How many search results to ask Spotify for per query. */
    private static final int RESULT_LIMIT = 5;

    // ------------------------------------------------------------------
    // Fields
    // ------------------------------------------------------------------

    private final ApiClient http;
    private final String    clientId;
    private final String    clientSecret;

    /**
     * Cached access token — reused until {@link #tokenExpiresAt} is in the past.
     * Starts null; fetched lazily on the first search call.
     */
    private String  accessToken;

    /**
     * When the current {@link #accessToken} expires, as a Unix epoch second.
     * We refresh 60 seconds early to avoid using a token that's about to expire.
     */
    private Instant tokenExpiresAt = Instant.EPOCH;

    /**
     * Separate HttpClient used only for the token endpoint — we bypass
     * {@link ApiClient} here because the token request uses POST with a
     * form body, which is different from our standard GET requests.
     */
    private final HttpClient tokenHttpClient;

    // ------------------------------------------------------------------
    // Constructor
    // ------------------------------------------------------------------

    /**
     * @param http         shared {@link ApiClient} instance
     * @param clientId     Spotify app client ID
     * @param clientSecret Spotify app client secret
     */
    public SpotifyClient(ApiClient http, String clientId, String clientSecret) {
        if (clientId     == null || clientId.isBlank())     throw new IllegalArgumentException("clientId required");
        if (clientSecret == null || clientSecret.isBlank()) throw new IllegalArgumentException("clientSecret required");
        this.http         = http;
        this.clientId     = clientId;
        this.clientSecret = clientSecret;
        this.tokenHttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Search Spotify for a track matching the given hints.
     *
     * <p>Automatically handles token refresh if the current token has expired.
     *
     * @param title  track title
     * @param artist artist name
     * @param album  album name (optional — omit to broaden the search)
     * @return {@link ApiResult} — always non-null
     */
    public ApiResult search(String title, String artist, String album) {
        if (title == null || title.isBlank()) {
            return ApiResult.error(SOURCE_NAME, "Cannot search without a title");
        }

        // Ensure we have a valid token before making the search request
        try {
            ensureToken();
        } catch (ApiException e) {
            return ApiResult.error(SOURCE_NAME, "Auth failed: " + e.getMessage());
        }

        String url     = buildSearchUrl(title, artist, album);
        String rawJson;

        try {
            rawJson = http.get(SOURCE_NAME, url,
                    "Authorization", "Bearer " + accessToken);
        } catch (ApiException e) {
            return ApiResult.error(SOURCE_NAME, e.getMessage());
        }

        return parse(rawJson);
    }

    // ------------------------------------------------------------------
    // Token management
    // ------------------------------------------------------------------

    /**
     * Fetch a new access token if the current one is missing or about to expire.
     *
     * <p>The token request uses HTTP Basic authentication where the credentials
     * are {@code clientId:clientSecret} encoded in Base64. This is Spotify's
     * required format for the Client Credentials flow.
     *
     * <p>We refresh 60 seconds before actual expiry to avoid edge cases where
     * a token expires mid-request.
     */
    private void ensureToken() throws ApiException {
        // If token is still valid (with 60-second buffer), do nothing
        if (accessToken != null && Instant.now().isBefore(tokenExpiresAt.minusSeconds(60))) {
            return;
        }

        // Build Basic auth header: Base64("clientId:clientSecret")
        String credentials = clientId + ":" + clientSecret;
        String basicAuth   = "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        // Token request body — application/x-www-form-urlencoded
        String body = "grant_type=client_credentials";

        HttpRequest tokenRequest = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization",  basicAuth)
                .header("Content-Type",   "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        String responseBody;
        try {
            HttpResponse<String> response = tokenHttpClient.send(
                    tokenRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new ApiException(SOURCE_NAME,
                        "Token request failed with HTTP " + response.statusCode());
            }
            responseBody = response.body();
        } catch (Exception e) {
            throw new ApiException(SOURCE_NAME, "Token request failed: " + e.getMessage(), e);
        }

        // Parse token response
        JSONObject tokenJson;
        try {
            tokenJson = new JSONObject(responseBody);
        } catch (Exception e) {
            throw new ApiException(SOURCE_NAME, "Could not parse token response");
        }

        accessToken    = tokenJson.getString("access_token");
        int expiresIn  = tokenJson.optInt("expires_in", 3600); // default 1 hour
        tokenExpiresAt = Instant.now().plusSeconds(expiresIn);
    }

    // ------------------------------------------------------------------
    // URL builder
    // ------------------------------------------------------------------

    /**
     * Build the Spotify search URL.
     *
     * <p>Spotify search query syntax:
     * <ul>
     *   <li>{@code track:Bohemian Rhapsody} — search by track name</li>
     *   <li>{@code artist:Queen}            — filter by artist</li>
     *   <li>{@code album:A Night at the Opera} — filter by album</li>
     * </ul>
     * Fields are combined with spaces (implicit AND).
     */
    private static String buildSearchUrl(String title, String artist, String album) {
        StringBuilder q = new StringBuilder();
        q.append("track:").append(title);
        if (artist != null && !artist.isBlank()) q.append(" artist:").append(artist);
        if (album  != null && !album.isBlank())  q.append(" album:").append(album);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("q",     q.toString());
        params.put("type",  "track");
        params.put("limit", String.valueOf(RESULT_LIMIT));

        return API_BASE + "/search?" + ApiClient.buildQuery(params);
    }

    // ------------------------------------------------------------------
    // Response parser
    // ------------------------------------------------------------------

    /**
     * Parse the Spotify search JSON response.
     *
     * <p>Spotify response structure (simplified):
     * <pre>{@code
     * {
     *   "tracks": {
     *     "items": [
     *       {
     *         "name": "Bohemian Rhapsody",
     *         "popularity": 87,              ← 0-100, used to help weight confidence
     *         "duration_ms": 354320,
     *         "track_number": 11,
     *         "artists": [{ "name": "Queen" }],
     *         "album": {
     *           "name": "A Night at the Opera",
     *           "release_date": "1975-11-21",
     *           "album_type": "album"
     *         }
     *       }
     *     ]
     *   }
     * }
     * }</pre>
     *
     * <p>Spotify does not return a match confidence score the way MusicBrainz
     * does. We derive confidence from:
     * <ul>
     *   <li>Whether a result was found at all</li>
     *   <li>The track's {@code popularity} score (0–100)</li>
     *   <li>How many of our query fields matched (title, artist, album)</li>
     * </ul>
     */
    private static ApiResult parse(String rawJson) {
        JSONObject root;
        try {
            root = new JSONObject(rawJson);
        } catch (Exception e) {
            return ApiResult.error(SOURCE_NAME, "Failed to parse JSON: " + e.getMessage());
        }

        JSONObject tracks = root.optJSONObject("tracks");
        if (tracks == null) return ApiResult.empty(SOURCE_NAME);

        JSONArray items = tracks.optJSONArray("items");
        if (items == null || items.isEmpty()) return ApiResult.empty(SOURCE_NAME);

        JSONObject top = items.getJSONObject(0);

        String title       = top.optString("name", null);
        double durationSec = top.optLong("duration_ms", 0) / 1000.0;
        int    trackNum    = top.optInt("track_number", 0);
        int    popularity  = top.optInt("popularity", 0);  // 0-100

        // Artist — Spotify also uses an array (like MusicBrainz)
        String artist = null;
        JSONArray artists = top.optJSONArray("artists");
        if (artists != null && !artists.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < artists.length(); i++) {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(artists.getJSONObject(i).optString("name", ""));
            }
            artist = sb.toString().strip();
            if (artist.isEmpty()) artist = null;
        }

        // Album
        String album = null;
        String year  = null;
        JSONObject albumObj = top.optJSONObject("album");
        if (albumObj != null) {
            album = albumObj.optString("name", null);
            String releaseDate = albumObj.optString("release_date", null);
            if (releaseDate != null && releaseDate.length() >= 4) {
                year = releaseDate.substring(0, 4);
            }
        }

        // Derive confidence:
        // Base 0.7 for a result existing, scaled up by popularity (0-0.2),
        // and down if the result looks like a low-quality match (popularity < 10)
        double confidence = 0.70 + (popularity / 100.0) * 0.20;
        if (popularity < 10) confidence -= 0.15; // penalise very obscure results
        confidence = Math.max(0.0, Math.min(1.0, confidence)); // clamp to [0, 1]

        TrackMetadata metadata = TrackMetadata.builder("")
                .title(title)
                .artist(artist)
                .album(album)
                .year(year)
                .trackNumber(trackNum > 0 ? String.valueOf(trackNum) : null)
                .durationSeconds(durationSec > 0 ? durationSec : null)
                .build();

        return ApiResult.of(SOURCE_NAME, metadata, confidence, rawJson);
    }
}

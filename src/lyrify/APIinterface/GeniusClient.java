package lyrify.APIinterface;

import lyrify.FileInterface.TrackMetadata;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

// Queries Genius for lyrics. Two steps: search API to find the page, then scrape the HTML.
// Scraping is unavoidable — Genius doesn't serve lyrics through their API directly.
// Needs a bearer token from genius.com/api-clients.

public final class GeniusClient {

    // ------------------------------------------------------------------
    // Constants
    // ------------------------------------------------------------------

    private static final String API_BASE    = "https://api.genius.com";
    private static final String SOURCE_NAME = "Genius";

    /** How many search results to request. */
    private static final int RESULT_LIMIT = 5;

    // ------------------------------------------------------------------
    // Fields
    // ------------------------------------------------------------------

    private final ApiClient http;
    private final String    bearerToken;

    // ------------------------------------------------------------------
    // Constructor
    // ------------------------------------------------------------------

    /**
     * @param http        shared {@link ApiClient} instance
     * @param bearerToken your Genius Client Access Token
     */
    public GeniusClient(ApiClient http, String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank())
            throw new IllegalArgumentException("Genius bearer token must not be blank");
        this.http        = http;
        this.bearerToken = bearerToken;
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Search Genius for a song and return its metadata + lyrics.
     *
     * <p>This performs two HTTP requests:
     * <ol>
     *   <li>Search the Genius API to find the song's page URL</li>
     *   <li>Fetch and scrape that page to extract the lyrics text</li>
     * </ol>
     *
     * @param title  song title
     * @param artist artist name (helps narrow the search)
     * @return {@link ApiResult} — always non-null
     */
    public ApiResult search(String title, String artist) {
        if (title == null || title.isBlank()) {
            return ApiResult.error(SOURCE_NAME, "Cannot search without a title");
        }

        // Step 1: find the song via the search endpoint
        SongHit hit = findSong(title, artist);
        if (hit == null) {
            return ApiResult.empty(SOURCE_NAME);
        }

        // Step 2: scrape lyrics from the song's Genius page
        String lyrics = fetchLyrics(hit.url());

        // Build metadata — Genius gives us title and artist from the search,
        // and lyrics from the page scrape
        TrackMetadata metadata = TrackMetadata.builder("")
                .title(hit.title())
                .artist(hit.artist())
                .lyrics(lyrics)
                .build();

        // Confidence: we base it on how closely the search terms matched.
        // If artist was provided and matched, confidence is higher.
        double confidence = computeConfidence(title, artist, hit.title(), hit.artist());

        return ApiResult.of(SOURCE_NAME, metadata, confidence, hit.rawJson());
    }

    // ------------------------------------------------------------------
    // Search — step 1
    // ------------------------------------------------------------------

    /**
     * Internal container for a matched Genius song entry.
     *
     * @param title   song title from Genius
     * @param artist  artist name from Genius
     * @param url     URL to the Genius lyrics page
     * @param rawJson raw JSON of this hit for debugging
     */
    private record SongHit(String title, String artist, String url, String rawJson) {}

    /**
     * Search the Genius API for a song and return the best hit, or null
     * if nothing was found.
     *
     * <p>Genius search response structure:
     * <pre>{@code
     * {
     *   "response": {
     *     "hits": [
     *       {
     *         "type": "song",
     *         "result": {
     *           "title": "Bohemian Rhapsody",
     *           "url": "https://genius.com/Queen-bohemian-rhapsody-lyrics",
     *           "primary_artist": {
     *             "name": "Queen"
     *           }
     *         }
     *       }
     *     ]
     *   }
     * }
     * }</pre>
     */
    private SongHit findSong(String title, String artist) {
        // Combine title and artist into a single search string
        String searchTerm = (artist != null && !artist.isBlank())
                ? artist + " " + title
                : title;

        Map<String, String> params = new LinkedHashMap<>();
        params.put("q",       searchTerm);
        params.put("per_page", String.valueOf(RESULT_LIMIT));
        String url = API_BASE + "/search?" + ApiClient.buildQuery(params);

        String rawJson;
        try {
            rawJson = http.get(SOURCE_NAME, url,
                    "Authorization", "Bearer " + bearerToken);
        } catch (ApiException e) {
            return null;
        }

        // Parse
        try {
            JSONObject root     = new JSONObject(rawJson);
            JSONObject response = root.optJSONObject("response");
            if (response == null) return null;

            JSONArray hits = response.optJSONArray("hits");
            if (hits == null || hits.isEmpty()) return null;

            // Walk through hits looking for the first one of type "song"
            for (int i = 0; i < hits.length(); i++) {
                JSONObject hit = hits.getJSONObject(i);
                if (!"song".equals(hit.optString("type"))) continue;

                JSONObject result  = hit.optJSONObject("result");
                if (result == null) continue;

                String hitTitle  = result.optString("title",           null);
                String hitUrl    = result.optString("url",             null);
                JSONObject pa    = result.optJSONObject("primary_artist");
                String hitArtist = pa != null ? pa.optString("name",   null) : null;

                if (hitUrl != null) {
                    return new SongHit(hitTitle, hitArtist, hitUrl, result.toString());
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    // ------------------------------------------------------------------
    // Lyrics scraping — step 2
    // ------------------------------------------------------------------

    /**
     * Fetch and parse the lyrics from a Genius song page.
     *
     * <p>The Genius API doesn't serve lyrics directly, so we fetch the HTML
     * page and extract lyrics from it. Genius wraps lyrics in
     * {@code <div data-lyrics-container="true">} elements.
     *
     * <p>We strip HTML tags and normalise line breaks to produce clean plain
     * text suitable for writing into an ID3 USLT frame or an LRC file.
     *
     * @param pageUrl the Genius song page URL (e.g. https://genius.com/Queen-...)
     * @return lyrics as plain text, or null if scraping failed
     */
    private String fetchLyrics(String pageUrl) {
        String html;
        try {
            // Fetch the page — note we're fetching HTML, not JSON, so we
            // use the raw URL directly rather than building an API URL
            html = http.get(SOURCE_NAME, pageUrl,
                    "User-Agent", "Mozilla/5.0 (compatible; Lyrify/1.0)");
        } catch (ApiException e) {
            return null;
        }

        return parseLyricsFromHtml(html);
    }

    /**
     * Extract the lyrics text from Genius page HTML.
     *
     * <p>Strategy: find all {@code data-lyrics-container="true"} div blocks,
     * extract their inner text, strip HTML tags, and join with newlines.
     *
     * <p>Note: this is inherently fragile — if Genius changes their page
     * structure, this parser will need updating. This is the unavoidable
     * trade-off of scraping rather than using a proper API endpoint.
     */
    private static String parseLyricsFromHtml(String html) {
        if (html == null || html.isBlank()) return null;

        StringBuilder lyrics = new StringBuilder();

        // Find all data-lyrics-container divs
        String marker = "data-lyrics-container=\"true\"";
        int pos = 0;

        while (true) {
            int start = html.indexOf(marker, pos);
            if (start < 0) break;

            // Find the closing > of this opening tag
            int tagEnd = html.indexOf('>', start);
            if (tagEnd < 0) break;

            // Now find the matching closing </div>
            // We track nesting depth because lyrics containers can have inner divs
            int depth    = 1;
            int searchAt = tagEnd + 1;
            int end      = -1;

            while (searchAt < html.length() && depth > 0) {
                int nextOpen  = html.indexOf("<div",  searchAt);
                int nextClose = html.indexOf("</div>", searchAt);

                if (nextClose < 0) break;

                if (nextOpen >= 0 && nextOpen < nextClose) {
                    depth++;
                    searchAt = nextOpen + 4;
                } else {
                    depth--;
                    if (depth == 0) end = nextClose;
                    searchAt = nextClose + 6;
                }
            }

            if (end > tagEnd) {
                String block = html.substring(tagEnd + 1, end);
                // Replace <br> tags with newlines before stripping all other tags
                block = block.replaceAll("(?i)<br\\s*/?>", "\n");
                // Strip remaining HTML tags
                block = block.replaceAll("<[^>]+>", "");
                // Decode common HTML entities
                block = decodeHtmlEntities(block);
                if (!lyrics.isEmpty()) lyrics.append("\n");
                lyrics.append(block.strip());
            }

            pos = tagEnd + 1;
        }

        String result = lyrics.toString().strip();
        return result.isEmpty() ? null : result;
    }

    /** Decode the most common HTML entities found in Genius lyrics. */
    private static String decodeHtmlEntities(String s) {
        return s.replace("&amp;",  "&")
                .replace("&lt;",   "<")
                .replace("&gt;",   ">")
                .replace("&quot;", "\"")
                .replace("&#39;",  "'")
                .replace("&nbsp;", " ");
    }

    // ------------------------------------------------------------------
    // Confidence computation
    // ------------------------------------------------------------------

    /**
     * Estimate match confidence by comparing the query terms against
     * what Genius actually returned.
     *
     * <p>Uses a simple normalised character-overlap metric rather than a
     * full string similarity algorithm (that lives in the scoring layer).
     * Here we just need a rough estimate.
     */
    private static double computeConfidence(String queryTitle, String queryArtist,
                                             String hitTitle,   String hitArtist) {
        double titleSim  = similarity(queryTitle,  hitTitle);
        double artistSim = queryArtist != null && hitArtist != null
                ? similarity(queryArtist, hitArtist)
                : 0.5; // neutral if we couldn't check

        // Weight: title matters more than artist for lyrics retrieval
        double confidence = (titleSim * 0.65) + (artistSim * 0.35);
        return Math.max(0.0, Math.min(1.0, confidence));
    }

    /**
     * Simple character-bigram similarity between two strings.
     * Returns 1.0 for identical strings, 0.0 for completely different ones.
     * Case-insensitive.
     */
    private static double similarity(String a, String b) {
        if (a == null || b == null) return 0.0;
        a = a.toLowerCase();
        b = b.toLowerCase();
        if (a.equals(b)) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;

        // Build bigram sets
        var bigramsA = bigrams(a);
        var bigramsB = bigrams(b);

        long intersection = bigramsA.stream().filter(bigramsB::contains).count();
        double union = bigramsA.size() + bigramsB.size() - intersection;
        return union == 0 ? 0.0 : intersection / union;
    }

    private static java.util.List<String> bigrams(String s) {
        java.util.List<String> result = new java.util.ArrayList<>();
        for (int i = 0; i < s.length() - 1; i++) {
            result.add(s.substring(i, i + 2));
        }
        return result;
    }
}

package APIinterface;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Shared HTTP client used by all API implementations.
// Handles rate limiting, retries with exponential backoff, and URL encoding.

public final class ApiClient {

    // ------------------------------------------------------------------
    // Configuration constants
    // ------------------------------------------------------------------

    /** How long to wait for a connection to be established. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** How long to wait for a full response body after connecting. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /**
     * How many times to retry a request that gets a transient error
     * (429 Too Many Requests or 503 Service Unavailable).
     */
    private static final int MAX_RETRIES = 3;

    /**
     * Base delay before the first retry, in milliseconds.
     * Each subsequent retry doubles this (exponential backoff):
     * retry 1 → 500ms, retry 2 → 1000ms, retry 3 → 2000ms.
     */
    private static final long RETRY_BASE_DELAY_MS = 500;

    // ------------------------------------------------------------------
    // Rate limiting
    // Each API has its own minimum gap between requests, stored here.
    // Key = source name (e.g. "MusicBrainz"), Value = min gap in ms.
    // ------------------------------------------------------------------

    /**
     * Minimum milliseconds between consecutive requests to the same API.
     * MusicBrainz publicly asks for at most 1 request/second from scripts.
     * Spotify and Genius are more lenient but we still throttle to be safe.
     */
    private static final Map<String, Long> RATE_LIMITS = Map.of(
            "MusicBrainz", 1100L,   // 1 req/sec + small safety margin
            "AcoustID",    334L,    // ~3 req/sec
            "Spotify",     200L,    // 5 req/sec (well under their real limit)
            "Genius",      200L
    );

    /**
     * Tracks the timestamp (epoch ms) of the last successful request per source.
     * {@link ConcurrentHashMap} so this is safe if we ever parallelise calls.
     */
    private final ConcurrentHashMap<String, Long> lastRequestTime = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // The underlying Java 21 HttpClient
    // ------------------------------------------------------------------

    /**
     * Java 21's built-in HTTP client — no third-party library needed.
     * Configured once and reused for every request (it manages connection
     * pooling internally).
     */
    private final HttpClient httpClient;

    // ------------------------------------------------------------------
    // Constructor
    // ------------------------------------------------------------------

    public ApiClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                // Follow redirects automatically (some APIs redirect http→https)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ------------------------------------------------------------------
    // Core request method
    // ------------------------------------------------------------------

    /**
     * Send a GET request to {@code url} with the given HTTP headers, and
     * return the response body as a plain String.
     *
     * <p>This method:
     * <ol>
     *   <li>Waits for the rate-limit window for {@code source} to pass</li>
     *   <li>Sends the request with the configured timeout</li>
     *   <li>Retries up to {@value MAX_RETRIES} times on 429 / 503 responses</li>
     *   <li>Records the timestamp of the successful request for future throttling</li>
     * </ol>
     *
     * @param source  API name — used for rate limiting (must match a key in
     *                {@link #RATE_LIMITS}, or a default 200ms gap is used)
     * @param url     fully formed request URL including query parameters
     * @param headers zero or more key-value pairs added as HTTP headers,
     *                e.g. {@code "Authorization", "Bearer abc123"}
     * @return response body as a UTF-8 string
     * @throws ApiException if the request fails after all retries
     */
    public String get(String source, String url, String... headers) throws ApiException {
        // Enforce rate limit before sending
        rateLimit(source);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .GET();

        // Add headers in pairs: headers[0]=key, headers[1]=value, etc.
        // We walk the array two elements at a time
        for (int i = 0; i + 1 < headers.length; i += 2) {
            requestBuilder.header(headers[i], headers[i + 1]);
        }

        HttpRequest request = requestBuilder.build();

        // Retry loop — attempt up to MAX_RETRIES times on transient failures
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                int status = response.statusCode();

                // 200 OK — happy path
                if (status == 200) {
                    // Record this request time so the next call knows to wait
                    lastRequestTime.put(source, System.currentTimeMillis());
                    return response.body();
                }

                // 429 Too Many Requests or 503 Service Unavailable — retry after backoff
                if ((status == 429 || status == 503) && attempt <= MAX_RETRIES) {
                    long delay = RETRY_BASE_DELAY_MS * (1L << (attempt - 1)); // 500, 1000, 2000
                    System.err.printf("[%s] HTTP %d — retrying in %dms (attempt %d/%d)%n",
                            source, status, delay, attempt, MAX_RETRIES);
                    sleep(delay);
                    continue; // go back to top of while loop
                }

                // Any other non-200 status — give up immediately
                throw new ApiException(source,
                        "HTTP %d from %s".formatted(status, url));

            } catch (IOException | InterruptedException e) {
                // Network-level failure (timeout, DNS, connection refused, etc.)
                if (attempt <= MAX_RETRIES) {
                    long delay = RETRY_BASE_DELAY_MS * (1L << (attempt - 1));
                    System.err.printf("[%s] Network error: %s — retrying in %dms%n",
                            source, e.getMessage(), delay);
                    sleep(delay);
                } else {
                    throw new ApiException(source,
                            "Request failed after %d attempts: %s".formatted(attempt, e.getMessage()), e);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // URL / query string helpers
    // ------------------------------------------------------------------

    /**
     * URL-encode a single string value so it is safe to include in a
     * query parameter.
     *
     * <p>Example: {@code encode("Led Zeppelin")} → {@code "Led+Zeppelin"}
     */
    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Build a query string from a map of parameter names to values.
     *
     * <p>Example:
     * <pre>{@code
     * buildQuery(Map.of("q", "Bohemian Rhapsody", "limit", "5"))
     * // → "q=Bohemian+Rhapsody&limit=5"
     * }</pre>
     *
     * <p>The map is iterated in insertion order if a {@link java.util.LinkedHashMap}
     * is passed — useful when the API cares about parameter ordering.
     */
    public static String buildQuery(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (var entry : params.entrySet()) {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(encode(entry.getKey()))
              .append('=')
              .append(encode(entry.getValue()));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Rate limiting — internal
    // ------------------------------------------------------------------

    /**
     * Block the calling thread until enough time has passed since the last
     * request to {@code source}.
     *
     * <p>Uses the gap defined in {@link #RATE_LIMITS}, defaulting to 200ms
     * for any unknown source name.
     */
    private void rateLimit(String source) {
        long minGapMs = RATE_LIMITS.getOrDefault(source, 200L);
        Long last = lastRequestTime.get(source);

        if (last != null) {
            long elapsed = System.currentTimeMillis() - last;
            long waitMs  = minGapMs - elapsed;
            if (waitMs > 0) {
                sleep(waitMs);
            }
        }
    }

    /**
     * Sleep for {@code ms} milliseconds, swallowing {@link InterruptedException}
     * and restoring the interrupt flag so callers can detect it if needed.
     */
    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // restore interrupt flag
        }
    }
}

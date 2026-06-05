package lyrify.APIinterface;

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

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    // Transient errors (429, 503) trigger retries with exponential backoff
    private static final int MAX_RETRIES = 3;

    // Retry delays: attempt 1 → 500ms, attempt 2 → 1000ms, attempt 3 → 2000ms
    private static final long RETRY_BASE_DELAY_MS = 500;

    // ------------------------------------------------------------------
    // Rate limiting
    // Each API has its own minimum gap between requests, stored here.
    // Key = source name (e.g. "MusicBrainz"), Value = min gap in ms.
    // ------------------------------------------------------------------

    // MusicBrainz publicly asks for at most 1 request/second from scripts
    private static final Map<String, Long> RATE_LIMITS = Map.of(
            "MusicBrainz", 1100L,   // 1 req/sec + small safety margin
            "AcoustID",    334L,    // ~3 req/sec
            "Genius",      200L
    );

    // ConcurrentHashMap so this is safe when parallel API calls share this client
    private final ConcurrentHashMap<String, Long> lastRequestTime = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // The underlying Java 21 HttpClient
    // ------------------------------------------------------------------

    // Configured once and reused — manages connection pooling internally
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

    public String get(String source, String url, String... headers) throws ApiException {
        // Enforces rate limit before sending
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
                    lastRequestTime.put(source, System.currentTimeMillis());
                    return response.body();
                }
                // 403 -
                if (status == 403) {
                    System.out.println("[GET DEBUG] 403 body: " + response.body());
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

    public String post(String source, String url, String formBody) throws ApiException {
        rateLimit(source);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                .build();

        int attempt = 0;
        while (true) {
            attempt++;
            try {
                HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if (status == 200) {
                    lastRequestTime.put(source, System.currentTimeMillis());
                    return response.body();
                }
                // Add this:
                if (status == 400) {
                    System.out.println("[POST DEBUG] 400 response body: " + response.body());
                }
                if ((status == 429 || status == 503) && attempt <= MAX_RETRIES) {
                    long delay = RETRY_BASE_DELAY_MS * (1L << (attempt - 1));
                    sleep(delay);
                    continue;
                }
                throw new ApiException(source, "[" + source + "] HTTP " + status + " from " + url);
            } catch (IOException | InterruptedException e) {
                if (attempt <= MAX_RETRIES) {
                    sleep(RETRY_BASE_DELAY_MS * (1L << (attempt - 1)));
                } else {
                    throw new ApiException(source, "Request failed: " + e.getMessage(), e);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // URL / query string helpers
    // ------------------------------------------------------------------

    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // Pass a LinkedHashMap to preserve parameter ordering if the API requires it
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

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // restore interrupt flag
        }
    }
}

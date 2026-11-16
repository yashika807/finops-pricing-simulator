import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP GET with an on-disk cache. Every response remembers when it was fetched, so the UI can
 * show the exact retrieval time of each live price. If the network is down, the last good
 * response is reused and flagged as "stale" instead of falling back to made-up numbers.
 */
final class Http {
    static final String USER_AGENT = "FinOpsPricingSimulator/2.0 (university cloud-computing project; Java HttpClient)";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Path CACHE_DIR = System.getProperty("finops.home") != null
            ? Paths.get(System.getProperty("finops.home"), "cache") : Paths.get("cache");
    private static final ConcurrentHashMap<String, Fetched> MEMORY = new ConcurrentHashMap<>();

    static final class Fetched {
        final String url, body;
        final Instant fetchedAt;
        final boolean fromCache, stale;
        Fetched(String url, String body, Instant fetchedAt, boolean fromCache, boolean stale) {
            this.url = url; this.body = body; this.fetchedAt = fetchedAt; this.fromCache = fromCache; this.stale = stale;
        }
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }

    static Fetched get(String url, Duration ttl) throws IOException {
        Fetched mem = MEMORY.get(url);
        if (mem != null && mem.fetchedAt.plus(ttl).isAfter(Instant.now())) return mem;

        Path file = CACHE_DIR.resolve(Integer.toHexString(url.hashCode()) + "_" + url.length() + ".cache");
        Fetched disk = readDisk(url, file);
        if (disk != null && disk.fetchedAt.plus(ttl).isAfter(Instant.now())) {
            MEMORY.put(url, disk);
            return disk;
        }
        try {
            String body = download(url);
            Fetched f = new Fetched(url, body, Instant.now(), false, false);
            writeDisk(file, f);
            MEMORY.put(url, f);
            return f;
        } catch (IOException | InterruptedException e) {
            if (disk != null) {
                Fetched stale = new Fetched(url, disk.body, disk.fetchedAt, true, true);
                MEMORY.put(url, stale);
                return stale;
            }
            throw new IOException("Could not reach " + URI.create(url).getHost() + ": " + e.getMessage(), e);
        }
    }

    private static String download(String url) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(40))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() == 200) return res.body();
            if (res.statusCode() == 404) throw new IOException("HTTP 404 (not found)");
            if (res.statusCode() == 429 || res.statusCode() >= 500) {
                Thread.sleep(1500L * (attempt + 1)); // simple back-off for rate limits
                continue;
            }
            throw new IOException("HTTP " + res.statusCode());
        }
        throw new IOException("Service busy (rate limited) - try again in a minute");
    }

    private static Fetched readDisk(String url, Path file) {
        try {
            if (!Files.exists(file)) return null;
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            int nl = raw.indexOf('\n');
            int nl2 = raw.indexOf('\n', nl + 1);
            if (!raw.substring(0, nl).equals(url)) return null;
            Instant at = Instant.parse(raw.substring(nl + 1, nl2));
            return new Fetched(url, raw.substring(nl2 + 1), at, true, false);
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeDisk(Path file, Fetched f) {
        try {
            Files.createDirectories(CACHE_DIR);
            Files.writeString(file, f.url + "\n" + f.fetchedAt + "\n" + f.body, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Cache is an optimisation only.
        }
    }
}

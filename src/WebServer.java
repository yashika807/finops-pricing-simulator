import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;

/** Serves the live web dashboard and a JSON API backed by the same Java engine. */
final class WebServer {

    static HttpServer start(int port, Path webDir) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/api/meta", ex -> send(ex, 200, "application/json", Json.write(meta())));
        server.createContext("/api/simulate", WebServer::simulate);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";
            Path file = webDir.resolve(path.substring(1)).normalize();
            if (!file.startsWith(webDir) || !Files.isRegularFile(file)) { send(ex, 404, "text/plain", "Not found"); return; }
            String type = path.endsWith(".html") ? "text/html; charset=utf-8" : path.endsWith(".js") ? "text/javascript"
                    : path.endsWith(".css") ? "text/css" : path.endsWith(".csv") ? "text/csv" : "application/octet-stream";
            send(ex, 200, type, Files.readAllBytes(file));
        });
        server.start();
        return server;
    }

    static Map<String, Object> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Object> regions = new ArrayList<>();
        PriceService.REGIONS.forEach((k, v) -> regions.add(Map.of("id", k, "label", v[0])));
        List<Object> skus = new ArrayList<>();
        PriceService.SKUS.forEach((k, v) -> skus.add(Map.of("id", k, "label", v[0], "vcpu", v[1], "ramGb", v[2])));
        List<Object> presets = new ArrayList<>();
        for (WorkloadService.Preset p : WorkloadService.Preset.values())
            presets.add(Map.of("id", p.name(), "label", p.label, "description", p.description));
        m.put("regions", regions);
        m.put("skus", skus);
        m.put("currencies", Arrays.asList(PriceService.CURRENCIES));
        m.put("presets", presets);
        return m;
    }

    private static void simulate(HttpExchange ex) throws IOException {
        try {
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            String csv = null;
            if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                try (InputStream in = ex.getRequestBody()) {
                    byte[] body = in.readNBytes(2_000_000);
                    csv = new String(body, StandardCharsets.UTF_8);
                }
            }
            Engine.Inputs in = new Engine.Inputs();
            in.avgFleet = clamp(num(q, "fleet", 10), 1, 5000);
            in.demandMultiplier = clamp(num(q, "demand", 1), 0.1, 10);
            in.priceMultiplier = clamp(num(q, "price", 1), 0.1, 10);
            in.storageGb = clamp(num(q, "storage", 500), 0, 1e7);
            in.egressGb = clamp(num(q, "egress", 300), 0, 1e7);
            in.termYears = "3".equals(q.get("term")) ? 3 : 1;
            in.interruptionTolerant = !"false".equals(q.get("tolerant"));
            in.restartMinutes = clamp(num(q, "restart", 5), 0, 600);
            in.checkpointMinutes = clamp(num(q, "checkpoint", 30), 0, 1440);
            in.impactPerInterruption = clamp(num(q, "impact", 0), 0, 1e7);

            PriceService.PriceSheet p = PriceService.load(q.getOrDefault("region", "eastus"),
                    q.getOrDefault("sku", "Standard_D2s_v5"), q.getOrDefault("currency", "USD"));
            WorkloadService.Preset preset = WorkloadService.Preset.valueOf(q.getOrDefault("workload", "STEADY"));
            WorkloadService.Workload w = WorkloadService.load(preset, q.get("article"), csv);
            Map<String, Object> report = Engine.report(p, w, in, !"false".equals(q.get("chargeback")));
            send(ex, 200, "application/json", Json.write(report));
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            send(ex, 502, "application/json", Json.write(Map.of("error", msg)));
        }
    }

    private static double num(Map<String, String> q, String k, double def) {
        try { return q.containsKey(k) ? Double.parseDouble(q.get(k)) : def; } catch (NumberFormatException e) { return def; }
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            m.put(URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        send(ex, code, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }
}

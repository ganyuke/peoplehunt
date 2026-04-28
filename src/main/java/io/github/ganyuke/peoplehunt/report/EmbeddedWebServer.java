package io.github.ganyuke.peoplehunt.report;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.ganyuke.peoplehunt.report.ReportModels.IndexEntry;
import io.github.ganyuke.peoplehunt.util.HtmlUtil;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class EmbeddedWebServer {
    private final HttpServer server;
    private final ReportService reportService;
    private final ViewerAssets viewerAssets;
    private final Gson gson;
    private final Logger logger;

    public EmbeddedWebServer(ReportService reportService, ViewerAssets viewerAssets, Gson gson, Logger logger, String bindAddress, int port) throws IOException {
        this.reportService = reportService;
        this.viewerAssets = viewerAssets;
        this.gson = gson;
        this.logger = logger;
        // The bind address selects which local interface this embedded server listens on. Typical
        // values are 127.0.0.1 (local only), 0.0.0.0 (all interfaces), or a specific local IP.
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(bindAddress), port), 0);
        createContexts();
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    private String renderViewerHtml(UUID reportId) throws IOException {
        return viewerAssets.render(reportId.toString(), gson.toJson(reportService.readSnapshot(reportId)));
    }

    public String renderExportHtml(UUID reportId) throws IOException {
        return viewerAssets.render("LOCAL_EXPORT", gson.toJson(reportService.readSnapshot(reportId)));
    }

    private void createContexts() {
        server.createContext("/", this::handleIndex);
        server.createContext("/report", this::handleReport);
        server.createContext("/api/report", this::handleReportApi);
    }

    private void handleIndex(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "Method not allowed", "text/plain");
            return;
        }
        try {
            List<IndexEntry> entries = reportService.listReports();
            respond(exchange, 200, renderIndexHtml(entries), "text/html; charset=utf-8");
        } catch (Exception exception) {
            logRequestFailure(exchange, "render the report index", exception);
            respond(exchange, 500, "Unable to render report index.", "text/plain; charset=utf-8");
        }
    }

    private String renderIndexHtml(List<IndexEntry> entries) {
        String rows = entries.isEmpty()
                ? "<tr class='empty-row'><td colspan='5'>No reports recorded yet.</td></tr>"
                : entries.stream().map(this::renderIndexRow).reduce("", String::concat);

        IndexEntry latest = entries.isEmpty() ? null : entries.getFirst();
        long latestEndedAt = latest == null ? 0L : latest.endedAtEpochMillis();
        String latestHref = latest == null ? "#" : HtmlUtil.escape("/report/" + latest.reportId());

        long runnerWins = entries.stream().filter(entry -> "RUNNER_VICTORY".equalsIgnoreCase(entry.outcome())).count();
        long hunterWins = entries.stream().filter(entry -> "HUNTER_VICTORY".equalsIgnoreCase(entry.outcome())).count();
        long inconclusive = entries.stream().filter(entry -> "INCONCLUSIVE".equalsIgnoreCase(entry.outcome())).count();

        Map<String, String> replacements = new LinkedHashMap<>();
        replacements.put("__MATCH_COUNT__", Integer.toString(entries.size()));
        replacements.put("__RUNNER_WINS__", Long.toString(runnerWins));
        replacements.put("__HUNTER_WINS__", Long.toString(hunterWins));
        replacements.put("__INCONCLUSIVE__", Long.toString(inconclusive));
        replacements.put("__LATEST_ENDED_AT__", Long.toString(latestEndedAt));
        replacements.put(
                "__LATEST_ACTION__",
                latest == null
                        ? "<span class='open-btn' aria-disabled='true'>No reports yet</span>"
                        : "<a class='open-btn' href='" + latestHref + "'>Open latest</a>"
        );
        replacements.put("__INDEX_ROWS__", rows);
        return viewerAssets.renderIndex(replacements);
    }

    private String renderIndexRow(IndexEntry entry) {
        long durationMillis = Math.max(0L, entry.endedAtEpochMillis() - entry.startedAtEpochMillis());
        String href = HtmlUtil.escape("/report/" + entry.reportId());
        String runnerToken = entry.runnerUuid() != null ? entry.runnerUuid().toString() : displayLiteralValue(entry.runnerName());
        String runnerHeadUrl = "https://api.mineatar.io/face/" + HtmlUtil.escape(runnerToken) + "?scale=2";
        String outcomeClass = outcomeClass(entry.outcome());
        return """
                <tr data-href='%s' data-outcome='%s' data-sort-date='%d' data-duration='%d' data-runner='%s'>
                  <td class='col-runner'>
                    <div class='runner-cell'>
                      <img class='runner-head' src='%s' alt='' loading='lazy' referrerpolicy='no-referrer'>
                      <span class='runner-name-text'>%s</span>
                    </div>
                  </td>
                  <td class='col-outcome %s'>%s</td>
                  <td class='col-time js-datetime' data-epoch='%d'>--</td>
                  <td class='col-duration'>%s</td>
                  <td class='col-action'><a class='open-btn' href='%s'>Open</a></td>
                </tr>
                """.formatted(
                href,
                HtmlUtil.escape(entry.outcome() == null ? "" : entry.outcome()),
                entry.startedAtEpochMillis(),
                durationMillis,
                HtmlUtil.escape(sortableRunner(entry.runnerName())),
                runnerHeadUrl,
                HtmlUtil.escape(displayLiteralValue(entry.runnerName())),
                outcomeClass,
                HtmlUtil.escape(pretty(entry.outcome())),
                entry.startedAtEpochMillis(),
                HtmlUtil.escape(formatDuration(durationMillis)),
                href
        );
    }

    private static String sortableRunner(String value) {
        if (value == null || value.isBlank()) {
            return "~";
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private void handleReport(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "Method not allowed", "text/plain");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String[] parts = path.split("/");
        if (parts.length < 3) {
            respond(exchange, 404, "Report not found", "text/plain");
            return;
        }
        UUID reportId;
        try {
            reportId = UUID.fromString(parts[2]);
        } catch (IllegalArgumentException exception) {
            respond(exchange, 404, "Report not found", "text/plain");
            return;
        }
        if (reportService.findIndex(reportId).isEmpty()) {
            respond(exchange, 404, "Report not found", "text/plain");
            return;
        }
        try {
            respond(exchange, 200, renderViewerHtml(reportId), "text/html; charset=utf-8");
        } catch (Exception exception) {
            logRequestFailure(exchange, "render report viewer for " + reportId, exception);
            respond(exchange, 500, "Unable to render report viewer.", "text/plain; charset=utf-8");
        }
    }

    private void handleReportApi(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "Method not allowed", "text/plain");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String[] parts = path.split("/");
        if (parts.length < 4) {
            respond(exchange, 404, "Report not found", "text/plain");
            return;
        }
        UUID reportId;
        try {
            reportId = UUID.fromString(parts[3]);
        } catch (IllegalArgumentException exception) {
            respond(exchange, 404, "Report not found", "text/plain");
            return;
        }
        if (reportService.findIndex(reportId).isEmpty()) {
            respond(exchange, 404, "Report not found", "text/plain");
            return;
        }
        try {
            String json = gson.toJson(reportService.readSnapshot(reportId));
            respond(exchange, 200, json, "application/json; charset=utf-8");
        } catch (Exception exception) {
            logRequestFailure(exchange, "read report API payload for " + reportId, exception);
            respond(exchange, 500, "Unable to read report.", "text/plain; charset=utf-8");
        }
    }

    private void logRequestFailure(HttpExchange exchange, String action, Exception exception) {
        logger.log(Level.WARNING,
                "Embedded web request failed while attempting to " + action + " for "
                        + exchange.getRequestMethod() + ' ' + exchange.getRequestURI(),
                exception);
    }

    private static String formatDuration(long durationMillis) {
        long totalSeconds = Math.max(0L, durationMillis / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return "%dh %02dm %02ds".formatted(hours, minutes, seconds);
        }
        if (minutes > 0L) {
            return "%dm %02ds".formatted(minutes, seconds);
        }
        return "%ds".formatted(seconds);
    }

    private static String displayLiteralValue(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String pretty(String value) {
        if (value == null || value.isBlank()) {
            return "—";
        }
        String normalized = value
                .replace("minecraft:", "")
                .replace("entity.", "")
                .replace('/', ' ')
                .replace('_', ' ');
        StringBuilder out = new StringBuilder(normalized.length());
        boolean capitalizeNext = true;
        for (int i = 0; i < normalized.length(); i++) {
            char current = normalized.charAt(i);
            if (Character.isWhitespace(current)) {
                capitalizeNext = true;
                out.append(current);
                continue;
            }
            if (capitalizeNext) {
                out.append(Character.toUpperCase(current));
                capitalizeNext = false;
            } else {
                out.append(current);
            }
        }
        return out.isEmpty() ? "—" : out.toString();
    }

    private static String outcomeClass(String outcome) {
        if (outcome == null) return "unknown";
        return switch (outcome.toUpperCase(Locale.ROOT)) {
            case "RUNNER_VICTORY" -> "runner";
            case "HUNTER_VICTORY" -> "hunter";
            case "INCONCLUSIVE"   -> "draw";
            default               -> "unknown";
        };
    }

    private void respond(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, payload.length);
        try (var output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }
}

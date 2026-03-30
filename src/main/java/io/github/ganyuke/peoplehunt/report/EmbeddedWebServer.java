package io.github.ganyuke.peoplehunt.report;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.ganyuke.peoplehunt.report.ReportModels.IndexEntry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

public final class EmbeddedWebServer {
    private final HttpServer server;
    private final ReportService reportService;
    private final ViewerAssets viewerAssets;
    private final Gson gson;

    public EmbeddedWebServer(ReportService reportService, ViewerAssets viewerAssets, Gson gson, int port) throws IOException {
        this.reportService = reportService;
        this.viewerAssets = viewerAssets;
        this.gson = gson;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        createContexts();
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    public String renderViewerHtml(String reportId) {
        return viewerAssets.render(reportId);
    }

    public String renderExportHtml() {
        return viewerAssets.render("LOCAL_EXPORT");
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
        List<IndexEntry> entries = reportService.listReports();
        StringBuilder html = new StringBuilder("<!doctype html><html><head><meta charset='utf-8'><title>PeopleHunt reports</title>")
                .append("<style>body{font-family:system-ui;background:#0f1720;color:#e6edf3;padding:24px}a{color:#7dd3fc}li{margin:10px 0}</style></head><body>")
                .append("<h1>PeopleHunt reports</h1><ul>");
        for (IndexEntry entry : entries) {
            html.append("<li><a href='/report/").append(entry.reportId()).append("'>")
                    .append(entry.reportId())
                    .append("</a> — ")
                    .append(entry.runnerName())
                    .append(" — ")
                    .append(entry.outcome())
                    .append("</li>");
        }
        html.append("</ul></body></html>");
        respond(exchange, 200, html.toString(), "text/html; charset=utf-8");
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
        respond(exchange, 200, renderViewerHtml(parts[2]), "text/html; charset=utf-8");
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
        try {
            UUID reportId = UUID.fromString(parts[3]);
            String json = gson.toJson(reportService.readSnapshot(reportId));
            respond(exchange, 200, json, "application/json; charset=utf-8");
        } catch (Exception exception) {
            respond(exchange, 404, "Report not found", "text/plain");
        }
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

package com.main;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Serves the dashboard from the analytics service so the two share an origin. That is what makes
 * the session cookie possible at all — a {@code SameSite=Strict} cookie is never sent to a
 * different origin — and it is why there is no CORS configuration left to get wrong.
 *
 * <p>The files carry no secrets and are served without authentication: the sign-in page has to be
 * reachable before there is a session.
 */
class DashboardHandler implements HttpHandler {
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=UTF-8",
            "css", "text/css; charset=UTF-8",
            "js", "text/javascript; charset=UTF-8",
            "json", "application/json; charset=UTF-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "ico", "image/x-icon",
            "woff2", "font/woff2",
            "txt", "text/plain; charset=UTF-8",
            "map", "application/json; charset=UTF-8");

    private final HttpSupport http;
    private final Path root;

    DashboardHandler(EngineContext context, Path root) {
        this.http = context.http();
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())
                && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            http.sendResponse(exchange, 405, "Method not allowed");
            return;
        }

        Path file = resolve(exchange.getRequestURI().getPath());
        if (file == null) {
            http.sendResponse(exchange, 404, "Not found");
            return;
        }
        byte[] body = Files.readAllBytes(file);
        // The dashboard is small and changes with the service; a stale copy is worse than a fetch.
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        http.sendBytes(exchange, 200, body, contentType(file));
    }

    /**
     * Returns the file to serve, or null when the request does not name one inside the root.
     *
     * <p>The decoded path is resolved and normalised before it is compared with the root, so an
     * encoded traversal (<code>%2e%2e%2f</code>) is caught by the same check as a plain one.
     */
    private Path resolve(String requestPath) {
        String decoded = URLDecoder.decode(requestPath, StandardCharsets.UTF_8);
        if (decoded.isEmpty() || "/".equals(decoded)) {
            decoded = "/index.html";
        }
        if (decoded.indexOf('\0') >= 0) {
            return null;
        }
        Path candidate;
        try {
            candidate = root.resolve(decoded.substring(1)).normalize();
        } catch (InvalidPathException exception) {
            return null;
        }
        if (!candidate.startsWith(root)) {
            return null;
        }
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");
    }
}

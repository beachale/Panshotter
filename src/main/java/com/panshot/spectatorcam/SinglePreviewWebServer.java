package com.panshot.spectatorcam;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SinglePreviewWebServer {
    private static final byte[] INDEX_HTML = loadResourceBytes("/web/single_viewer.html");
    private static final String HOST = "127.0.0.1";

    private HttpServer server;
    private ExecutorService executor;
    private StateProvider stateProvider;
    private int port = -1;

    public synchronized String ensureStarted(StateProvider provider) throws IOException {
        this.stateProvider = Objects.requireNonNull(provider, "provider");
        if (server != null) {
            return getUrl();
        }

        server = HttpServer.create(new InetSocketAddress(HOST, 0), 0);
        server.createContext("/", this::handleIndex);
        server.createContext("/api/state", this::handleState);
        server.createContext("/live-single.png", this::handleLiveImage);
        executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "panshot-single-web");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.start();
        port = server.getAddress().getPort();
        return getUrl();
    }

    private String getUrl() {
        return "http://" + HOST + ":" + port + "/";
    }

    private void handleIndex(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, INDEX_HTML.length);
        exchange.getResponseBody().write(INDEX_HTML);
        exchange.close();
    }

    private void handleState(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }

        StateProvider provider = stateProvider;
        byte[] imageBytes = provider != null ? provider.getLatestImageBytes() : null;
        boolean available = imageBytes != null && imageBytes.length > 0;
        long lastModified = provider != null ? provider.getLatestImageTimestamp() : 0L;
        boolean running = provider != null && provider.isSingleRunning();

        String response = "{\"running\":" + running + ",\"available\":" + available + ",\"lastModified\":" + lastModified + "}";
        byte[] payload = response.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    private void handleLiveImage(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }

        StateProvider provider = stateProvider;
        byte[] payload = provider != null ? provider.getLatestImageBytes() : null;
        if (payload == null || payload.length == 0) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "image/png");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    private void sendMethodNotAllowed(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(405, -1);
        exchange.close();
    }

    private static byte[] loadResourceBytes(String resourcePath) {
        try (InputStream stream = SinglePreviewWebServer.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("Missing resource: " + resourcePath);
            }
            return stream.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read resource: " + resourcePath, exception);
        }
    }

    public interface StateProvider {
        boolean isSingleRunning();

        byte[] getLatestImageBytes();

        long getLatestImageTimestamp();
    }
}

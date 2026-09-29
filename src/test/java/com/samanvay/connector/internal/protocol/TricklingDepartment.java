package com.samanvay.connector.internal.protocol;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A local department that answers {@code /slow} with headers at once and then trickles a JSON
 * body one byte every {@code gapMillis} (every byte would reset a socket read timeout), and
 * {@code /fast} with the whole body at once. Records whether a trickle was cut off by the client.
 */
public final class TricklingDepartment implements AutoCloseable {

    static final byte[] BODY = "{\"annualIncome\":120000,\"issuedBy\":\"Tahsildar\"}".getBytes(StandardCharsets.UTF_8);

    private final HttpServer server;
    public final AtomicInteger slowRequests = new AtomicInteger();
    public final AtomicBoolean trickleCutOff = new AtomicBoolean();
    public final AtomicBoolean trickleCompleted = new AtomicBoolean();
    public final CountDownLatch trickleEnded = new CountDownLatch(1);

    public TricklingDepartment(long gapMillis) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/fast", ex -> {
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, BODY.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(BODY);
            }
        });
        server.createContext("/slow", ex -> {
            slowRequests.incrementAndGet();
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, BODY.length);
            try (OutputStream out = ex.getResponseBody()) {
                for (byte b : BODY) {
                    out.write(b);
                    out.flush();
                    Thread.sleep(gapMillis);
                }
                trickleCompleted.set(true);
            } catch (IOException e) {
                trickleCutOff.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                trickleEnded.countDown();
            }
        });
        server.start();
    }

    public URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    /** Full trickle duration. */
    public static long trickleMillis(long gapMillis) {
        return BODY.length * gapMillis;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

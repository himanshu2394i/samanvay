package in.samanvay.departments.kit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** A stand-in for Samanvay: a token endpoint and whatever department API replies a test registers. Records every call. */
final class FakeSamanvay implements AutoCloseable {

    record Call(String method, String path, String authorization, String body) {}

    record Reply(int status, String json) {}

    private final HttpServer server;
    final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Map<String, Function<Call, Reply>> routes = new ConcurrentHashMap<>();
    volatile int tokenRequests;

    FakeSamanvay() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    FakeSamanvay on(String method, String path, Function<Call, Reply> reply) {
        routes.put(method + " " + path, reply);
        return this;
    }

    FakeSamanvay on(String method, String path, int status, String json) {
        return on(method, path, c -> new Reply(status, json));
    }

    List<Call> callsTo(String path) {
        return calls.stream().filter(c -> c.path().equals(path) || c.path().startsWith(path + "?")).toList();
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = ex.getRequestURI().getPath();
        Call call = new Call(ex.getRequestMethod(), ex.getRequestURI().toString(), ex.getRequestHeaders().getFirst("Authorization"), body);
        Reply reply;
        if (path.equals("/token")) {
            tokenRequests++;
            reply = new Reply(200, "{\"access_token\":\"tok-" + tokenRequests + "\",\"expires_in\":300}");
        } else {
            calls.add(call);
            Function<Call, Reply> f = routes.get(call.method() + " " + path);
            reply = f == null ? new Reply(404, "{\"detail\":\"no route " + path + "\"}") : f.apply(call);
        }
        byte[] out = reply.json().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(reply.status(), out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

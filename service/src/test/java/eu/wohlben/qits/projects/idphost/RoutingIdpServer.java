package eu.wohlben.qits.projects.idphost;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A qits-idp that routes by {@code "<METHOD> <path>"} (qits-767): {@link StubIdpServer}'s sibling
 * for a suite that drives several doors in one test, where one FIFO of answers would make the order
 * of every call part of the assertion. Each route holds a queue; its last answer repeats once the
 * queue is down to one, and an unrouted request is a 500.
 */
public final class RoutingIdpServer implements AutoCloseable {

  /** One request, as the stub saw it. */
  public record Received(String method, String path, String body) {}

  private record Answer(int status, String body) {}

  private final HttpServer server;
  private final List<Received> received = Collections.synchronizedList(new ArrayList<>());
  private final Map<String, Deque<Answer>> routes = new HashMap<>();

  public RoutingIdpServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          byte[] body = exchange.getRequestBody().readAllBytes();
          String method = exchange.getRequestMethod();
          String path = exchange.getRequestURI().getRawPath();
          received.add(new Received(method, path, new String(body, StandardCharsets.UTF_8)));
          Answer answer = answerFor(method + " " + path);
          byte[] out = answer.body().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(answer.status(), out.length == 0 ? -1 : out.length);
          if (out.length > 0) {
            exchange.getResponseBody().write(out);
          }
          exchange.close();
        });
    server.start();
  }

  private Answer answerFor(String route) {
    synchronized (routes) {
      Deque<Answer> queue = routes.get(route);
      if (queue == null) {
        // A DELETE of any id under a routed prefix: the commonest give-back, answered 204.
        if (route.startsWith("DELETE ")) {
          return new Answer(204, "");
        }
        return new Answer(500, "{}");
      }
      return queue.size() > 1 ? queue.removeFirst() : queue.getFirst();
    }
  }

  /** The base a caller is pointed at — an idp's {@code …/idp}. */
  public String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/idp";
  }

  /** Answer {@code route} ({@code "POST /idp/api/tokens"}) with these, in order. */
  public RoutingIdpServer on(String route, int status, String body) {
    synchronized (routes) {
      routes.computeIfAbsent(route, ignored -> new ArrayDeque<>()).addLast(new Answer(status, body));
    }
    return this;
  }

  public List<Received> received() {
    return List.copyOf(received);
  }

  /** The requests that arrived on {@code method} {@code path}. */
  public List<Received> received(String method, String path) {
    return received().stream()
        .filter(r -> r.method().equals(method) && r.path().equals(path))
        .toList();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}

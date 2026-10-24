package eu.wohlben.qits.projects.agenthost;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonCodec;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.projectsdaemon.protocol.Hello;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetClient;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * <b>A project agent's daemon, played by a test</b>, reached the only way a real one is: this
 * fixture dials the control socket, says {@code Hello}, and serves every {@code OpenStream} by
 * connecting to its own loopback API first and dialling back second — so a request this service
 * makes through {@link AgentTunnels} crosses the real tunnel, byte for byte.
 *
 * <p>The same choreography {@code AgentTunnelProxyTest} plays inline (and whose javadoc carries why
 * each step is ordered as it is), as a reusable piece: the front desk's doors (qits-1572) are tested
 * against it, and the provider state {@code a project whose front desk is running} stands one up so
 * the golden masters and the consumer pacts record a real proxied answer rather than a stub.
 *
 * <p>What the API answers is the caller's {@code answers} function, given what arrived. Close it
 * when done: the tunnel first (as {@code AgentTunnelProxyTest#tearDown} explains), then the sockets.
 */
public final class FakeProjectDaemon implements AutoCloseable {

  private static final long TIMEOUT_SECONDS = 15;

  /** What reached the fake daemon's API. */
  public record Seen(String method, String uri, String authorization, String host, String body) {}

  /** What it answers: a status and a JSON body (null for none). */
  public record Reply(int status, String body) {}

  private final Vertx vertx;
  private final AgentTunnels tunnels;
  private final String projectId;
  private final List<Seen> seen = new CopyOnWriteArrayList<>();

  private HttpServer api;
  private WebSocketClient wsClient;
  private NetClient netClient;
  private WebSocket controlSocket;

  private FakeProjectDaemon(Vertx vertx, AgentTunnels tunnels, String projectId) {
    this.vertx = vertx;
    this.tunnels = tunnels;
    this.projectId = projectId;
  }

  /**
   * Start the fake API, connect it as {@code projectId}'s daemon to the service at {@code
   * host:port}, and return once the {@code Hello} is acknowledged.
   */
  public static FakeProjectDaemon connect(
      Vertx vertx,
      AgentTunnels tunnels,
      String host,
      int port,
      String projectId,
      Function<Seen, Reply> answers) {
    FakeProjectDaemon daemon = new FakeProjectDaemon(vertx, tunnels, projectId);
    try {
      daemon.start(host, port, answers);
      return daemon;
    } catch (Exception e) {
      daemon.close();
      throw new IllegalStateException("could not stand up a fake daemon for " + projectId, e);
    }
  }

  /** Everything that reached the API, in order. */
  public List<Seen> seen() {
    return List.copyOf(seen);
  }

  private void start(String host, int port, Function<Seen, Reply> answers) throws Exception {
    api =
        vertx
            .createHttpServer()
            .requestHandler(
                request ->
                    request
                        .body()
                        .onComplete(
                            read -> {
                              Seen observed =
                                  new Seen(
                                      request.method().name(),
                                      request.uri(),
                                      request.getHeader("Authorization"),
                                      request.getHeader("Host"),
                                      read.succeeded() && read.result() != null
                                          ? read.result().toString()
                                          : null);
                              seen.add(observed);
                              Reply reply = answers.apply(observed);
                              var response = request.response().setStatusCode(reply.status());
                              if (reply.body() == null) {
                                response.end();
                              } else {
                                response.putHeader("Content-Type", "application/json").end(reply.body());
                              }
                            }))
            .listen(0, "127.0.0.1")
            .toCompletionStage()
            .toCompletableFuture()
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    int apiPort = api.actualPort();

    wsClient = vertx.createWebSocketClient();
    netClient = vertx.createNetClient();
    CompletableFuture<Void> acked = new CompletableFuture<>();
    controlSocket =
        wsClient
            .connect(
                new WebSocketConnectOptions()
                    .setHost(host)
                    .setPort(port)
                    .setURI(DaemonProtocol.CONTROL_SOCKET_PATH_PREFIX + projectId))
            .toCompletionStage()
            .toCompletableFuture()
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    controlSocket.textMessageHandler(
        frame -> {
          JsonObject decoded = new JsonObject(frame);
          String type = decoded.getString(DaemonProtocol.Field.TYPE);
          if (DaemonProtocol.Type.ACK.equals(type)) {
            acked.complete(null);
          } else if (DaemonProtocol.Type.OPEN_STREAM.equals(type)) {
            serveStream(host, port, decoded.getString(DaemonProtocol.Field.PATH), apiPort);
          }
        });
    controlSocket.writeTextMessage(
        new ObjectMapper()
            .writeValueAsString(
                DaemonCodec.encode(
                    new Hello(
                        projectId,
                        "contract-contract",
                        DaemonProtocol.CAPABILITY_VERSION,
                        "test",
                        null))));
    acked.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  /** One stream: loopback connection first, dial-back second, then a raw byte pipe both ways. */
  private void serveStream(String host, int port, String path, int apiPort) {
    netClient
        .connect(apiPort, "127.0.0.1")
        .onSuccess(
            local ->
                wsClient
                    .connect(new WebSocketConnectOptions().setHost(host).setPort(port).setURI(path))
                    .onSuccess(
                        remote -> {
                          remote.handler(local::write);
                          local.handler(remote::writeBinaryMessage);
                          remote.endHandler(v -> local.close());
                          local.endHandler(v -> remote.close());
                        })
                    .onFailure(t -> local.close()));
  }

  @Override
  public void close() {
    tunnels.closeTunnel(projectId);
    if (controlSocket != null) {
      awaitQuietly(controlSocket.close());
    }
    if (wsClient != null) {
      wsClient.close();
    }
    if (netClient != null) {
      netClient.close();
    }
    if (api != null) {
      api.close();
    }
  }

  private static void awaitQuietly(io.vertx.core.Future<?> future) {
    try {
      future.toCompletionStage().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (Exception ignored) {
      // A socket already gone is closed enough.
    }
  }
}

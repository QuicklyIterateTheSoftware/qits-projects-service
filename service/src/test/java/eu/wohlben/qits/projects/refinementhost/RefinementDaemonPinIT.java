package eu.wohlben.qits.projects.refinementhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.wohlben.qits.workspacedaemon.protocol.CommandChunk;
import eu.wohlben.qits.workspacedaemon.protocol.CommandExit;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonCodec;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.workspacedaemon.protocol.Hello;
import eu.wohlben.qits.workspacedaemon.protocol.RunCommand;
import eu.wohlben.qits.workspacedaemon.protocol.Stream;
import eu.wohlben.qits.workspacedaemon.protocol.WorkspaceImage;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * <b>THE PIN TEST.</b> The workspace daemon at exactly the version this reactor pins is downloaded,
 * started as a process configured to dial this host on localhost, made to run a command over the
 * real control-socket protocol, and then decommissioned — before any refinement container is ever
 * started from the image that daemon ships in.
 *
 * <h2>What it is for</h2>
 *
 * <p>A refinement container runs the <b>workspace</b> image and the workspace daemon, and this
 * service is its home: {@link RefinementContainerFactory} hands it {@code
 * QITS_WORKSPACE_DAEMON_URL} pointing at {@link RefinementPaths#CONTROL_SOCKET_PREFIX}, and
 * {@code refinementhost/} decodes every frame that comes back. The version of that image used to
 * arrive as the configuration entry {@code QITS_PROJECTS_REFINEMENT_IMAGE_VERSION}, rewritten by
 * whoever released the image, so a daemon whose wire had moved reached the next refinement with
 * <em>nothing having tested the pair</em> — and the clone-alone default behind it aged in silence
 * until it named a tag the registry's retention had deleted. The version is a pinned dependency
 * now ({@link WorkspaceImage#VERSION}, off {@code eu.wohlben.qits:qits-workspace-daemon-protocol},
 * which is also the jar this service decodes frames with), and <b>this is the test that makes the
 * pin mean something</b>: a bump that breaks the protocol fails <em>this repository's</em> release
 * request, which is a red gate on a branch, rather than a live refinement somebody is waiting on.
 *
 * <p>It proves <b>this</b> host against that daemon, not a generic round trip. The near end below
 * is deliberately shaped like {@link RefinementControlSocket}: the dial-home URL carries the
 * refinement's <b>row id</b> in the path — a refinement is keyed by its own row, never by the
 * branch-derived label a workspace is keyed by over in qits-workspaces — while the daemon announces
 * its {@code label} in the {@code Hello}'s {@code workspaceId}, exactly as
 * {@link RefinementDaemonRegistry} reads it. Both halves of that vocabulary are asserted, because
 * both halves are what the shipped {@code QITS_WORKSPACE_DAEMON_*} environment sets up.
 *
 * <h2>What it does NOT cover</h2>
 *
 * <p><b>The image's toolchain and its entrypoint are not exercised.</b> What is under test is the
 * daemon <em>binary</em> and the wire it speaks; the {@code qits/workspace} image around it — its
 * git, node, java and claude toolchain, its user, its volumes and the entrypoint that launches the
 * daemon inside it — is qits-workspace-daemon's own pipeline's subject and keeps being tested
 * there. A container buys nothing here and costs everything: a CI step container runs
 * {@code --cap-drop=ALL} with no privilege and no docker at all, and the suite has to stay green
 * from a clone that has neither.
 *
 * <p>What is downloaded is that repository's {@code daemon} artifact, which is a <b>runnable
 * uber-jar</b> and deliberately not the native binary. The native image is compiled on UBI9 against
 * <b>glibc</b> and every CI step image on this platform is Alpine — {@code musl} — so a native bare
 * binary is an artifact this gate could never execute: it would skip for ever, which is worse than
 * having no pin test at all. Same source, same reactor, same version, so the wire contract
 * exercised here is exactly the one the image's daemon speaks, and the protocol is what a daemon
 * bump breaks. The native image's own linkage and reflection registration are not covered and are
 * not this repository's to cover.
 *
 * <p>Nor is anything about <em>this</em> service's own refinement flow claimed: the container
 * ladder, the reverse tunnel, the proxy and the registry's state machine are the surefire suites'
 * ({@link RefinementLifecycleTest}, {@link RefinementContainerFactoryTest}) and stay theirs.
 *
 * <h2>Why it boots no Quarkus application</h2>
 *
 * <p>Plain JUnit 5 — not {@code @QuarkusTest}, not {@code @QuarkusIntegrationTest}. Nothing in the
 * assertion needs the application: the subject is whether the pinned daemon and this reactor's
 * {@link DaemonCodec} still agree, and both ends of that are on this classpath. A second launched
 * artifact would mean a second {@code @TestProfile} — a whole second qits-projects beside the story
 * catalogue's, with its own boot, its own two databases and its own port — for no assertion a bare
 * Vert.x server cannot make. That is not only slowness: a {@code @TestProfile} is a Quarkus
 * application and retains on the order of 125 MB of metaspace, and a CI step container is capped at
 * 4g, where the symptom of one profile too many is exit 137 from the OOM killer reported as a test
 * failure.
 *
 * <h2>It gates, and it skips only where it must</h2>
 *
 * <p>Where an artifacts origin IS configured, a missing artifact or a failed round trip is a
 * <b>failure with a sentence</b> and never a skip — in a CI step the origin is always injected, so
 * this cannot quietly pass by not running. Name it in {@code
 * .config/qits/ci-event-release-request.yml}'s {@code -Dit.test} list or it never runs there at all,
 * silently, which is why AGENTS.md says to change both together.
 *
 * <p><b>The one skip is defensive rather than a supported mode.</b> It covers an artifacts origin
 * configured to nothing. It is deliberately not load-bearing: this reactor resolves
 * qits-eventstream, qits-githost-events, qits-arch-rules and the workspace daemon protocol itself
 * from the platform Maven repository, so a checkout with no platform to ask fails at dependency
 * resolution long before any test runs. The branch exists so a deployment that blanks the address
 * gets a legible sentence instead of a malformed URL, not so this test can be opted out of.
 */
public class RefinementDaemonPinIT {

  /**
   * Where qits-artifacts is, derived from the Maven repository address the build already carries —
   * the same {@code ${…%%/artifacts/*}} arithmetic every release pipeline does, because the daemon
   * store is a sibling path of the maven one inside one deployment. Derived rather than given its
   * own key, so there is no second address to configure wrongly.
   */
  private static final String ARTIFACTS_BASE = artifactsBase();

  /**
   * The refinement row id the daemon is told to dial, standing in for {@code Refinement.id}. A
   * number, because {@link RefinementControlSocket} parses this segment as a {@code Long} and a
   * path it cannot parse registers nothing at all.
   */
  private static final long REFINEMENT_ROW_ID = 4242L;

  /**
   * What the daemon announces itself as — {@code Refinement.label}, injected as {@code
   * QITS_WORKSPACE_DAEMON_WORKSPACE_ID} and echoed back in the {@code Hello}. Deliberately NOT the
   * row id: the two are different facts here, and reading the label as an identity is the mistake
   * that let two workspaces own one branch one repository over.
   */
  private static final String REFINEMENT_LABEL = "refining-pin-it";

  private static String artifactsBase() {
    String maven =
        System.getProperty(
            "qits.maven.repository.url",
            System.getenv().getOrDefault("QITS_MAVEN_REPOSITORY_URL", ""));
    int marker = maven.indexOf("/artifacts/");
    return marker < 0 ? "" : maven.substring(0, marker) + "/artifacts";
  }

  @Test
  public void theDaemonThisReactorPinsStartsAndRoundTripsACommand() throws Exception {
    assumeTrue(
        !ARTIFACTS_BASE.isBlank(),
        "no artifacts origin is configured (qits.maven.repository.url / QITS_MAVEN_REPOSITORY_URL)"
            + " — a clone with no platform to ask cannot run the pin test");

    Path work = Files.createTempDirectory("qits-refinement-pin-it");
    Path jar = work.resolve("qits-workspace-daemon.jar");
    Path checkout = Files.createDirectories(work.resolve("refinement"));
    download(jar);

    Vertx vertx = Vertx.vertx();
    String correlationId = "pin-" + UUID.randomUUID();
    CompletableFuture<Hello> hello = new CompletableFuture<>();
    CompletableFuture<Integer> exit = new CompletableFuture<>();
    StringBuilder stdout = new StringBuilder();

    // A stand-in refinement host: on HELLO, ask for an echo and collect the reply. The real
    // RefinementDaemonRegistry is covered by the surefire suites; what is under test here is the
    // far end, so the near end is deliberately the smallest thing that speaks this service's half
    // of the protocol — RefinementControlSocket's path shape and RefinementMessageCodec's framing,
    // with neither CDI nor Jackson in the way.
    HttpServer server = vertx.createHttpServer();
    server.webSocketHandler(
        ws ->
            ws.textMessageHandler(
                text -> {
                  DaemonMessage message = DaemonCodec.decode(new JsonObject(text).getMap());
                  switch (message) {
                    case Hello said -> {
                      hello.complete(said);
                      ws.writeTextMessage(
                          encode(
                              new RunCommand(
                                  correlationId,
                                  List.of("echo", "refinement-daemon-pin-ok"),
                                  checkout.toString(),
                                  Map.of())));
                    }
                    case CommandChunk chunk -> {
                      if (chunk.stream() == Stream.STDOUT) {
                        stdout.append(chunk.text());
                      }
                    }
                    case CommandExit ended -> exit.complete(ended.exitCode());
                    default -> {
                      /* Heartbeat / DaemonLog / GitStatus — nothing for the stand-in to do */
                    }
                  }
                }));
    int port =
        server
            .listen(0, "127.0.0.1")
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS)
            .actualPort();

    Path log = work.resolve("daemon.log");
    Process daemon = null;
    try {
      daemon = start(jar, checkout, port, log);

      Hello said = awaitOrReport(hello, log, "the daemon never dialled home");
      // The label, not the row id: the row id is in the PATH (asserted by the daemon having reached
      // this handler at all, since RefinementPaths is what composed the url it dialled), and the
      // label is what the daemon announces about itself.
      assertEquals(
          REFINEMENT_LABEL, said.workspaceId(), "the daemon dialled home as who it was told");
      // THE VERSION IS THE ASSERTION, not decoration: it is what makes this "the pinned daemon" and
      // not "a daemon". If this ever disagrees, something downloaded a different build than the pom
      // names and every other assertion below is about the wrong thing.
      assertEquals(
          WorkspaceImage.VERSION,
          said.daemonVersion(),
          "the daemon that answered is not the version this reactor pins");
      // The capability RefinementTunnels branches on. A daemon below TUNNEL_CAPABILITY_VERSION
      // binds its API to 0.0.0.0 and cannot serve an OpenStream at all — and since a refinement
      // container is reachable ONLY through the reverse tunnel, that is a live-topology failure no
      // unit test here can see: RefinementTunnels reads exactly this number.
      assertTrue(
          said.capabilityVersion() >= DaemonProtocol.TUNNEL_CAPABILITY_VERSION,
          "the pinned daemon announces capability "
              + said.capabilityVersion()
              + ", below the reverse tunnel's "
              + DaemonProtocol.TUNNEL_CAPABILITY_VERSION);

      assertEquals(
          0,
          awaitOrReport(exit, log, "the daemon never finished the command").intValue(),
          "the echo the host asked for");
      assertTrue(
          stdout.toString().contains("refinement-daemon-pin-ok"),
          "the command's output came back over the socket: " + stdout);
    } finally {
      // Decommission, in the order the ticket asks for and a stuck process demands: the child
      // first, so nothing is still dialling a server that is closing.
      if (daemon != null) {
        daemon.destroy();
        if (!daemon.waitFor(15, TimeUnit.SECONDS)) {
          daemon.destroyForcibly();
        }
      }
      server.close();
      vertx.close();
      deleteTree(work);
    }
  }

  /**
   * Waits for one half of the round trip, and <b>fails with the daemon's own log</b> rather than
   * with a bare timeout.
   *
   * <p>The whole point of this test is that a daemon a release published does not work with this
   * service, and the far end's startup output is the only thing that says which of the many reasons
   * it is — a missing config key, a port it could not bind, a protocol frame it could not decode. A
   * {@code TimeoutException} on its own sends the reader to a log that a deliberately non-inherited
   * stream means they cannot find.
   */
  private static <T> T awaitOrReport(CompletableFuture<T> future, Path log, String what)
      throws Exception {
    try {
      return future.get(60, TimeUnit.SECONDS);
    } catch (Exception e) {
      String output;
      try {
        output = Files.exists(log) ? Files.readString(log) : "(the daemon wrote nothing)";
      } catch (IOException unreadable) {
        output = "(the daemon's log could not be read: " + unreadable + ")";
      }
      throw new AssertionError(
          what
              + " within 60s — "
              + WorkspaceImage.DAEMON_NAME
              + " "
              + WorkspaceImage.VERSION
              + ", the version this reactor pins. Its output was:\n"
              + output,
          e);
    }
  }

  /**
   * Fetches the pinned daemon out of qits-artifacts' {@code daemons} store.
   *
   * <p>A missing artifact is a <b>failure with a sentence</b>, never a skip. It means the version
   * this reactor pins was released without its daemon — or that retention removed it — and either
   * is the exact class of defect this test exists to surface, one release earlier than a refinement
   * container that will not come up.
   */
  private static void download(Path target) throws Exception {
    String url =
        ARTIFACTS_BASE + "/daemons/" + WorkspaceImage.DAEMON_NAME + "/" + WorkspaceImage.VERSION;
    try (HttpClient client =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()) {
      HttpResponse<InputStream> answer =
          client.send(
              HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build(),
              HttpResponse.BodyHandlers.ofInputStream());
      if (answer.statusCode() != 200) {
        fail(
            "the pinned daemon "
                + WorkspaceImage.DAEMON_NAME
                + " "
                + WorkspaceImage.VERSION
                + " is not in qits-artifacts ("
                + answer.statusCode()
                + " from "
                + url
                + "). The pom pins a version whose daemon was never published or no longer"
                + " exists.");
      }
      try (InputStream body = answer.body()) {
        Files.copy(body, target, StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }

  /**
   * Starts the daemon pointed at the stand-in host, with the environment a refinement container
   * gets — {@link RefinementContainerFactory}'s own {@code QITS_WORKSPACE_DAEMON_URL} /
   * {@code _WORKSPACE_ID} shape, composed through {@link RefinementPaths} rather than spelled here,
   * so a change to the path contract moves this test with it.
   *
   * <p><b>Every listening port it binds is given to it, and every one is free.</b> The daemon's
   * three shipped ports are fixed numbers (13337 hooks, 13338 api, 13339 editor) because inside a
   * container they are unshared; this test runs it as an ordinary process on a developer's machine
   * and on a CI host, where 13338 is quite likely to be something else — and a bind failure there
   * is logged rather than fatal, so the symptom would be an unrelated test flaking much later.
   */
  private static Process start(Path jar, Path checkout, int hostPort, Path log) throws IOException {
    ProcessBuilder builder =
        new ProcessBuilder(
            javaBinary(),
            "-Dqits.workspace-daemon.hooks-port=" + freePort(),
            "-Dqits.workspace-daemon.api-port=" + freePort(),
            "-Dqits.workspace-daemon.editor-port=" + freePort(),
            "-jar",
            jar.toString());
    Map<String, String> env = builder.environment();
    // EVERY AMBIENT QITS_ VARIABLE IS REMOVED FIRST, and this is the difference between a test and
    // a coincidence. `ProcessBuilder` seeds the child from this process's environment, and this
    // process runs somewhere that has opinions: a workspace container carries the full
    // QITS_WORKSPACE_DAEMON_* set plus a commissioned credential, and a CI step container carries
    // QITS_COMMISSIONED_CLIENT_ID/SECRET and nothing else of the set.
    //
    // That difference is not cosmetic, because the daemon's ControlSocket refuses a PARTIAL
    // commission: any one of client-id, secret, auth-token-url and auth-audience present means all
    // four are required, and a step container supplies exactly two. The daemon then fails to mint,
    // never dials, and the test times out with a daemon that started perfectly — measured on
    // qits-workspaces' first gating run of the same test, 2026-09-16, after it had passed locally
    // for weeks. It passed there because a workspace container HAS all four, so the assertion was
    // riding on the surrounding container's credentials.
    //
    // A pin test whose result depends on where it runs proves nothing about the pin, which is the
    // same class of defect as the configuration entry this whole change replaced. So the child gets
    // exactly the three variables below and no others, and dials anonymously — the stand-in host
    // accepts any upgrade, and who may open a refinement's control socket is
    // RefinementControlSocketAccess's subject, not this one's.
    env.keySet().removeIf(key -> key.startsWith("QITS_"));
    env.put(
        "QITS_WORKSPACE_DAEMON_URL",
        "ws://127.0.0.1:"
            + hostPort
            + RefinementPaths.CONTROL_SOCKET_PREFIX
            + REFINEMENT_ROW_ID);
    env.put("QITS_WORKSPACE_DAEMON_WORKSPACE_ID", REFINEMENT_LABEL);
    env.put("QITS_WORKSPACE_DAEMON_WORKSPACE_DIR", checkout.toString());
    // TO A FILE, AND NOT inheritIO(). A pipe nobody drains would fill and wedge the child, so the
    // output has to go somewhere — and `inheritIO` sends it to this forked JVM's native stdout,
    // which is failsafe's own control channel: surefire answers that with "Corrupted channel by
    // directly writing to native stream in forked JVM". The file is printed by the caller when
    // anything goes wrong, so a daemon that dies at startup still says why.
    return builder
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.to(log.toFile()))
        .start();
  }

  private static String javaBinary() {
    return Path.of(System.getProperty("java.home"), "bin", "java").toString();
  }

  /**
   * A port nothing is listening on — asked of the OS and released immediately.
   *
   * <p>Inherently racy and correct enough: the window is microseconds, the alternative is three
   * hard-coded numbers that are wrong on any host running a workspace, and the daemon treats a
   * failed bind on these three as non-fatal anyway.
   */
  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static String encode(DaemonMessage message) {
    return new JsonObject(DaemonCodec.encode(message)).encode();
  }

  private static void deleteTree(Path root) {
    try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
      paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
    } catch (IOException e) {
      // A leftover temp directory is not worth failing a green run over.
    }
  }
}

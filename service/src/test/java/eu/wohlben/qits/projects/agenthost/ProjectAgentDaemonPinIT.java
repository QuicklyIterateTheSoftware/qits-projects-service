package eu.wohlben.qits.projects.agenthost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.wohlben.qits.projectsdaemon.protocol.CommandChunk;
import eu.wohlben.qits.projectsdaemon.protocol.CommandExit;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonCodec;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonMessage;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.projectsdaemon.protocol.Hello;
import eu.wohlben.qits.projectsdaemon.protocol.ProjectAgentImage;
import eu.wohlben.qits.projectsdaemon.protocol.RunCommand;
import eu.wohlben.qits.projectsdaemon.protocol.Stream;
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
 * <b>THE PIN TEST.</b> The projects-daemon at exactly the version this reactor pins is downloaded,
 * started as a process against localhost, and made to run a command over the real control-socket
 * protocol — before any agent container is started from the image that daemon ships in.
 *
 * <h2>What it is for</h2>
 *
 * <p>qits-projects used to learn which {@code qits/project-agent} image to start from {@code
 * env.QITS_PROJECTS_AGENT_IMAGE_VERSION}, a qits-configuration entry a release listener rewrote the
 * moment qits-projects-daemon pushed an image. So a new daemon reached the next real refinement run
 * with nothing having tested the pair, and a daemon that changed the wire would be discovered by a
 * person whose agent container came up healthy and never dialled home. The version is a pinned
 * dependency now ({@link ProjectAgentImage#VERSION}, off {@code
 * eu.wohlben.qits:qits-projects-daemon-protocol}), and this is the test that makes the pin mean
 * something: a bump that breaks the protocol fails <em>this repository's</em> release request, which
 * is a red gate on a branch, rather than a live container.
 *
 * <h2>A process, not a container — and a jar, not the native binary</h2>
 *
 * <p>What this service talks to is the daemon inside the image, not the image, so a container buys
 * nothing here and costs everything: a CI step container runs {@code --cap-drop=ALL} with no
 * privilege and has no docker at all, and the first rule of this repository is that a clone tests
 * green with no docker. The image's own toolchain and entrypoint keep being tested by the image's
 * own pipeline, which is where they belong.
 *
 * <p>What is downloaded is qits-projects-daemon's {@code daemons} artifact, which is a <b>runnable
 * uber-jar</b>. That is not a shortcut: the native image is compiled on UBI9 against <b>glibc</b>
 * and every CI step image on this platform is Alpine — {@code musl} — so a native bare binary is an
 * artifact this gate could never execute. It would skip for ever, which is worse than having no pin
 * test. Same source, same reactor, same version: the wire contract exercised here is exactly the one
 * the image's daemon speaks, and the protocol is what a daemon bump breaks. The native image's own
 * linkage and reflection registration are not covered, and are not this repository's to cover.
 *
 * <h2>Plain JUnit, and NOT a {@code @QuarkusIntegrationTest}</h2>
 *
 * <p>The ticket asked for an integration test and this is one; what it is not is a second launched
 * application. Nothing in the assertion needs either end of qits-projects: the subject is whether the
 * pinned daemon and this reactor's {@link DaemonCodec} still agree, and both ends of that are on
 * this classpath. The local cost of getting that wrong is sharper here than it was in the
 * qits-workspaces pilot — <b>every other {@code *IT} in this module shares one {@code @TestProfile},
 * {@code TokenValidationBootstrapIT.PackagedWithMockIdp}</b>, so a profiled class would mean a
 * second launched qits-projects, a second embedded postgres and a second boot, for an assertion that
 * needs no route, no database and no identity. {@code AgentDaemonRegistryTest} makes the same
 * judgement about the near end of this same protocol.
 *
 * <p>It is named {@code *IT} and runs under failsafe all the same, because it reaches the network
 * and downloads an artifact — which is what separates this suite from the surefire one, not whether
 * a Quarkus application is launched.
 *
 * <h2>It gates, and it skips only where it must</h2>
 *
 * <p>No {@code @Tag}: this one has to run. Where an origin IS configured, a missing artifact or a
 * failed round trip is a <b>failure</b> and never a skip — in a CI step the origin is always
 * injected, so this cannot quietly pass by not running. It must stay named in {@code
 * .config/qits/userflow-stories}, which the composed QA step turns back into {@code -Dit.test}, or it
 * never runs there at all, silently, because that list is not derived and {@code *IT} is
 * deliberately not a wildcard there.
 *
 * <p><b>The one skip is defensive rather than a supported mode.</b> It covers an artifacts origin
 * configured to nothing, and it is deliberately not load-bearing: the reactor resolves
 * qits-eventstream, qits-db-core and now the agent image pin itself from the platform Maven
 * repository, so a checkout with no platform to ask fails at dependency resolution long before any
 * test runs. The branch exists so that a deployment which blanks the address gets a legible sentence
 * instead of a malformed URL, not so that this test can be opted out of.
 */
public class ProjectAgentDaemonPinIT {

  /**
   * Where qits-artifacts is, derived from the Maven repository address the build already carries —
   * the same {@code ${…%%/artifacts/*}} arithmetic every release pipeline does, because the daemon
   * store is a sibling path of the maven one inside one deployment. Derived rather than given its
   * own key, so there is no second address to configure wrongly.
   */
  private static final String ARTIFACTS_BASE = artifactsBase();

  /** The project the stand-in host tells the daemon it serves. */
  private static final String PROJECT_ID = "pin-it";

  /**
   * <b>Both halves of this are load-bearing and neither is redundant.</b> A developer runs {@code
   * ./mvnw verify -Dqits.maven.repository.url=…} and the system property is what carries it; but
   * failsafe <em>forks a JVM</em> and that fork does not inherit this JVM's {@code -D} properties,
   * so in a CI step it is the inherited environment variable {@code QITS_MAVEN_REPOSITORY_URL} that
   * supplies the address. Drop either one and the test skips in exactly the place it was written to
   * gate.
   */
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
        "no artifacts origin is configured (qits.maven.repository.url /"
            + " QITS_MAVEN_REPOSITORY_URL) — a clone with no platform to ask cannot run the pin"
            + " test");

    Path work = Files.createTempDirectory("qits-agent-pin-it");
    Path jar = work.resolve("qits-projects-daemon.jar");
    Path cwd = Files.createDirectories(work.resolve("cwd"));
    download(jar);

    Vertx vertx = Vertx.vertx();
    String correlationId = "pin-" + UUID.randomUUID();
    CompletableFuture<Hello> hello = new CompletableFuture<>();
    CompletableFuture<Integer> exit = new CompletableFuture<>();
    StringBuilder stdout = new StringBuilder();

    // A stand-in host: on HELLO, ask for an echo and collect the reply. The real AgentDaemonRegistry
    // is covered by AgentDaemonRegistryTest; what is under test here is the FAR end, so the near end
    // is deliberately the smallest thing that speaks the protocol — and it accepts any upgrade,
    // because who may open a control socket is AgentControlSocketAccess' subject and not this one's.
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
                                  List.of("echo", "project-agent-pin-ok"),
                                  // CommandExecutor honours a non-blank cwd by handing it to
                                  // ProcessBuilder.directory, so a temp directory here keeps the
                                  // spawn out of whatever the daemon's own working directory is.
                                  cwd.toString(),
                                  Map.of())));
                    }
                    case CommandChunk chunk -> {
                      if (chunk.stream() == Stream.STDOUT) {
                        stdout.append(chunk.text());
                      }
                    }
                    case CommandExit ended -> exit.complete(ended.exitCode());
                    default -> {
                      /* Heartbeat / DaemonLog / ProvisionFailed — nothing for the stand-in to do */
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
      daemon = start(jar, port, log);

      Hello said = awaitOrReport(hello, log, "the daemon never dialled home");
      assertEquals(PROJECT_ID, said.projectId(), "the daemon dialled home as who it was told");
      // THE VERSION IS THE ASSERTION, not decoration: it is what makes this "the pinned daemon" and
      // not "a daemon". If this ever disagrees, something downloaded a different build than the pom
      // names and every other assertion below is about the wrong thing.
      assertEquals(
          ProjectAgentImage.VERSION,
          said.daemonVersion(),
          "the daemon that answered is not the version this reactor pins");
      // The capability the host branches on, and the only one there is: AgentTunnels.originFor
      // refuses to open a tunnel to a daemon announcing less than CAPABILITY_VERSION, and a tunnel
      // is the ONLY way this service reaches a container — the daemon's API binds 127.0.0.1 and has
      // no address on the shared network at all. A daemon below the floor is therefore a project
      // whose every proxied route 502s, which is a live-topology failure no unit test here can see.
      // qits-workspaces' pilot names TUNNEL_CAPABILITY_VERSION instead because its daemon reached
      // the loopback bind at version 4 of a protocol that started on the shared network; this
      // protocol started there (DaemonProtocol's javadoc says why it did not copy the numbering), so
      // there is no separate constant to name and no older shape to fall back to.
      assertTrue(
          said.capabilityVersion() >= DaemonProtocol.CAPABILITY_VERSION,
          "the pinned daemon announces capability "
              + said.capabilityVersion()
              + ", below the tunnel's "
              + DaemonProtocol.CAPABILITY_VERSION);

      assertEquals(
          0,
          awaitOrReport(exit, log, "the daemon never finished the command").intValue(),
          "the echo the host asked for");
      assertTrue(
          stdout.toString().contains("project-agent-pin-ok"),
          "the command's output came back over the socket: " + stdout);
    } finally {
      // Decommission, in the order a stuck process demands: the child first, so nothing is still
      // dialling a server that is closing.
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
   * it is — a missing config key, a port it could not bind, a protocol frame it could not decode, a
   * commission it could not mint. A {@code TimeoutException} on its own sends the reader to a log
   * that a deliberately non-inherited stream means they cannot find.
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
              + ProjectAgentImage.DAEMON_NAME
              + " "
              + ProjectAgentImage.VERSION
              + ", the version this reactor pins. Its output was:\n"
              + output,
          e);
    }
  }

  /**
   * Fetches the pinned daemon out of qits-artifacts' {@code daemons} store.
   *
   * <p>A missing artifact is a <b>failure with a sentence</b>, never a skip. It means the version
   * this reactor pins was released without its daemon — or that retention removed it — and either is
   * the exact class of defect this test exists to surface, one release earlier than an agent
   * container that comes up and never speaks.
   */
  private static void download(Path target) throws Exception {
    String url =
        ARTIFACTS_BASE
            + "/daemons/"
            + ProjectAgentImage.DAEMON_NAME
            + "/"
            + ProjectAgentImage.VERSION;
    try (HttpClient client =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()) {
      HttpResponse<InputStream> answer =
          client.send(
              HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build(),
              HttpResponse.BodyHandlers.ofInputStream());
      if (answer.statusCode() != 200) {
        fail(
            "the pinned daemon "
                + ProjectAgentImage.DAEMON_NAME
                + " "
                + ProjectAgentImage.VERSION
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
   * Starts the daemon pointed at the stand-in host.
   *
   * <p><b>Every listening port it could bind is given to it, and every one is free.</b> The daemon's
   * two shipped ports are fixed numbers (13337 hooks, 13338 api) because inside a container they are
   * unshared; this test runs it as an ordinary process on a developer's machine and on a CI host,
   * where 13337 is quite likely to be another agent's daemon — and a bind failure there is logged
   * rather than fatal, so the symptom would be an unrelated test flaking much later. Only the hooks
   * port is actually bound in this run (the API refuses to bind at all without a token, see below),
   * and the api port is handed over anyway rather than left to a default that would become a bind
   * the day that changes.
   */
  private static Process start(Path jar, int hostPort, Path log) throws IOException {
    ProcessBuilder builder =
        new ProcessBuilder(
            javaBinary(),
            "-Dqits.projects-daemon.hooks-port=" + freePort(),
            "-Dqits.projects-daemon.api-port=" + freePort(),
            "-jar",
            jar.toString());
    Map<String, String> env = builder.environment();
    // EVERY AMBIENT QITS_ VARIABLE IS REMOVED FIRST, and this is the difference between a test and a
    // coincidence. `ProcessBuilder` seeds the child from THIS process's environment, and this
    // process runs somewhere that has opinions: a workspace container carries the full
    // QITS_PROJECTS_DAEMON_*/QITS_WORKSPACE_DAEMON_* set plus a commissioned credential, and a CI
    // step container carries QITS_COMMISSIONED_CLIENT_ID/_SECRET and nothing else of the set.
    //
    // That difference is not cosmetic, because ControlSocket.authorization() refuses a PARTIAL
    // commission — VERIFIED against this daemon's own source, and its rule is the same as
    // qits-workspace-daemon's: if ANY ONE of qits.commissioned-client-id,
    // qits.commissioned-client-secret, qits.projects-daemon.auth-token-url and
    // qits.projects-daemon.auth-audience is present then all four are required, and a step container
    // supplies exactly the first two. The future then fails with "commissioned dial-home
    // authentication is incomplete", connect() logs at DEBUG and re-arms its backoff, the daemon
    // never dials, and this test times out having passed locally for weeks — because a workspace
    // container HAS all four and the assertion was riding on this container's credentials.
    //
    // A pin test whose result depends on where it runs proves nothing about the pin, which is the
    // same class of defect as the configuration entry this whole change replaced. So the child gets
    // exactly the two variables below and no others, and dials anonymously: with none of the four
    // present, authorization() short-circuits to Optional.empty() and no Authorization header is
    // sent at all.
    env.keySet().removeIf(key -> key.startsWith("QITS_"));
    env.put(
        "QITS_PROJECTS_DAEMON_URL",
        "ws://127.0.0.1:"
            + hostPort
            + DaemonProtocol.CONTROL_SOCKET_PATH_PREFIX
            + PROJECT_ID);
    env.put("QITS_PROJECTS_DAEMON_PROJECT_ID", PROJECT_ID);
    // DELIBERATELY ABSENT: QITS_PROJECTS_DAEMON_GIT_BASE and QITS_PROJECTS_DAEMON_REPO_NAME.
    //
    // The boot self-provision is not what is under test and must not run, and the reason is
    // concrete rather than tidy: Provisioner's WORKSPACE_DIR is the hard-coded `/workspace`, which
    // on every machine this test actually runs on is a real checkout of somebody's tree — a
    // workspace container's estate, or the CI step container's own build tree. A provision that got
    // past its gates would run `git submodule` commands in it.
    //
    // Provisioner has two ordered gates and leaving both variables unset keeps BOTH: no repo name
    // fails first ("the wrapper is cloned by its qits-githost repository name"), and no git base
    // would fail second. One gate is enough to be correct; two is what this is worth. The daemon
    // sends ProvisionFailed, stays alive exactly as it is built to, and wires its capabilities
    // anyway — which is the path a container with a failed clone takes in production too.
    //
    // The cost is that Hello.repoName() is empty, and it is not an assertion this test wants:
    // "the pinned daemon" is daemonVersion and "dialled home as told" is projectId, both of which
    // are set and both of which are asserted.
    //
    // QITS_PROJECTS_DAEMON_API_TOKEN is absent for its own reason: without it ProjectsApi refuses to
    // bind (fail-closed — it runs processes over an untrusted checkout and is never anonymous), and
    // nothing here reaches that API. The RunCommand round trip below travels the control socket.
    //
    // TO A FILE, AND NOT inheritIO(). A pipe nobody drains would fill and wedge the child, so the
    // output has to go somewhere — and `inheritIO` sends it to this forked JVM's native stdout,
    // which is failsafe's own control channel: surefire answers that with "Corrupted channel by
    // directly writing to native stream in forked JVM". The file is printed by awaitOrReport when
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
   * <p>Inherently racy and correct enough: the window is microseconds, the alternative is two
   * hard-coded numbers that are wrong on any host running an agent container, and the daemon treats
   * a failed bind on them as non-fatal anyway.
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

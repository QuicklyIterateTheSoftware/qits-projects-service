package eu.wohlben.qits.projects.contracts.consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * <b>The guard: no outbound call without a consumer row</b> (ticket qits-1149).
 *
 * <p>The set of calls is <b>derived from the code</b>, never written down here:
 *
 * <ul>
 *   <li><b>A client method</b> is a non-private method of a class in this service that reaches a
 *       request leaving the JVM ({@code java.net.http.HttpClient.send}/{@code sendAsync}, Vert.x
 *       {@code HttpClient.request}) itself or through its own class's helpers and lambdas. Read
 *       off the bytecode with ArchUnit. The qits-eventstream library's calls to qits-events are not
 *       derived here: {@link EventstreamPactTest} pacts them from the library's own kit, and its
 *       guard holds the listeners in step with the code (qits-1163).
 *   <li><b>A qits-containers client method</b> this service calls: every method of the library's
 *       {@code ContainersClient} some class here invokes. The library's other doors are not this
 *       service's calls. {@link ContainersClientUsageTest} holds the same rows against the
 *       qits-containers-client-contracts kit's own scan, per feature (qits-1163).
 *   <li><b>A quarkus-oidc-client</b> switched on in {@code application.properties} (its unprofiled
 *       {@code client-enabled=true}): the client-credentials token mint at qits-idp.
 * </ul>
 *
 * <p>The set of rows is {@link ConsumerContracts#all()}, pending rows included: a row waiting on a
 * provider state still names its call. <b>Which call a row covers is observed</b>: each row's real
 * client runs against a stub server on a thread of its own, and the stub samples that thread's
 * stack while the client waits for the answer, so the client methods on it are the ones the row
 * exercises. A row that stands in for a client the test cannot run (the Vert.x relay over a
 * daemon's tunnel, quarkus-oidc-client outside Quarkus) says which in {@link #STANDS_IN_FOR}.
 *
 * <p>It fails when a client method has no row, when a row reaches no client method, and when an
 * exclusion or a stand-in names something the code no longer has. Every exclusion is named here,
 * with its reason, so excluding a call is a visible decision.
 */
class ConsumerCallCoverageTest {

  /**
   * Calls the code makes that are not REST contracts, each with the reason it has no row. Empty
   * today: the WebSockets (the events stream, the daemon control sockets) and the browser proxies
   * ({@code ContainerProxyRoute}, {@code RefinementProxyRoute}) send no request this derivation
   * reaches, and every call it does reach has a row.
   */
  static final Map<String, String> EXCLUDED =
      Map.of(
          "eu.wohlben.qits.projects.releasehost.HttpReleaseGitHost#head",
          "ReleaseGitHost.head has no caller in the shipped code");

  /**
   * Rows whose call stands in for a client the test cannot run itself: row description to the
   * client calls it covers. Each such row makes the same request with the JDK client and says so in
   * its contract's comment.
   */
  static final Map<String, Set<String>> STANDS_IN_FOR =
      Map.of(
          // The daemon's three rows stand in for the one tunnel client they share (qits-1572):
          // AgentCapabilityRelay and ProjectFrontDeskController both ask through DaemonApiClient,
          // and so does the desk trigger sweep's prompt (FrontDeskPrompter.prompt, epic qits-1604),
          // the same POST the promptFrontDesk row makes.
          "AgentCapabilityRelay.relay (on the daemon's Hello): listAvailableAgents",
          Set.of("eu.wohlben.qits.projects.agenthost.DaemonApiClient#get"),
          "promptFrontDesk: promptFrontDesk",
          Set.of("eu.wohlben.qits.projects.agenthost.DaemonApiClient#post"),
          "getFrontDeskLastAnswer: getFrontDeskLastAnswer",
          Set.of("eu.wohlben.qits.projects.agenthost.DaemonApiClient#get"),
          "setWorkBlocked (RefinementAgentEntities.changed): setAgentEntity",
          Set.of(
              "eu.wohlben.qits.projects.refinementhost.RefinementAgentEntities#changed",
              "eu.wohlben.qits.projects.refinementhost.RefinementAgentEntities#send"),
          IdpContract.TOKEN_MINT_TRIGGER + ": issueToken",
          Set.of("quarkus-oidc-client:qits"));

  private static final String CONTAINERS_CLIENT = "eu.wohlben.qits.containers.client.ContainersClient";

  /** Where a request leaves the JVM: owner type and method name. */
  private static final Map<String, Set<String>> SENDS =
      Map.of(
          "java.net.http.HttpClient", Set.of("send", "sendAsync"),
          "io.vertx.core.http.HttpClient", Set.of("request"));

  private static Set<String> calls;

  @BeforeAll
  static void derive() throws IOException {
    calls = derivedCalls();
  }

  @Test
  void everyOutboundCallHasARow() throws Exception {
    Map<String, Set<String>> covered = coveredByRows();
    Set<String> rowed = new TreeSet<>();
    covered.values().forEach(rowed::addAll);
    Set<String> missing = new TreeSet<>(calls);
    missing.removeAll(rowed);
    missing.removeAll(EXCLUDED.keySet());
    assertTrue(
        missing.isEmpty(),
        "client calls with no consumer row (add one to a *Contract, pending on the provider state"
            + " it needs if the provider has not recorded it, or name it in EXCLUDED with the reason):"
            + "\n  "
            + String.join("\n  ", missing));
  }

  @Test
  void everyRowReachesAClientCall() throws Exception {
    Set<String> idle =
        coveredByRows().entrySet().stream()
            .filter(e -> e.getValue().isEmpty())
            .map(Map.Entry::getKey)
            .collect(Collectors.toCollection(TreeSet::new));
    assertTrue(
        idle.isEmpty(),
        "rows whose call reaches no client method of this service (a stale row, or a stand-in"
            + " missing from STANDS_IN_FOR):\n  "
            + String.join("\n  ", idle));
  }

  @Test
  void everyExclusionAndStandInNamesACallTheCodeMakes() {
    Set<String> stale = new TreeSet<>(EXCLUDED.keySet());
    STANDS_IN_FOR.values().forEach(stale::addAll);
    stale.removeAll(calls);
    assertTrue(stale.isEmpty(), "named calls the code no longer makes: " + stale);
    Set<String> descriptions = new HashSet<>();
    ConsumerContracts.all().forEach(row -> descriptions.add(row.description()));
    Set<String> unknown = new TreeSet<>(STANDS_IN_FOR.keySet());
    unknown.removeAll(descriptions);
    assertTrue(unknown.isEmpty(), "STANDS_IN_FOR names rows that do not exist: " + unknown);
  }

  // --- the calls, from the code ---------------------------------------------------------------

  static Set<String> derivedCalls() throws IOException {
    JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(
                "eu.wohlben.qits.projects", "eu.wohlben.qits.containers.client");
    Set<String> out = new TreeSet<>();
    for (JavaClass type : classes) {
      String name = type.getName();
      if (!name.startsWith("eu.wohlben.qits.projects.")) {
        continue;
      }
      for (JavaMethod method : type.getMethods()) {
        for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
          if (CONTAINERS_CLIENT.equals(call.getTargetOwner().getName())) {
            out.add(CONTAINERS_CLIENT + "#" + call.getName());
          }
        }
        if (method.getModifiers().contains(JavaModifier.PRIVATE)
            || method.getModifiers().contains(JavaModifier.SYNTHETIC)
            || method.getName().startsWith("lambda$")) {
          continue;
        }
        if (reachesASend(method)) {
          out.add(name + "#" + method.getName());
        }
      }
    }
    out.addAll(oidcClients());
    return out;
  }

  /** Whether {@code start} sends a request itself or through a method of its own class. */
  private static boolean reachesASend(JavaMethod start) {
    JavaClass owner = start.getOwner();
    Deque<JavaCodeUnit> todo = new ArrayDeque<>(List.of(start));
    Set<JavaCodeUnit> seen = new HashSet<>();
    while (!todo.isEmpty()) {
      JavaCodeUnit unit = todo.pop();
      if (!seen.add(unit)) {
        continue;
      }
      for (JavaMethodCall call : unit.getMethodCallsFromSelf()) {
        String target = call.getTargetOwner().getName();
        if (SENDS.getOrDefault(target, Set.of()).contains(call.getName())) {
          return true;
        }
        if (target.equals(owner.getName())) {
          call.getTarget().resolveMember().ifPresent(todo::push);
        }
      }
    }
    return false;
  }

  /** {@code quarkus-oidc-client:<name>} for each named client the shipped profile switches on. */
  private static Set<String> oidcClients() {
    Properties properties = IdpContract.shippedProperties();
    Set<String> out = new TreeSet<>();
    for (String key : properties.stringPropertyNames()) {
      if (key.startsWith("quarkus.oidc-client.")
          && key.endsWith(".client-enabled")
          && "true".equals(properties.getProperty(key).trim())) {
        String name =
            key.substring("quarkus.oidc-client.".length(), key.length() - ".client-enabled".length());
        if (!name.isEmpty() && !name.contains(".")) {
          out.add("quarkus-oidc-client:" + name);
        }
      }
    }
    return out;
  }

  // --- the rows, observed ---------------------------------------------------------------------

  private static Map<String, Set<String>> covered;

  /** Row (provider, description, state) to the client calls its real client was seen making. */
  static synchronized Map<String, Set<String>> coveredByRows() throws Exception {
    if (covered != null) {
      return covered;
    }
    ConsumerGoldenMasters masters = ConsumerGoldenMasters.classpath();
    Map<String, Set<String>> out = new TreeMap<>();
    for (ConsumerRow row : ConsumerContracts.all()) {
      String key = row.provider() + " — " + row.description() + " [" + row.state() + "]";
      Set<String> reached = new TreeSet<>(observe(row, masters));
      reached.addAll(STANDS_IN_FOR.getOrDefault(row.description(), Set.of()));
      out.computeIfAbsent(key, k -> new TreeSet<>()).addAll(reached);
    }
    covered = out;
    return out;
  }

  /** The client methods on the row's thread while its request is in flight. */
  private static Set<String> observe(ConsumerRow row, ConsumerGoldenMasters masters)
      throws Exception {
    Set<String> reached = ConcurrentHashMap.newKeySet();
    AtomicReference<Thread> caller = new AtomicReference<>();
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          Thread thread = caller.get();
          if (thread != null) {
            for (StackTraceElement frame : thread.getStackTrace()) {
              String call = frame.getClassName() + "#" + frame.getMethodName();
              if (calls.contains(call)) {
                reached.add(call);
              }
            }
          }
          exchange.sendResponseHeaders(503, -1);
          exchange.close();
        });
    server.start();
    try {
      Map<String, String> params = params(row, masters);
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      Thread thread =
          new Thread(
              () -> {
                try {
                  row.call().run(base, params);
                } catch (Throwable expected) {
                  // The stub answers 503 to everything: the row's own assertions are not the point.
                } finally {
                  RowClients.closeAll();
                }
              },
              "coverage " + row.description());
      thread.setDaemon(true);
      caller.set(thread);
      thread.start();
      thread.join(15_000);
      thread.interrupt();
    } finally {
      server.stop(0);
    }
    return reached;
  }

  /** The recorded params, or a UUID for any a pending row names before its state is recorded. */
  private static Map<String, String> params(ConsumerRow row, ConsumerGoldenMasters masters) {
    Map<String, String> recorded =
        row.recorded(masters).map(ConsumerGoldenMasters.Recorded::params).orElse(Map.of());
    return new HashMap<>(new LinkedHashMap<>(recorded)) {
      @Override
      public String get(Object key) {
        String value = super.get(key);
        return value != null ? value : "00000000-0000-4000-8000-000000000001";
      }
    };
  }
}

package eu.wohlben.qits.projects.deskhost;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import eu.wohlben.qits.projects.agenthost.ContainerProxyPath;
import eu.wohlben.qits.projects.control.AgentSurfaceConfigurationService;
import eu.wohlben.qits.projects.control.GitIdentity;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.entity.FrontDesk;
import eu.wohlben.qits.projects.entity.FrontDeskLifecycle;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.projectsdaemon.protocol.ProjectAgentImage;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskSpec;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * What a project's front desk container is (qits-767): {@link #compose} turns a project and its
 * {@code front_desk} row into the runner protocol's {@link DeskSpec}, its canonical JSON and the
 * SHA-256 of that JSON — the {@code spec_hash} the runner labels the container with.
 *
 * <p><b>Pure.</b> {@link #compose(Settings, Project, FrontDesk, Map, String)} reads nothing but its
 * arguments, so a golden test pins the whole spec for a fixed project, token and domain; the bean
 * method only gathers the configuration, the git identity and the agent configuration document.
 *
 * <p><b>Edge-only.</b> The desk runs on a runner's node and reaches the platform only through the
 * public edge, so every address it is given is a {@code <app>.qits.<domain>} one: there is no {@code
 * QITS_COMMISSIONED_*}, no {@code *_AUTH_TOKEN_URL} and no {@code *.internal}, {@code -qits-} or
 * {@code *.localhost} value. It authenticates with its own {@code qits_tok_} ({@code QITS_TOKEN}).
 * A domain that is blank, has no dot, or is {@code localhost}/{@code *.localhost} refuses to compose
 * ({@link EdgePlaneUnconfigured}, the belt qits-workspaces-service's {@code WorkspaceAddressPlane}
 * carries), and the desk reads FAILED with {@link #EDGE_PLANE_UNCONFIGURED}.
 *
 * <p><b>Mounts, network, init and the restart policy are not in the spec</b>: the runner decides
 * them from its own identity.
 *
 * <p>The pieces moved here from {@code AgentContainerFactory} (the direct path qits-1111 deletes):
 * the image pin rule ({@link #imageVersion()}), the git identity env, the agent configuration
 * document, the limits, the user (the host uid) and the claude mount.
 */
@ApplicationScoped
public class FrontDeskSpecs {

  private static final Logger LOG = Logger.getLogger(FrontDeskSpecs.class);

  /** The failure detail of a desk whose spec cannot be composed for want of a public domain. */
  public static final String EDGE_PLANE_UNCONFIGURED = "EDGE_PLANE_UNCONFIGURED";

  /** Where the runner mounts the node's agent home in a desk. */
  public static final String CLAUDE_MOUNT = "/claude-home";

  static final String MAVEN_MOUNT = "/caches/m2";

  static final String PNPM_MOUNT = "/caches/pnpm";

  static final String PLATFORM_PROJECT = "qits";

  /** The registry spelling the shipped image repository names, rewritten to the edge's. */
  static final String DEV_REGISTRY = "registry.dev.localhost:8080";

  /** The daemon's own environment name for the resolved agent configuration document. */
  public static final String AGENT_CONFIGURATION_ENV = "QITS_PROJECTS_DAEMON_AGENT_CONFIGURATION";

  /** Where the daemon writes {@link #AGENT_CONFIGURATION_ENV} before it starts anything. */
  public static final String AGENT_CONFIGURATION_PATH_ENV =
      "QITS_PROJECTS_DAEMON_AGENT_CONFIGURATION_PATH";

  /** Canonical JSON: sorted keys at every level, nulls written. */
  private static final ObjectMapper CANONICAL =
      new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

  /** The image repository, registry host included ({@code registry.dev.localhost:8080/...}). */
  @ConfigProperty(name = "qits.projects.agent-image-repo")
  String imageRepo;

  /** The emergency hatch over the pin; shipped unset. */
  @ConfigProperty(name = "qits.projects.agent-image-version-override")
  Optional<String> imageVersionOverride;

  @ConfigProperty(name = "qits.domain")
  Optional<String> domain;

  @ConfigProperty(name = "qits.projects.agent-timezone")
  Optional<String> timezone;

  @ConfigProperty(name = "qits.projects.agent-memory-limit")
  Optional<String> memoryLimit;

  @ConfigProperty(name = "qits.projects.agent-pids-limit")
  Optional<String> pidsLimit;

  @ConfigProperty(name = "qits.projects.agent-cpus")
  Optional<String> cpus;

  @ConfigProperty(name = "qits.projects.agent-oom-score-adj", defaultValue = "800")
  Integer oomScoreAdj;

  /** {@code --user}; unset is the host uid this service runs as, as the direct path used. */
  @ConfigProperty(name = "qits.projects.agent-user")
  Optional<String> user;

  @ConfigProperty(name = "qits.projects.daemon-api-token", defaultValue = "qits-projects-daemon")
  String daemonApiToken;

  @ConfigProperty(name = "qits.projects.daemon-api-port", defaultValue = "13338")
  int daemonApiPort;

  @ConfigProperty(name = "qits.projects.daemon-hooks-port", defaultValue = "13337")
  int daemonHooksPort;

  @ConfigProperty(
      name = "qits.projects.agent-configuration-path",
      defaultValue = "/tmp/qits/agent-configuration.json")
  String agentConfigurationPath;

  @Inject GitIdentity gitIdentity;

  @Inject AgentSurfaceConfigurationService surfaces;

  @Inject ObjectMapper json;

  /** A suite composing for a dotted domain without restarting the application; null restores. */
  private volatile String domainOverride;

  void domainOverride(String domain) {
    this.domainOverride = domain;
  }

  /** Everything a spec is composed from that is configuration rather than the project or the row. */
  public record Settings(
      String domain,
      String imageRepo,
      String imageVersion,
      String timezone,
      String user,
      String memory,
      Long pidsLimit,
      String cpus,
      Integer oomScoreAdj,
      String daemonApiToken,
      int daemonApiPort,
      int daemonHooksPort,
      String agentConfigurationPath) {}

  /** A composed spec: the record, its canonical JSON (what {@code spec_json} holds) and the hash. */
  public record Composed(DeskSpec spec, String json, String hash) {}

  /** The domain is not one an edge-only desk can be addressed under. */
  public static final class EdgePlaneUnconfigured extends RuntimeException {
    public EdgePlaneUnconfigured(String domain) {
      super(
          EDGE_PLANE_UNCONFIGURED
              + ": qits-projects knows no public dotted domain (QITS_DOMAIN is '"
              + domain
              + "'), so a front desk has no edge address to dial");
    }
  }

  // --- the pin -----------------------------------------------------------------------------------

  /**
   * The project-agent image's tag: {@code qits.projects.agent-image-version-override} if set (blank
   * counts as unset), else {@link ProjectAgentImage#VERSION}, the pinned daemon protocol's version.
   * The one resolution of that rule: the pins route, the runner health check, the capability relay
   * and every spec read it here.
   */
  public String imageVersion() {
    return imageVersionOverride.filter(version -> !version.isBlank()).orElse(pinnedImageVersion());
  }

  /**
   * The pin alone, with no override: {@link ProjectAgentImage#VERSION}, the version of the
   * daemon-protocol jar this reactor pins. {@code ProjectAgentDaemonPinIT} proves the daemon at this
   * version still speaks this service's codec.
   */
  public static String pinnedImageVersion() {
    return ProjectAgentImage.VERSION;
  }

  /** The configured image repository, as {@code GET /projects/api/pins} names it. */
  public String imageRepo() {
    return imageRepo;
  }

  /** The image a desk runs, at the edge registry; null while the domain is unconfigured. */
  public String imageOrNull() {
    try {
      return image(settings());
    } catch (EdgePlaneUnconfigured unconfigured) {
      return null;
    }
  }

  /** Where the runner mounts the agent home; the login command lays it out the same way. */
  public String claudeMount() {
    return CLAUDE_MOUNT;
  }

  /** The uid this service runs as: the desk's {@code --user} unless {@code agent-user} is set. */
  public long hostUid() {
    try {
      Object uid = Files.getAttribute(Path.of(System.getProperty("user.home")), "unix:uid");
      return ((Number) uid).longValue();
    } catch (Exception e) {
      return 1000L;
    }
  }

  /** A desk's {@code --user}: {@code qits.projects.agent-user}, else the host uid. */
  public String deskUser() {
    return set(user).orElseGet(() -> Long.toString(hostUid()));
  }

  // --- composing ---------------------------------------------------------------------------------

  /** The project's desk spec as it stands now. Throws {@link EdgePlaneUnconfigured}. */
  public Composed compose(Project project, FrontDesk row) {
    Settings settings = settings();
    domainOf(settings.domain()); // refuse before building the document
    return compose(settings, project, row, gitIdentity.envMap(), agentConfiguration(settings));
  }

  /** The configuration as it stands now. */
  public Settings settings() {
    String d = domainOverride != null ? domainOverride : domain.orElse(null);
    return new Settings(
        d,
        imageRepo,
        imageVersion(),
        set(timezone).orElse(null),
        deskUser(),
        set(memoryLimit).orElse(null),
        pids(),
        set(cpus).orElse(null),
        oomScoreAdj,
        daemonApiToken,
        daemonApiPort,
        daemonHooksPort,
        agentConfigurationPath);
  }

  /**
   * The spec, pure. {@code identity} is the git identity env, {@code document} the serialized agent
   * configuration document (ignored when {@code settings.agentConfigurationPath()} is blank).
   */
  public static Composed compose(
      Settings settings,
      Project project,
      FrontDesk row,
      Map<String, String> identity,
      String document) {
    String d = domainOf(settings.domain());
    String projectId = project.id;
    FrontDeskLifecycle lifecycle =
        project.frontDeskLifecycle == null
            ? FrontDeskLifecycle.ON_DEMAND
            : project.frontDeskLifecycle;
    Map<String, String> env = new LinkedHashMap<>();
    if (settings.timezone() != null) {
      env.put("TZ", settings.timezone());
    }
    env.put(
        "QITS_PROJECTS_DAEMON_URL",
        "wss://" + host("projects", d) + DaemonProtocol.CONTROL_SOCKET_PATH_PREFIX + projectId);
    env.put("QITS_PROJECTS_DAEMON_API_BASE_PATH", ContainerProxyPath.base(projectId));
    env.put("QITS_PROJECTS_DAEMON_PROJECT_ID", projectId);
    env.put("QITS_PROJECTS_DAEMON_REPO_NAME", ProjectService.wrapperName(project));
    env.put("QITS_PROJECTS_DAEMON_GIT_BASE", "https://" + host("githost", d) + "/git");
    env.put("QITS_PROJECTS_DAEMON_API_TOKEN", settings.daemonApiToken());
    env.put("QITS_PROJECTS_DAEMON_API_PORT", Integer.toString(settings.daemonApiPort()));
    env.put("QITS_PROJECTS_DAEMON_HOOKS_PORT", Integer.toString(settings.daemonHooksPort()));
    env.put("QITS_PROJECTS_DAEMON_CLAUDE_MOUNT", CLAUDE_MOUNT);
    String path = settings.agentConfigurationPath();
    if (path != null && !path.isBlank() && document != null) {
      env.put(AGENT_CONFIGURATION_PATH_ENV, path);
      env.put(AGENT_CONFIGURATION_ENV, document);
    }
    env.put("QITS_PROJECTS_DAEMON_LIFECYCLE", lifecycle.name());
    env.put("QITS_TOKEN", nullToEmpty(row.tokenValue));
    env.put("QITS_TOKEN_SUBJECT", nullToEmpty(row.tokenSubject));
    env.put("QITS_DOMAIN", d);
    env.put("GIT_CONFIG_GLOBAL", "/etc/qits-gitconfig");
    env.put("QITS_GIT_AUTH_HOST", host("githost", d));
    env.put("QITS_REPOSITORY_MCP_URL", "https://" + host("projects", d) + "/projects/mcp");
    env.put("QITS_PLATFORM_MCP_URL", "https://" + host("mcp", d) + "/mcp");
    if (identity != null) {
      identity.forEach(env::put);
    }
    env.put("CLAUDE_CONFIG_DIR", CLAUDE_MOUNT + "/.claude");
    env.put("KIMI_CODE_HOME", CLAUDE_MOUNT + "/.kimi-code");
    env.put("MAVEN_OPTS", "-Dmaven.repo.local=" + MAVEN_MOUNT);
    env.put("npm_config_store_dir", PNPM_MOUNT + "/store");
    DeskSpec spec =
        new DeskSpec(
            image(settings),
            env,
            settings.user(),
            settings.memory(),
            settings.memory(),
            settings.pidsLimit(),
            settings.cpus(),
            settings.oomScoreAdj());
    String canonical = canonical(spec);
    return new Composed(spec, canonical, sha256(canonical));
  }

  /** The image at the edge registry: the configured repository's registry rewritten. */
  static String image(Settings settings) {
    String d = domainOf(settings.domain());
    String reference = settings.imageRepo() + ":" + settings.imageVersion();
    int slash = reference.indexOf('/');
    if (slash <= 0) {
      return reference;
    }
    String registry = reference.substring(0, slash).toLowerCase(Locale.ROOT);
    if (!registrySpellings(settings.imageRepo()).contains(registry)) {
      return reference;
    }
    return host("registry", d) + reference.substring(slash);
  }

  /**
   * The registry spellings rewritten: the shipped dev registry, and whatever registry host the
   * configured repository names (a first segment with a dot or a port is a host, as docker reads
   * it).
   */
  private static List<String> registrySpellings(String repo) {
    List<String> spellings = new ArrayList<>(List.of(DEV_REGISTRY));
    int slash = repo == null ? -1 : repo.indexOf('/');
    if (slash > 0) {
      String first = repo.substring(0, slash).toLowerCase(Locale.ROOT);
      if (first.contains(".") || first.contains(":")) {
        spellings.add(first);
      }
    }
    return spellings;
  }

  /** The folded public domain, or {@link EdgePlaneUnconfigured}. */
  static String domainOf(String domain) {
    String folded =
        domain == null
            ? ""
            : domain.trim().toLowerCase(Locale.ROOT).replaceAll("^\\.+|\\.+$", "");
    if (folded.isEmpty()
        || folded.indexOf('.') < 0
        || folded.equals("localhost")
        || folded.endsWith(".localhost")) {
      throw new EdgePlaneUnconfigured(domain == null ? "" : domain.trim());
    }
    return folded;
  }

  private static String host(String app, String domain) {
    return app + "." + PLATFORM_PROJECT + "." + domain;
  }

  // --- canonical JSON and the hash ---------------------------------------------------------------

  /** The spec as a map with sorted keys at every level, nulls kept. */
  static Map<String, Object> canonicalMap(DeskSpec spec) {
    Map<String, Object> map = new TreeMap<>();
    map.put("image", spec.image());
    map.put("env", new TreeMap<>(spec.env()));
    map.put("user", spec.user());
    map.put("memory", spec.memory());
    map.put("memorySwap", spec.memorySwap());
    map.put("pidsLimit", spec.pidsLimit());
    map.put("cpus", spec.cpus());
    map.put("oomScoreAdj", spec.oomScoreAdj());
    return map;
  }

  /** The canonical JSON of the whole spec, env included, keys sorted. */
  public static String canonical(DeskSpec spec) {
    try {
      return CANONICAL.writeValueAsString(canonicalMap(spec));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Could not serialize a desk spec", e);
    }
  }

  /** The spec a stored {@code spec_json} holds. */
  public static DeskSpec parse(String specJson) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> map = CANONICAL.readValue(specJson, Map.class);
      @SuppressWarnings("unchecked")
      Map<String, Object> rawEnv = (Map<String, Object>) map.get("env");
      Map<String, String> env = new TreeMap<>();
      if (rawEnv != null) {
        rawEnv.forEach((k, v) -> env.put(k, v == null ? null : v.toString()));
      }
      Number pids = (Number) map.get("pidsLimit");
      Number oom = (Number) map.get("oomScoreAdj");
      return new DeskSpec(
          (String) map.get("image"),
          env,
          (String) map.get("user"),
          (String) map.get("memory"),
          (String) map.get("memorySwap"),
          pids == null ? null : pids.longValue(),
          (String) map.get("cpus"),
          oom == null ? null : oom.intValue());
    } catch (JsonProcessingException | ClassCastException e) {
      throw new IllegalStateException("A stored desk spec could not be read back", e);
    }
  }

  /** SHA-256 hex of the UTF-8 bytes. */
  public static String sha256(String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  // --- helpers -----------------------------------------------------------------------------------

  private String agentConfiguration(Settings settings) {
    String path = settings.agentConfigurationPath();
    if (path == null || path.isBlank()) {
      return null;
    }
    try {
      // The store is read in a transaction of its own: a spec is composed from a socket frame, a
      // sweep or a door alike, and only some of those stand in a request context.
      return json.writeValueAsString(
          QuarkusTransaction.requiringNew().call(() -> surfaces.documentForContainerSpec()));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(
          "Could not serialize the agent configuration document for a front desk", e);
    }
  }

  private Long pids() {
    String value = set(pidsLimit).orElse(null);
    if (value == null) {
      return null;
    }
    try {
      return Long.valueOf(value.trim());
    } catch (NumberFormatException e) {
      LOG.warnf("qits.projects.agent-pids-limit is not a number ('%s'); no pids cap is set", value);
      return null;
    }
  }

  private static Optional<String> set(Optional<String> value) {
    return value == null ? Optional.empty() : value.map(String::trim).filter(v -> !v.isEmpty());
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}

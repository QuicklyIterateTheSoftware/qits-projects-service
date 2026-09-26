package eu.wohlben.qits.projects.refinementhost;

import eu.wohlben.qits.containers.client.ContainersWire.EnsureRequest;
import eu.wohlben.qits.containers.client.ContainersWire.Policy;
import eu.wohlben.qits.containers.client.ContainersWire.PullPolicy;
import eu.wohlben.qits.containers.client.ContainersWire.Recreate;
import eu.wohlben.qits.containers.client.ContainersWire.Security;
import eu.wohlben.qits.containers.client.ContainersWire.SharedMount;
import eu.wohlben.qits.containers.client.ContainersWire.Spec;
import eu.wohlben.qits.containers.client.ContainersWire.VolumeMount;
import eu.wohlben.qits.projects.control.GitIdentity;
import eu.wohlben.qits.projects.entity.Refinement;
import eu.wohlben.qits.workspacedaemon.protocol.WorkspaceImage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.ZoneId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The whole of what a refinement container is started with — the refinement twin of
 * {@code agenthost/AgentContainerFactory}, assembling the <b>workspace image and daemon,
 * unchanged</b>: the daemon dials home to whatever {@code QITS_WORKSPACE_DAEMON_URL} names, and
 * every name below is read from qits-workspace-daemon's own environment table, not invented here.
 * The reference for the values is qits-workspaces' {@code WorkspaceContainerFactory}, whose spec a
 * refinement matches except where refinement genuinely differs:
 *
 * <ul>
 *   <li><b>No bootstrap, no services.</b> {@code BOOTSTRAP_AUTORUN=false} and {@code
 *       SERVICES_AUTOSTART=false} — a refinement runs no code, which is why the Services and
 *       Actions tabs do not exist on its route — and no {@code SERVICE_PROXY_BASE}, so a
 *       web-viewable spawn (which never happens) would WARN rather than guess.
 *   <li><b>The dial-home addresses are this service's</b>: the control socket, the proxy base and
 *       the auth audience all name qits-projects.
 *   <li><b>One extra MCP server beside {@code repository}</b>: observability, the same pair a
 *       repository-scope launch wires in a workspace. Actions is deliberately absent — there is no
 *       actions surface on this route and the daemon fails an ACTIONS launch saying so.
 * </ul>
 *
 * <p>{@code Recreate.ifChanged} on <b>both</b> arms, the workspaces reading: an unchanged spec is a
 * start in place, and a workspace-image bump landing while the refinement slept is applied at wake
 * — the one moment it can be without taking a container away from somebody working in it. That is
 * also why the commissioned credential must come off the row byte for byte
 * ({@link RefinementCommissions}).
 */
@ApplicationScoped
public class RefinementContainerFactory {

  private static final Logger LOG = Logger.getLogger(RefinementContainerFactory.class);

  /**
   * The registry host and path of the image — the workspace toolchain plus the workspace daemon,
   * exactly what a qits-workspaces workspace runs. Fully qualified for the same reason the agent
   * image is: a bare name resolves against whatever is lying in the host daemon's image store.
   */
  @ConfigProperty(name = "qits.projects.refinement-image-repo")
  String imageRepo;

  /**
   * The released calver the workspace image is pinned to — <b>{@link WorkspaceImage#VERSION}, the
   * version of the dependency this reactor pins</b>, with config as an explicit override and
   * nothing else.
   *
   * <p><b>It used to be the other way round</b>, and that is the defect this field records. The
   * value came from config: qits-configuration's release listener consumed qits-workspace-daemon's
   * {@code SoftwareRelease} of {@code qits/workspace} and wrote it as this application's {@code
   * QITS_PROJECTS_REFINEMENT_IMAGE_VERSION} extra, which the deployer injected and SmallRye let
   * win. So a version rewritten by whoever released the image reached the <em>next</em> refinement
   * container with nothing having tested the pair — this service speaks that daemon's protocol, and
   * a protocol change arriving as an environment variable is a change no gate here ever saw. The
   * committed fallback beside it aged in silence for the same reason, until it named a tag the
   * registry's retention had deleted and every run without the injection failed at the pull.
   *
   * <p>Now the version is the version of {@code eu.wohlben.qits:qits-workspace-daemon-protocol},
   * the jar that also carries the protocol both ends speak — one artifact, because what this
   * service must <em>speak</em> and what it must <em>start</em> are one release. It cannot age: the
   * dependency has to resolve for this reactor to build, the pin is a reviewed line in this
   * repository's own pom, it is gated by this repository's own release request, and {@code
   * PinsControllerTest} proves the pin answer is that same constant.
   *
   * <p><b>An emergency override survives, under a name nothing automates.</b> {@code
   * qits.projects.refinement-image-version-override} — {@code
   * QITS_PROJECTS_REFINEMENT_IMAGE_VERSION_OVERRIDE} — is {@code Optional} with no shipped default,
   * so absent is the ordinary state. An operator who has to pin a different image live can still do
   * it, and the pair is then explicitly untested by anything, which is the honest reading of an
   * override.
   *
   * <p><b>The RENAME is the point, and the old name is the reason.</b> This used to read {@code
   * qits.projects.refinement-image-version}, which is exactly the key qits-configuration's {@code
   * ImagePins} wrote on every image release — so "an emergency override" and "the automatic pin"
   * were one string, and the automatic one won on every deploy. Retiring the declaration stops the
   * row being written again but cannot unwrite the entry already there: <b>nothing on this platform
   * deletes a configuration entry</b> — qits-configuration's own {@code ConfigurationService} says
   * so, "an orphan is reported and never cleaned up" — and this service's credential could not do
   * it anyway. The residue therefore stays in every deployment's environment for ever, and a
   * different name is what makes it inert, with no deletion required and no way for an automatic
   * writer to land on the override again by accident. {@link RetiredRefinementImageVersionKey} says
   * so out loud while the old entry is still present.
   */
  @ConfigProperty(name = "qits.projects.refinement-image-version-override")
  Optional<String> imageVersionOverride;

  String image() {
    return imageRepo + ":" + imageVersion();
  }

  /**
   * The two halves apart, for the launch-pin route ({@code GET /projects/api/pins}) — the same
   * accessor pair {@code AgentContainerFactory} carries and for its reason: the pin must name what a
   * start would pull, read from the field a start reads, never from a second declaration of the key.
   */
  public String imageRepo() {
    return imageRepo;
  }

  /**
   * The refinement image's calver tag — <b>the pinned dependency's version, unless an operator has
   * deliberately overridden it</b>; see {@link #imageRepo()} for why the two halves are readable
   * apart, and {@link #imageVersionOverride} for why the pin is the default and not the other way
   * round.
   *
   * <p>One method rather than a field resolved at injection, because {@code GET
   * /projects/api/pins} and every container start have to give the same answer, and a second read
   * of the key somewhere else is exactly how they would stop doing so — which is why {@link
   * #image()} calls this rather than reading a field.
   *
   * <p><b>The blank filter is load-bearing and is not tidiness.</b> SmallRye maps {@code
   * QITS_PROJECTS_REFINEMENT_IMAGE_VERSION_OVERRIDE} onto this key, and a deployment template that
   * renders the variable with nothing to put in it produces a <em>present, empty</em> value rather
   * than an absent one. Taken literally that composes {@code …/qits/workspace:} — a reference with
   * no tag — and fails every container launch, on a deployment whose only mistake was rendering an
   * empty template line. Empty means unset here, which is the only reading that makes sense of it.
   */
  public String imageVersion() {
    return imageVersionOverride.filter(value -> !value.isBlank()).orElse(WorkspaceImage.VERSION);
  }

  /** The same shared network, credential volume and build caches the agent factory mounts. */
  @ConfigProperty(name = "qits.projects.agent-network", defaultValue = "qits-net")
  String network;

  @ConfigProperty(name = "qits.projects.claude-volume", defaultValue = "qits_shared_dot_claude")
  String claudeVolume;

  @ConfigProperty(name = "qits.projects.claude-mount", defaultValue = "/claude-home")
  String claudeMount;

  @ConfigProperty(name = "qits.projects.maven-volume", defaultValue = "qits_shared_m2")
  String mavenVolume;

  @ConfigProperty(name = "qits.projects.pnpm-volume", defaultValue = "qits_shared_pnpm")
  String pnpmVolume;

  /** Name prefix for the per-refinement {@code /workspace} volume — {@code prefix + rowId}. */
  @ConfigProperty(name = "qits.projects.refinement-volume-prefix", defaultValue = "qits_refinement_")
  String volumePrefix;

  @ConfigProperty(name = "qits.projects.agent-timezone")
  Optional<String> timezone;

  @ConfigProperty(name = "qits.projects.refinement-memory-limit", defaultValue = "4g")
  Optional<String> memoryLimit;

  @ConfigProperty(name = "qits.projects.refinement-pids-limit")
  Optional<String> pidsLimit;

  @ConfigProperty(name = "qits.projects.refinement-cpus")
  Optional<String> cpus;

  /** Reaped under host memory pressure with the agents (800), before workspaces (600). */
  @ConfigProperty(name = "qits.projects.refinement-oom-score-adj", defaultValue = "800")
  Integer oomScoreAdj;

  @ConfigProperty(
      name = "qits.projects.own-host",
      defaultValue = "${QITS_ENVIRONMENT:dev}-qits-projects")
  String ownHost;

  @ConfigProperty(name = "qits.projects.own-port", defaultValue = "8080")
  String ownPort;

  @ConfigProperty(name = "quarkus.oidc-client.qits.auth-server-url")
  String idpAuthServerUrl;

  /**
   * The one audience every service now asks for and every service now accepts
   * (service-client-identity-plan.md, C4) — what the container's git credential helper requests for
   * its git reads AND what the daemon requests for its own dial-home to this service's refinement
   * control socket. A constant, not a config key: there is nothing left for a deployment to
   * configure here.
   */
  static final String PLATFORM_AUDIENCE = "qits-platform";

  /**
   * The address a <em>container</em> reaches git at — scheme, host and port, no path. The daemon's
   * clone base is this plus {@code /git}; {@code QITS_GIT_AUTH_HOST} is its authority. The shipped
   * default is the internal githost alias and {@code application.properties} carries the
   * measurement behind it: the image's credential helper answers Basic and only that alias's oauth2
   * transport turns it into the Bearer the git host accepts.
   *
   * <p><b>The same key {@code AgentContainerFactory} reads</b>, because it is the same fact about
   * the same platform — one concept, one key, one expression. It was {@code
   * qits.projects.refinement-git-url} until 2026-09-18; see {@code
   * agenthost.RetiredContainerGitKeys} for why the move is a rename rather than a rewrite.
   */
  @ConfigProperty(
      name = "qits.projects.container-git-url",
      defaultValue = "http://githost.dev.internal:8080")
  String containerGitUrl;

  /** The observability MCP server a repository-scope launch attaches beside {@code repository}. */
  @ConfigProperty(
      name = "qits.projects.refinement-observability-mcp-url",
      defaultValue = "http://dev-qits-observability:8080/observability/mcp")
  String observabilityMcpUrl;

  /** The one other MCP server: this service's own {@code repository} server, the epic tools. */
  @ConfigProperty(name = "qits.projects.agent-mcp-url")
  Optional<String> agentMcpUrl;

  /**
   * The package registries a refinement's checkout builds against, blank meaning "inject nothing" —
   * the same three keys, same names in the container, and same deliberately-absent defaults as
   * qits-workspaces (the artifacts alias carries the environment name, so a default here would be a
   * guess at the deployment's topology).
   */
  @ConfigProperty(name = "qits.projects.refinement-maven-repository-url")
  Optional<String> mavenRepositoryUrl;

  @ConfigProperty(name = "qits.projects.refinement-npm-registry-url")
  Optional<String> npmRegistryUrl;

  @ConfigProperty(name = "qits.projects.refinement-npm-proxy-url")
  Optional<String> npmProxyUrl;

  /** The bearer the daemon's loopback API requires; {@link RefinementProxyRoute} presents it. */
  @ConfigProperty(
      name = "qits.projects.refinement-daemon-api-token",
      defaultValue = "qits-projects-refinement-daemon")
  String daemonApiToken;

  @Inject GitIdentity gitIdentity;

  @Inject RefinementCommissions commissions;

  static final String MAVEN_MOUNT = "/caches/m2";
  static final String PNPM_MOUNT = "/caches/pnpm";

  /** The {@code qits.managed} hint on every refinement container. Selects nothing. */
  public static final String MANAGED_LABEL_VALUE = "refinement";

  /**
   * The deterministic container name: {@code qits-ref-<projectSlug>-<epicSlug>}, truncated to
   * docker's practical bound. A {@code docker ps} hint travelling as {@code explicitName} — the
   * address is {@code owner/refinement/<rowId>}, and the provisioning arm answers a name collision
   * with a 409.
   */
  public String containerName(String projectSlug, String epicSlug) {
    String name = "qits-ref-" + projectSlug + "-" + epicSlug;
    return name.length() <= 63 ? name : name.substring(0, 63);
  }

  /** The deterministic per-refinement {@code /workspace} volume name. */
  public String volumeName(long refinementId) {
    return volumePrefix + refinementId;
  }

  /** The fresh arm — commissions a credential of the container's own. */
  public EnsureRequest forFreshContainer(
      Refinement refinement, String projectSlug, String epicSlug, String wrapperName) {
    return request(
        refinement, projectSlug, epicSlug, wrapperName, commissions.forFreshContainer(refinement));
  }

  /** The wake arm — reads the row's pair back and sends it unchanged. */
  public EnsureRequest forExistingContainer(
      Refinement refinement, String projectSlug, String epicSlug, String wrapperName) {
    return request(
        refinement,
        projectSlug,
        epicSlug,
        wrapperName,
        commissions.forExistingContainer(refinement));
  }

  private EnsureRequest request(
      Refinement refinement,
      String projectSlug,
      String epicSlug,
      String wrapperName,
      Optional<RefinementCredentials.Commissioned> credential) {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("TZ", timezone());
    // The dial-home url, dialled verbatim; the daemon parses no path out of it.
    env.put(
        "QITS_WORKSPACE_DAEMON_URL",
        "ws://" + ownHost + ":" + ownPort + RefinementPaths.CONTROL_SOCKET_PREFIX + refinement.id);
    // The path RefinementProxyRoute addresses this container at. The proxy forwards a caller's
    // path untouched, so the daemon is TOLD which leading part is its own address.
    env.put("QITS_WORKSPACE_DAEMON_API_BASE_PATH", RefinementPaths.proxyBase(refinement.id));
    env.put("QITS_WORKSPACE_DAEMON_WORKSPACE_ID", refinement.label);
    env.put("QITS_WORKSPACE_DAEMON_REPOSITORY_ID", refinement.repositoryId);
    env.put("QITS_WORKSPACE_DAEMON_BRANCH", refinement.branch);
    env.put("QITS_WORKSPACE_DAEMON_PARENT", refinement.parent);
    // Both halves of the name-addressed clone: relative submodule urls resolve only against
    // <gitBase>/<projectId>/<repoName>.
    env.put("QITS_WORKSPACE_DAEMON_PROJECT_ID", refinement.projectId);
    env.put("QITS_WORKSPACE_DAEMON_REPO_NAME", wrapperName);
    env.put("QITS_WORKSPACE_DAEMON_GIT_BASE_URL", trimSlash(containerGitUrl) + "/git");
    // A refinement runs no code: no bootstrap chain (the daemon then emits a benign ok so nothing
    // ever awaits one), and no service autostart. Deliberate, and load-bearing for the tab set.
    env.put("QITS_WORKSPACE_DAEMON_BOOTSTRAP_AUTORUN", "false");
    env.put("QITS_WORKSPACE_DAEMON_SERVICES_AUTOSTART", "false");
    env.put("QITS_WORKSPACE_DAEMON_API_TOKEN", daemonApiToken);
    // The two MCP servers a refinement launch may attach: this service's repository server (the
    // epic tools — the reason the container exists) and observability. No actions server, on
    // purpose: there is no actions surface on this route.
    env.put("QITS_REPOSITORY_MCP_URL", repositoryMcpUrl());
    env.put("QITS_OBSERVABILITY_MCP_URL", observabilityMcpUrl);
    credential.ifPresent(
        pair -> {
          env.put("QITS_COMMISSIONED_CLIENT_ID", pair.clientId());
          env.put("QITS_COMMISSIONED_CLIENT_SECRET", pair.secret());
          // The workspace image's git credential helper: Basic against the edge, exchanged for a
          // bearer with the platform audience. The helper reads these three names.
          env.put("GIT_CONFIG_GLOBAL", "/etc/qits-gitconfig");
          env.put("QITS_GIT_AUTH_HOST", authority(containerGitUrl));
          env.put("QITS_GIT_AUTH_TOKEN_URL", trimSlash(idpAuthServerUrl) + "/token");
          env.put("QITS_GIT_AUTH_AUDIENCE", PLATFORM_AUDIENCE);
          // The daemon's own dial-home bearer, presented on every control-socket handshake.
          env.put("QITS_WORKSPACE_DAEMON_AUTH_TOKEN_URL", trimSlash(idpAuthServerUrl) + "/token");
          env.put("QITS_WORKSPACE_DAEMON_AUTH_AUDIENCE", PLATFORM_AUDIENCE);
        });
    gitIdentity.envMap().forEach(env::put);

    List<SharedMount> shared = new ArrayList<>();
    if (claudeVolume != null && !claudeVolume.isBlank()) {
      shared.add(new SharedMount(claudeVolume, claudeMount));
      // The daemon's key for the shared credential home carries the qits.workspace. prefix, not
      // the daemon prefix — its own table says so.
      env.put("QITS_WORKSPACE_CLAUDE_MOUNT", claudeMount);
      env.put("CLAUDE_CONFIG_DIR", claudeMount + "/.claude");
      env.put("KIMI_CODE_HOME", claudeMount + "/.kimi-code");
    }
    if (mavenVolume != null && !mavenVolume.isBlank()) {
      shared.add(new SharedMount(mavenVolume, MAVEN_MOUNT));
      env.put("MAVEN_OPTS", "-Dmaven.repo.local=" + MAVEN_MOUNT);
    }
    if (pnpmVolume != null && !pnpmVolume.isBlank()) {
      shared.add(new SharedMount(pnpmVolume, PNPM_MOUNT));
      env.put("npm_config_store_dir", PNPM_MOUNT + "/store");
    }
    // The registries, when the deployment names them. Same env names as a workspace, including the
    // POSIX-shaped stand-in for the scoped-registry key (the image shim spells the @qits scope).
    mavenRepositoryUrl
        .filter(url -> !url.isBlank())
        .ifPresent(url -> env.put("QITS_MAVEN_REPOSITORY_URL", url));
    npmProxyUrl.filter(url -> !url.isBlank()).ifPresent(url -> env.put("npm_config_registry", url));
    npmRegistryUrl
        .filter(url -> !url.isBlank())
        .ifPresent(url -> env.put("QITS_WORKSPACE_NPM_REGISTRY_URL", url));

    Map<String, String> labels = new LinkedHashMap<>();
    labels.put("qits.managed", MANAGED_LABEL_VALUE);
    labels.put("qits.project", refinement.projectId);
    labels.put("qits.epic", refinement.epicId);

    String memory = memoryLimit.filter(value -> !value.isBlank()).orElse(null);
    Spec spec =
        new Spec(
            image(),
            // No entrypoint and no command: the container runs qits-workspace-daemon via the image
            // ENTRYPOINT, and a container that cannot run it must fail rather than linger.
            null,
            null,
            env,
            labels,
            network,
            null,
            List.of("host.docker.internal:host-gateway"),
            // The checkout, on a named volume: a recreate reattaches it, the daemon skips its
            // self-clone on a populated /workspace, and uncommitted work survives an image bump.
            List.of(new VolumeMount(volumeName(refinement.id), "/workspace")),
            shared,
            // No docker socket, ever: a refinement runs no code and publishes nothing.
            false,
            new Security(
                false,
                false,
                memory,
                memory,
                pids(),
                cpus.filter(v -> !v.isBlank()).orElse(null),
                oomScoreAdj),
            PullPolicy.MISSING,
            containerName(projectSlug, epicSlug),
            Long.toString(hostUid()),
            // tini at PID 1, so a long-lived container spawning agents collects no zombies.
            true);
    // EXPLICIT lifetime + ifChanged: stoppable only by its verbs, its volume is what it comes back
    // to, and an image bump is applied at wake — the workspaces contract, carried over.
    return new EnsureRequest(spec, Policy.explicitLifetime(), Recreate.ifChanged);
  }

  private String repositoryMcpUrl() {
    return agentMcpUrl
        .filter(url -> !url.isBlank())
        .orElseGet(() -> "http://" + ownHost + ":" + ownPort + "/projects/mcp");
  }

  private Long pids() {
    String value = pidsLimit.filter(text -> !text.isBlank()).orElse(null);
    if (value == null) {
      return null;
    }
    try {
      return Long.valueOf(value.trim());
    } catch (NumberFormatException e) {
      LOG.warnf(
          "qits.projects.refinement-pids-limit is not a number ('%s'); no pids cap is set", value);
      return null;
    }
  }

  private String timezone() {
    return timezone.filter(zone -> !zone.isBlank()).orElseGet(() -> ZoneId.systemDefault().getId());
  }

  private static String trimSlash(String url) {
    return url.replaceAll("/+$", "");
  }

  /** The {@code host[:port]} half of a url — what the git credential helper matches on. */
  private static String authority(String url) {
    URI uri = URI.create(url);
    return uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
  }

  private long hostUid() {
    try {
      Object uid = Files.getAttribute(Path.of(System.getProperty("user.home")), "unix:uid");
      return ((Number) uid).longValue();
    } catch (Exception e) {
      return 1000L;
    }
  }
}

package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.error.CodedRefusalException;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerProtocol;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Locale;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Every address a front-desk runner is handed, composed from the platform's public domain alone
 * (qits-767) — qits-workspaces-service's {@code WorkspaceRunnerAddresses} in the projects spelling.
 * A runner lives outside the swarm and reaches this platform only through its edge, so every name
 * here is a public one: {@code https://projects.qits.<d>} for the install script and the register
 * door, {@code wss://projects.qits.<d>/projects/runners/socket} for its socket, {@code
 * https://idp.qits.<d>/idp/token} for its bearer, {@code registry.qits.<d>} for every image it
 * pulls. The platform's own project, {@code qits}, is the second label of each: the edge names a
 * platform application {@code <app>.qits.<d>}.
 *
 * <p><b>A domain without a dot is no domain</b>: the suites' and a bare local install's {@code
 * localhost} names nothing a runner on another host could reach. Every method then refuses with 503
 * {@link #RUNNER_PLANE_UNCONFIGURED}, which is what the create door, the register door and {@code
 * install.sh} answer before anything is minted.
 *
 * <p>The domain is {@code qits.domain} ({@code QITS_DOMAIN}), the key {@code PublicCloneUrls} and
 * the agent container factory already read.
 */
@ApplicationScoped
public class DeskRunnerAddresses {

  /** The refusal of every runner door on a deployment with no public domain. */
  public static final String RUNNER_PLANE_UNCONFIGURED = "RUNNER_PLANE_UNCONFIGURED";

  /** The audience every token this platform mints carries, and the one a runner asks for. */
  public static final String AUDIENCE = "qits-platform";

  /** The platform's own project: the second label of every platform application's host. */
  static final String PLATFORM_PROJECT = "qits";

  static final String PROJECTS_HOST = "projects";

  static final String IDP_HOST = "idp";

  static final String REGISTRY_HOST = "registry";

  @ConfigProperty(name = "qits.domain")
  Optional<String> domain;

  /** {@code https://projects.qits.<d>}: the install script's and the register door's origin. */
  public String serviceBase() {
    return origin(PROJECTS_HOST);
  }

  /** {@code wss://projects.qits.<d>/projects/runners/socket}. */
  public String socketUrl() {
    return "wss://" + host(PROJECTS_HOST) + DeskRunnerProtocol.SOCKET_PATH;
  }

  /** {@code https://idp.qits.<d>/idp/token}, where a runner mints its bearer. */
  public String tokenUrl() {
    return origin(IDP_HOST) + "/idp/token";
  }

  /** The audience a runner asks its bearer for. */
  public String audience() {
    return AUDIENCE;
  }

  /** {@code registry.qits.<d>}: the host every runner image is pulled from. */
  public String registryHost() {
    return host(REGISTRY_HOST);
  }

  /** {@code registry.qits.<d>/qits/qits-projects-desk-runner:<version>}: an upgrade's image. */
  public String runnerImage(String version) {
    return registryHost() + "/" + DeskRunnerProtocol.IMAGE_REPOSITORY + ":" + version;
  }

  /** Whether a public domain is configured, so every method above answers rather than refusing. */
  public boolean configured() {
    return publicDomain().isPresent();
  }

  /** 503 {@link #RUNNER_PLANE_UNCONFIGURED} unless a public domain is configured. */
  public void requireConfigured() {
    host(PROJECTS_HOST);
  }

  private String origin(String app) {
    return "https://" + host(app);
  }

  private String host(String app) {
    return app
        + "."
        + PLATFORM_PROJECT
        + "."
        + publicDomain()
            .orElseThrow(
                () ->
                    new CodedRefusalException(
                        503,
                        RUNNER_PLANE_UNCONFIGURED,
                        "qits-projects knows no public domain (QITS_DOMAIN is '"
                            + set(domain).orElse("")
                            + "'), so it has no address to give a front-desk runner; set"
                            + " QITS_DOMAIN to the platform's dotted public domain"));
  }

  /** The domain, lower-cased and without outer dots, when it has a dot in it; else empty. */
  Optional<String> publicDomain() {
    return set(domain)
        .map(value -> value.toLowerCase(Locale.ROOT).replaceAll("^\\.+|\\.+$", ""))
        .filter(value -> value.indexOf('.') > 0);
  }

  private static Optional<String> set(Optional<String> value) {
    return value == null ? Optional.empty() : value.map(String::trim).filter(v -> !v.isEmpty());
  }
}

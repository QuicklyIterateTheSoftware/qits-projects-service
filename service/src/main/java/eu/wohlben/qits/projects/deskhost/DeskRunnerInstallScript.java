package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.entity.DeskRunner;
import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerBinary;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerProtocol;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.install.InstallScript;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * The install line a front-desk runner is created with and the generic {@code install.sh} it pipes
 * into {@code sh}, both rendered by qits-runner-toolkit's {@link InstallScript} from the toolkit's
 * own template (qits-767) — qits-workspaces-service's {@code WorkspaceRunnerInstallScript} in the
 * desk spelling. <b>There is no copy of the template here</b>: what a runner's host runs is the
 * template the runner's own release was built with, filled with this service's names.
 *
 * <p><b>The identity is the runner's, built the runner's way.</b> {@link #IDENTITY} is constructed
 * from {@link DeskRunnerProtocol}'s roots and {@link DeskRunnerBinary#VERSION}, in {@link
 * RunnerIdentity}'s order, exactly as the runner's own identity is — so the environment variables
 * the line sets, the container and volume names the script creates and the image it starts are the
 * ones the runner reads, and cannot drift.
 *
 * <p>Both renderings carry no secret of the script's own: the line carries the registration token
 * it was minted for (that is its purpose, and it is shown once), the script carries none.
 */
@ApplicationScoped
public class DeskRunnerInstallScript {

  /** Where {@code install.sh} is served, under this service's public origin. */
  public static final String PATH = "/projects/api/runners/install.sh";

  /** How the script names the place an operator got the line from. */
  static final InstallScript.InstallPage PAGE =
      new InstallScript.InstallPage("the Projects UI's Runners page", List.of());

  /** The front-desk runner's identity at the pinned version — the runner's own, see the javadoc. */
  public static final RunnerIdentity IDENTITY =
      new RunnerIdentity(
          DeskRunnerProtocol.KIND,
          DeskRunnerProtocol.ENV_PREFIX,
          DeskRunnerProtocol.LABEL_ROOT,
          DeskRunnerProtocol.NAME_PREFIX,
          DeskRunnerProtocol.IMAGE_REPOSITORY,
          DeskRunnerProtocol.STATE_DIR,
          DeskRunnerProtocol.REGISTER_PATH,
          DeskRunnerProtocol.SERVICE_NAME,
          DeskRunnerBinary.VERSION,
          DeskRunnerProtocol.CAPABILITY_VERSION,
          DeskRunnerProtocol.VOCABULARY,
          DeskRunnerProtocol.WORK_LABEL_SUFFIX);

  @Inject DeskRunnerAddresses addresses;

  /**
   * 503 unless both renderings would succeed now: a public domain to address the runner by ({@code
   * RUNNER_PLANE_UNCONFIGURED}) and a script the template can be filled into. Asked before anything
   * is minted, so a misconfiguration costs no token.
   */
  public void requireRenderable() {
    addresses.requireConfigured();
    script();
  }

  /** The generic script: this deployment's registry and the pinned runner image, and no secret. */
  public String script() {
    try {
      return InstallScript.script(IDENTITY, PAGE, addresses.registryHost());
    } catch (IllegalStateException unrenderable) {
      throw new DomainException(503, unrenderable.getMessage());
    }
  }

  /**
   * The one line that installs {@code runner}, carrying {@code registrationToken}: it fetches {@link
   * #PATH} with the token and pipes the script into {@code sudo env … sh} with the runner's URL, id,
   * token and slots.
   */
  public String line(DeskRunner runner, String registrationToken) {
    try {
      return InstallScript.line(
          IDENTITY,
          addresses.serviceBase(),
          PATH,
          runner.id.toString(),
          registrationToken,
          runner.slots);
    } catch (IllegalStateException unrenderable) {
      throw new DomainException(503, unrenderable.getMessage());
    }
  }

  /** {@link InstallScript#requireCarriable}: a token qits-idp minted that the line cannot carry. */
  public static void requireCarriable(String registrationToken) {
    InstallScript.requireCarriable(registrationToken);
  }
}

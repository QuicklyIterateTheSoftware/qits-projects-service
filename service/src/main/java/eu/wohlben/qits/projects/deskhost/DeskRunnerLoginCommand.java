package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.error.DomainException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The one command an operator runs on a front-desk runner's node to log its agent home in
 * (qits-767), copied from qits-workspaces-service's {@code RunnerLoginCommand}. The platform never
 * sees or moves a login secret: the operator runs the CLI's own sign-in against the node's {@code
 * dot-claude} volume, with the project-agent image a desk runs, and every desk on that runner shares
 * the result.
 *
 * <pre>
 * docker run --rm -it --user &lt;uid&gt; --entrypoint claude \
 *   -v &lt;dot-claude volume&gt;:&lt;mount&gt; -e HOME=&lt;mount&gt; \
 *   -e CLAUDE_CONFIG_DIR=&lt;mount&gt;/.claude \
 *   registry.qits.&lt;d&gt;/qits/project-agent:&lt;pin&gt;
 * </pre>
 *
 * (one line).
 *
 * <ul>
 *   <li><b>The user and the mount are a project agent container's</b>, read off the injected {@link
 *       FrontDeskSpecs} — its host uid ({@link FrontDeskSpecs#deskUser()}) and its
 *       {@code qits.projects.claude-mount} ({@link FrontDeskSpecs#claudeMount()}), under
 *       which it sets {@code CLAUDE_CONFIG_DIR=<mount>/.claude} — so a login lands where every desk
 *       reads it and is readable by the user it runs as. Two copies of one value is what let the
 *       workspaces command drift (qits-945); there is one.
 *   <li><b>{@code --entrypoint claude}</b>, because the image's ENTRYPOINT is the project daemon,
 *       not a shell.
 *   <li><b>The volume is the runner's own</b>: {@code nodeVolume(runnerId, "dot-claude")} of the
 *       runner identity ({@link DeskRunnerInstallScript#IDENTITY}), the name the runner creates. It
 *       is spliced into a line a person pastes into a shell only when it is a plain docker volume
 *       name ({@link #splicable}).
 *   <li><b>The image is the pinned project-agent image's public reference</b> ({@link
 *       DeskRunnerAddresses#projectAgentImage} at {@link DeskRunnerPins#projectAgentVersion()}). A
 *       deployment with no public domain has no image to name, and answers null.
 * </ul>
 *
 * <p>When the command is shown at all — once a {@code loginState} arrived on the runner's current
 * connection, the proof the image is on the node — is {@link DeskRunnerRegistry#loginCommand}'s.
 */
@ApplicationScoped
public class DeskRunnerLoginCommand {

  /** The purpose of the runner's agent home volume on its node. */
  public static final String DOT_CLAUDE = "dot-claude";

  /** A docker volume name: what may be spliced into a shell line. */
  private static final Pattern VOLUME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,254}");

  @Inject DeskRunnerAddresses addresses;

  @Inject DeskRunnerPins pins;

  @Inject FrontDeskSpecs specs;

  /** The runner's agent home volume on its node, as the runner names it. */
  public static String dotClaudeVolume(UUID runnerId) {
    return DeskRunnerInstallScript.IDENTITY.nodeVolume(runnerId.toString(), DOT_CLAUDE);
  }

  /**
   * The Claude login command for {@code runnerId}'s node, or null when its volume name is not
   * shell-safe or the deployment has no public domain.
   */
  public String claude(UUID runnerId) {
    String volume = runnerId == null ? null : dotClaudeVolume(runnerId);
    if (!splicable(volume)) {
      return null;
    }
    String image;
    try {
      image = addresses.projectAgentImage(pins.projectAgentVersion());
    } catch (DomainException unconfigured) {
      return null;
    }
    return command(
        volume, image, specs.deskUser(), specs.claudeMount());
  }

  /** Whether {@code volume} is a plain docker volume name, safe to splice into a shell line. */
  static boolean splicable(String volume) {
    return volume != null && VOLUME.matcher(volume).matches();
  }

  /** The command's one shape; every value is the caller's, so this method carries no literal. */
  static String command(String volume, String image, String user, String home) {
    return "docker run --rm -it --user "
        + user
        + " --entrypoint claude -v "
        + volume
        + ":"
        + home
        + " -e HOME="
        + home
        + " -e CLAUDE_CONFIG_DIR="
        + home
        + "/.claude "
        + image;
  }
}

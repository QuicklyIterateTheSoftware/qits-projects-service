package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.agenthost.AgentContainerFactory;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerBinary;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerProtocol;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Which versions a front-desk runner is held to (qits-767) — qits-workspaces-service's {@code
 * WorkspaceRunnerPins}, plus the image a desk runs.
 *
 * <ul>
 *   <li>{@link #version()}: the {@code qits-projects-desk-runner} version every {@code hello} is
 *       compared with (a runner of any other is sent {@code upgrade}). It is {@link
 *       DeskRunnerBinary#VERSION} — the version of the protocol jar the root pom pins ({@code
 *       qits.projects-desk-runner-protocol.version}), by construction the tag of the image the same
 *       release published. It moves by a gated bump of that property and nowhere else.
 *   <li>{@link #projectAgentVersion()}: the project-agent image's tag, the same value a project
 *       agent container is started from today ({@link AgentContainerFactory#imageVersion()}: {@code
 *       ProjectAgentImage.VERSION}, or the shipped-unset override). A runner's health check runs
 *       that image, and its login command starts it, under {@link
 *       DeskRunnerAddresses#projectAgentImage} — the public reference a node can pull.
 * </ul>
 */
@ApplicationScoped
public class DeskRunnerPins {

  /** The runner image's repository in the platform registry. */
  public static final String IMAGE_REPOSITORY = DeskRunnerProtocol.IMAGE_REPOSITORY;

  @Inject AgentContainerFactory agentContainers;

  /** The pinned runner version; never blank (the jar refuses an unfiltered one at class load). */
  public String version() {
    return DeskRunnerBinary.VERSION;
  }

  /** The pinned project-agent image tag. */
  public String projectAgentVersion() {
    return agentContainers.imageVersion();
  }
}

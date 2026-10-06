package eu.wohlben.qits.projects.workspacehost;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * An assembly with no workspaces context at all: both workspaces ports — {@code
 * WorkspaceAgentDispatch} and {@code ReleasedBranchWorkspaces} — with every implementation of each
 * excluded, so each {@code Instance<T>} is genuinely unresolvable.
 *
 * <p>The absence is made by config rather than by a missing class, {@code quarkus.arc.exclude-types}
 * naming the {@code @DefaultBean} HTTP adapter and the recording double of each port. Every other
 * suite here has both on the classpath, so this is the only place either absence can be shown.
 *
 * <p><b>One profile for both ports, not one per port</b> (qits-965, the test-profile budget rule).
 * {@code EntityDispatchWithNoWorkspacesTest} and {@code ReleaseWithNoWorkspaceResolutionTest} each
 * held a profile excluding only their own port, and a profile is keyed by its class: two of them
 * were two whole app boots, each leaving ~125 MB of metaspace retained for the rest of the run
 * inside a 4g step container. Neither class touches the other's port, and a deployment that lacks
 * the workspaces context lacks both, so the union is the more honest assembly anyway.
 */
public class NoWorkspacesContextProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of(
        "quarkus.arc.exclude-types",
        "eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentDispatch,"
            + "eu.wohlben.qits.projects.workspacehost.HttpWorkspaceAgentDispatch,"
            + "eu.wohlben.qits.projects.testsupport.RecordingReleasedBranchWorkspaces,"
            + "eu.wohlben.qits.projects.workspacehost.HttpReleasedBranchWorkspaces");
  }
}

package eu.wohlben.qits.projects.testsupport;

import eu.wohlben.qits.projects.control.WorkspaceAgentLookup;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A TEST-SCOPE implementation of the {@link WorkspaceAgentLookup} port: answers the work item a test
 * staged for an agent instead of dialling qits-workspaces, and records every agent it was asked
 * about.
 *
 * <p><b>{@code @Alternative @Priority}</b>, by {@link RecordingWorkspaceAgentTurns}' rule: the
 * method returns a result and there is a shipped {@code @DefaultBean} adapter to displace
 * ({@code workspacehost/HttpWorkspaceAgentLookup}).
 *
 * <p>The resting answer is empty — "could not say" — which is what the far side would answer for an
 * agent nobody staged. {@link #willThrow} scripts the one thing the port promises never to do.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class RecordingWorkspaceAgentLookup implements WorkspaceAgentLookup {

  private final Map<String, String> works = new HashMap<>();

  private final List<String> asked = new ArrayList<>();

  private RuntimeException failure;

  @Override
  public synchronized Optional<String> workOf(String agentId) {
    asked.add(agentId);
    if (failure != null) {
      throw failure;
    }
    return Optional.ofNullable(works.get(agentId));
  }

  /** {@code agentId} works on the row {@code workId}. */
  @Override
  public synchronized Optional<List<ActiveAgent>> active() {
    if (failure != null) {
      return Optional.empty();
    }
    return Optional.of(
        works.entrySet().stream()
            .map(entry -> new ActiveAgent(entry.getKey(), entry.getValue()))
            .toList());
  }

  public synchronized void stage(String agentId, String workId) {
    works.put(agentId, workId);
  }

  /** A port bug: an implementation that throws although its contract forbids it. */
  public synchronized void willThrow(RuntimeException exception) {
    this.failure = exception;
  }

  public synchronized List<String> asked() {
    return List.copyOf(asked);
  }

  /** Forgets everything; one application, and so one instance, serves a whole test class. */
  public synchronized void reset() {
    works.clear();
    asked.clear();
    failure = null;
  }
}

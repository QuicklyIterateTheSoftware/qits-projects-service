package eu.wohlben.qits.projects.testsupport;

import eu.wohlben.qits.projects.control.WorkspaceAgentBlocks;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

/**
 * A TEST-SCOPE implementation of the {@link WorkspaceAgentBlocks} port that records which workspace
 * was told its work is blocked or unblocked instead of dialling qits-workspaces (qits-614).
 *
 * <p>No {@code @Alternative}: the port is {@code void}, so there is no answer for a second
 * implementation to make a caller report — the rule this package states for {@link
 * RecordingWorkspaceLifecycle}. The shipped adapter ({@code workspacehost/HttpWorkspaceAgentBlocks})
 * is a {@code @DefaultBean}, which any other bean displaces.
 *
 * <p>{@link #willThrow} scripts the one thing the port promises never to do, for {@link
 * RecordingWorkspaceAgentTurns}' reason: the signal follows a block or a transition already
 * recorded, and the only way to assert a port bug cannot undo either is to have one break its own
 * contract on purpose.
 */
@ApplicationScoped
public class RecordingWorkspaceAgentBlocks implements WorkspaceAgentBlocks {

  /** One signal, exactly as the caller sent it. */
  public record Told(String repositoryId, String branch, boolean blocked) {}

  private final List<Told> calls = new ArrayList<>();

  private RuntimeException failure;

  @Override
  public synchronized void blocked(String repositoryId, String branch, boolean blocked) {
    calls.add(new Told(repositoryId, branch, blocked));
    if (failure != null) {
      throw failure;
    }
  }

  public synchronized List<Told> calls() {
    return List.copyOf(calls);
  }

  /** Every signal sent to {@code branch}, oldest first. */
  public synchronized List<Told> callsOn(String branch) {
    return calls.stream().filter(told -> told.branch().equals(branch)).toList();
  }

  /** A port bug: an implementation that throws although its contract forbids it. */
  public synchronized void willThrow(RuntimeException exception) {
    this.failure = exception;
  }

  /** Forgets everything recorded. {@code @QuarkusTest} shares one instance across a class. */
  public synchronized void reset() {
    calls.clear();
    failure = null;
  }
}

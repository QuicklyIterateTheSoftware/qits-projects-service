package eu.wohlben.qits.projects.testsupport;

import eu.wohlben.qits.projects.control.WorkspaceAgentTurns;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.ArrayList;
import java.util.List;

/**
 * A TEST-SCOPE implementation of the {@link WorkspaceAgentTurns} port that records what was said to
 * the agent on a branch instead of dialling qits-workspaces, and answers whatever the test
 * scripted.
 *
 * <p><b>{@code @Alternative @Priority}</b>, by the same rule as {@link
 * RecordingWorkspaceAgentDispatch} beside it: the method returns a result, so two implementations
 * would leave the caller reporting whichever the container handed it first. There is a shipped
 * adapter to displace ({@code workspacehost/HttpWorkspaceAgentTurns}, a {@code @DefaultBean}), so
 * the annotations are load-bearing rather than kept as form.
 *
 * <p>{@link #willThrow} scripts the one thing the port promises never to do. That is not an idle
 * case: the phase hand-off runs after a transition that is already recorded, so what a port bug
 * must <em>not</em> do is undo it — and the only way to assert that is to have something break its
 * own contract on purpose.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class RecordingWorkspaceAgentTurns implements WorkspaceAgentTurns {

  /** One turn, exactly as the caller asked for it. */
  public record Spoken(String repositoryId, String branch, String text) {}

  private final List<Spoken> calls = new ArrayList<>();

  /**
   * <b>The default answer is {@code NO_WORKSPACE}</b>, and that is the honest resting state rather
   * than a convenience: in every suite here a ticket is created and walked through its statuses
   * with no workspace ever stood up on its branch, which is exactly what the far side would say. A
   * fake that claimed delivery by default would put a "started the next phase" comment on every
   * thread in this repository's ticket suites and make each of them assert a workspace that does
   * not exist. A test about delivery scripts it with {@link #willAnswer}.
   */
  private Turn scripted = new Turn(Outcome.NO_WORKSPACE, "");

  private RuntimeException failure;

  @Override
  public synchronized Turn deliver(String repositoryId, String branch, String text) {
    calls.add(new Spoken(repositoryId, branch, text));
    if (failure != null) {
      throw failure;
    }
    return scripted;
  }

  public synchronized List<Spoken> calls() {
    return List.copyOf(calls);
  }

  public synchronized Spoken lastCall() {
    if (calls.isEmpty()) {
      throw new AssertionError("nothing was said to any workspace");
    }
    return calls.get(calls.size() - 1);
  }

  /** What the next turn answers. */
  public synchronized void willAnswer(Outcome outcome, String detail) {
    this.scripted = new Turn(outcome, detail);
    this.failure = null;
  }

  /** A port bug: an implementation that throws although its contract forbids it. */
  public synchronized void willThrow(RuntimeException exception) {
    this.failure = exception;
  }

  /**
   * Forgets everything recorded and restores the default answer. {@code @QuarkusTest} shares one
   * application — and so one instance of this bean — across a class.
   */
  public synchronized void reset() {
    calls.clear();
    scripted = new Turn(Outcome.NO_WORKSPACE, "");
    failure = null;
  }
}

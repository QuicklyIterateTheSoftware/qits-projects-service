package eu.wohlben.qits.projects.testsupport;

import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import eu.wohlben.qits.projects.error.DomainException;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.ArrayList;
import java.util.List;

/**
 * A TEST-SCOPE implementation of the {@link WorkspaceAgentDispatch} port that records what a ticket
 * asked for instead of dialling qits-workspaces, and answers whatever the test scripted.
 *
 * <p><b>{@code @Alternative @Priority}, unlike {@link RecordingReleasedBranchWorkspaces} beside
 * it</b>, and the rule is the one the module's test notes state: a fake for a port whose method
 * <em>returns a result</em> carries them, because two implementations of such a port leave the
 * caller reporting whichever the container hands it first. This port has a shipped adapter
 * ({@code workspacehost/HttpWorkspaceAgentDispatch}, a {@code @DefaultBean}), so there is something
 * real to displace here and the annotations are load-bearing rather than kept as form.
 *
 * <p>{@link #failWith} scripts the other half of the port's contract — a dispatch that could not be
 * made is a {@link DomainException} the door surfaces, never a hopeful empty — so the door's error
 * path is proved rather than described. Nothing in {@code src/main} references this class.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class RecordingWorkspaceAgentDispatch implements WorkspaceAgentDispatch {

  /** One dispatch, exactly as the door asked for it. */
  public record Dispatched(
      String repositoryId,
      String branch,
      boolean branchTree,
      String preamble,
      String instruction) {}

  private final List<Dispatched> calls = new ArrayList<>();

  private Dispatch scripted = new Dispatch(41L, true, "SCHEDULED");

  private DomainException failure;

  @Override
  public synchronized Dispatch dispatchAgent(
      String repositoryId,
      String branch,
      boolean branchTree,
      String preamble,
      String instruction) {
    calls.add(new Dispatched(repositoryId, branch, branchTree, preamble, instruction));
    if (failure != null) {
      throw failure;
    }
    return scripted;
  }

  public synchronized List<Dispatched> calls() {
    return List.copyOf(calls);
  }

  public synchronized Dispatched lastCall() {
    if (calls.isEmpty()) {
      throw new AssertionError("no dispatch was asked for");
    }
    return calls.get(calls.size() - 1);
  }

  /** What the next dispatch answers. */
  public synchronized void willAnswer(Dispatch dispatch) {
    this.scripted = dispatch;
    this.failure = null;
  }

  /** The far side refused, was unreachable, or answered nothing readable. */
  public synchronized void willFailWith(DomainException exception) {
    this.failure = exception;
  }

  /**
   * Forgets everything recorded and restores the default answer. {@code @QuarkusTest} shares one
   * application — and so one instance of this bean — across a class.
   */
  public synchronized void reset() {
    calls.clear();
    scripted = new Dispatch(41L, true, "SCHEDULED");
    failure = null;
  }
}

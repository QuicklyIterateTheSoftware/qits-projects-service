package eu.wohlben.qits.projects.testsupport;

import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import eu.wohlben.qits.projects.error.DomainException;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.ArrayList;
import java.util.Collection;
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
      List<String> gitRefs,
      boolean branchTree,
      Subject subject,
      String instruction) {}

  private final List<Dispatched> calls = new ArrayList<>();

  private Dispatch scripted = new Dispatch(41L, true, "SCHEDULED");

  private DomainException failure;

  @Override
  public synchronized Dispatch dispatchAgent(
      String repositoryId,
      String branch,
      List<String> gitRefs,
      boolean branchTree,
      Subject subject,
      String instruction) {
    calls.add(new Dispatched(repositoryId, branch, gitRefs, branchTree, subject, instruction));
    if (failure != null) {
      throw failure;
    }
    return scripted;
  }

  /** One reference lookup, exactly as the read door asked for it. */
  public record Looked(List<String> ticketIds, List<String> epicIds) {}

  private final List<Looked> lookups = new ArrayList<>();

  private List<Reference> references = List.of();

  /**
   * The read half. It never throws, which is the port's contract and not this fake being kind: a
   * lookup that failed would take down the listing it decorates, so an implementation warns and
   * answers empty. A test that wants that case scripts {@link #willReference} with nothing.
   */
  @Override
  public synchronized List<Reference> workspacesReferencing(
      Collection<String> ticketIds, Collection<String> epicIds) {
    lookups.add(new Looked(List.copyOf(ticketIds), List.copyOf(epicIds)));
    return references;
  }

  public synchronized List<Looked> lookups() {
    return List.copyOf(lookups);
  }

  /** What the next lookup answers, whatever it is asked about. */
  public synchronized void willReference(Reference... found) {
    this.references = List.of(found);
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
    lookups.clear();
    references = List.of();
    scripted = new Dispatch(41L, true, "SCHEDULED");
    failure = null;
  }
}

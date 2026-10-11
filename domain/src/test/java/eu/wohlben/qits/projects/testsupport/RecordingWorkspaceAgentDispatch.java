package eu.wohlben.qits.projects.testsupport;

import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import eu.wohlben.qits.projects.error.DomainException;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.time.Instant;
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
      String repositoryId, String wrapperBranch, Subject subject, String instruction) {}

  private final List<Dispatched> calls = new ArrayList<>();

  /** The agent identity the default answer names (qits-887), so an assignee is predictable. */
  public static final String DEFAULT_AGENT = "ws-agent-41";

  /** The agent id the default answer names. */
  public static final String DEFAULT_AGENT_ID = "agent-41";

  private Dispatch scripted = defaultAnswer();

  private DomainException failure;

  private static Dispatch defaultAnswer() {
    return new Dispatch(DEFAULT_AGENT_ID, 41L, null, true, "SCHEDULED", DEFAULT_AGENT);
  }

  @Override
  public synchronized Dispatch dispatchAgent(
      String repositoryId, String wrapperBranch, Subject subject, String instruction) {
    calls.add(new Dispatched(repositoryId, wrapperBranch, subject, instruction));
    if (failure != null) {
      throw failure;
    }
    return scripted;
  }

  /** One agent lookup, as a reader asked it. */
  public record Looked(List<String> workIds) {}

  private final List<Looked> lookups = new ArrayList<>();

  private List<Agent> agents = List.of();

  @Override
  public synchronized List<Agent> agentsFor(Collection<String> workIds) {
    lookups.add(new Looked(List.copyOf(workIds)));
    return agents;
  }

  /** Every lookup, oldest first. */
  public synchronized List<Looked> lookups() {
    return List.copyOf(lookups);
  }

  /** Answer every later lookup with these agents, whatever it asked about. */
  public synchronized void willFind(Agent... found) {
    this.agents = List.of(found);
  }

  /** An ACTIVE agent on {@code wrapperBranch}, with the branches it pushed. */
  public static Agent active(
      String agentId,
      long rowId,
      String repositoryId,
      String workId,
      String wrapperBranch,
      Branch... pushed) {
    return new Agent(
        agentId,
        rowId,
        repositoryId,
        workId,
        null,
        wrapperBranch,
        List.of(pushed),
        Agent.ACTIVE,
        "RUNNING",
        null,
        null);
  }

  /** An agent in {@code state} — OBSOLETE or REMOVED — removed at {@code removedAt}. */
  public static Agent inState(
      String agentId,
      long rowId,
      String repositoryId,
      String workId,
      String wrapperBranch,
      String state,
      Instant removedAt) {
    return new Agent(
        agentId,
        rowId,
        repositoryId,
        workId,
        null,
        wrapperBranch,
        List.of(),
        state,
        null,
        null,
        removedAt);
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

  public synchronized void willAnswer(Dispatch dispatch) {
    this.scripted = dispatch;
    this.failure = null;
  }

  public synchronized void willFailWith(DomainException exception) {
    this.failure = exception;
  }

  public synchronized void reset() {
    calls.clear();
    lookups.clear();
    agents = List.of();
    scripted = defaultAnswer();
    failure = null;
  }
}

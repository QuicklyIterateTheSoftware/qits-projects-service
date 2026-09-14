package eu.wohlben.qits.projects.control;

/**
 * Something said to the agent in the workspace standing on a branch — one turn, delivered after the
 * fact and waited on by nobody.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>A ticket's phases run in <b>one</b> workspace: refine, implement and verify are separated by a
 * context reset and not by a container. So the moment a phase ends is a moment this service knows
 * something the workspace does not — the transition was recorded, the ticket is REFINED now, the
 * next phase is implement — and there is no channel that says so. Without one, the only way to start
 * the next phase is to press "Assign agent" again, which re-enters the door that stands a workspace
 * up; with one, the agent already there is told, in the session it is already in. This is that
 * channel, and it carries prose rather than a status because the far side launches a model and not a
 * state machine.
 *
 * <h2>Nobody is waiting on it, and that is the contract</h2>
 *
 * <p><b>An implementation must never throw.</b> This is told <em>after</em> a transition that has
 * already been recorded — the row is written, the thread is stamped, the caller's request is
 * already a success — so a workspace that could not be spoken to must never turn a transition that
 * happened into a 500. Absent implementation, no address, no machine credential, an unreachable or
 * refusing qits-workspaces and an answer that will not parse are <b>one behaviour</b>: one WARN
 * naming the branch and the reason, and a normal return. That is {@link ReleasedBranchWorkspaces}'
 * contract exactly, and it is the one this port takes — not {@link WorkspaceAgentDispatch}', whose
 * whole point is that a person pressed a button and a failure has to reach them. Two verbs against
 * the same service, opposite failure contracts, and the failure contract is what decides which
 * shape a seam has.
 *
 * <p>It is also cheap to lose. A turn nobody delivered costs the phase its automatic start: the
 * ticket is in its new status, the workspace is still standing on the branch, and pressing "Assign
 * agent" resumes it at exactly that status. Nothing is stranded and nothing has to be undone, which
 * is what makes never-throwing the right trade rather than a shrug.
 *
 * <h2>The answer is ADVISORY</h2>
 *
 * <p>Unlike {@link ReleasedBranchWorkspaces#branchReleased}, which answers nothing, this one returns
 * {@link Outcome} — because there is a reader for it: the caller stamps the ticket's thread, and
 * "the agent on this branch was told" and "no workspace stands on this branch" are different
 * sentences for a person reading it later. <b>That return may not become a decision.</b> Nothing
 * retries on it, nothing fails on it, and {@link Outcome#COULD_NOT} is not an error to propagate —
 * it is the WARN, said again where a comment can carry it. A caller that branched on it would have
 * re-invented the failure contract this port exists to keep flat.
 *
 * <h2>Absent is a supported configuration</h2>
 *
 * <p>Injected as {@code Instance<T>} like every port here. With no implementation present nothing is
 * asked and every transition behaves exactly as it did before this port existed: the status moves,
 * the thread is stamped, and the next phase starts when somebody presses the button. That is a loss
 * of hand-off, never of transition.
 *
 * <h2>Why this is not a verb on {@link WorkspaceAgentDispatch}</h2>
 *
 * <p>That port stands a workspace <em>up</em> and its failure is a 502 or a 503 on a door. This one
 * speaks to a workspace already standing and cannot fail anything at all. The package rule in
 * AGENTS.md is the same one the dispatch itself was split out under — <b>two verbs with opposite
 * failure contracts do not share a class</b> — and the address being configured twice is not a
 * reason to merge them. The far side agrees: it never creates a workspace, so a branch with none is
 * the ordinary answer here and is the thing the dispatch door exists to fix.
 */
public interface WorkspaceAgentTurns {

  /**
   * What happened to the turn, as far as the far side said. Four values and no fifth: a caller has
   * one sentence to write, and a vocabulary it can exhaust is what keeps that sentence honest.
   */
  enum Outcome {
    /** An agent was already running in the workspace and the text was handed to it. */
    DELIVERED,
    /** No agent was running, so one was launched to take the turn. */
    LAUNCHED,
    /**
     * No workspace stands on that branch. <b>Not a failure</b> — the far side never creates one, so
     * this is the honest answer for a ticket nobody has dispatched an agent onto yet.
     */
    NO_WORKSPACE,
    /**
     * The turn was not delivered and this side could not find out whether it ever could be: no
     * address, no credential, an unreachable or refusing far side, an unreadable answer. The WARN
     * has already been logged by the time a caller sees this.
     */
    COULD_NOT
  }

  /**
   * The answer, narrowed to what a caller can say on a thread.
   *
   * @param outcome what happened; see {@link Outcome}
   * @param detail the far side's own sentence when it gave one, or this side's reason when it did
   *     not. Never null, possibly blank — it is prose for a log line or a comment and nothing parses
   *     it
   */
  record Turn(Outcome outcome, String detail) {

    public Turn {
      detail = detail == null ? "" : detail;
    }

    /** Whether the text reached an agent, however it got there. */
    public boolean spoken() {
      return outcome == Outcome.DELIVERED || outcome == Outcome.LAUNCHED;
    }
  }

  /**
   * Say {@code text} to the agent in the workspace standing on {@code branch}.
   *
   * <p><b>Never throws.</b> See the class javadoc: this is told after a transition that has already
   * been recorded, and no failure here may reach the caller as one.
   *
   * @param repositoryId the <b>catalog</b> repository id — this service's row id, which is what
   *     qits-workspaces keys its {@code repository_id} by. For a ticket this is the project's
   *     wrapper, because a ticket names no repository
   * @param branch the branch the workspace stands on — {@code ticket/<slug>} for a ticket's phases
   * @param text the turn, as prose. It is the agent's next instruction, so it is written for a model
   *     to act on rather than for a person to read
   * @return what happened, {@link Outcome#COULD_NOT} included; advisory, and never null
   */
  Turn deliver(String repositoryId, String branch, String text);
}

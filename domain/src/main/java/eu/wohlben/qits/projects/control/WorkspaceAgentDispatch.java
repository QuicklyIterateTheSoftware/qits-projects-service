package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.error.DomainException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * A workspace with a coding agent already running in it, asked for on a branch of one repository —
 * the seam behind the "Assign agent" button on a ticket.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>A ticket names work and names no repository, so the thing that can act on one is an aggregate
 * workspace over the whole estate: the wrapper's branch with every submodule branched alongside it.
 * qits-workspaces is the only context that can make one, and the only context that can launch an
 * agent into it. Nothing here can do either, and nothing on the bus can ask for them — a dispatch is
 * a request with an outcome somebody is waiting on, not a fact to announce.
 *
 * <h2>The outcome matters, and that is the whole difference from {@link ReleasedBranchWorkspaces}</h2>
 *
 * <p>That port is a fire-and-forget told <em>after</em> a release has already landed, so its contract
 * is that it never throws and answers nothing. This one is the request itself: a person pressed a
 * button and is waiting to be sent to the workspace. <b>So an implementation that could not dispatch
 * must throw</b>, as a {@link DomainException} carrying the status the door should answer —
 * <b>502</b> when qits-workspaces refused, was unreachable or answered something unreadable, and
 * <b>503</b> when this hop is not configured to reach it at all (no address, no machine credential).
 * A WARN and a hopeful empty would leave a ticket saying an agent is on it when none is.
 *
 * <p>It is <b>not</b> idempotent in the way a resolution is, and the far side is what makes that
 * safe: a second dispatch onto the same branch adopts the workspace that is already there, which is
 * what {@link Dispatch#fresh()} and {@code agentLaunch} report back. Nothing here retries — a
 * dispatch is one press of one button, and a person can press it again.
 *
 * <h2>Absent is a supported configuration</h2>
 *
 * <p>Injected as {@code Instance<T>} like every port here. With no implementation present the door
 * answers <b>503</b> naming what is missing, which is the honest answer: an assembly that does not
 * run qits-workspaces has no workspaces to dispatch into, and the ticket is unchanged — no comment
 * is stamped and no hint is fired, because nothing happened.
 *
 * <h2>Why this is not a verb on {@link WorkspaceLifecycle}</h2>
 *
 * <p>{@link ReleasedBranchWorkspaces}' javadoc gives the reason in full and it has not changed: that
 * port has <b>no production adapter</b>, so an HTTP implementation added for one verb would be
 * forced to co-implement {@code createMainWorkspace} and {@code releaseRepository}, each of which is
 * a live flow this change neither owns nor tests. A separate port keeps the blast radius at exactly
 * one call on exactly one path.
 */
public interface WorkspaceAgentDispatch {

  /**
   * What qits-workspaces answered, narrowed to what this side acts on.
   *
   * @param workspaceRowId the workspace's row id at qits-workspaces — the SPA composes the link to
   *     it from this and the repository id, so it is the one field the door exists to return
   * @param fresh whether the workspace was created by this call, or adopted because it was already
   *     standing on the branch
   * @param agentLaunch {@code SCHEDULED} when an agent was started for this dispatch, {@code
   *     SKIPPED_RUNNING} when one was already working in that workspace. A string rather than an
   *     enum: it is the far side's vocabulary, and a value this side has never heard of must reach
   *     the ticket thread rather than fail the dispatch.
   */
  record Dispatch(long workspaceRowId, boolean fresh, String agentLaunch) {}

  /**
   * What the dispatched workspace is <b>for</b>: the row this dispatch is about, named by id.
   *
   * <h2>Why an id and not the row rendered as prose</h2>
   *
   * <p>This used to be a {@code preamble} — the ticket's title, its status line and its whole
   * description, or the epic's outline, rendered here and frozen into the workspace's goal at
   * creation. That copy had no reader it served well. The instruction beside it already sends the
   * agent to read the source live over MCP, so the prose was a stale second copy of something one
   * tool call away, and on the workspace page it buried the one fact a person scanning the list
   * wants: what this workspace is for. A reference is that fact, so it travels as a field.
   *
   * <p><b>The far side resolves neither id and renders neither.</b> It carries them, and the
   * workspaces SPA turns one into a link — composing it needs this platform's public origin, which a
   * browser is told by the navigation document and no service here holds a key for.
   *
   * <p>Two static factories rather than a public constructor: the members are same-typed and
   * adjacent, and a dispatch filed under the wrong one is a link that opens somebody else's work.
   *
   * @param ticketId the ticket this dispatch is about, or {@code null}
   * @param epicId the epic this dispatch is about, or {@code null}
   */
  record Subject(String ticketId, String epicId) {

    /** A ticket dispatch. */
    public static Subject ticket(String ticketId) {
      return new Subject(ticketId, null);
    }

    /** An epic dispatch. */
    public static Subject epic(String epicId) {
      return new Subject(null, epicId);
    }
  }

  /**
   * Make (or adopt) the workspace on {@code branch} and launch an agent in it.
   *
   * @param repositoryId the <b>catalog</b> repository id — this service's row id, which is what
   *     qits-workspaces keys its {@code repository_id} by. For a ticket this is the project's
   *     wrapper, because a ticket names no repository.
   * @param branch the branch to stand the workspace on, created at the repository's default branch
   *     if it does not exist
   * @param gitRefs the Git refs an agent in this workspace may push: exact refs, each {@code
   *     refs/heads/…} (plan contract C4). Sent as {@code gitRefs}. {@code null} sends nothing, and
   *     qits-workspaces then allows only the workspace's own branch. Computed by {@code
   *     WorkBranches}, together with {@code branch}.
   * @param branchTree whether the submodules are branched alongside the wrapper — true for the
   *     whole-estate aggregate a ticket needs
   * @param subject what the workspace is for — see {@link Subject}. There is deliberately no
   *     {@code preamble} beside it: the workspace's goal is a person's prose, and neither door here
   *     has a person's prose to send.
   * @param instruction the agent's first turn
   * @return what the far side made or adopted
   * @throws DomainException when the dispatch could not be made — 502 for a far side that refused,
   *     was unreachable or answered unreadably, 503 for a hop with no address or no credential
   */
  Dispatch dispatchAgent(
      String repositoryId,
      String branch,
      List<String> gitRefs,
      boolean branchTree,
      Subject subject,
      String instruction);

  /**
   * A workspace over there that names one of our rows as its subject — the {@link Subject} read
   * back — <b>live or resolved</b>, carrying which it is.
   *
   * <p>{@link #status} is the far side's own word and travels as a {@code String} for {@link
   * Dispatch#agentLaunch}'s reason: it is qits-workspaces' vocabulary, and a value this side has
   * never heard of must reach a reader rather than fail a lookup that decorates a page. The three it
   * spells today are {@code ACTIVE}, {@code INTEGRATED} and {@code ABANDONED}.
   *
   * @param workspaceRowId the workspace's row id at qits-workspaces
   * @param repositoryId the repository it belongs to; the pair composes the link, exactly as on
   *     {@link Dispatch}
   * @param workspaceId the workspace's display label
   * @param branch the branch it owns — {@code ticket/<slug>} or {@code epic/<slug>} for a dispatch
   * @param ticketId the ticket it is for, or {@code null}
   * @param epicId the epic it is for, or {@code null}
   * @param status the far side's resolution status — {@link #ACTIVE} while the workspace and its
   *     container still stand, {@code INTEGRATED} or {@code ABANDONED} once it is resolved. Never
   *     null: an implementation that was told nothing reports {@link #ACTIVE}
   * @param resolvedAt when the workspace was resolved, or {@code null} while it is live
   */
  record Reference(
      long workspaceRowId,
      String repositoryId,
      String workspaceId,
      String branch,
      String ticketId,
      String epicId,
      String status,
      Instant resolvedAt) {

    /**
     * The one status word that means a container is standing and an agent can be spoken to. Every
     * reader that needs a <em>live</em> workspace compares against this and says why it is doing so;
     * see {@link #workspacesReferencing}.
     */
    public static final String ACTIVE = "ACTIVE";
  }

  /**
   * Which workspaces name these tickets and epics — <b>every</b> one of them, live or resolved, each
   * carrying its own {@link Reference#status}.
   *
   * <h2>Derived per read, stored nowhere</h2>
   *
   * <p>A ticket does not point at a workspace and must not: a pointer has to be cleared when the
   * workspace is integrated or discarded, and one that is only ever written disables its own button
   * forever and links to a row nobody can open. The workspace is the thing that comes and goes, so
   * the workspace carries the reference and this is a query over those references. Zero, one or
   * several; the rows are what the links point at.
   *
   * <h2>The list is not filtered, and every reader decides what to make of it</h2>
   *
   * <p>This used to answer <em>live</em> workspaces alone, and the liveness rule was stated as the
   * far side's to define with nothing here re-deciding it. <b>That is no longer what comes back.</b>
   * A ticket keeps a link to where its work happened after that workspace is integrated or
   * abandoned — the transcripts, the diff and the thread are the record of the change, and a
   * reference that vanished the moment somebody tidied up took the only way back to them with it. So
   * the far side answers every workspace that names the subject and says which kind each is, and the
   * decision moves here, once per reader, in the open.
   *
   * <p><b>A reader that wants only live workspaces must filter on {@code status ==
   * Reference.ACTIVE} itself</b>, and must say why in a comment where it does. That is not
   * ceremony: the readers divide cleanly into two and the difference is what a wrong answer costs.
   * A <em>read</em> decorating a page wants them all — a resolved workspace is a link worth drawing,
   * greyed or not. A <em>write</em> — anything that speaks to an agent, delivers a turn, or asks for
   * the release of the branch a workspace stands on — wants {@link Reference#ACTIVE} and nothing
   * else, because a resolved workspace has no container to speak to and, quite possibly, no branch
   * left to release.
   *
   * <p>The count therefore no longer decides a button on its own: a ticket whose only workspace is
   * abandoned has nobody on it, and "Assign agent" is offered again.
   *
   * <h2>The failure contract is the opposite of {@link #dispatchAgent}'s, on purpose</h2>
   *
   * <p>That verb is a person pressing a button and waiting, so a failure has to reach them and it
   * throws. This one <b>decorates a read</b> — a project's tickets panel, an epics board — and a
   * lookup that threw would take a whole page down because a sibling service was restarting. <b>So
   * an implementation must never throw: it warns and answers empty.</b> Empty is also the honest
   * degraded answer, because it is exactly what this returned before the feature existed — every
   * button live, which is the behaviour a reader already knows.
   *
   * <p>Batched for the same reason: the caller is one listing asking about every row on it, and a
   * call per row would put a network hop inside a loop over a page.
   *
   * @param ticketIds the tickets asked about; may be empty
   * @param epicIds the epics asked about; may be empty. Both empty answers empty without a call
   * @return every workspace naming any of them, live or resolved, in no particular order; never
   *     null, and never filtered by status
   */
  List<Reference> workspacesReferencing(Collection<String> ticketIds, Collection<String> epicIds);
}

package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.error.DomainException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * A coding agent for one work item, asked for on the project's wrapper — the seam behind the
 * "Dispatch" button on a ticket or an epic.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>Since qits-1152 an <b>agent</b> is scoped to a work item and works in its own worktree inside
 * a workspace; the workspace is only a slot that hosts agents. qits-workspaces is the only context
 * that can make an agent and place it. Nothing here can, and nothing on the bus can ask for it — a
 * dispatch is a request with an outcome somebody is waiting on, not a fact to announce.
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
 * safe: a second dispatch for the same work item adopts the agent that is already there, which is
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
   * What qits-workspaces answered, narrowed to what this side acts on (qits-1152: the answer names
   * an <b>agent</b>, which lives in a workspace once it is placed).
   *
   * @param agentId the agent's id at qits-workspaces — the one member a dispatch cannot do without
   * @param workspaceRowId the row id of the workspace the agent was placed in, or {@code null} while
   *     it is QUEUED for a free slot. With the repository id it composes the SPA's link
   * @param repositoryId the wrapper the agent works on, as qits-workspaces names it
   * @param fresh whether the agent was created by this call, or adopted because the work item
   *     already had an ACTIVE one
   * @param agentLaunch {@code SCHEDULED}, {@code SKIPPED_RUNNING} or {@code QUEUED}. A string rather
   *     than an enum: it is the far side's vocabulary, and a value this side has never heard of must
   *     reach the ticket thread rather than fail the dispatch.
   * @param agentIdentity the principal the dispatched agent's own calls carry (qits-887). <b>{@code
   *     null} when the far side named none</b>. Never a secret. It is what an entity's assignee
   *     records, see {@link #assignee()}.
   */
  record Dispatch(
      String agentId,
      Long workspaceRowId,
      String repositoryId,
      boolean fresh,
      String agentLaunch,
      String agentIdentity) {

    /**
     * Who is on the work, as an entity's assignee records it (qits-887): the agent identity, or —
     * when the far side named none — {@code agent <agentId>}, so the field still says who it is.
     */
    public String assignee() {
      return agentIdentity == null || agentIdentity.isBlank() ? "agent " + agentId : agentIdentity;
    }
  }

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
   * <h2>The qualified id rides beside the row id, and is the one member a person reads (qits-614)</h2>
   *
   * <p>{@code qualifiedId} is the same row named the way a commit subject and a person name it —
   * {@code <project-slug>-<number>}, {@code qits-614} — rendered by {@code QualifiedEntityIds} and
   * nowhere else. The far side hands it to the workspace daemon, which names the agent's sessions
   * {@code <status square> qits-614 <title>} (qits-617; it was {@code qits-614: ticket/<slug>}
   * before) rather than after a UUID nobody can read. It is a <b>label</b>
   * and never a key: nothing resolves it, so a dispatch that could not render it (a project row gone
   * between two reads, a row with no number) sends the two ids alone and loses nothing but the name.
   * {@code null} therefore means "not known here", and the wire leaves the field off rather than
   * stating an absence.
   *
   * <h2>Title, status and the blocked flag ride too, as the session name's other three parts (qits-617)</h2>
   *
   * <p>The far side names the agent's sessions {@code <status square> <qualified id> <title>} — the
   * square a colour for the entity's status, pale while it is blocked — so a person scanning a list
   * of sessions reads what each one is working on and whether it is waiting on them without opening
   * any. This is the value <b>at dispatch</b>; every later change of any of the three is pushed to
   * the standing workspace by {@code WorkspaceAgentEntities}, so this is the seed and not the only
   * copy. Like the qualified id they are labels and never keys: {@code entityTitle} and {@code
   * entityStatus} are left off the wire when unknown, and {@code entityStatus} is the status enum's
   * <em>name</em> ({@code REFINED}), the far side's word to map to a colour, never a rendered label.
   *
   * @param ticketId the ticket this dispatch is about, or {@code null}
   * @param epicId the epic this dispatch is about, or {@code null}
   * @param qualifiedId the row's {@code <project-slug>-<number>}, or {@code null} when it could not
   *     be rendered
   * @param title the row's title as it stands at dispatch, or {@code null} when not known
   * @param status the row's status enum name at dispatch, or {@code null} when not known
   * @param blocked whether the row is blocked at dispatch; {@code false} when not known
   */
  record Subject(
      String ticketId,
      String epicId,
      String qualifiedId,
      String title,
      String status,
      boolean blocked) {

    /** A ticket dispatch, its qualified id and entity state not (yet) known. */
    public static Subject ticket(String ticketId) {
      return new Subject(ticketId, null, null, null, null, false);
    }

    /** An epic dispatch, its qualified id and entity state not (yet) known. */
    public static Subject epic(String epicId) {
      return new Subject(null, epicId, null, null, null, false);
    }

    /** The work item's row id whatever its archetype: what qits-workspaces keys agents by. */
    public String workId() {
      return ticketId != null ? ticketId : epicId;
    }

    /**
     * The same subject, also named by its qualified id. A wither rather than a third argument on the
     * factories, so the two same-typed row ids still cannot be swapped by position and a caller with
     * no slug to hand simply does not call it.
     */
    public Subject withQualifiedId(String qualifiedId) {
      return new Subject(ticketId, epicId, qualifiedId, title, status, blocked);
    }

    /**
     * The same subject, also carrying the row's title, status name and blocked flag as they stand
     * now — the rest of the session name. A wither for {@link #withQualifiedId}'s reason.
     */
    public Subject withEntityState(String title, String status, boolean blocked) {
      return new Subject(ticketId, epicId, qualifiedId, title, status, blocked);
    }
  }

  /**
   * Make (or adopt) the work item's agent and place it in a workspace (qits-1152). One ACTIVE agent
   * per work item: a second dispatch adopts the one there.
   *
   * @param repositoryId the <b>catalog</b> repository id of the project's wrapper — this service's
   *     row id, which is what qits-workspaces keys its {@code repository_id} by
   * @param wrapperBranch the branch the agent's wrapper worktree stands on, {@code
   *     <prefix>/<qualified id>} from {@code WorkBranches}. The agent names its submodule branches
   *     {@code <wrapperBranch>_<its choice>}; qits-workspaces derives the refs it may push from this
   * @param subject what the agent is for — see {@link Subject}. Its {@link Subject#workId()} keys
   *     the agent
   * @param instruction the agent's first turn
   * @return what the far side made or adopted
   * @throws DomainException when the dispatch could not be made — 502 for a far side that refused,
   *     was unreachable or answered unreadably, 503 for a hop with no address or no credential
   */
  Dispatch dispatchAgent(
      String repositoryId, String wrapperBranch, Subject subject, String instruction);

  /** A branch an agent pushed, in one repository (qits-1152). */
  record Branch(String repositoryId, String branch) {}

  /**
   * An agent over there that works on one of our rows — the {@link Subject} read back, in any
   * state (qits-1152).
   *
   * <p>{@link #state} and {@link #runState} are the far side's own words and travel as strings for
   * {@link Dispatch#agentLaunch}'s reason. {@code state} is {@link #ACTIVE}, {@link #OBSOLETE} (its
   * branches were released; it waits for cleanup) or {@code REMOVED}; {@code runState} is {@code
   * QUEUED}, {@code RUNNING} or {@code YIELDED}.
   *
   * @param agentId the agent's id at qits-workspaces
   * @param workspaceRowId the workspace it is placed in, or {@code null} while QUEUED
   * @param repositoryId the wrapper it works on; with the row id it composes the link
   * @param workId the work item's row id
   * @param entityId the work item's qualified id, or {@code null}
   * @param wrapperBranch the branch its wrapper worktree stands on
   * @param branches every (repository, branch) it pushed, the wrapper's among them when it pushed
   *     there; never null
   * @param state never null: an implementation that was told nothing reports {@link #ACTIVE}
   * @param runState the far side's run state, or {@code null}
   * @param createdAt when it was created, or {@code null}
   * @param removedAt when it was removed, or {@code null} while it is not
   */
  record Agent(
      String agentId,
      Long workspaceRowId,
      String repositoryId,
      String workId,
      String entityId,
      String wrapperBranch,
      List<Branch> branches,
      String state,
      String runState,
      Instant createdAt,
      Instant removedAt) {

    /** The agent works on its item: it can be spoken to and its branches are not released yet. */
    public static final String ACTIVE = "ACTIVE";

    /** Its branches were released; it stays until the cleanup check removes it. */
    public static final String OBSOLETE = "OBSOLETE";

    public Agent {
      branches = branches == null ? List.of() : List.copyOf(branches);
      state = state == null || state.isBlank() ? ACTIVE : state;
    }

    public boolean active() {
      return ACTIVE.equals(state);
    }
  }

  /**
   * Which agents work on these work items — <b>every</b> one of them, in any state, each carrying
   * its own {@link Agent#state}.
   *
   * <h2>Derived per read, stored nowhere</h2>
   *
   * <p>A work item does not point at an agent and must not: the agent is the thing that comes and
   * goes, so the agent carries the reference and this is a query over those references.
   *
   * <h2>The list is not filtered, and every reader decides what to make of it</h2>
   *
   * <p>A <em>read</em> decorating a page wants them all — a removed agent is a link worth drawing,
   * greyed or not. A <em>write</em> — anything that speaks to an agent or asks for the release of its
   * branches — wants {@link Agent#active()} and nothing else, and must say why in a comment where it
   * filters.
   *
   * <h2>The failure contract is the opposite of {@link #dispatchAgent}'s, on purpose</h2>
   *
   * <p>This one <b>decorates a read</b>, and a lookup that threw would take a whole page down
   * because a sibling service was restarting. <b>So an implementation must never throw: it warns
   * and answers empty.</b> Batched because the caller may be one listing asking about every row on
   * it.
   *
   * @param workIds the work items' row ids; empty answers empty without a call
   * @return every agent working on any of them, in no particular order; never null
   */
  List<Agent> agentsFor(Collection<String> workIds);
}

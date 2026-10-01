package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.control.Archetypes;
import eu.wohlben.qits.entities.control.EntityCommentService;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.BadRequestException;
import eu.wohlben.qits.entities.error.ConflictException;
import eu.wohlben.qits.projects.campaignhost.CampaignExecutor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Locale;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * <b>Blocking and unblocking an entity with a lifecycle — a ticket, an epic or a campaign: the
 * refusal, the write and the remark, once for every door.</b>
 *
 * <p>A block says the phase the entity's <em>current</em> status starts cannot finish right now. It
 * is a flag and not a status ({@code WorkEntity.blocked} carries that argument), it is cleared by
 * every transition ({@code WorkEntityService.transition}), and it is set here and nowhere else. It
 * was a ticket's alone until qits-592; the column, the write and the clearing rule were already
 * archetype-blind, so what widened is only this class's test — "does a phase run now" — which never
 * mentioned tickets in the first place.
 *
 * <h2>Why this class exists rather than the rule being written at each door</h2>
 *
 * <p>Several surfaces perform this write — {@code entities/api/EntityBlockController}'s route and
 * {@code TicketController}'s older one, {@code mcp/CommentMcpTools}' {@code block_entity} pair and
 * {@code mcp/TicketMcpTools}' {@code block_ticket} pair — exactly as several surfaces perform a
 * transition, and a rule written at each would be free to drift. What is written here is all of the
 * rule: a reason is required to block and not to unblock, only a status that starts a phase may be
 * blocked, and what happened is said on the entity's own thread. Each door keeps only what is
 * genuinely its own — the id it accepts, the agent binding, the hint and the shape it answers.
 *
 * <h2>Why it is in {@code projects.api} and not in the entities module</h2>
 *
 * <p>Because the refusal is about a <b>phase</b>, and a phase is this layer's concept. {@link
 * PhasePrompts#phaseOf} is the one place in this service that reads a status as the work that
 * starts from it, and {@link #requireBlockable} asks exactly that question rather than re-listing
 * the three statuses a phase runs under — so a fourth phase, or a status moving off the line,
 * changes one switch and this refusal follows it. It asks {@code phaseOf} and not {@code
 * startedBy}: the question is whether a phase runs, not what its agent is told, and a campaign has
 * phases and no turns to render. The entities module cannot hold the rule at all: it has no idea a
 * phase exists and depends on {@code domain} nowhere, which is what keeps it liftable. {@code
 * WorkEntityService.setBlocked} therefore writes the row and judges nothing, and says so.
 *
 * <p>That places this beside {@link PhaseAdvance} and {@link EntityWorkspaces}, which are here for
 * the same reason and are the precedent: what an entity's status <em>means for the work</em> is
 * decided in this package, and the row is written one module down.
 *
 * <h2>The reason is a comment and not a column</h2>
 *
 * <p>A blocker is a remark with an author and a time — which is what the thread already is, and
 * every archetype has one since qits-551 — so it lands through {@code EntityCommentService
 * .addComment} the way {@code PhaseAdvance.say} lands what became of a phase. A column would be a
 * second place the same sentence lives, and it would go stale the moment the thread moved past it.
 * It is <b>required when blocking</b> because a block with no stated blocker is one nobody can
 * clear: the next reader is told the work stopped and not what would restart it. It is optional
 * when unblocking, where the entity simply resumes and there may be nothing to add.
 *
 * <p><b>The comment is written before the row is, and never after.</b> A comment that failed would
 * otherwise leave a blocked entity with no stated blocker, which is the one state this door exists
 * to prevent; a row write that failed after a comment leaves an unblocked entity carrying a remark,
 * which is merely a remark. The comment is also what makes the refusals worth ordering: both run
 * before either write, so an entity refused has had nothing written on it.
 *
 * <h2>A campaign's unblock is followed by an executor pass</h2>
 *
 * <p>A blocked campaign claims no new member ({@code CampaignExecutor}'s steps 1 and 3), and the
 * latches that arrived meanwhile stay latched with nobody acting on them — their observers already
 * fired and stood down. The periodic sweep would find them within an interval, but it runs only in
 * {@code NORMAL} launch mode and an unblock is a person saying "go on now", so the unblock asks for
 * the same pass a start press does, {@code CampaignExecutor.sweep(campaignId)}, after the row is
 * written. It never throws; a member it cannot try is the sweep's to retry.
 *
 * <h2>A ticket's or an epic's agents are told, after the write (qits-614)</h2>
 *
 * <p>The agent sessions working a ticket or an epic carry a {@code ❗ } marker on their names while
 * it is blocked, so {@link AgentBlockSignals} is told whenever this door actually <b>changes</b> the
 * flag — compared against the row the door resolved, so an idempotent re-block (which the entities
 * module records again on purpose, for the comment) does not rename anything twice. It runs after
 * {@code setBlocked} has returned, i.e. after its own transaction committed, so no target is ever
 * told about a value a rollback undid; and it never throws, so an unreachable workspace costs a
 * marker and never the block. A campaign is not told anything: no session works one.
 */
@ApplicationScoped
public class EntityBlocks {

  @Inject EntityCommentService thread;

  @Inject WorkEntityService entities;

  /** The pass after a campaign's unblock — see the class javadoc. */
  @Inject CampaignExecutor executor;

  /** The agents working a ticket or an epic, told the flag moved — see the class javadoc. */
  @Inject AgentBlockSignals agents;

  /**
   * What every generic block door answers: the entity as the flag now stands on it, and no more. A
   * record of its own rather than an archetype's DTO, for the reason {@code EntityPatchController}
   * gives for the merged shape — a door that takes any id must answer one shape.
   */
  @Schema(
      name = "EntityBlock",
      description = "An entity's block flag as the write left it, with its archetype and status.")
  public record Blocked(String entityId, Archetype archetype, String status, boolean blocked) {

    public static Blocked of(WorkEntity row) {
      return new Blocked(row.id, row.archetype, row.status, row.blocked);
    }
  }

  /**
   * Blocks or unblocks {@code entity}, records why on its thread, and answers the row as it now
   * stands.
   *
   * @param entity the entity as resolved by the door — already known to exist and already bound to
   *     the caller, because a refusal about a row the caller may not see is a refusal that reports
   *     on it
   * @param blocked what the flag should become
   * @param reason why; required when {@code blocked} is true, optional otherwise
   * @param changedBy the caller, resolved by the surface that took the call — passed in for the
   *     reason {@link PhaseAdvance#afterTransition} takes it, since the surfaces answer an unnamed
   *     caller differently
   */
  public WorkEntity apply(WorkEntity entity, boolean blocked, String reason, String changedBy) {
    String stated = reason == null ? "" : reason.trim();
    if (blocked && stated.isEmpty()) {
      throw new BadRequestException(
          "A blocked "
              + WorkEntityService.nounOf(entity.archetype).toLowerCase(Locale.ROOT)
              + " needs a stated blocker: say what is in the way, so somebody can clear it.");
    }
    if (blocked) {
      requireBlockable(entity);
    }
    // Read before the write: the door's row is the value the flag had, and only a change is told.
    boolean was = entity.blocked;
    thread.addComment(entity.id, remark(blocked, stated), changedBy);
    WorkEntity written = entities.setBlocked(entity.archetype, entity.id, blocked, changedBy);
    if (!blocked && written.archetype == Archetype.CAMPAIGN) {
      executor.sweep(written.id);
    }
    if (was != blocked) {
      // Ticket and epic only, never throws — AgentBlockSignals filters and swallows both.
      agents.blocked(written, blocked);
    }
    return written;
  }

  /**
   * <b>409 unless a phase runs while this entity's status holds.</b> Two questions, one answer: a
   * kind with no lifecycle — a feature, a task — has no status of its own (its phase is its epic's),
   * and a status {@link PhasePrompts#phaseOf} answers empty for starts no phase. Either way there is
   * nothing for a block to be about. The second is VERIFIED and DONE — the work is over and what is
   * left is a person's judgement — and DROPPED, where the work was decided against and no phase
   * will ever run again.
   *
   * <p>The message names what is missing rather than the status alone, because "409" on an entity
   * that plainly exists leaves the caller guessing whether the block was rejected or the entity was.
   * A caller that genuinely cannot proceed on a VERIFIED ticket has a transition to make, not a flag
   * to set.
   *
   * <p>Only the <em>blocking</em> direction is refused. Unblocking an entity whose status starts no
   * phase is a no-op on the flag and is allowed: the only way to reach that state is an entity that
   * was blocked and then transitioned — which already cleared it — so an unblock there asks for
   * something that is already true, and refusing it would mean refusing to tidy up a state this door
   * can produce.
   */
  static void requireBlockable(WorkEntity entity) {
    String noun = WorkEntityService.nounOf(entity.archetype);
    if (Archetypes.legalStatuses(entity.archetype).isEmpty()) {
      throw new ConflictException(
          noun
              + " "
              + entity.id
              + " has no lifecycle of its own, so there is nothing to block — its phase is its"
              + " epic's; block the epic instead.");
    }
    if (PhasePrompts.phaseOf(entity).isEmpty()) {
      throw new ConflictException(
          noun
              + " "
              + entity.id
              + " is "
              + entity.status
              + ", so no phase is running and there is nothing to block — a block says the work"
              + " that runs now cannot finish, and no work runs while this status holds.");
    }
  }

  /**
   * What lands on the thread. It names the flag in the first words, because the thread is read for
   * what happened to the entity and a blocker buried mid-sentence reads as ordinary commentary.
   */
  private static String remark(boolean blocked, String stated) {
    if (blocked) {
      return "Blocked: " + stated;
    }
    return stated.isEmpty() ? "Unblocked." : "Unblocked: " + stated;
  }
}

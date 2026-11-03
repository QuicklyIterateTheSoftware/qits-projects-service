package eu.wohlben.qits.entities.campaign;

import eu.wohlben.qits.entities.control.Archetypes;
import eu.wohlben.qits.entities.control.AuditService;
import eu.wohlben.qits.entities.control.CampaignStatusRecompute;
import eu.wohlben.qits.entities.control.EntityStateMachine;
import eu.wohlben.qits.entities.control.Nesting;
import eu.wohlben.qits.entities.control.ReadPatience;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.control.WritePatience;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.AuditEntityType;
import eu.wohlben.qits.entities.entity.AuditOperation;
import eu.wohlben.qits.entities.entity.EntityMembership;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.MembershipKind;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.BadRequestException;
import eu.wohlben.qits.entities.error.ConflictException;
import eu.wohlben.qits.entities.error.NotFoundException;
import eu.wohlben.qits.entities.milestone.CampaignMembershipMilestone;
import eu.wohlben.qits.entities.milestone.CampaignMembershipMilestoneRepository;
import eu.wohlben.qits.entities.milestone.Milestone;
import eu.wohlben.qits.entities.milestone.MilestoneCriterion;
import eu.wohlben.qits.entities.milestone.MilestoneGroup;
import eu.wohlben.qits.entities.milestone.MilestoneRepository;
import eu.wohlben.qits.entities.milestone.MilestoneService;
import eu.wohlben.qits.entities.milestone.MilestonesChanged;
import eu.wohlben.qits.entities.persistence.EntityMembershipRepository;
import eu.wohlben.qits.entities.persistence.WorkEntityRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * <b>A campaign's membership, its members' conditions, and the latches a person or the current
 * state can set</b> (epic f6c67e74, qits-413) — the authoring half. Nothing here matches an event or
 * dispatches.
 *
 * <h2>A member's condition is a milestone (epic qits-615)</h2>
 *
 * <p>Since epics V38 a membership waits on milestones ({@code campaign_membership_milestone}), and
 * its <b>condition</b> — what {@link #setCondition}, {@link #approve} and the seed read and write —
 * is the one milestone marked {@code generated_condition}, named {@code Before "<member title>" in
 * "<campaign title>"} ({@link #conditionName}) when these doors create it, and attached to the
 * member entity too. The doors behave as they did from outside: a whole replace, a restated
 * criterion keeps its latch, an approval latches once. Latching from events is {@code
 * MilestoneEvaluator}'s; whether a membership may run is {@link CampaignReadiness}'s — every
 * milestone it waits on met, none at all being no condition. No {@code campaign_criterion*} table is
 * read or written here any more.
 *
 * <h2>The rules, each stated once</h2>
 *
 * <ul>
 *   <li><b>A member is an EPIC or a TICKET of the campaign's project</b>, joined once: the kind must
 *       have a lifecycle ({@link Archetypes#legalStatuses}) and must be one a campaign may hold
 *       ({@link Nesting#mayContain}, which refuses a campaign inside a campaign). A campaign edge is
 *       not a tree edge and never goes through {@link Nesting#check}.
 *   <li><b>Membership edits are allowed while the campaign is not DROPPED</b> (qits-650,
 *       qits-1805), running or not, DONE included, and refused with a 409 at DROPPED. Its status is
 *       derived from its members, so an add or a remove re-derives it in the same write ({@link
 *       CampaignStatusRecompute}): adding a REPORTED member to an IMPLEMENTED — or a DONE —
 *       campaign is how it drops back to REPORTED. Starting and approving stay refused at DONE.
 *   <li><b>Positions are dense and zero-based</b>; an insert shifts the tail and a move renumbers the
 *       affected span, {@code DossierService.move}'s idiom.
 *   <li><b>The seed.</b> A member added at position p when a member stands at p−1 that is neither
 *       VERIFIED nor DONE waits on it: one group, one {@code ENTITY_STATUS {entityId: <predecessor>,
 *       status: VERIFIED}}, {@code seeded = true}. Otherwise nothing. The seed is never re-derived:
 *       criteria are the dependency, so an insertion between A and C seeds the newcomer on A and
 *       leaves C waiting on A, and a move touches no criterion at all.
 *   <li><b>A claim freezes the membership.</b> Once {@code claimed_at} is set its condition is not
 *       edited and it is not removed — the run record would be lost.
 * </ul>
 *
 * <h2>Locks</h2>
 *
 * <p>Every operation first takes {@code PESSIMISTIC_WRITE} on the <b>campaign's entity row</b>, which
 * serialises this class's writes per campaign (two concurrent inserts would otherwise both renumber
 * from the same picture). Every operation that writes a membership's condition, claim or removal
 * then takes {@code PESSIMISTIC_WRITE} on <b>that membership row</b> before reading its criteria,
 * which is the {@code FOR UPDATE} the executor's claim also takes: a condition edit and a claim
 * cannot interleave. The order is always campaign row, then membership rows, and the executor takes
 * no campaign-row lock, so the two never wait on each other in a cycle. {@link #start} takes the
 * campaign row and then the {@code campaign_start} row, the pause hook's order; nothing here locks
 * {@code campaign_start} while holding a membership, which is what keeps the executor's own order
 * ({@code campaign_start FOR SHARE}, then the membership) out of any cycle as well. The add and
 * the remove re-derive the campaign's status last ({@link CampaignStatusRecompute}), which re-takes
 * the campaign row it already holds and locks nothing else; a member's own move takes its row and
 * then the campaign row, and nothing here waits on a member's row, so the two only ever queue.
 *
 * <h2>Transactions and audit</h2>
 *
 * <p>Every write is one {@link WritePatience} attempt, database-only, so a retry re-runs it whole;
 * validation that needs no row runs before the wrap. Every write is audited as an {@link
 * AuditOperation#UPDATE} of the <b>campaign</b> ({@link AuditEntityType#CAMPAIGN}, the campaign's id
 * as both entity and subtree key) whose snapshot names the change and the membership — a membership
 * is the campaign's content, the way a feature is an epic's, and the existing vocabulary says so
 * with no new audit word and no migration of {@code ck_audit_entity_type}.
 */
@ApplicationScoped
public class CampaignService {

  /**
   * The forward walk ENTITY_STATUS reads "at or past" along; DROPPED is never past anything. Read
   * off {@link EntityStateMachine#walk()} rather than copied (qits-749): a copy missed IMPLEMENTING
   * once, and {@code indexOf} answered -1 for it in silence.
   */
  private static final List<EntityStatus> FORWARD = EntityStateMachine.walk();

  /**
   * Why a start press is refused (qits-650): the campaign's qualified id and its status, DONE or
   * DROPPED. One sentence for {@link #start} and {@code campaignhost/CampaignStarter}, which decides
   * it first, unlocked.
   */
  public static final String START_REFUSED =
      "A campaign is started while it is neither DONE nor DROPPED; campaign %s is %s.";

  /**
   * Where a campaign is over (qits-650): nothing in it is approved, and it is not started. Its
   * members are still edited at DONE (qits-1805, {@link #requireEditable}).
   */
  private static final Set<String> FINISHED =
      Set.of(EntityStatus.DONE.name(), EntityStatus.DROPPED.name());

  /** A predecessor in one of these needs no waiting on. */
  private static final Set<String> ARRIVED =
      Set.of(EntityStatus.VERIFIED.name(), EntityStatus.DONE.name());

  @Inject WorkEntityService workEntities;

  @Inject WorkEntityRepository entities;

  @Inject EntityMembershipRepository memberships;

  @Inject MilestoneService milestoneService;

  @Inject MilestoneRepository milestones;

  @Inject CampaignMembershipMilestoneRepository waits;

  @Inject CampaignStartRecordRepository starts;

  @Inject AuditService auditService;

  /** Re-derives the campaign's status inside an add and a remove (qits-650). */
  @Inject CampaignStatusRecompute campaignStatus;

  @Inject ReadPatience patience;

  @Inject WritePatience writes;

  /** The one "satisfied memberships" step every source answers through. */
  @Inject CampaignReadiness readiness;

  /** Renders {@code qits-412}; optional — see {@link EntityQualifier}. */
  @Inject Instance<EntityQualifier> qualifier;

  /**
   * Hands the memberships a write left satisfied to the executor — fired inside the write, observed
   * after it commits. See {@link CampaignMembersSatisfied}.
   */
  @Inject Event<CampaignMembersSatisfied> satisfied;

  // --- the views ------------------------------------------------------------------------------------

  /** One OR'd group of a member's condition and its AND'd criteria, in position order. */
  public record Group(MilestoneGroup group, List<MilestoneCriterion> criteria) {}

  /**
   * A membership, the entity it gathers, its condition (the groups of its generated milestone), and
   * every milestone it waits on. {@code milestones} null means only the condition is known — a view
   * built by hand — and then the condition alone decides.
   */
  public record Member(
      EntityMembership membership, WorkEntity entity, List<Group> groups, List<Milestone> milestones) {

    /** A member whose condition alone is known. */
    public Member(EntityMembership membership, WorkEntity entity, List<Group> groups) {
      this(membership, entity, groups, null);
    }

    /**
     * Whether it may run as far as its condition goes: every milestone it waits on is met (none is
     * no condition) — or, with no milestones known, {@link Conditions#satisfied} over the groups.
     */
    public boolean satisfied() {
      if (milestones == null) {
        return Conditions.satisfied(groups.stream().map(Group::criteria).toList());
      }
      return milestones.stream().allMatch(Milestone::met);
    }
  }

  /** A campaign, its start (null if never started), and its members in position order. */
  public record Campaign(WorkEntity campaign, CampaignStartRecord start, List<Member> members) {}

  /** A campaign in a project's listing. */
  public record Summary(WorkEntity campaign, CampaignStartRecord start, int members) {}

  /** One OR'd group of a condition, as a caller states it. */
  public record GroupSpec(List<CriterionSpec> criteria) {}

  /**
   * One criterion as a caller states it. {@code id} restates an existing criterion of the same
   * membership, keeping its latch; null states a new one.
   */
  public record CriterionSpec(String id, String kind, Map<String, Object> predicate) {}

  /**
   * What the progress read (qits-418) derives from: the whole campaign, and every row an
   * ENTITY_STATUS criterion of it targets, keyed by id — members or not, so a target that left the
   * campaign still reads as itself and only a deleted one is absent.
   */
  public record ProgressRead(Campaign campaign, Map<String, WorkEntity> targets) {}

  /** An approval's answer: the member, and — when its condition now holds — its membership id. */
  public record Approved(Member member, List<String> satisfied) {}

  // --- reads ----------------------------------------------------------------------------------------

  /**
   * The campaign with its start and its members, groups and criteria — five queries, whatever its
   * size.
   *
   * <p><b>In a transaction of its own</b>, inside the patience wrap: a door that resolved the
   * campaign for its binding and then wrote through this class (whose writes commit in their own
   * transactions) would otherwise read the campaign back out of its request's session, as it was
   * before the write. A fresh transaction is a fresh session, so the answer is what is committed.
   */
  public Campaign get(String campaignId) {
    return patience.hold(
        "campaign read",
        () -> QuarkusTransaction.requiringNew().call(() -> view(campaign(campaignId))));
  }

  /**
   * <b>The campaign and its criteria's targets, read together</b> (qits-418) — {@link #get}'s five
   * queries plus one for the targets, in one fresh transaction for {@link #get}'s reason. Nothing is
   * stored: the progress read derives every word from these rows.
   */
  public ProgressRead progress(String campaignId) {
    return patience.hold(
        "campaign progress read",
        () ->
            QuarkusTransaction.requiringNew()
                .call(
                    () -> {
                      Campaign whole = view(campaign(campaignId));
                      Set<String> targetIds = new HashSet<>();
                      for (Member member : whole.members()) {
                        targetIds.addAll(targets(member));
                      }
                      return new ProgressRead(
                          whole,
                          targetIds.isEmpty()
                              ? Map.of()
                              : Map.copyOf(byId(entities.listByIds(targetIds))));
                    }));
  }

  /**
   * The campaign's status as committed now (qits-650), in a fresh transaction for {@link #get}'s
   * reason — what a door answering one member reads to say whether that member sets the floor
   * ({@link CampaignStatusRecompute#holds}), after a write that may just have re-derived it.
   */
  public String status(String campaignId) {
    return patience.hold(
        "campaign status read",
        () -> QuarkusTransaction.requiringNew().call(() -> campaign(campaignId).status));
  }

  /** A project's campaigns, oldest first, each with its start and its member count. */
  public List<Summary> listByProject(String projectId) {
    return patience.hold(
        "campaign list",
        () -> {
          List<WorkEntity> campaigns =
              entities.listByProjectAndArchetype(projectId, Archetype.CAMPAIGN);
          List<String> ids = campaigns.stream().map(row -> row.id).toList();
          Map<String, CampaignStartRecord> started = new LinkedHashMap<>();
          for (CampaignStartRecord start : starts.startsOfAll(ids)) {
            started.put(start.campaignId, start);
          }
          Map<String, Integer> counts = new LinkedHashMap<>();
          for (EntityMembership edge : memberships.campaignMembersOfAll(ids)) {
            counts.merge(edge.parentId, 1, Integer::sum);
          }
          return campaigns.stream()
              .map(
                  row ->
                      new Summary(row, started.get(row.id), counts.getOrDefault(row.id, 0)))
              .toList();
        });
  }

  /**
   * The row {@code entityId} names, of any archetype, or a 404 — for the service layer's in-flight
   * default, which has to read a member's status before the write that adds it.
   */
  public WorkEntity entity(String entityId) {
    WorkEntity row =
        entityId == null ? null : patience.hold("campaign member read", () -> entities.findById(entityId));
    if (row == null) {
      throw new NotFoundException("Entity not found: " + entityId);
    }
    return row;
  }

  // --- create ---------------------------------------------------------------------------------------

  /** A new campaign, REPORTED, with no members — {@link WorkEntityService#createCampaign}. */
  public WorkEntity create(String projectId, String title, String description, String changedBy) {
    return workEntities.createCampaign(projectId, title, description, changedBy);
  }

  // --- membership -----------------------------------------------------------------------------------

  /**
   * Adds {@code entityId} to the campaign at {@code position} (null or past the end appends), seeds
   * its condition (see the class javadoc) and, when {@code inFlight}, joins it claimed: {@code
   * claimed_at = now}, {@code joined_running = true}. Whether a member is in flight is decided by the
   * caller — the service layer, which can see workspaces — never here.
   *
   * <p>Refused with a 409 for a feature or a task (a piece of a plan, which runs no phase), a kind a
   * campaign may not hold, a member of another project, a duplicate, and a campaign that is no
   * longer editable. When the campaign has an active start, the new member's seed is latched from
   * the current state at once.
   */
  public Member addMember(
      String campaignId, String entityId, Integer position, boolean inFlight, String changedBy) {
    requireText(entityId, "entityId");
    if (position != null && position < 0) {
      throw new BadRequestException("A position cannot be negative: " + position);
    }
    CampaignStatusRecompute.Recomputed[] derived = {CampaignStatusRecompute.Recomputed.none()};
    Member added =
        writes.hold(
            "campaign add member",
            () -> {
              derived[0] = CampaignStatusRecompute.Recomputed.none();
              WorkEntity campaign = lockCampaign(campaignId);
              requireEditable(campaign);
              WorkEntity member = entities.findById(entityId);
              if (member == null) {
                throw new NotFoundException("Entity not found: " + entityId);
              }
              requireMayJoin(campaign, member);

              List<EntityMembership> current = memberships.campaignMembers(campaignId);
              for (EntityMembership edge : current) {
                if (edge.childId.equals(entityId)) {
                  throw new ConflictException(
                      qid(member)
                          + " is already a member of campaign "
                          + qid(campaign)
                          + ", at position "
                          + edge.position
                          + ". Move it instead.");
                }
              }
              int at = position == null ? current.size() : Math.min(position, current.size());
              EntityMembership predecessor = null;
              for (EntityMembership edge : current) {
                if (edge.position == at - 1) {
                  predecessor = edge;
                }
                if (edge.position >= at) {
                  edge.position = edge.position + 1;
                }
              }

              EntityMembership edge = new EntityMembership();
              edge.id = UUID.randomUUID().toString();
              edge.kind = MembershipKind.CAMPAIGN;
              edge.parentId = campaignId;
              edge.childId = entityId;
              edge.position = at;
              if (inFlight) {
                edge.claimedAt = Instant.now();
                edge.joinedRunning = true;
              }
              memberships.persist(edge);
              if (predecessor != null) {
                seed(campaign, edge, predecessor);
              }
              flush();
              audit(campaign, "MEMBER_ADDED", edge, changedBy);
              if (starts.isActive(campaignId)) {
                handOver(campaign, latch(campaign), edge);
              }
              derived[0] = campaignStatus.recompute(List.of(campaignId));
              return member(edge);
            });
    campaignStatus.announce(derived[0]);
    return added;
  }

  /**
   * Moves a member to {@code position} (clamped to the last one), renumbering the span between. Never
   * touches a criterion: the dependency is the criteria, not the order.
   */
  public Campaign moveMember(
      String campaignId, String membershipId, int position, String changedBy) {
    if (position < 0) {
      throw new BadRequestException("A position cannot be negative: " + position);
    }
    return writes.hold(
        "campaign move member",
        () -> {
          WorkEntity campaign = lockCampaign(campaignId);
          requireEditable(campaign);
          EntityMembership moved = membershipOf(campaignId, membershipId, false);
          List<EntityMembership> current = memberships.campaignMembers(campaignId);
          int target = Math.min(position, current.size() - 1);
          int from = moved.position;
          if (target != from) {
            for (EntityMembership sibling : current) {
              if (sibling.id.equals(moved.id)) {
                continue;
              }
              if (from < target && sibling.position > from && sibling.position <= target) {
                sibling.position = sibling.position - 1;
              } else if (from > target && sibling.position >= target && sibling.position < from) {
                sibling.position = sibling.position + 1;
              }
            }
            moved.position = target;
            flush();
            audit(campaign, "MEMBER_MOVED", moved, changedBy);
          }
          return view(campaign);
        });
  }

  /**
   * Removes a member and its condition. Refused once claimed (the run record would be lost), and
   * refused while another member's criterion targets this member's entity — the 409 names those
   * members, whose conditions the caller edits first. Deleting the member <em>entity</em> still
   * removes the membership by the delete path; that is the one way around this refusal, and the
   * progress read reports "target deleted" on whatever pointed at it.
   */
  public void removeMember(String campaignId, String membershipId, String changedBy) {
    CampaignStatusRecompute.Recomputed[] derived = {CampaignStatusRecompute.Recomputed.none()};
    writes.run(
        "campaign remove member",
        () -> {
          derived[0] = CampaignStatusRecompute.Recomputed.none();
          WorkEntity campaign = lockCampaign(campaignId);
          requireEditable(campaign);
          EntityMembership gone = membershipOf(campaignId, membershipId, true);
          if (gone.claimedAt != null) {
            throw new ConflictException(
                "Member "
                    + membershipId
                    + " was claimed at "
                    + gone.claimedAt
                    + (gone.joinedRunning ? " (it joined running)" : "")
                    + "; removing it would lose its run record.");
          }
          Campaign whole = view(campaign);
          List<String> waiting = new ArrayList<>();
          Member removed = null;
          for (Member other : whole.members()) {
            if (other.membership().id.equals(gone.id)) {
              removed = other;
              continue;
            }
            if (targets(other).contains(gone.childId)) {
              waiting.add(qid(other.entity()) + " (member " + other.membership().id + ")");
            }
          }
          if (!waiting.isEmpty()) {
            throw new ConflictException(
                "Cannot remove "
                    + qid(removed.entity())
                    + " from campaign "
                    + qid(campaign)
                    + ": "
                    + String.join(", ", waiting)
                    + (waiting.size() == 1 ? " waits" : " wait")
                    + " on it. Edit their conditions first.");
          }
          audit(campaign, "MEMBER_REMOVED", gone, changedBy);
          Milestone condition = conditionOf(gone);
          if (condition != null) {
            dropCondition(gone, condition);
          }
          int position = gone.position;
          memberships.delete(gone);
          flush();
          memberships.campaignCloseGapAfter(campaignId, position);
          derived[0] = campaignStatus.recompute(List.of(campaignId));
        });
    campaignStatus.announce(derived[0]);
  }

  // --- the condition --------------------------------------------------------------------------------

  /**
   * <b>Replaces a member's condition</b> — every group and criterion (PUT semantics). A criterion
   * restated by {@code id} keeps its latch ({@code satisfied_at} and its evidence or approval); one
   * restated with a different kind or predicate is the same row edited, and its latch is cleared,
   * because the evidence was evidence for something else. Omitted criteria are deleted.
   *
   * <p>400 before any row is read for an empty group and for every invalid predicate, all listed at
   * once; 400 inside for an ENTITY_STATUS target that is not a member of this campaign and for an id
   * that is not one of this member's criteria; 409 once claimed. With an active start, the new
   * condition is latched from the current state at once.
   */
  public Member setCondition(
      String campaignId, String membershipId, List<GroupSpec> stated, String changedBy) {
    MilestoneService.Decoded decoded = MilestoneService.decode(toMilestoneSpecs(stated), true);
    List<GroupSpec> specs = stated == null ? List.of() : stated;

    MilestonesChanged[] changes = {MilestonesChanged.none()};
    Member answer =
        writes.hold(
            "campaign member condition",
            () -> {
              changes[0] = MilestonesChanged.none();
              WorkEntity campaign = lockCampaign(campaignId);
              requireEditable(campaign);
              EntityMembership edge = membershipOf(campaignId, membershipId, true);
              if (edge.claimedAt != null) {
                throw new ConflictException(
                    "Member "
                        + membershipId
                        + " was claimed at "
                        + edge.claimedAt
                        + "; its condition is no longer edited.");
              }
              Set<String> members =
                  memberships.campaignMembers(campaignId).stream()
                      .map(member -> member.childId)
                      .collect(Collectors.toSet());
              Milestone condition = conditionOf(edge);
              Set<String> own = new HashSet<>();
              if (condition != null) {
                for (Group group : groupsOf(condition)) {
                  group.criteria().forEach(criterion -> own.add(criterion.id));
                }
              }

              List<String> refused = new ArrayList<>();
              Set<String> restated = new HashSet<>();
              for (int g = 0; g < specs.size(); g++) {
                for (int c = 0; c < specs.get(g).criteria().size(); c++) {
                  String where = "group " + (g + 1) + ", criterion " + (c + 1);
                  String id = specs.get(g).criteria().get(c).id();
                  if (id != null && (!own.contains(id) || !restated.add(id))) {
                    refused.add(
                        where
                            + ": "
                            + id
                            + (own.contains(id)
                                ? " is restated twice"
                                : " is not one of this member's criteria — omit the id to state a"
                                    + " new one"));
                  }
                  if (decoded.predicates().get(g).get(c)
                          instanceof CriterionPredicate.EntityStatusIs target
                      && !members.contains(target.entityId())) {
                    refused.add(
                        where
                            + ": target "
                            + target.entityId()
                            + " is not a member of this campaign — add it first");
                  }
                }
              }
              if (!refused.isEmpty()) {
                throw new BadRequestException(String.join("; ", refused));
              }

              if (specs.isEmpty()) {
                if (condition != null) {
                  dropCondition(edge, condition);
                }
              } else if (condition == null) {
                WorkEntity member = entities.findById(edge.childId);
                Milestone created =
                    milestoneService.createIn(
                        campaign.projectId, conditionName(member, campaign), decoded);
                waitOn(edge, created);
                changes[0] = changes[0].and(milestoneService.recomputeIn(created.id));
              } else {
                milestoneService.lock(condition.id);
                changes[0] = changes[0].and(milestoneService.replaceGroupsIn(condition, decoded));
              }
              flush();
              milestoneService.announce(changes[0]);
              audit(campaign, "CONDITION_SET", edge, changedBy);
              if (starts.isActive(campaignId)) {
                handOver(campaign, latch(campaign), edge);
              }
              return member(edge);
            });
    return answer;
  }

  /**
   * <b>A person's yes</b>: latches an APPROVAL criterion of the member's condition with {@code
   * approved_by}, {@code approval_note} and {@code satisfied_at}. 409 when the criterion is not
   * APPROVAL, when it is already satisfied (naming who and when), and when the campaign is DONE or
   * DROPPED. Answers the member if it may now run and is unclaimed ({@link
   * CampaignReadiness#satisfiedUnclaimed}), and hands it to the executor ({@link
   * CampaignMembersSatisfied}, observed after commit); the milestone it met is announced too.
   */
  public Approved approve(
      String campaignId, String membershipId, String criterionId, String note, String actor) {
    requireText(actor, "actor");
    return writes.hold(
        "campaign approve",
        () -> {
          WorkEntity campaign = lockCampaign(campaignId);
          if (FINISHED.contains(campaign.status)) {
            throw new ConflictException(
                "Campaign " + qid(campaign) + " is " + campaign.status + "; nothing in it is approved any more.");
          }
          EntityMembership edge = membershipOf(campaignId, membershipId, true);
          Member before = member(edge);
          MilestoneCriterion criterion =
              before.groups().stream()
                  .flatMap(group -> group.criteria().stream())
                  .filter(candidate -> candidate.id.equals(criterionId))
                  .findFirst()
                  .orElseThrow(
                      () ->
                          new NotFoundException(
                              "No criterion " + criterionId + " on member " + membershipId));
          Milestone condition = milestoneService.lock(conditionOf(edge).id);
          MilestonesChanged changes =
              milestoneService.approveIn(condition, criterion.id, note, actor);
          milestoneService.announce(changes);
          audit(campaign, "CRITERION_APPROVED", edge, actor);
          List<String> ready = readiness.satisfiedUnclaimed(List.of(edge.id));
          if (!ready.isEmpty()) {
            satisfied.fire(new CampaignMembersSatisfied(campaign.id, ready));
          }
          return new Approved(member(edge), ready);
        });
  }

  /**
   * <b>A start press's row</b> (qits-417): upserts {@code campaign_start} — the first press inserts
   * it with {@code first_started_at = started_at = now}, every later one sets {@code started_at},
   * {@code started_by} and {@code active = true}, and {@code first_started_at}, the forward-only
   * floor event criteria read, never moves. 409 when the campaign is DONE or DROPPED (qits-650 —
   * every other status runs, {@link EntityStateMachine#campaignRuns}), decided under the campaign
   * row's lock, so a pause that committed first is what this sees.
   *
   * <p>Locks the campaign's entity row, then the start row — the same order the pause hook in {@code
   * WorkEntityService.transition} takes them in, so a press and a pause serialise on the first and
   * never meet in opposite orders. Latching from the current state and sweeping are the caller's,
   * after this has committed.
   */
  public CampaignStartRecord start(String campaignId, String startedBy) {
    requireText(startedBy, "startedBy");
    return writes.hold(
        "campaign start",
        () -> {
          WorkEntity campaign = lockCampaign(campaignId);
          if (!EntityStateMachine.campaignRuns(campaign.status)) {
            throw new ConflictException(START_REFUSED.formatted(qid(campaign), campaign.status));
          }
          // Microseconds, what the column holds, so the row answered is the row stored.
          Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
          CampaignStartRecord start =
              em().find(CampaignStartRecord.class, campaignId, LockModeType.PESSIMISTIC_WRITE);
          boolean first = start == null;
          if (first) {
            start = new CampaignStartRecord();
            start.campaignId = campaignId;
            start.firstStartedAt = now;
          }
          start.startedAt = now;
          start.startedBy = startedBy;
          start.active = true;
          if (first) {
            starts.persist(start);
          }
          flush();
          Map<String, Object> snapshot = new LinkedHashMap<>();
          snapshot.put("change", first ? "STARTED" : "RESTARTED");
          snapshot.put("firstStartedAt", text(start.firstStartedAt));
          snapshot.put("startedAt", text(start.startedAt));
          snapshot.put("startedBy", start.startedBy);
          auditService.record(
              AuditEntityType.CAMPAIGN,
              campaign.id,
              campaign.id,
              AuditOperation.UPDATE,
              startedBy,
              snapshot);
          return start;
        });
  }

  /**
   * An active campaign's add or condition edit hands over what it left satisfied: the memberships
   * the current-state latch satisfied, and the edited one itself when it may now run (a member
   * joined with no condition, or a condition edited down to one already met).
   */
  private void handOver(WorkEntity campaign, List<String> latched, EntityMembership edge) {
    Set<String> ready = new LinkedHashSet<>(latched);
    ready.addAll(readiness.satisfiedUnclaimed(List.of(edge.id)));
    if (!ready.isEmpty()) {
      satisfied.fire(new CampaignMembersSatisfied(campaign.id, List.copyOf(ready)));
    }
  }

  /**
   * <b>Latches ENTITY_STATUS criteria from the targets' current state</b>, over every milestone the
   * campaign's unclaimed members wait on — run at a start press, so a member whose target finished
   * before anybody pressed (an event nothing was listening for yet, or a condition migrated from
   * before milestones) is not left waiting on an event that is in the past.
   *
   * <p>For every unsatisfied ENTITY_STATUS criterion whose target's current status is at or past the
   * wanted one along {@code REPORTED < REFINED < READY_FOR_DEV < IMPLEMENTING < IMPLEMENTED <
   * VERIFYING < VERIFIED < DONE} (DROPPED is never past anything), the criterion latches with {@code
   * evidence_signature = 'CURRENT_STATE'}, no event id, and the summary {@code "<qid> was already
   * <status> when the campaign started"}. The milestones that came to hold are announced.
   *
   * @return the unclaimed memberships waiting on a milestone that came to hold, which may now run
   */
  public List<String> latchFromCurrentState(String campaignId) {
    return writes.hold("campaign latch from current state", () -> latch(lockCampaign(campaignId)));
  }

  /** The body of {@link #latchFromCurrentState}, inside a write that already holds the campaign row. */
  private List<String> latch(WorkEntity campaign) {
    List<String> unclaimed = new ArrayList<>();
    for (EntityMembership edge : memberships.campaignMembers(campaign.id)) {
      // Locked BEFORE its milestones are read, so a condition edit or a claim that committed while
      // we waited is what the read below sees.
      lock(edge);
      if (edge.claimedAt == null) {
        unclaimed.add(edge.id);
      }
    }
    Map<String, Set<String>> waitingOn = new LinkedHashMap<>();
    for (CampaignMembershipMilestone wait : waits.ofMemberships(unclaimed)) {
      waitingOn.computeIfAbsent(wait.milestoneId, id -> new LinkedHashSet<>()).add(wait.membershipId);
    }
    Set<String> ids = new TreeSet<>(waitingOn.keySet());
    for (String id : ids) {
      milestoneService.lock(id);
    }
    MilestonesChanged changes =
        milestoneService.latchCurrentStateOfIn(ids, "when the campaign started");
    if (changes.isEmpty()) {
      return List.of();
    }
    milestoneService.announce(changes);
    Set<String> latchedOn = new LinkedHashSet<>();
    for (String id : changes.metIds()) {
      latchedOn.addAll(waitingOn.getOrDefault(id, Set.of()));
    }
    for (String membershipId : latchedOn) {
      EntityMembership edge = memberships.findById(membershipId);
      if (edge != null) {
        audit(campaign, "LATCHED_FROM_CURRENT_STATE", edge, null);
      }
    }
    return readiness.satisfiedUnclaimed(latchedOn);
  }

  /**
   * Whether {@code current} is at or past {@code wanted} along the forward walk — {@link
   * MilestoneService#reached}, kept here for its callers.
   */
  public static boolean reached(String current, String wanted) {
    return MilestoneService.reached(current, wanted);
  }

  /**
   * {@code Before "<member title>" in "<campaign title>"} — the name of a member's condition when
   * these doors create it. Titles, not qualified ids, for the migration's reason (V38): the slug
   * lives in another database. A person may rename it.
   */
  public static String conditionName(WorkEntity member, WorkEntity campaign) {
    return "Before \""
        + (member == null ? "?" : member.title)
        + "\" in \""
        + (campaign == null ? "?" : campaign.title)
        + "\"";
  }

  // --- plumbing -------------------------------------------------------------------------------------

  /**
   * The seed: {@code edge} waits on {@code predecessor} reaching VERIFIED, unless it already has —
   * a condition milestone of one group of one seeded ENTITY_STATUS criterion, attached to the
   * membership and the member.
   */
  private void seed(WorkEntity campaign, EntityMembership edge, EntityMembership predecessor) {
    WorkEntity before = entities.findById(predecessor.childId);
    if (before == null || ARRIVED.contains(before.status)) {
      return;
    }
    WorkEntity member = entities.findById(edge.childId);
    Milestone condition =
        milestoneService.createSeededIn(
            campaign.projectId,
            conditionName(member, campaign),
            new CriterionPredicate.EntityStatusIs(before.id, EntityStatus.VERIFIED.name()));
    waitOn(edge, condition);
    milestoneService.announce(milestoneService.recomputeIn(condition.id));
  }

  /** {@code edge} waits on {@code condition}, its generated condition; the member is attached too. */
  private void waitOn(EntityMembership edge, Milestone condition) {
    CampaignMembershipMilestone wait = new CampaignMembershipMilestone();
    wait.membershipId = edge.id;
    wait.milestoneId = condition.id;
    wait.generatedCondition = true;
    waits.persist(wait);
    milestoneService.attachIn(edge.childId, condition.id);
  }

  /**
   * The condition is gone: the generated milestone stops being waited on by the membership and is
   * detached from the member entity, then deleted unless something else still waits on it ({@link
   * MilestoneService#dropGeneratedConditionsIn}).
   */
  private void dropCondition(EntityMembership edge, Milestone condition) {
    milestoneService.dropGeneratedConditionsIn(List.of(edge));
  }

  /** {@code edge}'s generated condition milestone, or null for a member with no condition. */
  private Milestone conditionOf(EntityMembership edge) {
    return waits.generatedOf(edge.id).map(wait -> milestones.findById(wait.milestoneId)).orElse(null);
  }

  /** One milestone's groups, in position order. */
  private List<Group> groupsOf(Milestone milestone) {
    return milestoneService.groupsOfIn(List.of(milestone.id)).getOrDefault(milestone.id, List.of())
        .stream()
        .map(group -> new Group(group.group(), group.criteria()))
        .toList();
  }

  /** The entity ids a member's ENTITY_STATUS criteria target, satisfied or not. */
  private static Set<String> targets(Member member) {
    Set<String> ids = new HashSet<>();
    for (Group group : member.groups()) {
      for (MilestoneCriterion criterion : group.criteria()) {
        if (criterion.kind == CriterionKind.ENTITY_STATUS) {
          ids.add(((CriterionPredicate.EntityStatusIs) criterion.decoded()).entityId());
        }
      }
    }
    return ids;
  }

  /**
   * A membership may be edited while its campaign is not DROPPED (qits-650, qits-1805: a DONE
   * campaign's DONE is derived and follows its members, so its membership stays editable), and
   * never then.
   */
  private void requireEditable(WorkEntity campaign) {
    if (EntityStatus.DROPPED.name().equals(campaign.status)) {
      throw new ConflictException(
          "Campaign "
              + qid(campaign)
              + " is "
              + campaign.status
              + ": its members and their conditions are edited only while it is not DROPPED.");
    }
  }

  /** The 409s about who may join — see {@link #addMember}. */
  private void requireMayJoin(WorkEntity campaign, WorkEntity member) {
    String kind = member.archetype.name().toLowerCase(Locale.ROOT);
    if (!Nesting.mayContain(Archetype.CAMPAIGN, member.archetype)) {
      throw new ConflictException(
          "A campaign cannot gather a " + kind + ": " + qid(member) + " is not work to be run.");
    }
    if (Archetypes.isPlanPiece(member.archetype)) {
      throw new ConflictException(
          "A "
              + kind
              + " is a piece of its epic's plan and runs no phase of its own, so "
              + qid(member)
              + " cannot be a campaign member — gather the epic it belongs to instead.");
    }
    if (!Objects.equals(member.projectId, campaign.projectId)) {
      throw new ConflictException(
          qid(member)
              + " belongs to project "
              + member.projectId
              + "; campaign "
              + qid(campaign)
              + " gathers work of project "
              + campaign.projectId
              + " only.");
    }
  }

  /**
   * The campaign row, locked {@code PESSIMISTIC_WRITE} for the rest of the write — see the class
   * javadoc — or a 404 for an id naming no campaign.
   */
  private WorkEntity lockCampaign(String campaignId) {
    WorkEntity row =
        campaignId == null
            ? null
            : em().find(WorkEntity.class, campaignId, LockModeType.PESSIMISTIC_WRITE);
    if (row == null || row.archetype != Archetype.CAMPAIGN) {
      throw new NotFoundException("Campaign not found: " + campaignId);
    }
    return row;
  }

  /** The campaign row, unlocked — for a read. */
  private WorkEntity campaign(String campaignId) {
    return workEntities.get(Archetype.CAMPAIGN, campaignId);
  }

  /**
   * The CAMPAIGN edge {@code membershipId} of {@code campaignId}, or a 404 — locked {@code
   * PESSIMISTIC_WRITE} (and re-read) when {@code locked}, before anything reads its criteria.
   */
  private EntityMembership membershipOf(String campaignId, String membershipId, boolean locked) {
    EntityMembership edge = membershipId == null ? null : memberships.findById(membershipId);
    if (edge == null
        || edge.kind != MembershipKind.CAMPAIGN
        || !edge.parentId.equals(campaignId)) {
      throw new NotFoundException("No member " + membershipId + " in campaign " + campaignId);
    }
    if (locked) {
      lock(edge);
    }
    return edge;
  }

  /** {@code SELECT … FOR UPDATE} on the membership, refreshing it to what is committed. */
  private void lock(EntityMembership edge) {
    em().refresh(edge, LockModeType.PESSIMISTIC_WRITE);
  }

  /** A campaign's whole view, from rows already read or read now. */
  private Campaign view(WorkEntity campaign) {
    List<EntityMembership> edges = memberships.campaignMembers(campaign.id);
    Map<String, WorkEntity> rows =
        byId(entities.listByIds(edges.stream().map(edge -> edge.childId).toList()));
    Map<String, Member> members = membersOf(edges, rows);
    return new Campaign(
        campaign, starts.startOf(campaign.id).orElse(null), List.copyOf(members.values()));
  }

  /** One member's view. */
  private Member member(EntityMembership edge) {
    WorkEntity row = entities.findById(edge.childId);
    Map<String, WorkEntity> rows = new LinkedHashMap<>();
    if (row != null) {
      rows.put(row.id, row);
    }
    return membersOf(List.of(edge), rows).get(edge.id);
  }

  /**
   * The members of {@code edges}, keyed by membership id in their order: each with the groups of its
   * generated condition and every milestone it waits on — four queries for any number.
   */
  private Map<String, Member> membersOf(List<EntityMembership> edges, Map<String, WorkEntity> rows) {
    List<String> ids = edges.stream().map(edge -> edge.id).toList();
    Map<String, List<Milestone>> waitingOn = milestoneService.ofMembershipsIn(ids);
    Map<String, String> conditionIds = new LinkedHashMap<>();
    for (CampaignMembershipMilestone wait : waits.ofMemberships(ids)) {
      if (wait.generatedCondition) {
        conditionIds.put(wait.membershipId, wait.milestoneId);
      }
    }
    Map<String, List<MilestoneService.Group>> conditions =
        milestoneService.groupsOfIn(new LinkedHashSet<>(conditionIds.values()));
    Map<String, Member> members = new LinkedHashMap<>();
    for (EntityMembership edge : edges) {
      String conditionId = conditionIds.get(edge.id);
      List<Group> groups =
          conditionId == null
              ? List.of()
              : conditions.getOrDefault(conditionId, List.of()).stream()
                  .map(group -> new Group(group.group(), group.criteria()))
                  .toList();
      members.put(
          edge.id,
          new Member(
              edge,
              rows.get(edge.childId),
              groups,
              List.copyOf(waitingOn.getOrDefault(edge.id, List.of()))));
    }
    return members;
  }

  /** The caller's stated groups as the milestone service reads them. */
  private static List<MilestoneService.GroupSpec> toMilestoneSpecs(List<GroupSpec> stated) {
    if (stated == null) {
      return List.of();
    }
    List<MilestoneService.GroupSpec> specs = new ArrayList<>();
    for (GroupSpec group : stated) {
      specs.add(
          group == null || group.criteria() == null
              ? new MilestoneService.GroupSpec(null)
              : new MilestoneService.GroupSpec(
                  group.criteria().stream()
                      .map(
                          criterion ->
                              criterion == null
                                  ? null
                                  : new MilestoneService.CriterionSpec(
                                      criterion.id(), criterion.kind(), criterion.predicate()))
                      .toList()));
    }
    return specs;
  }

  /** {@code qits-412}, or {@code #412} with no qualifier — see {@link EntityQualifier}. */
  private String qid(WorkEntity row) {
    return EntityQualifier.render(qualifier, row);
  }

  /**
   * One audit row on the campaign, naming the change and snapshotting the membership as it now
   * stands — see the class javadoc for why it is the campaign's row and not a new audit word. The
   * snapshot is maps and strings, which serialise with no reflection registration in the native
   * image.
   */
  private void audit(WorkEntity campaign, String change, EntityMembership edge, String changedBy) {
    List<Map<String, Object>> condition = new ArrayList<>();
    for (Group group : member(edge).groups()) {
      List<Map<String, Object>> rows = new ArrayList<>();
      for (MilestoneCriterion criterion : group.criteria()) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", criterion.id);
        row.put("kind", criterion.kind.name());
        row.put("predicate", criterion.predicate);
        row.put("seeded", criterion.seeded);
        row.put("satisfiedAt", text(criterion.satisfiedAt));
        row.put("evidenceSignature", criterion.evidenceSignature);
        row.put("approvedBy", criterion.approvedBy);
        rows.add(row);
      }
      Map<String, Object> audited = new LinkedHashMap<>();
      audited.put("id", group.group().id);
      audited.put("criteria", rows);
      condition.add(audited);
    }
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("change", change);
    snapshot.put("membershipId", edge.id);
    snapshot.put("entityId", edge.childId);
    snapshot.put("position", edge.position);
    snapshot.put("claimedAt", text(edge.claimedAt));
    snapshot.put("joinedRunning", edge.joinedRunning);
    snapshot.put("groups", condition);
    auditService.record(
        AuditEntityType.CAMPAIGN,
        campaign.id,
        campaign.id,
        AuditOperation.UPDATE,
        changedBy,
        snapshot);
  }

  private static String text(Instant instant) {
    return instant == null ? null : instant.toString();
  }

  private void flush() {
    em().flush();
  }

  private EntityManager em() {
    return memberships.getEntityManager();
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new BadRequestException(field + " is required");
    }
  }

  private static Map<String, WorkEntity> byId(List<WorkEntity> rows) {
    Map<String, WorkEntity> indexed = new LinkedHashMap<>();
    for (WorkEntity row : rows) {
      indexed.put(row.id, row);
    }
    return indexed;
  }
}

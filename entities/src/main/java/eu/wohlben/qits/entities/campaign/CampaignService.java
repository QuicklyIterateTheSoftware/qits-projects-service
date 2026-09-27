package eu.wohlben.qits.entities.campaign;

import eu.wohlben.qits.entities.control.Archetypes;
import eu.wohlben.qits.entities.control.AuditService;
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
import eu.wohlben.qits.entities.persistence.EntityMembershipRepository;
import eu.wohlben.qits.entities.persistence.WorkEntityRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * <b>A campaign's membership, its members' conditions, and the latches a person or the current
 * state can set</b> (epic f6c67e74, qits-413) — the authoring half. Nothing here matches an event or
 * dispatches; the evaluator and the executor are later tasks that read and write the same rows.
 *
 * <h2>The rules, each stated once</h2>
 *
 * <ul>
 *   <li><b>A member is an EPIC or a TICKET of the campaign's project</b>, joined once: the kind must
 *       have a lifecycle ({@link Archetypes#legalStatuses}) and must be one a campaign may hold
 *       ({@link Nesting#mayContain}, which refuses a campaign inside a campaign). A campaign edge is
 *       not a tree edge and never goes through {@link Nesting#check}.
 *   <li><b>Membership edits are allowed while the campaign is REPORTED or REFINED</b>, running or
 *       not, and refused with a 409 at IMPLEMENTED, VERIFIED, DONE and DROPPED.
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
 * no campaign-row lock, so the two never wait on each other in a cycle.
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

  /** The forward walk ENTITY_STATUS reads "at or past" along; DROPPED is never past anything. */
  private static final List<EntityStatus> FORWARD =
      List.of(
          EntityStatus.REPORTED,
          EntityStatus.REFINED,
          EntityStatus.IMPLEMENTED,
          EntityStatus.VERIFIED,
          EntityStatus.DONE);

  /** Where a campaign's membership may still be edited. */
  private static final Set<String> EDITABLE =
      Set.of(EntityStatus.REPORTED.name(), EntityStatus.REFINED.name());

  /** Where an approval is refused: the campaign is over. */
  private static final Set<String> FINISHED =
      Set.of(EntityStatus.DONE.name(), EntityStatus.DROPPED.name());

  /** A predecessor in one of these needs no waiting on. */
  private static final Set<String> ARRIVED =
      Set.of(EntityStatus.VERIFIED.name(), EntityStatus.DONE.name());

  @Inject WorkEntityService workEntities;

  @Inject WorkEntityRepository entities;

  @Inject EntityMembershipRepository memberships;

  @Inject CampaignCriterionGroupRepository groups;

  @Inject CampaignCriterionRepository criteria;

  @Inject CampaignStartRecordRepository starts;

  @Inject AuditService auditService;

  @Inject ReadPatience patience;

  @Inject WritePatience writes;

  /** Renders {@code qits-412}; optional — see {@link EntityQualifier}. */
  @Inject Instance<EntityQualifier> qualifier;

  // --- the views ------------------------------------------------------------------------------------

  /** One OR'd group and its AND'd criteria, in position order. */
  public record Group(CampaignCriterionGroup group, List<CampaignCriterion> criteria) {}

  /** A membership, the entity it gathers, and its condition. */
  public record Member(EntityMembership membership, WorkEntity entity, List<Group> groups) {

    /** Whether the condition holds — {@link Conditions#satisfied}. */
    public boolean satisfied() {
      return Conditions.satisfied(groups.stream().map(Group::criteria).toList());
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

  /** An approval's answer: the member, and the memberships whose condition it made hold. */
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
   * <p>Refused with a 409 for a member whose kind has no lifecycle, a kind a campaign may not hold, a
   * member of another project, a duplicate, and a campaign that is no longer editable. When the
   * campaign has an active start, the new member's seed is latched from the current state at once.
   */
  public Member addMember(
      String campaignId, String entityId, Integer position, boolean inFlight, String changedBy) {
    requireText(entityId, "entityId");
    if (position != null && position < 0) {
      throw new BadRequestException("A position cannot be negative: " + position);
    }
    return writes.hold(
        "campaign add member",
        () -> {
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
            seed(edge, predecessor);
          }
          flush();
          audit(campaign, "MEMBER_ADDED", edge, changedBy);
          if (starts.isActive(campaignId)) {
            latch(campaign);
          }
          return member(edge);
        });
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
    writes.run(
        "campaign remove member",
        () -> {
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
          for (Group group : removed.groups()) {
            group.criteria().forEach(criteria::delete);
            groups.delete(group.group());
          }
          int position = gone.position;
          memberships.delete(gone);
          flush();
          memberships.campaignCloseGapAfter(campaignId, position);
        });
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
    List<GroupSpec> specs = stated == null ? List.of() : stated;
    List<String> violations = new ArrayList<>();
    List<List<CriterionPredicate>> decoded = new ArrayList<>();
    for (int g = 0; g < specs.size(); g++) {
      GroupSpec group = specs.get(g);
      List<CriterionPredicate> row = new ArrayList<>();
      decoded.add(row);
      if (group == null || group.criteria() == null || group.criteria().isEmpty()) {
        violations.add(
            "group " + (g + 1) + " has no criteria; an empty group would hold at once — remove it");
        continue;
      }
      for (int c = 0; c < group.criteria().size(); c++) {
        CriterionSpec spec = group.criteria().get(c);
        String where = "group " + (g + 1) + ", criterion " + (c + 1);
        if (spec == null) {
          violations.add(where + ": kind is required");
          row.add(null);
          continue;
        }
        CriterionPredicate.Decoded d = CriterionPredicate.decode(spec.kind(), spec.predicate(), where);
        violations.addAll(d.violations());
        row.add(d.predicate());
      }
    }
    if (!violations.isEmpty()) {
      throw new BadRequestException(String.join("; ", violations));
    }

    return writes.hold(
        "campaign member condition",
        () -> {
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
          List<CampaignCriterionGroup> oldGroups = groups.groupsOf(edge.id);
          Map<String, CampaignCriterion> old = new LinkedHashMap<>();
          for (CampaignCriterion criterion :
              criteria.criteriaOfAll(oldGroups.stream().map(group -> group.id).toList())) {
            old.put(criterion.id, criterion);
          }

          List<String> refused = new ArrayList<>();
          Set<String> restated = new HashSet<>();
          for (int g = 0; g < specs.size(); g++) {
            for (int c = 0; c < specs.get(g).criteria().size(); c++) {
              String where = "group " + (g + 1) + ", criterion " + (c + 1);
              String id = specs.get(g).criteria().get(c).id();
              if (id != null && (!old.containsKey(id) || !restated.add(id))) {
                refused.add(
                    where
                        + ": "
                        + id
                        + (old.containsKey(id)
                            ? " is restated twice"
                            : " is not one of this member's criteria — omit the id to state a new one"));
              }
              if (decoded.get(g).get(c) instanceof CriterionPredicate.EntityStatusIs target
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

          for (int g = 0; g < specs.size(); g++) {
            CampaignCriterionGroup group = new CampaignCriterionGroup();
            group.id = UUID.randomUUID().toString();
            group.membershipId = edge.id;
            group.position = g;
            groups.persist(group);
            for (int c = 0; c < specs.get(g).criteria().size(); c++) {
              CriterionPredicate predicate = decoded.get(g).get(c);
              String id = specs.get(g).criteria().get(c).id();
              CampaignCriterion criterion = id == null ? null : old.remove(id);
              String json = predicate.canonicalJson();
              if (criterion == null) {
                criterion = new CampaignCriterion();
                criterion.id = UUID.randomUUID().toString();
                criterion.kind = predicate.kind();
                criterion.predicate = json;
                criterion.groupId = group.id;
                criterion.position = c;
                criteria.persist(criterion);
              } else {
                if (criterion.kind != predicate.kind() || !criterion.predicate.equals(json)) {
                  criterion.kind = predicate.kind();
                  criterion.predicate = json;
                  criterion.seeded = false;
                  unlatch(criterion);
                }
                criterion.groupId = group.id;
                criterion.position = c;
              }
            }
          }
          old.values().forEach(criteria::delete);
          oldGroups.forEach(groups::delete);
          flush();
          audit(campaign, "CONDITION_SET", edge, changedBy);
          if (starts.isActive(campaignId)) {
            latch(campaign);
          }
          return member(edge);
        });
  }

  /**
   * <b>A person's yes</b>: latches an APPROVAL criterion with {@code approved_by}, {@code
   * approval_note} and {@code satisfied_at}. 409 when the criterion is not APPROVAL, when it is
   * already satisfied (naming who and when), and when the campaign is DONE or DROPPED. Answers the
   * memberships whose condition this made hold, so the executor can act on them.
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
          CampaignCriterion criterion =
              before.groups().stream()
                  .flatMap(group -> group.criteria().stream())
                  .filter(candidate -> candidate.id.equals(criterionId))
                  .findFirst()
                  .orElseThrow(
                      () ->
                          new NotFoundException(
                              "No criterion " + criterionId + " on member " + membershipId));
          if (criterion.kind != CriterionKind.APPROVAL) {
            throw new ConflictException(
                "Criterion "
                    + criterionId
                    + " is "
                    + criterion.kind
                    + ", not APPROVAL: only an approval is latched by a person.");
          }
          if (criterion.satisfiedAt != null) {
            throw new ConflictException(
                "Criterion "
                    + criterionId
                    + " was already approved by "
                    + criterion.approvedBy
                    + " at "
                    + criterion.satisfiedAt
                    + ".");
          }
          boolean held = before.satisfied();
          criterion.approvedBy = actor;
          criterion.approvalNote = note == null || note.isBlank() ? null : note;
          criterion.satisfiedAt = Instant.now();
          flush();
          audit(campaign, "CRITERION_APPROVED", edge, actor);
          Member after = member(edge);
          List<String> satisfied =
              !held && after.satisfied() && edge.claimedAt == null ? List.of(edge.id) : List.of();
          return new Approved(after, satisfied);
        });
  }

  /**
   * <b>Latches ENTITY_STATUS criteria from the targets' current state</b> — the in-flight hole: a
   * member that joined running may finish before the campaign is started, and its
   * EntityTransitioned is then in the past, where event criteria never look.
   *
   * <p>For every unsatisfied ENTITY_STATUS criterion of an unclaimed membership whose target's
   * current status is at or past the wanted one along {@code REPORTED < REFINED < IMPLEMENTED <
   * VERIFIED < DONE} (DROPPED is never past anything), the criterion latches with {@code
   * evidence_signature = 'STATE_AT_START'}, no event id, and the summary {@code "<qid> was already
   * <status> when the campaign started"}. Not history: it reads a row this service owns.
   *
   * @return the memberships whose condition now holds and did not before
   */
  public List<String> latchFromCurrentState(String campaignId) {
    return writes.hold("campaign latch from current state", () -> latch(lockCampaign(campaignId)));
  }

  /** The body of {@link #latchFromCurrentState}, inside a write that already holds the campaign row. */
  private List<String> latch(WorkEntity campaign) {
    List<EntityMembership> unclaimed = new ArrayList<>();
    for (EntityMembership edge : memberships.campaignMembers(campaign.id)) {
      // Locked BEFORE its criteria are read, so a condition edit or a claim that committed while we
      // waited is what the read below sees.
      lock(edge);
      if (edge.claimedAt == null) {
        unclaimed.add(edge);
      }
    }
    Map<String, List<Group>> conditions = conditionsOf(unclaimed);
    Set<String> targetIds = new HashSet<>();
    for (List<Group> condition : conditions.values()) {
      for (Group group : condition) {
        for (CampaignCriterion criterion : group.criteria()) {
          if (criterion.kind == CriterionKind.ENTITY_STATUS && criterion.satisfiedAt == null) {
            targetIds.add(((CriterionPredicate.EntityStatusIs) criterion.decoded()).entityId());
          }
        }
      }
    }
    Map<String, WorkEntity> targets = byId(entities.listByIds(targetIds));

    List<String> became = new ArrayList<>();
    Instant now = Instant.now();
    for (EntityMembership edge : unclaimed) {
      List<Group> condition = conditions.getOrDefault(edge.id, List.of());
      boolean held = Conditions.satisfied(condition.stream().map(Group::criteria).toList());
      boolean latched = false;
      for (Group group : condition) {
        for (CampaignCriterion criterion : group.criteria()) {
          if (criterion.kind != CriterionKind.ENTITY_STATUS || criterion.satisfiedAt != null) {
            continue;
          }
          CriterionPredicate.EntityStatusIs wanted =
              (CriterionPredicate.EntityStatusIs) criterion.decoded();
          WorkEntity target = targets.get(wanted.entityId());
          if (target == null || !reached(target.status, wanted.status())) {
            continue;
          }
          criterion.satisfiedAt = now;
          criterion.evidenceEventId = null;
          criterion.evidenceSignature = CampaignCriterion.STATE_AT_START;
          criterion.evidenceSummary =
              qid(target) + " was already " + target.status + " when the campaign started";
          latched = true;
        }
      }
      if (latched) {
        flush();
        audit(campaign, "LATCHED_FROM_CURRENT_STATE", edge, null);
        if (!held && Conditions.satisfied(condition.stream().map(Group::criteria).toList())) {
          became.add(edge.id);
        }
      }
    }
    return List.copyOf(became);
  }

  /**
   * Whether {@code current} is at or past {@code wanted} along {@link #FORWARD}. DROPPED is never
   * past anything; a DROPPED target is at a wanted DROPPED and nowhere else.
   */
  static boolean reached(String current, String wanted) {
    if (current == null || wanted == null) {
      return false;
    }
    if (EntityStatus.DROPPED.name().equals(current) || EntityStatus.DROPPED.name().equals(wanted)) {
      return current.equals(wanted);
    }
    int at = FORWARD.indexOf(EntityStatus.valueOf(current));
    int want = FORWARD.indexOf(EntityStatus.valueOf(wanted));
    return at >= 0 && want >= 0 && at >= want;
  }

  // --- plumbing -------------------------------------------------------------------------------------

  /** The seed: {@code edge} waits on {@code predecessor} reaching VERIFIED, unless it already has. */
  private void seed(EntityMembership edge, EntityMembership predecessor) {
    WorkEntity before = entities.findById(predecessor.childId);
    if (before == null || ARRIVED.contains(before.status)) {
      return;
    }
    CampaignCriterionGroup group = new CampaignCriterionGroup();
    group.id = UUID.randomUUID().toString();
    group.membershipId = edge.id;
    group.position = 0;
    groups.persist(group);
    CampaignCriterion criterion = new CampaignCriterion();
    criterion.id = UUID.randomUUID().toString();
    criterion.groupId = group.id;
    criterion.position = 0;
    CriterionPredicate predicate =
        new CriterionPredicate.EntityStatusIs(before.id, EntityStatus.VERIFIED.name());
    criterion.kind = predicate.kind();
    criterion.predicate = predicate.canonicalJson();
    criterion.seeded = true;
    criteria.persist(criterion);
  }

  private static void unlatch(CampaignCriterion criterion) {
    criterion.satisfiedAt = null;
    criterion.evidenceEventId = null;
    criterion.evidenceSignature = null;
    criterion.evidenceSummary = null;
    criterion.approvedBy = null;
    criterion.approvalNote = null;
  }

  /** The entity ids a member's ENTITY_STATUS criteria target, satisfied or not. */
  private static Set<String> targets(Member member) {
    Set<String> ids = new HashSet<>();
    for (Group group : member.groups()) {
      for (CampaignCriterion criterion : group.criteria()) {
        if (criterion.kind == CriterionKind.ENTITY_STATUS) {
          ids.add(((CriterionPredicate.EntityStatusIs) criterion.decoded()).entityId());
        }
      }
    }
    return ids;
  }

  /** A membership may be edited while its campaign is REPORTED or REFINED, and never after. */
  private void requireEditable(WorkEntity campaign) {
    if (!EDITABLE.contains(campaign.status)) {
      throw new ConflictException(
          "Campaign "
              + qid(campaign)
              + " is "
              + campaign.status
              + ": its members and their conditions are edited only while it is REPORTED or"
              + " REFINED.");
    }
  }

  /** The 409s about who may join — see {@link #addMember}. */
  private void requireMayJoin(WorkEntity campaign, WorkEntity member) {
    String kind = member.archetype.name().toLowerCase(Locale.ROOT);
    if (!Nesting.mayContain(Archetype.CAMPAIGN, member.archetype)) {
      throw new ConflictException(
          "A campaign cannot gather a " + kind + ": " + qid(member) + " is not work to be run.");
    }
    if (Archetypes.legalStatuses(member.archetype).isEmpty()) {
      throw new ConflictException(
          "A "
              + kind
              + " has no lifecycle of its own, so "
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
    Map<String, List<Group>> conditions = conditionsOf(edges);
    List<Member> members = new ArrayList<>();
    for (EntityMembership edge : edges) {
      members.add(
          new Member(edge, rows.get(edge.childId), conditions.getOrDefault(edge.id, List.of())));
    }
    return new Campaign(campaign, starts.startOf(campaign.id).orElse(null), List.copyOf(members));
  }

  /** One member's view. */
  private Member member(EntityMembership edge) {
    return new Member(
        edge,
        entities.findById(edge.childId),
        conditionsOf(List.of(edge)).getOrDefault(edge.id, List.of()));
  }

  /** The conditions of {@code edges}, keyed by membership id — two queries for any number. */
  private Map<String, List<Group>> conditionsOf(List<EntityMembership> edges) {
    List<CampaignCriterionGroup> found =
        groups.groupsOfAll(edges.stream().map(edge -> edge.id).toList());
    Map<String, List<CampaignCriterion>> byGroup = new LinkedHashMap<>();
    for (CampaignCriterion criterion :
        criteria.criteriaOfAll(found.stream().map(group -> group.id).toList())) {
      byGroup.computeIfAbsent(criterion.groupId, id -> new ArrayList<>()).add(criterion);
    }
    Map<String, List<Group>> conditions = new LinkedHashMap<>();
    for (CampaignCriterionGroup group : found) {
      conditions
          .computeIfAbsent(group.membershipId, id -> new ArrayList<>())
          .add(new Group(group, List.copyOf(byGroup.getOrDefault(group.id, List.of()))));
    }
    return conditions;
  }

  /** {@code qits-412}, or {@code #412} with no qualifier — see {@link EntityQualifier}. */
  private String qid(WorkEntity row) {
    if (row == null) {
      return "an entity";
    }
    if (!qualifier.isUnsatisfied()) {
      try {
        String rendered = qualifier.get().qualifiedId(row);
        if (rendered != null) {
          return rendered;
        }
      } catch (RuntimeException e) {
        // A decoration: it must never fail the write. The fallback below is still unambiguous.
      }
    }
    return "#" + row.number;
  }

  /**
   * One audit row on the campaign, naming the change and snapshotting the membership as it now
   * stands — see the class javadoc for why it is the campaign's row and not a new audit word. The
   * snapshot is maps and strings, which serialise with no reflection registration in the native
   * image.
   */
  private void audit(WorkEntity campaign, String change, EntityMembership edge, String changedBy) {
    List<Map<String, Object>> condition = new ArrayList<>();
    for (Group group : conditionsOf(List.of(edge)).getOrDefault(edge.id, List.of())) {
      List<Map<String, Object>> rows = new ArrayList<>();
      for (CampaignCriterion criterion : group.criteria()) {
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

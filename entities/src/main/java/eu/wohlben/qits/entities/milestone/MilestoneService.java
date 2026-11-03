package eu.wohlben.qits.entities.milestone;

import eu.wohlben.qits.entities.campaign.CriterionKind;
import eu.wohlben.qits.entities.campaign.CriterionPredicate;
import eu.wohlben.qits.entities.campaign.EntityQualifier;
import eu.wohlben.qits.entities.control.EntityStateMachine;
import eu.wohlben.qits.entities.control.ReadPatience;
import eu.wohlben.qits.entities.control.WritePatience;
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
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * <b>Milestones: written, edited, approved, attached</b> (epic qits-615) — the authoring half.
 * Nothing here matches an event: {@link MilestoneEvaluator} latches from events. Every level's
 * date is re-derived by {@link MilestoneDates} at the end of every write that could move it.
 *
 * <h2>The rules, each stated once</h2>
 *
 * <ul>
 *   <li><b>A milestone is project-scoped</b> and has a non-blank display name and at least one
 *       group; a group has at least one criterion (an empty one would hold at once). An
 *       ENTITY_STATUS target is any work entity of the milestone's project.
 *   <li><b>The current-state latch.</b> When a criterion is written, or edited, an ENTITY_STATUS
 *       criterion whose target already stands at or past the wanted status along the forward walk
 *       latches at once, signed {@link MilestoneCriterion#CURRENT_STATE}, no event id. DEPLOYMENT_ACTIVE
 *       and SCM_RELEASE stay forward-only from the criterion's {@code created_at}.
 *   <li><b>An edit replaces the groups whole.</b> A criterion restated by id with the same kind and
 *       predicate keeps its latch and its floor; one restated with a different kind or predicate is
 *       the same row with its latch cleared and its floor moved to now, as a new one starts. Omitted
 *       criteria are deleted. Then the current-state latch, then the dates: a milestone that stops
 *       holding loses its date and is reported un-met ({@code MilestoneUnmet}).
 *   <li><b>An approval</b> latches an APPROVAL criterion with {@code approved_by}, once.
 * </ul>
 *
 * <h2>Locks</h2>
 *
 * <p>Every write takes the <b>milestone row</b> {@code PESSIMISTIC_WRITE} before it reads or writes
 * a group or criterion of it, which is the row {@link MilestoneEvaluator} takes before its latch, so
 * an edit and a latch serialise per milestone. The campaign condition doors ({@code
 * CampaignService}) take the campaign row and the membership row first and the milestone after,
 * and nothing here takes a campaign or membership lock while holding a milestone.
 *
 * <h2>Events</h2>
 *
 * <p>The public writes fire {@link MilestonesChanged} on the CDI bus inside their transaction
 * ({@link #announce}); the service layer publishes it after commit. The {@code ...In} building
 * blocks fire nothing, so a caller composing several (the campaign doors) fires once.
 */
@ApplicationScoped
public class MilestoneService {

  /** The forward walk ENTITY_STATUS reads "at or past" along; DROPPED is never past anything. */
  private static final List<EntityStatus> FORWARD = EntityStateMachine.walk();

  @Inject MilestoneRepository milestones;

  @Inject MilestoneGroupRepository groups;

  @Inject MilestoneCriterionRepository criteria;

  @Inject WorkMilestoneRepository workMilestones;

  @Inject CampaignMembershipMilestoneRepository membershipMilestones;

  @Inject WorkEntityRepository entities;

  @Inject EntityMembershipRepository memberships;

  @Inject MilestoneDates dates;

  @Inject WritePatience writes;

  @Inject ReadPatience patience;

  /** Renders {@code qits-412}; optional — see {@link EntityQualifier}. */
  @Inject Instance<EntityQualifier> qualifier;

  @Inject Event<MilestonesChanged> changed;

  // --- the shapes -----------------------------------------------------------------------------------

  /** One OR'd group of a milestone, as a caller states it. */
  public record GroupSpec(List<CriterionSpec> criteria) {}

  /**
   * One criterion as a caller states it. {@code id} restates an existing criterion of the same
   * milestone, keeping its latch while its kind and predicate stay; null states a new one.
   */
  public record CriterionSpec(String id, String kind, Map<String, Object> predicate) {}

  /** A group and its criteria, in position order. */
  public record Group(MilestoneGroup group, List<MilestoneCriterion> criteria) {}

  /** A milestone, its groups, and what waits on it. */
  public record View(
      Milestone milestone, List<Group> groups, List<String> entityIds, List<String> membershipIds) {}

  /** A write's answer: the milestone as it now stands, and what it met or un-met. */
  public record Edited(View view, MilestonesChanged changes) {}

  /** A stated condition, decoded: one list of predicates per group. */
  public record Decoded(List<GroupSpec> specs, List<List<CriterionPredicate>> predicates) {}

  // --- reads ----------------------------------------------------------------------------------------

  /** One milestone with its groups and criteria and what waits on it, or a 404. */
  public View get(String milestoneId) {
    return patience.hold(
        "milestone read",
        () -> QuarkusTransaction.requiringNew().call(() -> view(require(milestoneId))));
  }

  /** A project's milestones, oldest first, each whole. */
  public List<View> listByProject(String projectId) {
    return patience.hold(
        "milestone list",
        () ->
            QuarkusTransaction.requiringNew()
                .call(() -> views(milestones.ofProject(projectId))));
  }

  /** What {@code entityId} waits on, in the order attached. */
  public List<Milestone> ofEntity(String entityId) {
    return patience.hold(
        "work milestones read",
        () ->
            QuarkusTransaction.requiringNew()
                .call(
                    () -> {
                      List<String> order =
                          workMilestones.ofEntity(entityId).stream()
                              .map(row -> row.milestoneId)
                              .toList();
                      return ordered(milestones.listByIds(order), order);
                    }));
  }

  /** What every entity of {@code entityIds} waits on, keyed by entity id — two queries. */
  @Transactional(TxType.MANDATORY)
  public Map<String, List<Milestone>> ofEntitiesIn(Collection<String> entityIds) {
    List<WorkMilestone> rows = workMilestones.ofEntities(entityIds);
    Map<String, Milestone> byId = new LinkedHashMap<>();
    for (Milestone milestone :
        milestones.listByIds(rows.stream().map(row -> row.milestoneId).toList())) {
      byId.put(milestone.id, milestone);
    }
    Map<String, List<Milestone>> answer = new LinkedHashMap<>();
    for (WorkMilestone row : rows) {
      Milestone milestone = byId.get(row.milestoneId);
      if (milestone != null) {
        answer.computeIfAbsent(row.entityId, id -> new ArrayList<>()).add(milestone);
      }
    }
    return answer;
  }

  /** What every membership of {@code membershipIds} waits on, keyed by membership id. */
  @Transactional(TxType.MANDATORY)
  public Map<String, List<Milestone>> ofMembershipsIn(Collection<String> membershipIds) {
    List<CampaignMembershipMilestone> rows = membershipMilestones.ofMemberships(membershipIds);
    Map<String, Milestone> byId = new LinkedHashMap<>();
    for (Milestone milestone :
        milestones.listByIds(rows.stream().map(row -> row.milestoneId).toList())) {
      byId.put(milestone.id, milestone);
    }
    Map<String, List<Milestone>> answer = new LinkedHashMap<>();
    for (CampaignMembershipMilestone row : rows) {
      Milestone milestone = byId.get(row.milestoneId);
      if (milestone != null) {
        answer.computeIfAbsent(row.membershipId, id -> new ArrayList<>()).add(milestone);
      }
    }
    return answer;
  }

  /** The groups of every milestone in {@code milestoneIds}, keyed by milestone id — two queries. */
  @Transactional(TxType.MANDATORY)
  public Map<String, List<Group>> groupsOfIn(Collection<String> milestoneIds) {
    List<MilestoneGroup> found = groups.groupsOfAll(milestoneIds);
    Map<String, List<MilestoneCriterion>> byGroup = new LinkedHashMap<>();
    for (MilestoneCriterion criterion :
        criteria.criteriaOfAll(found.stream().map(group -> group.id).toList())) {
      byGroup.computeIfAbsent(criterion.groupId, id -> new ArrayList<>()).add(criterion);
    }
    Map<String, List<Group>> answer = new LinkedHashMap<>();
    for (MilestoneGroup group : found) {
      answer
          .computeIfAbsent(group.milestoneId, id -> new ArrayList<>())
          .add(new Group(group, List.copyOf(byGroup.getOrDefault(group.id, List.of()))));
    }
    return answer;
  }

  // --- writes ---------------------------------------------------------------------------------------

  /**
   * A new milestone of {@code projectId} with {@code stated} groups, latched from the current state
   * at once. 400 for a blank name, no groups, an empty group, an invalid predicate, and an
   * ENTITY_STATUS target that is not a work entity of the project.
   */
  public Edited create(
      String projectId, String displayName, List<GroupSpec> stated, String changedBy) {
    requireText(projectId, "projectId");
    String name = requireName(displayName);
    Decoded decoded = decode(stated, false);
    return writes.hold(
        "milestone create",
        () -> {
          Milestone milestone = createIn(projectId, name, decoded);
          MilestonesChanged changes = dates.recompute(List.of(milestone.id));
          announce(changes);
          return new Edited(view(milestone), changes);
        });
  }

  /** Renames a milestone; nothing else moves. 400 for a blank name, 404 for none. */
  public View rename(String milestoneId, String displayName) {
    String name = requireName(displayName);
    return writes.hold(
        "milestone rename",
        () -> {
          Milestone milestone = lock(milestoneId);
          milestone.displayName = name;
          flush();
          return view(milestone);
        });
  }

  /** Replaces a milestone's groups whole — see the class javadoc. */
  public Edited replaceGroups(String milestoneId, List<GroupSpec> stated, String changedBy) {
    Decoded decoded = decode(stated, false);
    return writes.hold(
        "milestone groups",
        () -> {
          Milestone milestone = lock(milestoneId);
          MilestonesChanged changes = replaceGroupsIn(milestone, decoded);
          announce(changes);
          return new Edited(view(milestone), changes);
        });
  }

  /**
   * <b>A person's yes</b>: latches an APPROVAL criterion of the milestone. 404 for a criterion that
   * is not the milestone's; 409 when it is not APPROVAL or already approved (naming who and when).
   */
  public Edited approve(String milestoneId, String criterionId, String note, String actor) {
    requireText(actor, "actor");
    return writes.hold(
        "milestone approve",
        () -> {
          Milestone milestone = lock(milestoneId);
          MilestonesChanged changes = approveIn(milestone, criterionId, note, actor);
          announce(changes);
          return new Edited(view(milestone), changes);
        });
  }

  /**
   * {@code entityId} waits on {@code milestoneId}; idempotent. 404 for either missing, 409 for a
   * milestone of another project.
   */
  public void attach(String entityId, String milestoneId) {
    writes.run(
        "work milestone attach",
        () -> {
          WorkEntity entity = requireEntity(entityId);
          Milestone milestone = require(milestoneId);
          requireSameProject(milestone, entity);
          attachIn(entity.id, milestone.id);
        });
  }

  /** {@code entityId} no longer waits on {@code milestoneId}; idempotent. */
  public void detach(String entityId, String milestoneId) {
    writes.run(
        "work milestone detach",
        () -> workMilestones.deleteById(new WorkMilestone.Key(entityId, milestoneId)));
  }

  /**
   * <b>What a CAMPAIGN membership waits on, set whole</b>: the listed milestones, in order, each of
   * the campaign's project. 404 for an unknown membership or milestone, 409 once claimed (a claim
   * freezes the membership) or for a milestone of another project. A milestone left off that was
   * the membership's generated condition is only detached, never deleted: it may wait elsewhere.
   */
  public List<Milestone> setMembershipMilestones(String membershipId, List<String> milestoneIds) {
    List<String> wanted =
        milestoneIds == null ? List.of() : List.copyOf(new LinkedHashSet<>(milestoneIds));
    return writes.hold(
        "membership milestones",
        () -> {
          EntityMembership edge = membershipId == null ? null : memberships.findById(membershipId);
          if (edge == null || edge.kind != MembershipKind.CAMPAIGN) {
            throw new NotFoundException("No campaign membership " + membershipId);
          }
          em().refresh(edge, LockModeType.PESSIMISTIC_WRITE);
          if (edge.claimedAt != null) {
            throw new ConflictException(
                "Member "
                    + membershipId
                    + " was claimed at "
                    + edge.claimedAt
                    + "; what it waits on is no longer edited.");
          }
          WorkEntity campaign = entities.findById(edge.parentId);
          List<Milestone> found = new ArrayList<>();
          for (String id : wanted) {
            Milestone milestone = require(id);
            if (campaign != null && !Objects.equals(milestone.projectId, campaign.projectId)) {
              throw new ConflictException(
                  "Milestone "
                      + id
                      + " belongs to project "
                      + milestone.projectId
                      + "; the campaign is of project "
                      + campaign.projectId
                      + ".");
            }
            found.add(milestone);
          }
          Set<String> keep = new HashSet<>(wanted);
          for (CampaignMembershipMilestone row : membershipMilestones.ofMembership(edge.id)) {
            if (!keep.remove(row.milestoneId)) {
              membershipMilestones.delete(row);
            }
          }
          for (String id : wanted) {
            if (keep.contains(id)) {
              CampaignMembershipMilestone row = new CampaignMembershipMilestone();
              row.membershipId = edge.id;
              row.milestoneId = id;
              membershipMilestones.persist(row);
            }
          }
          flush();
          return List.copyOf(found);
        });
  }

  /** Fires {@code changes} on the CDI bus, inside the caller's transaction, when not empty. */
  public void announce(MilestonesChanged changes) {
    if (changes != null && !changes.isEmpty()) {
      changed.fire(changes);
    }
  }

  // --- the building blocks, inside a caller's transaction -------------------------------------------

  /**
   * {@code milestoneId}'s row, locked {@code PESSIMISTIC_WRITE} for the rest of the transaction, or a
   * 404 — the first thing every write to a milestone takes.
   */
  @Transactional(TxType.MANDATORY)
  public Milestone lock(String milestoneId) {
    Milestone milestone = milestoneId == null ? null : em().find(Milestone.class, milestoneId);
    if (milestone == null) {
      throw new NotFoundException("Milestone not found: " + milestoneId);
    }
    em().refresh(milestone, LockModeType.PESSIMISTIC_WRITE);
    return milestone;
  }

  /**
   * A new milestone with {@code decoded}'s groups and criteria, latched from the current state, its
   * dates not yet derived (the caller recomputes, so it can fold the answer into its own).
   */
  @Transactional(TxType.MANDATORY)
  public Milestone createIn(String projectId, String displayName, Decoded decoded) {
    requireTargetsIn(projectId, decoded);
    Milestone milestone = new Milestone();
    milestone.id = UUID.randomUUID().toString();
    milestone.projectId = projectId;
    milestone.displayName = displayName;
    milestones.persist(milestone);
    List<MilestoneCriterion> written = new ArrayList<>();
    for (int g = 0; g < decoded.specs().size(); g++) {
      MilestoneGroup group = newGroup(milestone.id, g);
      for (int c = 0; c < decoded.predicates().get(g).size(); c++) {
        written.add(newCriterion(group.id, c, decoded.predicates().get(g).get(c), false));
      }
    }
    flush();
    latchCurrentStateIn(written, "when the criterion was written");
    return milestone;
  }

  /**
   * A new milestone with one group of one seeded criterion — the campaign seed's shape — its dates
   * not yet derived.
   */
  @Transactional(TxType.MANDATORY)
  public Milestone createSeededIn(
      String projectId, String displayName, CriterionPredicate predicate) {
    Milestone milestone = new Milestone();
    milestone.id = UUID.randomUUID().toString();
    milestone.projectId = projectId;
    milestone.displayName = displayName;
    milestones.persist(milestone);
    MilestoneGroup group = newGroup(milestone.id, 0);
    MilestoneCriterion criterion = newCriterion(group.id, 0, predicate, true);
    flush();
    latchCurrentStateIn(List.of(criterion), "when the criterion was written");
    return milestone;
  }

  /**
   * Replaces {@code milestone}'s groups whole (it is locked) and re-derives its dates — see the
   * class javadoc. 400 for a restated id that is not one of its criteria or is restated twice, and
   * for an ENTITY_STATUS target outside the project.
   */
  @Transactional(TxType.MANDATORY)
  public MilestonesChanged replaceGroupsIn(Milestone milestone, Decoded decoded) {
    requireTargetsIn(milestone.projectId, decoded);
    List<GroupSpec> specs = decoded.specs();
    List<MilestoneGroup> oldGroups = groups.groupsOf(milestone.id);
    Map<String, MilestoneCriterion> old = new LinkedHashMap<>();
    for (MilestoneCriterion criterion :
        criteria.criteriaOfAll(oldGroups.stream().map(group -> group.id).toList())) {
      old.put(criterion.id, criterion);
    }
    List<String> refused = new ArrayList<>();
    Set<String> restated = new HashSet<>();
    for (int g = 0; g < specs.size(); g++) {
      for (int c = 0; c < specs.get(g).criteria().size(); c++) {
        String id = specs.get(g).criteria().get(c).id();
        if (id != null && (!old.containsKey(id) || !restated.add(id))) {
          refused.add(
              "group "
                  + (g + 1)
                  + ", criterion "
                  + (c + 1)
                  + ": "
                  + id
                  + (old.containsKey(id)
                      ? " is restated twice"
                      : " is not one of this milestone's criteria — omit the id to state a new one"));
        }
      }
    }
    if (!refused.isEmpty()) {
      throw new BadRequestException(String.join("; ", refused));
    }

    Instant now = Instant.now();
    List<MilestoneCriterion> fresh = new ArrayList<>();
    for (int g = 0; g < specs.size(); g++) {
      MilestoneGroup group = newGroup(milestone.id, g);
      for (int c = 0; c < specs.get(g).criteria().size(); c++) {
        CriterionPredicate predicate = decoded.predicates().get(g).get(c);
        String id = specs.get(g).criteria().get(c).id();
        MilestoneCriterion criterion = id == null ? null : old.remove(id);
        if (criterion == null) {
          fresh.add(newCriterion(group.id, c, predicate, false));
          continue;
        }
        String json = predicate.canonicalJson();
        if (criterion.kind != predicate.kind() || !criterion.predicate.equals(json)) {
          criterion.kind = predicate.kind();
          criterion.predicate = json;
          criterion.seeded = false;
          criterion.unlatch();
          criterion.createdAt = now;
          fresh.add(criterion);
        }
        criterion.groupId = group.id;
        criterion.position = c;
      }
    }
    old.values().forEach(criteria::delete);
    flush();
    oldGroups.forEach(groups::delete);
    flush();
    latchCurrentStateIn(fresh, "when the criterion was written");
    return dates.recompute(List.of(milestone.id));
  }

  /** The approval on a locked milestone, its dates re-derived — see {@link #approve}. */
  @Transactional(TxType.MANDATORY)
  public MilestonesChanged approveIn(
      Milestone milestone, String criterionId, String note, String actor) {
    MilestoneCriterion criterion = criterionId == null ? null : criteria.findById(criterionId);
    MilestoneGroup group = criterion == null ? null : groups.findById(criterion.groupId);
    if (group == null || !group.milestoneId.equals(milestone.id)) {
      throw new NotFoundException(
          "No criterion " + criterionId + " on milestone " + milestone.id);
    }
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
    criterion.approvedBy = actor;
    criterion.approvalNote = note == null || note.isBlank() ? null : note;
    criterion.satisfiedAt = Instant.now();
    flush();
    return dates.recompute(List.of(milestone.id));
  }

  /**
   * <b>The current-state latch</b>: every unsatisfied ENTITY_STATUS criterion of {@code written}
   * whose target already stands at or past the wanted status latches now, signed {@link
   * MilestoneCriterion#CURRENT_STATE}, no event id, summary {@code "<qid> was already <status>
   * <when>"}. Answers the milestone ids a latch landed on; the dates are the caller's to re-derive.
   */
  @Transactional(TxType.MANDATORY)
  public Set<String> latchCurrentStateIn(Collection<MilestoneCriterion> written, String when) {
    Set<String> targetIds = new HashSet<>();
    for (MilestoneCriterion criterion : written) {
      if (criterion.kind == CriterionKind.ENTITY_STATUS && criterion.satisfiedAt == null) {
        targetIds.add(((CriterionPredicate.EntityStatusIs) criterion.decoded()).entityId());
      }
    }
    if (targetIds.isEmpty()) {
      return Set.of();
    }
    Map<String, WorkEntity> targets = new LinkedHashMap<>();
    for (WorkEntity row : entities.listByIds(targetIds)) {
      targets.put(row.id, row);
    }
    Set<String> latchedOn = new LinkedHashSet<>();
    Instant now = Instant.now();
    for (MilestoneCriterion criterion : written) {
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
      criterion.evidenceSignature = MilestoneCriterion.CURRENT_STATE;
      criterion.evidenceSummary =
          EntityQualifier.render(qualifier, target) + " was already " + target.status + " " + when;
      MilestoneGroup group = groups.findById(criterion.groupId);
      if (group != null) {
        latchedOn.add(group.milestoneId);
      }
    }
    flush();
    return latchedOn;
  }

  /**
   * The current-state latch over every unsatisfied criterion of {@code milestoneIds}, which the
   * caller has locked — what a campaign start runs over its members' milestones. Answers what came
   * to hold.
   */
  @Transactional(TxType.MANDATORY)
  public MilestonesChanged latchCurrentStateOfIn(Collection<String> milestoneIds, String when) {
    List<MilestoneCriterion> open = new ArrayList<>();
    for (List<Group> condition : groupsOfIn(milestoneIds).values()) {
      for (Group group : condition) {
        open.addAll(group.criteria());
      }
    }
    return dates.recompute(latchCurrentStateIn(open, when));
  }

  /** Re-derives one milestone's dates — {@link MilestoneDates#recompute}. */
  @Transactional(TxType.MANDATORY)
  public MilestonesChanged recomputeIn(String milestoneId) {
    return dates.recompute(List.of(milestoneId));
  }

  /** {@code entityId} waits on {@code milestoneId}, unless it already does. */
  @Transactional(TxType.MANDATORY)
  public void attachIn(String entityId, String milestoneId) {
    if (workMilestones.findById(new WorkMilestone.Key(entityId, milestoneId)) == null) {
      WorkMilestone row = new WorkMilestone();
      row.entityId = entityId;
      row.milestoneId = milestoneId;
      workMilestones.persist(row);
    }
  }

  /**
   * Whether {@code current} is at or past {@code wanted} along the forward walk. DROPPED is never
   * past anything; a DROPPED target is at a wanted DROPPED and nowhere else.
   */
  public static boolean reached(String current, String wanted) {
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

  /**
   * <b>Decodes a stated condition</b>, every violation listed at once in one 400, before any row is
   * read: an empty group, a missing kind, an invalid predicate — and no groups at all unless {@code
   * mayBeEmpty}.
   */
  public static Decoded decode(List<GroupSpec> stated, boolean mayBeEmpty) {
    List<GroupSpec> specs = stated == null ? List.of() : List.copyOf(stated);
    List<String> violations = new ArrayList<>();
    if (specs.isEmpty() && !mayBeEmpty) {
      violations.add("a milestone needs at least one group of criteria");
    }
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
    return new Decoded(specs, decoded);
  }

  // --- plumbing -------------------------------------------------------------------------------------

  private MilestoneGroup newGroup(String milestoneId, int position) {
    MilestoneGroup group = new MilestoneGroup();
    group.id = UUID.randomUUID().toString();
    group.milestoneId = milestoneId;
    group.position = position;
    groups.persist(group);
    return group;
  }

  private MilestoneCriterion newCriterion(
      String groupId, int position, CriterionPredicate predicate, boolean seeded) {
    MilestoneCriterion criterion = new MilestoneCriterion();
    criterion.id = UUID.randomUUID().toString();
    criterion.groupId = groupId;
    criterion.position = position;
    criterion.kind = predicate.kind();
    criterion.predicate = predicate.canonicalJson();
    criterion.seeded = seeded;
    criteria.persist(criterion);
    return criterion;
  }

  /** Every ENTITY_STATUS target names a work entity of {@code projectId}, or a 400 naming them. */
  private void requireTargetsIn(String projectId, Decoded decoded) {
    Map<String, String> wanted = new LinkedHashMap<>();
    for (int g = 0; g < decoded.predicates().size(); g++) {
      for (int c = 0; c < decoded.predicates().get(g).size(); c++) {
        if (decoded.predicates().get(g).get(c) instanceof CriterionPredicate.EntityStatusIs target) {
          wanted.putIfAbsent(
              target.entityId(), "group " + (g + 1) + ", criterion " + (c + 1));
        }
      }
    }
    if (wanted.isEmpty()) {
      return;
    }
    Map<String, WorkEntity> found = new LinkedHashMap<>();
    for (WorkEntity row : entities.listByIds(wanted.keySet())) {
      found.put(row.id, row);
    }
    List<String> refused = new ArrayList<>();
    for (Map.Entry<String, String> entry : wanted.entrySet()) {
      WorkEntity row = found.get(entry.getKey());
      if (row == null || !Objects.equals(row.projectId, projectId)) {
        refused.add(
            entry.getValue()
                + ": target "
                + entry.getKey()
                + " is not a work entity of this milestone's project");
      }
    }
    if (!refused.isEmpty()) {
      throw new BadRequestException(String.join("; ", refused));
    }
  }

  private Milestone require(String milestoneId) {
    Milestone milestone = milestoneId == null ? null : milestones.findById(milestoneId);
    if (milestone == null) {
      throw new NotFoundException("Milestone not found: " + milestoneId);
    }
    return milestone;
  }

  private WorkEntity requireEntity(String entityId) {
    WorkEntity row = entityId == null ? null : entities.findById(entityId);
    if (row == null) {
      throw new NotFoundException("Entity not found: " + entityId);
    }
    return row;
  }

  private static void requireSameProject(Milestone milestone, WorkEntity entity) {
    if (!Objects.equals(milestone.projectId, entity.projectId)) {
      throw new ConflictException(
          "Milestone "
              + milestone.id
              + " belongs to project "
              + milestone.projectId
              + "; "
              + entity.id
              + " is of project "
              + entity.projectId
              + ".");
    }
  }

  private View view(Milestone milestone) {
    return views(List.of(milestone)).get(0);
  }

  private List<View> views(List<Milestone> found) {
    if (found.isEmpty()) {
      return List.of();
    }
    List<String> ids = found.stream().map(milestone -> milestone.id).toList();
    Map<String, List<Group>> conditions = groupsOfIn(ids);
    Map<String, List<String>> waitingEntities = new LinkedHashMap<>();
    Map<String, List<String>> waitingMemberships = new LinkedHashMap<>();
    for (String id : ids) {
      waitingEntities.put(
          id, workMilestones.ofMilestone(id).stream().map(row -> row.entityId).toList());
      waitingMemberships.put(
          id, membershipMilestones.ofMilestone(id).stream().map(row -> row.membershipId).toList());
    }
    return found.stream()
        .map(
            milestone ->
                new View(
                    milestone,
                    conditions.getOrDefault(milestone.id, List.of()),
                    waitingEntities.get(milestone.id),
                    waitingMemberships.get(milestone.id)))
        .toList();
  }

  private static List<Milestone> ordered(List<Milestone> found, List<String> order) {
    return found.stream()
        .sorted(Comparator.comparingInt(milestone -> order.indexOf(milestone.id)))
        .toList();
  }

  private static String requireName(String displayName) {
    if (displayName == null || displayName.isBlank()) {
      throw new BadRequestException("displayName is required");
    }
    return displayName.strip();
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new BadRequestException(field + " is required");
    }
  }

  private void flush() {
    em().flush();
  }

  private EntityManager em() {
    return milestones.getEntityManager();
  }
}

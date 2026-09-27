package eu.wohlben.qits.entities.campaign;

import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.persistence.EntityMembershipRepository;
import eu.wohlben.qits.entities.persistence.WorkEntityRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>The pure half of the criteria evaluator</b> (epic f6c67e74, qits-415): an observed fact in,
 * the criteria it latches written, and the CAMPAIGN memberships whose condition now holds out — for
 * the executor to act on. No bus, no dispatch, no clock: the listener decodes the event into an
 * {@link Observation.Observed} and hands it here, inside its own transaction.
 *
 * <h2>{@link #observe}, step by step</h2>
 *
 * <ol>
 *   <li><b>Candidates</b>, in one query: unsatisfied criteria of the observation's kind, on CAMPAIGN
 *       memberships that are <b>unclaimed</b>, whose campaign has a {@code campaign_start} row (it
 *       was started at least once — active or paused, because a latch records a fact, not a
 *       dispatch) and is neither DONE nor DROPPED.
 *   <li><b>The natural key is filtered in the query</b> — the entity id, the application or the
 *       repository — by extracting that one field from the canonical predicate JSON with postgres'
 *       {@code cast(predicate as jsonb) ->> '<field>'}. Chosen over a {@code like} because it is
 *       exact (no wildcard or JSON-escaping to get right; an application name may contain {@code
 *       _}) and over generated columns because it needs no migration; the scan it costs is bounded
 *       by {@code idx_campaign_criterion_outstanding}, which already narrows to unsatisfied rows of
 *       one kind. {@link CriterionPredicate#matches} still decides; the filter only narrows.
 *   <li><b>Forward-only</b>, also in the query: a candidate whose floor — {@code
 *       greatest(campaign_start.first_started_at, criterion.created_at)} — is after {@code
 *       occurredAt} is dropped. Event kinds never look at history; the in-flight hole is {@link
 *       CampaignService#latchFromCurrentState}'s.
 *   <li><b>Latch</b> each match with one guarded UPDATE: {@code ... where id = ? and satisfied_at is
 *       null and kind = ? and predicate = ?}. Zero rows is a no-op — a redelivered event, or a
 *       condition edit that replaced the criterion since the read. The {@code kind}/{@code
 *       predicate} guard is what keeps that second case honest: without it a criterion whose
 *       predicate was rewritten (and unlatched) between our read and our write would be latched
 *       with evidence for the predicate it no longer has.
 *   <li><b>Return</b> the memberships a latch landed on whose DNF now holds — {@link
 *       #satisfiedUnclaimed}, the one "satisfied memberships" step {@link CampaignService#approve}
 *       and {@link CampaignService#latchFromCurrentState} return through too. One evaluation path,
 *       three sources.
 * </ol>
 *
 * <h2>Locks: none taken, deliberately</h2>
 *
 * <p>{@link CampaignService} takes the campaign's entity row {@code PESSIMISTIC_WRITE}, then the
 * membership rows. This class takes <b>neither</b>. The latch is a single-row UPDATE whose {@code
 * where} carries its own preconditions, so it needs no picture held stable, and the listener's
 * transaction also holds the {@code consumed_event} claim: taking a campaign row there would
 * serialise every event against every authoring write on the campaign, for the life of the claim.
 * The only lock this class ends up holding is the UPDATE's own lock on the criterion row, which a
 * concurrent condition edit waits on (or we wait on it) — never the campaign or membership row, so
 * no cycle with {@link CampaignService}'s order is possible through those. Two transactions both
 * writing several criterion rows can still meet in opposite orders; the UPDATEs here run in
 * criterion-id order to keep that narrow, and postgres' deadlock detector aborts one side, which for
 * the listener is a rollback and a redelivery — the latch is idempotent by construction.
 *
 * <p><b>A claimed membership is not a candidate</b>, and one claimed after the read and before the
 * latch is latched anyway. That is harmless: a latch is a fact, a claim freezes the condition
 * against edits and not against facts, and the executor decides on its own re-check.
 */
@ApplicationScoped
public class CampaignEvaluator {

  private static final String CANDIDATES =
      """
      select c.id, g.membership_id, c.predicate
        from campaign_criterion c
        join campaign_criterion_group g on g.id = c.group_id
        join entity_membership m on m.id = g.membership_id
        join entity campaign on campaign.id = m.parent_id
        join campaign_start s on s.campaign_id = m.parent_id
       where c.kind = :kind
         and c.satisfied_at is null
         and m.kind = 'CAMPAIGN'
         and m.claimed_at is null
         and campaign.status not in ('DONE', 'DROPPED')
         and cast(c.predicate as jsonb) ->> '%s' = :key
         and greatest(s.first_started_at, c.created_at) <= :occurredAt
       order by c.id
      """;

  private static final String LATCH =
      """
      update campaign_criterion
         set satisfied_at = :at, evidence_event_id = :eventId,
             evidence_signature = :signature, evidence_summary = :summary
       where id = :id and satisfied_at is null and kind = :kind and predicate = :predicate
      """;

  private static final String CONDITIONS =
      """
      select m.id, g.id, c.id, c.satisfied_at is not null
        from entity_membership m
        left join campaign_criterion_group g on g.membership_id = m.id
        left join campaign_criterion c on c.group_id = g.id
       where m.id in (:ids)
         and m.kind = 'CAMPAIGN'
         and m.claimed_at is null
      """;

  private static final String MEMBERSHIPS_OF =
      """
      select m.id
        from entity_membership m
        join entity campaign on campaign.id = m.parent_id
        join campaign_start s on s.campaign_id = m.parent_id
       where m.child_id = :entityId
         and m.kind = 'CAMPAIGN'
         and m.claimed_at is null
         and campaign.status not in ('DONE', 'DROPPED')
       order by m.parent_id, m.id
      """;

  @Inject EntityMembershipRepository memberships;

  @Inject WorkEntityRepository entities;

  /** Renders {@code qits-412} in an evidence summary; optional — see {@link EntityQualifier}. */
  @Inject Instance<EntityQualifier> qualifier;

  /**
   * <b>Latches every criterion {@code observed} satisfies</b> and answers the memberships whose
   * condition now holds — see the class javadoc. Runs in the caller's transaction and refuses to
   * run outside one: the latches must commit or roll back with the listener's claim of the event.
   */
  @Transactional(TxType.MANDATORY)
  public List<String> observe(Observation.Observed observed) {
    Observation observation = observed.observation();
    String key = naturalKey(observation);
    if (key == null) {
      return List.of();
    }
    CriterionKind kind = observation.kind();
    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        em().createNativeQuery(CANDIDATES.formatted(naturalKeyField(kind)))
            .setParameter("kind", kind.name())
            .setParameter("key", key)
            .setParameter("occurredAt", observed.occurredAt())
            .getResultList();

    Set<String> latchedOn = new LinkedHashSet<>();
    String summary = null;
    for (Object[] row : rows) {
      String criterionId = (String) row[0];
      String membershipId = (String) row[1];
      String predicate = (String) row[2];
      if (!CriterionPredicate.parse(kind, predicate).matches(observation)) {
        continue;
      }
      if (summary == null) {
        summary = summary(observed);
      }
      int updated =
          em().createNativeQuery(LATCH)
              .setParameter("at", observed.occurredAt())
              .setParameter("eventId", observed.eventId())
              .setParameter("signature", observed.signature())
              .setParameter("summary", summary)
              .setParameter("id", criterionId)
              .setParameter("kind", kind.name())
              .setParameter("predicate", predicate)
              .executeUpdate();
      if (updated == 1) {
        latchedOn.add(membershipId);
      }
    }
    return satisfiedUnclaimed(latchedOn);
  }

  /**
   * <b>The one "satisfied memberships" step</b>: of {@code membershipIds}, the CAMPAIGN memberships
   * that are unclaimed and whose DNF holds ({@link Conditions}), in the order given. Read from the
   * database after a flush, so latches written through the session and through a statement are
   * both seen. It does not look at the campaign's start: whether to act is the executor's decision
   * on its own re-check, and {@link CampaignService#approve} answers through here on a campaign
   * that may not have been started yet.
   */
  @Transactional(TxType.MANDATORY)
  public List<String> satisfiedUnclaimed(Collection<String> membershipIds) {
    if (membershipIds == null || membershipIds.isEmpty()) {
      return List.of();
    }
    em().flush();
    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        em().createNativeQuery(CONDITIONS)
            .setParameter("ids", List.copyOf(new LinkedHashSet<>(membershipIds)))
            .getResultList();
    // membership -> group -> latched flags; a membership with no groups still gets an entry.
    Map<String, Map<String, List<Boolean>>> conditions = new LinkedHashMap<>();
    for (Object[] row : rows) {
      Map<String, List<Boolean>> groups =
          conditions.computeIfAbsent((String) row[0], id -> new LinkedHashMap<>());
      if (row[1] == null) {
        continue;
      }
      List<Boolean> criteria = groups.computeIfAbsent((String) row[1], id -> new ArrayList<>());
      if (row[2] != null) {
        criteria.add(Boolean.TRUE.equals(row[3]));
      }
    }
    List<String> satisfied = new ArrayList<>();
    for (String membershipId : new LinkedHashSet<>(membershipIds)) {
      Map<String, List<Boolean>> groups = conditions.get(membershipId);
      if (groups != null && Conditions.holds(groups.values(), Boolean::booleanValue)) {
        satisfied.add(membershipId);
      }
    }
    return List.copyOf(satisfied);
  }

  /**
   * <b>The unclaimed CAMPAIGN memberships whose MEMBER is {@code entityId} and whose condition
   * holds</b>, of campaigns that have been started and are neither DONE nor DROPPED — what the
   * executor retries off the member's own transition, since a member refused dispatch earlier
   * (blocked, wrong status) may be dispatchable now while no criterion of its will ever latch again.
   */
  @Transactional(TxType.MANDATORY)
  public List<String> satisfiedUnclaimedMembershipsOf(String entityId) {
    if (entityId == null) {
      return List.of();
    }
    @SuppressWarnings("unchecked")
    List<String> ids =
        em().createNativeQuery(MEMBERSHIPS_OF).setParameter("entityId", entityId).getResultList();
    return satisfiedUnclaimed(ids);
  }

  // --- plumbing -------------------------------------------------------------------------------------

  /** The predicate field the natural key is stored under, per kind. */
  private static String naturalKeyField(CriterionKind kind) {
    return switch (kind) {
      case ENTITY_STATUS -> "entityId";
      case DEPLOYMENT_ACTIVE -> "applicationName";
      case SCM_RELEASE -> "repositoryName";
      case APPROVAL -> throw new IllegalArgumentException("APPROVAL is latched by no observation");
    };
  }

  /** The observation's value for {@link #naturalKeyField}; null (or blank) matches nothing. */
  private static String naturalKey(Observation observation) {
    String key =
        switch (observation) {
          case Observation.EntityReached reached -> reached.entityId();
          case Observation.DeploymentWentActive active -> active.applicationName();
          case Observation.Released released -> released.repositoryName();
        };
    return key == null || key.isBlank() ? null : key;
  }

  /**
   * The one-line evidence, per the catalogue: {@code EntityTransitioned: qits-412 IMPLEMENTED →
   * VERIFIED}, {@code DeploymentActive: qits-projects 2026.1001.91244 in dev}, {@code SCMRelease:
   * qits-qits 2026.1001.93000}.
   */
  private String summary(Observation.Observed observed) {
    String body =
        switch (observed.observation()) {
          case Observation.EntityReached reached -> {
            WorkEntity row = entities.findById(reached.entityId());
            String who =
                row == null ? reached.entityId() : EntityQualifier.render(qualifier, row);
            yield who
                + " "
                + (reached.statusBefore() == null ? "" : reached.statusBefore() + " ")
                + "→ "
                + reached.status();
          }
          case Observation.DeploymentWentActive active ->
              active.applicationName()
                  + versionPart(active.version())
                  + (active.environmentName() == null || active.environmentName().isBlank()
                      ? ""
                      : " in " + active.environmentName());
          case Observation.Released released ->
              released.repositoryName() + versionPart(released.version());
        };
    return observed.signature() + ": " + body;
  }

  private static String versionPart(String version) {
    return version == null || version.isBlank() ? "" : " " + version;
  }

  private EntityManager em() {
    return memberships.getEntityManager();
  }
}

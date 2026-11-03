package eu.wohlben.qits.entities.milestone;

import eu.wohlben.qits.entities.campaign.CriterionKind;
import eu.wohlben.qits.entities.campaign.CriterionPredicate;
import eu.wohlben.qits.entities.campaign.EntityQualifier;
import eu.wohlben.qits.entities.campaign.Observation;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.persistence.WorkEntityRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * <b>The criteria evaluator, over milestones</b> (epic qits-615, replacing the campaign-era {@code
 * CampaignEvaluator}): an observed fact in, the criteria it latches written, the groups' and
 * milestones' dates re-derived, and the milestones that came to hold out — for the bus listener to
 * publish as {@code MilestoneMet}. No bus, no dispatch: the listener decodes the event into an
 * {@link Observation.Observed} and hands it here, inside its own transaction.
 *
 * <h2>{@link #observe}, step by step</h2>
 *
 * <ol>
 *   <li><b>Candidates</b>, in one query: unsatisfied criteria of the observation's kind, of any
 *       milestone, whatever waits on it — a milestone is evaluated from its own creation,
 *       independent of any campaign. The natural key (the entity id, the application, the
 *       repository) is filtered in the query from the canonical predicate JSON, {@code
 *       cast(predicate as jsonb) ->> '<field>'}; {@link CriterionPredicate#matches} still decides.
 *   <li><b>Forward-only</b>, also in the query: a candidate whose floor — the criterion's own {@code
 *       created_at} — is after {@code occurredAt} is dropped. Event kinds never look at history;
 *       the hole is closed by the current-state latch at create and edit ({@link
 *       MilestoneService}).
 *   <li><b>Lock</b> the matched criteria's milestones, {@code FOR UPDATE}, in id order — the same
 *       row every authoring write takes before it touches a criterion, so a latch and an edit of
 *       one milestone serialise and never meet in opposite orders.
 *   <li><b>Latch</b> each match with one guarded UPDATE: {@code ... where id = ? and satisfied_at is
 *       null and kind = ? and predicate = ?}. Zero rows is a no-op — a redelivered event, or an
 *       edit that replaced or rewrote the criterion since the read.
 *   <li><b>Re-derive</b> the dates of every milestone a latch landed on ({@link MilestoneDates})
 *       and answer what came to hold. A latch never un-meets anything.
 * </ol>
 */
@ApplicationScoped
public class MilestoneEvaluator {

  private static final String CANDIDATES =
      """
      select c.id, g.milestone_id, c.predicate
        from milestone_criterion c
        join milestone_group g on g.id = c.group_id
       where c.kind = :kind
         and c.satisfied_at is null
         and cast(c.predicate as jsonb) ->> '%s' = :key
         and c.created_at <= :occurredAt
       order by c.id
      """;

  private static final String LOCK =
      "select id from milestone where id in (:ids) order by id for update";

  private static final String LATCH =
      """
      update milestone_criterion
         set satisfied_at = :at, evidence_event_id = :eventId,
             evidence_signature = :signature, evidence_summary = :summary
       where id = :id and satisfied_at is null and kind = :kind and predicate = :predicate
      """;

  @Inject MilestoneRepository milestones;

  @Inject MilestoneDates dates;

  @Inject WorkEntityRepository entities;

  /** Renders {@code qits-412} in an evidence summary; optional — see {@link EntityQualifier}. */
  @Inject Instance<EntityQualifier> qualifier;

  /**
   * <b>Latches every criterion {@code observed} satisfies</b> and answers the milestones that came
   * to hold — see the class javadoc. Runs in the caller's transaction and refuses to run outside
   * one: the latches and the dates must commit or roll back together.
   */
  @Transactional(TxType.MANDATORY)
  public MilestonesChanged observe(Observation.Observed observed) {
    Observation observation = observed.observation();
    String key = naturalKey(observation);
    if (key == null) {
      return MilestonesChanged.none();
    }
    CriterionKind kind = observation.kind();
    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        em().createNativeQuery(CANDIDATES.formatted(naturalKeyField(kind)))
            .setParameter("kind", kind.name())
            .setParameter("key", key)
            .setParameter("occurredAt", observed.occurredAt())
            .getResultList();

    List<Object[]> matched = new ArrayList<>();
    Set<String> milestoneIds = new TreeSet<>();
    for (Object[] row : rows) {
      if (CriterionPredicate.parse(kind, (String) row[2]).matches(observation)) {
        matched.add(row);
        milestoneIds.add((String) row[1]);
      }
    }
    if (matched.isEmpty()) {
      return MilestonesChanged.none();
    }
    em().createNativeQuery(LOCK).setParameter("ids", List.copyOf(milestoneIds)).getResultList();

    String summary = summary(observed);
    Set<String> latchedOn = new LinkedHashSet<>();
    for (Object[] row : matched) {
      int updated =
          em().createNativeQuery(LATCH)
              .setParameter("at", observed.occurredAt())
              .setParameter("eventId", observed.eventId())
              .setParameter("signature", observed.signature())
              .setParameter("summary", summary)
              .setParameter("id", row[0])
              .setParameter("kind", kind.name())
              .setParameter("predicate", row[2])
              .executeUpdate();
      if (updated == 1) {
        latchedOn.add((String) row[1]);
      }
    }
    return dates.recompute(latchedOn);
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
   * The one-line evidence: {@code EntityTransitioned: qits-412 IMPLEMENTED → VERIFIED}, {@code
   * DeploymentActive: qits-projects 2026.1001.91244 in dev}, {@code ReleaseRequestStateChanged:
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
    return milestones.getEntityManager();
  }
}

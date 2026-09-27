package eu.wohlben.qits.entities.campaign;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * <b>A fact a campaign criterion can wait on</b>, in neutral records the bus side builds from an
 * event — so this module never sees an {@code EventFrame} and depends on no event vocabulary. One
 * record per event-sourced {@link CriterionKind}; APPROVAL has none, since a person's yes is not an
 * event.
 *
 * <p>The field names are the catalogue's (dossier page "EntityTransitioned, the criterion catalogue
 * and version order"); {@link CriterionPredicate#matches} is the rule over them.
 */
public sealed interface Observation {

  /** The criterion kind this fact can latch. */
  CriterionKind kind();

  /**
   * One entity of an {@code EntityTransitioned} batch. {@code statusBefore} is null for a created
   * row, and equal to {@code status} for a re-announcement — which satisfies nothing.
   */
  record EntityReached(String entityId, String projectId, String status, String statusBefore)
      implements Observation {
    @Override
    public CriterionKind kind() {
      return CriterionKind.ENTITY_STATUS;
    }
  }

  /** A {@code DeploymentActive}: {@code version} is blank on some old deployments. */
  record DeploymentWentActive(String applicationName, String environmentName, String version)
      implements Observation {
    @Override
    public CriterionKind kind() {
      return CriterionKind.DEPLOYMENT_ACTIVE;
    }
  }

  /** An {@code SCMRelease}. */
  record Released(String projectId, String repositoryName, String version) implements Observation {
    @Override
    public CriterionKind kind() {
      return CriterionKind.SCM_RELEASE;
    }
  }

  /**
   * An observation as it travels to {@link CampaignEvaluator#observe}: the fact, and the event that
   * carried it — its id (the latch's {@code evidence_event_id}), its signature ({@code
   * evidence_signature}) and when it occurred (the forward-only comparison). All four are required:
   * a latch without an event id is refused by {@code ck_campaign_criterion_evidence}, and a fact
   * with no time cannot be placed after a start.
   */
  record Observed(Observation observation, UUID eventId, String signature, Instant occurredAt) {
    public Observed {
      Objects.requireNonNull(observation, "observation");
      Objects.requireNonNull(eventId, "eventId");
      Objects.requireNonNull(signature, "signature");
      Objects.requireNonNull(occurredAt, "occurredAt");
    }
  }
}

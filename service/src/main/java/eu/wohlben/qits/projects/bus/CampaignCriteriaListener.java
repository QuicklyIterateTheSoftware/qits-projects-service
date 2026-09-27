package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.entities.campaign.CampaignEvaluator;
import eu.wohlben.qits.entities.campaign.Observation;
import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.campaignhost.CampaignCriteriaConsumerHealth;
import eu.wohlben.qits.projects.campaignhost.CampaignMemberSatisfied;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.jboss.logging.Logger;

/**
 * <b>The bus side of the criteria evaluator</b> (epic f6c67e74, qits-416): decode the three
 * event-sourced facts a campaign criterion can wait on, hand each to {@link
 * CampaignEvaluator#observe}, and fire a CDI event per membership it satisfies. Everything the
 * decision itself needs — the candidate query, the latch, the DNF fold — is {@link
 * CampaignEvaluator}'s; this class is the seam between the wire and it, the same shape {@code
 * DeploymentActiveListener} and {@code BuildStatusListener} already are for their own consumptions.
 *
 * <h2>Why this goes through the bus even for {@code EntityTransitioned}, which this very service
 * publishes</h2>
 *
 * <p>It would be tempting to "optimise" a same-process fact into an in-process observer of the
 * announcer that publishes it — no wire, no redelivery, no consumer row. Do not: a criterion is
 * latched by the SAME fact, under the SAME redelivery semantics, that every OTHER consumer of that
 * fact sees. An in-process shortcut would give this one consumer a different notion of "did this
 * happen" than qits-ci or qits-deployments' own subscribers hold — most concretely, it would latch
 * on a transition that the announcing transaction went on to roll back, or fail to re-latch on the
 * durable redelivery every other consumer gets for free. Durable delivery is not overhead being
 * paid for nothing here; it is the one guarantee the whole feature rests on.
 *
 * <h2>Three signatures, three shapes, one funnel</h2>
 *
 * <ul>
 *   <li>{@code EntityTransitioned} — THIS service's own announcement (see {@link
 *       EntityTransitionAnnouncer}), decoded straight into the class already published with, since
 *       it never left this package. One {@link Observation.EntityReached} per entity in the batch —
 *       see that record's own javadoc for why a batch, not a stream of singles.
 *   <li>{@code DeploymentActive} — qits-deployments', decoded into {@link
 *       DeploymentActiveListener#DeploymentActivePayload}, the SAME local record that consumption
 *       already binds and already registers for reflection. A second record here naming the same
 *       three fields would be a second place a rename over there has to be caught.
 *   <li>{@code SCMRelease} — this service's own, decoded straight into the class already published
 *       with, exactly as {@code EntityTransitioned} is.
 * </ul>
 *
 * <h2>The retry arm</h2>
 *
 * <p>Every entity an {@code EntityTransitioned} batch names is also asked through {@link
 * CampaignEvaluator#satisfiedUnclaimedMembershipsOf}, beside the fresh {@link
 * CampaignEvaluator#observe} call for the batch as a whole. A member refused dispatch earlier —
 * blocked, wrong status — may be dispatchable now while none of ITS OWN criteria will ever latch
 * again (the criterion that mattered was some OTHER member reaching a status), so the one moment
 * worth re-checking it is exactly when the member itself moves. Membership ids from both arms are
 * deduplicated within the frame before anything fires.
 *
 * <h2>Two transactions, never one — the boundary this class owns (qits-416)</h2>
 *
 * <p>The library runs {@code onFrame} inside its claim transaction ({@code DurableFunnel}), and by
 * the time it does, the {@code consumed_event} insert has already enlisted a connection of the
 * {@code eventstream} datasource. {@link CampaignEvaluator} reads and writes {@code epics}, a second
 * non-XA datasource, and Narayana refuses a second local resource in one JTA transaction — measured
 * live on 2026-09-27, when this consumer failed on every frame with {@code ARJUNA016045 …
 * Enlisted connection used without active transaction}, the claim rolled back each time, and the
 * catch-up stopped at the first frame for good. So every piece of {@code epics} work a frame causes
 * runs in <b>one transaction of its own</b>, {@link QuarkusTransaction#requiringNew()}, which
 * suspends the claim for its duration — the same rule {@code BuildStatusLedger.record} and {@code
 * ReleaseFinalization} already follow on this service's other durable consumptions: the claim never
 * spans a second datasource. The evaluator's methods stay {@code MANDATORY}; they are mandatory
 * inside THAT transaction.
 *
 * <p><b>What the split costs, and why it is harmless.</b> The latch and the claim no longer commit
 * together, so there are two half-outcomes to reason about:
 *
 * <ul>
 *   <li><b>The latch commits, the claim then rolls back</b> (the claim's commit fails, or the
 *       process dies between the two). The frame stays owed and is offered again. The redelivery
 *       finds its criteria latched already — the latch is {@code where satisfied_at is null}, so
 *       nothing is written twice — and any membership that tipped over was already handed to the
 *       executor, whose claim is one conditional UPDATE: a second attempt on it is a {@code LOST}
 *       no-op. Redelivery is idempotent end to end, which is the only thing the reorder asks of it.
 *   <li><b>The {@code epics} transaction fails.</b> That throws out of {@code onFrame} unchanged,
 *       so the claim rolls back with it and the event stays owed — the fact is never lost by a
 *       claim that committed over a latch that did not. A poison frame (unreadable payload, an id
 *       that is not a UUID) is still WARN-and-return, before any transaction is opened.
 * </ul>
 *
 * <h2>What fires, and when</h2>
 *
 * <p>Every resulting membership id fires a CDI {@link CampaignMemberSatisfied} event —
 * synchronously ({@link Event#fire}, never {@code fireAsync}), because the executor that observes it
 * ({@code campaignhost/CampaignExecutor}, qits-417) is an {@code @Observes(during =
 * TransactionPhase.AFTER_SUCCESS)} listener, which registers against the transaction active at fire
 * time. <b>They are fired inside the {@code epics} transaction</b>, after the latches, so the
 * executor runs exactly when the latch it acts on has committed — never before, and never for a
 * latch that rolled back. Firing them in the outer claim transaction instead would tie the dispatch
 * to the claim's commit, and a claim that then rolled back would lose the hand-over outright: the
 * redelivery finds the criterion latched and names nothing (the sweep would be the only way back).
 *
 * <h2>Failure, and being seen to fail</h2>
 *
 * <p>The seam's rule, restated for the third time in this package: a throw rolls the claim back and
 * the event is owed forever, so swallow what retrying cannot fix and throw what it can. Every
 * outcome is also recorded in {@link CampaignCriteriaConsumerHealth} — a throw as a failure before
 * it is rethrown, a return as a success once the claim commits — so the progress read's {@code
 * evaluator} block says {@code consumerFailing}/{@code stalled} while this consumer is wedged,
 * rather than {@code connected: true} over a watermark that no longer moves.
 */
@ApplicationScoped
public class CampaignCriteriaListener implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(CampaignCriteriaListener.class);

  static final String ENTITY_TRANSITIONED_SIGNATURE = "EntityTransitioned";
  static final String DEPLOYMENT_ACTIVE_SIGNATURE = "DeploymentActive";
  static final String SCM_RELEASE_SIGNATURE = "SCMRelease";

  /**
   * This consumer's storage key, in {@code consumed_event} and {@code consumer_watermark}. <b>Never
   * change it</b> — a new value is a brand-new consumer initializing at the head of the log, silently
   * skipping every one of these three events in between. It names the consumption, not the class.
   */
  static final String CONSUMER_ID = CampaignCriteriaConsumerHealth.CONSUMER_ID;

  @Inject CampaignEvaluator evaluator;

  @Inject Event<CampaignMemberSatisfied> memberSatisfied;

  @Inject CampaignCriteriaConsumerHealth health;

  /**
   * The {@code epics} transaction every frame's work runs in — see the class javadoc. A field only
   * so the container-free unit test can run the work directly; nothing else ever replaces it.
   */
  Consumer<Runnable> epicsTransaction = work -> QuarkusTransaction.requiringNew().run(work);

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(ENTITY_TRANSITIONED_SIGNATURE, DEPLOYMENT_ACTIVE_SIGNATURE, SCM_RELEASE_SIGNATURE);
  }

  @Override
  public void onFrame(EventFrame frame) {
    try {
      route(frame);
    } catch (RuntimeException e) {
      health.failed(frame.name(), frame.id(), e);
      throw e;
    }
    health.handled(frame.name(), frame.id());
  }

  private void route(EventFrame frame) {
    switch (frame.name()) {
      case ENTITY_TRANSITIONED_SIGNATURE -> onEntityTransitioned(frame);
      case DEPLOYMENT_ACTIVE_SIGNATURE -> onDeploymentActive(frame);
      case SCM_RELEASE_SIGNATURE -> onScmRelease(frame);
      default ->
          // The dispatcher only ever offers what signatures() named; this is defensive rather than
          // reachable.
          LOG.warnf("%s is not a signature this consumer subscribed to; ignored", frame.name());
    }
  }

  private void onEntityTransitioned(EventFrame frame) {
    EntityTransitioned transitioned = decode(frame, EntityTransitioned.class);
    if (transitioned == null) {
      return;
    }
    UUID eventId = eventIdOf(frame);
    if (eventId == null) {
      return;
    }
    epicsTransaction.accept(
        () -> {
          Set<String> satisfied = new LinkedHashSet<>();
          for (EntityTransitioned.Entity entity : transitioned.entities()) {
            Observation observation =
                new Observation.EntityReached(
                    entity.entityId(), entity.projectId(), entity.status(), entity.statusBefore());
            satisfied.addAll(observe(observation, eventId, frame));
          }
          for (EntityTransitioned.Entity entity : transitioned.entities()) {
            satisfied.addAll(evaluator.satisfiedUnclaimedMembershipsOf(entity.entityId()));
          }
          fire(satisfied, eventId);
        });
  }

  private void onDeploymentActive(EventFrame frame) {
    DeploymentActiveListener.DeploymentActivePayload payload =
        decode(frame, DeploymentActiveListener.DeploymentActivePayload.class);
    if (payload == null) {
      return;
    }
    UUID eventId = eventIdOf(frame);
    if (eventId == null) {
      return;
    }
    Observation observation =
        new Observation.DeploymentWentActive(
            payload.applicationName(), payload.environmentName(), payload.version());
    epicsTransaction.accept(() -> fire(observe(observation, eventId, frame), eventId));
  }

  private void onScmRelease(EventFrame frame) {
    SCMRelease release = decode(frame, SCMRelease.class);
    if (release == null) {
      return;
    }
    UUID eventId = eventIdOf(frame);
    if (eventId == null) {
      return;
    }
    Observation observation =
        new Observation.Released(release.projectId(), release.repositoryName(), release.version());
    epicsTransaction.accept(() -> fire(observe(observation, eventId, frame), eventId));
  }

  private List<String> observe(Observation observation, UUID eventId, EventFrame frame) {
    Observation.Observed observed =
        new Observation.Observed(observation, eventId, frame.name(), frame.occurredAt());
    return evaluator.observe(observed);
  }

  /** Inside the {@code epics} transaction, after the latches — see "What fires, and when". */
  private void fire(Collection<String> membershipIds, UUID evidenceEventId) {
    for (String membershipId : membershipIds) {
      memberSatisfied.fire(new CampaignMemberSatisfied(membershipId, evidenceEventId));
    }
  }

  /** Null on anything that will not read as this payload, warned about once, never thrown. */
  private <T> T decode(EventFrame frame, Class<T> type) {
    try {
      return CanonicalJson.payloadTo(frame.payload(), type);
    } catch (RuntimeException e) {
      LOG.warnf("%s %s has an unreadable payload: %s", frame.name(), frame.id(), e.getMessage());
      return null;
    }
  }

  /**
   * {@link Observation.Observed} requires a non-null event id, unlike {@code
   * BuildStatusListener#causeOf}'s lenient trace edge — so a frame whose own id is not a UUID is
   * poison here rather than a field merely lost.
   */
  private UUID eventIdOf(EventFrame frame) {
    try {
      return UUID.fromString(frame.id());
    } catch (RuntimeException e) {
      LOG.warnf(
          "%s has an id that is not a UUID (%s), so no evidence can be recorded; it is skipped",
          frame.name(), frame.id());
      return null;
    }
  }
}

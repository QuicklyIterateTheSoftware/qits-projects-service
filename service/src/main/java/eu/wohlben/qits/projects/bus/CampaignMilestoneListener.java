package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.entities.campaign.CampaignReadiness;
import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.campaignhost.CampaignMemberSatisfied;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.jboss.logging.Logger;

/**
 * <b>The campaign executor's ear for {@link MilestoneMet}</b> (epic qits-615): a milestone came to
 * hold, so every unclaimed campaign membership waiting on it whose milestones are now all met is
 * handed to {@code campaignhost/CampaignExecutor} — the same {@link CampaignMemberSatisfied} CDI
 * event the criteria consumer's retry arm fires, observed {@code AFTER_SUCCESS} and tried off the
 * committing thread, with the {@code MilestoneMet}'s own id as the dispatch's cause. Which
 * memberships those are is {@link CampaignReadiness#satisfiedUnclaimedWaitingOn}; no criterion is
 * read. A claimed membership is frozen and never named, and {@link MilestoneUnmet} is not consumed
 * at all: an unclaimed member waiting on an un-met milestone simply stays not dispatchable.
 *
 * <h2>Its own consumer id</h2>
 *
 * <p>{@link #CONSUMER_ID} is new storage, beside {@code projects-campaign-criteria} and never
 * instead of it: that consumer latches criteria and must not skip a frame, this one only hurries a
 * dispatch the periodic sweep would make anyway. So it starts at the head of the log ({@link
 * #replayFromEpoch} false — there is no history of a brand-new event to catch up on), and a frame it
 * misses costs one sweep interval, never a dispatch. <b>Never change it</b> once shipped: it names
 * the {@code consumed_event} rows and the watermark.
 *
 * <p>The {@code epics} read runs in a transaction of its own, {@link
 * QuarkusTransaction#requiringNew()} — the claim transaction already holds the {@code eventstream}
 * connection, and Narayana refuses a second non-XA resource ({@link CampaignCriteriaListener}'s
 * javadoc). The CDI events are fired inside it, so the executor acts only once it committed. A
 * payload that will not read is a WARN and a return; a database failure throws, and the frame stays
 * owed.
 */
@ApplicationScoped
public class CampaignMilestoneListener implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(CampaignMilestoneListener.class);

  static final String MILESTONE_MET_SIGNATURE = "MilestoneMet";

  /** This consumer's storage key — see the class javadoc. */
  public static final String CONSUMER_ID = "projects-campaign-milestones";

  @Inject CampaignReadiness readiness;

  @Inject Event<CampaignMemberSatisfied> memberSatisfied;

  /** The {@code epics} transaction; a field only so a container-free test can run the work as is. */
  Consumer<Runnable> epicsTransaction = work -> QuarkusTransaction.requiringNew().run(work);

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(MILESTONE_MET_SIGNATURE);
  }

  @Override
  public boolean replayFromEpoch() {
    return false;
  }

  @Override
  public void onFrame(EventFrame frame) {
    if (!MILESTONE_MET_SIGNATURE.equals(frame.name())) {
      return;
    }
    MilestoneMet met;
    try {
      met = CanonicalJson.payloadTo(frame.payload(), MilestoneMet.class);
    } catch (RuntimeException e) {
      LOG.warnf("%s %s has an unreadable payload: %s", frame.name(), frame.id(), e.getMessage());
      return;
    }
    if (met == null || met.milestoneId() == null || met.milestoneId().isBlank()) {
      LOG.warnf("%s %s names no milestone; skipped", frame.name(), frame.id());
      return;
    }
    UUID cause = causeOf(frame);
    epicsTransaction.accept(
        () -> {
          for (String membershipId : readiness.satisfiedUnclaimedWaitingOn(met.milestoneId())) {
            memberSatisfied.fire(new CampaignMemberSatisfied(membershipId, cause));
          }
        });
  }

  /** The frame's own id as the dispatch's cause, or none when it is not a UUID. */
  private static UUID causeOf(EventFrame frame) {
    try {
      return UUID.fromString(frame.id());
    } catch (RuntimeException e) {
      return null;
    }
  }
}

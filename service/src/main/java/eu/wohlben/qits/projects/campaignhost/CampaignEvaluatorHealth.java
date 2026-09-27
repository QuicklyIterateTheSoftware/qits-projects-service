package eu.wohlben.qits.projects.campaignhost;

import eu.wohlben.qits.entities.api.CampaignDtos.CampaignEvaluatorDto;
import eu.wohlben.qits.eventstream.control.CatchupSweeper;
import eu.wohlben.qits.eventstream.control.EventStreamSubscriber;
import eu.wohlben.qits.eventstream.control.SweepCensus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * <b>Whether anything is listening for the events a campaign's criteria wait on</b> (qits-418) —
 * the {@code evaluator} block of the progress read, so a person looking at a waiting campaign can
 * tell "correctly waiting" from "nothing is listening" without reading {@code /q/health/ready}.
 *
 * <p>The same facts {@code CatchupHealthCheck} reads, from the eventstream library's own beans:
 * {@link EventStreamSubscriber#connected()} for the live stream, {@link CatchupSweeper#census()}
 * for the last completed catch-up sweep and whether the running one is stalled. Both are looked up
 * through {@link Instance}, so an assembly without them — or one that throws while answering —
 * reads as not connected, never as a failed progress read. In {@code %test} the bus is dark and
 * {@code connected} is false.
 */
@ApplicationScoped
public class CampaignEvaluatorHealth {

  @Inject Instance<EventStreamSubscriber> subscriber;

  @Inject Instance<CatchupSweeper> sweeper;

  /** The evaluator's health as it stands now. */
  public CampaignEvaluatorDto now() {
    boolean connected = false;
    if (subscriber.isResolvable()) {
      try {
        connected = subscriber.get().connected();
      } catch (RuntimeException e) {
        connected = false;
      }
    }
    SweepCensus census = null;
    if (sweeper.isResolvable()) {
      try {
        census = sweeper.get().census();
      } catch (RuntimeException e) {
        census = null;
      }
    }
    return new CampaignEvaluatorDto(
        connected,
        census == null ? null : census.lastCompletedAt(),
        census != null && census.stalled());
  }
}

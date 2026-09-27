package eu.wohlben.qits.projects.campaignhost;

import eu.wohlben.qits.entities.api.CampaignDtos.CampaignEvaluatorDto;
import eu.wohlben.qits.eventstream.control.CatchupSweeper;
import eu.wohlben.qits.eventstream.control.EventStreamSubscriber;
import eu.wohlben.qits.eventstream.control.SweepCensus;
import eu.wohlben.qits.eventstream.entity.ConsumerWatermark;
import eu.wohlben.qits.eventstream.persistence.ConsumerWatermarkRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;

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
 *
 * <p><b>And whether the criteria consumer itself is getting through its frames</b> — {@link
 * CampaignCriteriaConsumerHealth}, plus that consumer's {@code consumer_watermark} row. Connected
 * and sweeping is not listening: on 2026-09-27 the stream was up and every sweep completed while
 * this one consumer failed on its first frame forever, and this read said {@code connected: true,
 * stalled: false} throughout. A failing consumer is therefore folded into {@code stalled} as well as
 * reported on its own ({@code consumerFailing}), so every reader that already warns on {@code
 * !connected || stalled} warns on it with no change of its own.
 *
 * <p>The watermark is read in a transaction of its own ({@link QuarkusTransaction#requiringNew()}):
 * it lives on the {@code eventstream} datasource, and a caller's open transaction on another one
 * must never have a second non-XA resource enlisted into it — the very failure this read exists to
 * surface. Any failure reads as null, never as a failed progress read.
 */
@ApplicationScoped
public class CampaignEvaluatorHealth {

  @Inject Instance<EventStreamSubscriber> subscriber;

  @Inject Instance<CatchupSweeper> sweeper;

  @Inject CampaignCriteriaConsumerHealth consumer;

  @Inject Instance<ConsumerWatermarkRepository> watermarks;

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
    boolean sweepStalled = census != null && census.stalled();
    boolean consumerFailing = consumer.failing();
    return new CampaignEvaluatorDto(
        connected,
        census == null ? null : census.lastCompletedAt(),
        sweepStalled || consumerFailing,
        consumerFailing,
        consumer.lastError(),
        consumer.lastErrorAt(),
        watermarkAt());
  }

  private Instant watermarkAt() {
    if (!watermarks.isResolvable()) {
      return null;
    }
    try {
      return QuarkusTransaction.requiringNew()
          .call(
              () -> {
                ConsumerWatermark mark =
                    watermarks.get().findById(CampaignCriteriaConsumerHealth.CONSUMER_ID);
                return mark == null ? null : mark.occurredAt;
              });
    } catch (RuntimeException e) {
      return null;
    }
  }
}

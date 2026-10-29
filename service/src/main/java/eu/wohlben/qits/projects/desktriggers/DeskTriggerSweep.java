package eu.wohlben.qits.projects.desktriggers;

import eu.wohlben.qits.projects.agenthost.FrontDeskPrompter;
import eu.wohlben.qits.projects.agenthost.FrontDeskPrompter.Outcome;
import eu.wohlben.qits.projects.control.DevelopmentGuideDefaults;
import eu.wohlben.qits.projects.control.DevelopmentGuideService;
import eu.wohlben.qits.projects.entity.DeskTriggerEvent;
import eu.wohlben.qits.projects.entity.DeskTriggerWindow;
import eu.wohlben.qits.projects.entity.DeskTriggerWindowState;
import eu.wohlben.qits.projects.entity.Project;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * <b>Closes due desk trigger windows and delivers them</b> (epic qits-1604). Every pass first asks
 * each source what time passing has made due ({@link DeskTriggerSource#scan}, qits-1689) and admits
 * it under the listener's drops, then does two things, in this order:
 *
 * <ol>
 *   <li><b>Close.</b> Each OPEN window whose {@code closes_at} has passed is rendered ({@link
 *       DeskTriggerMessage}) from its lines, grouped by the source that admitted them, and becomes
 *       OWED with its {@code message} and {@code closed_at}. One transaction per window, holding the
 *       row lock admit takes, so a line is either in the message or in the next window — never
 *       neither. A trigger arriving after the close opens a new window.
 *   <li><b>Deliver.</b> The oldest OWED window of each project — one per project per pass, so two
 *       owed windows arrive in order and never interleave — is posted to the desk's daemon through
 *       {@link FrontDeskPrompter}, the reading the person's prompt door uses, <b>outside any
 *       transaction</b>: the hop is a network call and holds no row. What came back is recorded in
 *       a transaction of its own:
 *       <ul>
 *         <li>delivered → DELIVERED, queued → QUEUED (with its position); both stamp {@code
 *             accepted_at} and {@code command_id};
 *         <li>refused (409 no kept command or a daemon too old, 429 the queue full, 503 outside the
 *             contract) or unreachable → stays OWED, {@code attempts + 1}, {@code last_status} and
 *             {@code last_detail} say why — the next pass tries again;
 *         <li>400 → FAILED: the daemon called the message malformed, which is this service's bug and
 *             will not change by asking again.
 *       </ul>
 *       A window still OWED {@code qits.projects.desk-triggers.give-up} after it closed is FAILED
 *       before it is tried again: what it says is stale by then.
 * </ol>
 *
 * <p>Delivery is not gated on {@code delivery_enabled}: admission is. A window that opened while the
 * loop was on is owed to a desk that existed and is delivered if the desk will take it. A trigger
 * the desk asked for itself ({@link DeskTriggerSource#requestedByDesk()}, qits-1618's {@code qits
 * wait}) is admitted with delivery off too, and reaches the desk the same way: {@link
 * FrontDeskPrompter} types into the project's one kept front desk session, which delivery does not
 * create or remove — it only decides the rights that session holds. A project with no desk running
 * answers 409 or 503, and the window stays owed and is retried each pass until the desk takes it or
 * {@code give-up} passes, as any other window.
 *
 * <p>The message is stored at the close, so every retry sends the same text. Nothing here throws out
 * of a pass for one window's sake: a window that fails to close or record is logged and the rest go
 * on.
 *
 * <p>Gated to {@link LaunchMode#NORMAL} and {@code off} in {@code %test}, the {@code
 * CampaignExecutor} idiom: the suite calls {@link #sweep()} directly.
 */
@ApplicationScoped
public class DeskTriggerSweep {

  private static final Logger LOG = Logger.getLogger(DeskTriggerSweep.class);

  @Inject @Any Instance<DeskTriggerSource> sources;

  @Inject FrontDeskPrompter prompter;

  @Inject DeskTriggerWindows windows;

  @Inject DevelopmentGuideService guide;

  /** How long a closed window may stay owed before it is given up on. */
  @ConfigProperty(name = "qits.projects.desk-triggers.give-up", defaultValue = "PT4H")
  Duration giveUp;

  @Scheduled(
      every = "{qits.projects.desk-triggers.sweep-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void scheduledSweep() {
    if (LaunchMode.current() != LaunchMode.NORMAL) {
      return;
    }
    try {
      sweep();
    } catch (RuntimeException e) {
      LOG.error("The desk trigger sweep failed — retried on the next interval.", e);
    }
  }

  /** One pass: close what is due, then deliver the oldest owed window of each project. */
  public void sweep() {
    sweep(Instant.now());
  }

  /** One pass as of {@code now}; a test's clock. */
  void sweep(Instant now) {
    Instant at = now.truncatedTo(ChronoUnit.MICROS);
    for (DeskTriggerSource source : sources) {
      try {
        QuarkusTransaction.requiringNew().run(() -> scan(source, at));
      } catch (RuntimeException e) {
        LOG.warnf(e, "Desk trigger source %s could not scan; tried again next pass", source.id());
      }
    }
    for (UUID id : due(at)) {
      try {
        QuarkusTransaction.requiringNew().run(() -> close(id, at));
      } catch (RuntimeException e) {
        LOG.warnf(e, "Desk trigger window %s could not be closed; tried again next pass", id);
      }
    }
    for (UUID id : owedHeads()) {
      try {
        deliver(id, at);
      } catch (RuntimeException e) {
        LOG.warnf(e, "Desk trigger window %s could not be delivered; tried again next pass", id);
      }
    }
  }

  /** What {@code source} finds due by time, admitted under the listener's drops. */
  private void scan(DeskTriggerSource source, Instant now) {
    for (DeskTrigger trigger : source.scan(now)) {
      if (DeskTriggerListener.admissible(source, trigger)) {
        windows.admit(trigger.projectId(), source.id(), trigger, now, source.requestedByDesk());
      }
    }
  }

  private List<UUID> due(Instant now) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                DeskTriggerWindow.<DeskTriggerWindow>list(
                        "state = ?1 and closesAt <= ?2 order by closesAt",
                        DeskTriggerWindowState.OPEN,
                        now)
                    .stream()
                    .map(window -> window.id)
                    .toList());
  }

  /** The oldest OWED window of each project, oldest project first. */
  private List<UUID> owedHeads() {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Map<String, UUID> heads = new LinkedHashMap<>();
              for (DeskTriggerWindow window :
                  DeskTriggerWindow.<DeskTriggerWindow>list(
                      "state = ?1 order by closedAt, openedAt", DeskTriggerWindowState.OWED)) {
                heads.putIfAbsent(window.projectId, window.id);
              }
              return new ArrayList<>(heads.values());
            });
  }

  /** Inside the window's transaction; the lock is the one admit takes on the OPEN row. */
  private void close(UUID id, Instant now) {
    DeskTriggerWindow window = DeskTriggerWindow.findById(id, LockModeType.PESSIMISTIC_WRITE);
    if (window == null || window.state != DeskTriggerWindowState.OPEN) {
      return;
    }
    List<DeskTriggerEvent> events =
        DeskTriggerEvent.list(
            "windowId = ?1 order by admittedAt, occurredAt, itemRef", window.id);
    Map<String, DeskTriggerSource> byId = new LinkedHashMap<>();
    for (DeskTriggerSource source : sources) {
      byId.put(source.id(), source);
    }
    Map<DeskTriggerSource, List<DeskTriggerEventRow>> grouped = new LinkedHashMap<>();
    for (DeskTriggerEvent event : events) {
      DeskTriggerSource source = byId.get(event.source);
      if (source == null) {
        LOG.warnf(
            "Desk trigger window %s holds a line of source %s, which no bean provides; it is left"
                + " out of the message",
            window.id, event.source);
        continue;
      }
      grouped.computeIfAbsent(source, s -> new ArrayList<>()).add(DeskTriggerEventRow.of(event));
    }
    Project project = Project.findById(window.projectId);
    String slug = project == null || project.slug == null ? window.projectId : project.slug;
    window.message =
        DeskTriggerMessage.render(
            slug, guide.renderHash(DevelopmentGuideDefaults.DESK), window, grouped);
    window.closedAt = now;
    window.state = DeskTriggerWindowState.OWED;
    LOG.infof(
        "Desk trigger window %s of project %s closed with %d line(s); owed to the desk",
        window.id, window.projectId, Integer.valueOf(events.size()));
  }

  /** What is sent, read in a transaction and posted outside it. */
  private record Owed(String projectId, String message) {}

  private void deliver(UUID id, Instant now) {
    Owed owed = QuarkusTransaction.requiringNew().call(() -> owedOrGivenUp(id, now));
    if (owed == null) {
      return;
    }
    Outcome outcome = prompter.prompt(owed.projectId(), owed.message());
    QuarkusTransaction.requiringNew().run(() -> record(id, now, outcome));
  }

  /** The window to send, or null: no longer owed, or owed past the give-up and FAILED here. */
  private Owed owedOrGivenUp(UUID id, Instant now) {
    DeskTriggerWindow window = DeskTriggerWindow.findById(id, LockModeType.PESSIMISTIC_WRITE);
    if (window == null || window.state != DeskTriggerWindowState.OWED) {
      return null;
    }
    if (window.closedAt != null && window.closedAt.plus(giveUp).isBefore(now)) {
      window.state = DeskTriggerWindowState.FAILED;
      window.lastDetail =
          "Owed for longer than "
              + giveUp
              + " (qits.projects.desk-triggers.give-up); given up."
              + (window.lastDetail == null ? "" : " Last: " + window.lastDetail);
      LOG.warnf(
          "Desk trigger window %s of project %s was owed for longer than %s after %d attempt(s);"
              + " given up",
          window.id, window.projectId, giveUp, Integer.valueOf(window.attempts));
      return null;
    }
    return new Owed(window.projectId, window.message);
  }

  private static void record(UUID id, Instant now, Outcome outcome) {
    DeskTriggerWindow window = DeskTriggerWindow.findById(id, LockModeType.PESSIMISTIC_WRITE);
    if (window == null || window.state != DeskTriggerWindowState.OWED) {
      return;
    }
    window.attempts++;
    window.lastAttemptAt = now;
    switch (outcome) {
      case Outcome.Delivered delivered -> {
        window.state = DeskTriggerWindowState.DELIVERED;
        window.lastStatus = 200;
        window.lastDetail = null;
        window.acceptedAt = now;
        window.commandId = delivered.commandId();
      }
      case Outcome.Queued queued -> {
        window.state = DeskTriggerWindowState.QUEUED;
        window.lastStatus = 200;
        window.lastDetail = null;
        window.acceptedAt = now;
        window.commandId = queued.commandId();
        window.queuePosition = queued.position();
      }
      case Outcome.Refused refused -> {
        window.lastStatus = refused.status();
        window.lastDetail = refused.reason();
      }
      case Outcome.Unreachable unreachable -> {
        window.lastStatus = 503;
        window.lastDetail = unreachable.detail();
      }
      case Outcome.Invalid invalid -> {
        window.state = DeskTriggerWindowState.FAILED;
        window.lastStatus = 400;
        window.lastDetail = invalid.reason();
      }
    }
    if (window.state == DeskTriggerWindowState.OWED) {
      LOG.infof(
          "Desk trigger window %s of project %s not taken (attempt %d, %s: %s); tried again next"
              + " pass",
          window.id,
          window.projectId,
          Integer.valueOf(window.attempts),
          window.lastStatus,
          window.lastDetail);
    } else if (window.state == DeskTriggerWindowState.FAILED) {
      LOG.warnf(
          "Desk trigger window %s of project %s was refused as malformed (400: %s); not retried",
          window.id, window.projectId, window.lastDetail);
    } else {
      LOG.infof(
          "Desk trigger window %s of project %s %s (command %s)",
          window.id,
          window.projectId,
          window.state == DeskTriggerWindowState.QUEUED ? "queued" : "delivered",
          window.commandId);
    }
  }
}

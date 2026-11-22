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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * <b>Closes due desk trigger windows and delivers them</b> (epic qits-1604). Every pass first asks
 * each source what time passing has made due ({@link DeskTriggerSource#scan}, qits-1689) and admits
 * it under the listener's drops, then does three things, in this order:
 *
 * <ol>
 *   <li><b>Re-check</b> (qits-2501). Every OPEN window's live lines are asked whether they still
 *       hold ({@link DeskTriggerSource#resolved}, outside any transaction: some sources ask another
 *       service), and newer lines supersede older ones of the same request ({@link
 *       DeskTriggerSource#supersedesOlderLinesOfItsItem}, {@link DeskTriggerSource#supersededBy}).
 *       A line that no longer holds is stamped {@code dropped_at} and {@code dropped_reason} ({@code
 *       resolved} or {@code superseded}) under the window's row lock, never deleted, so the read
 *       door shows the summary shrinking and V65 still refuses its event's re-admission. A check
 *       that fails, or throws, drops nothing.
 *   <li><b>Close.</b> Each OPEN window whose {@code closes_at} has passed is rendered ({@link
 *       DeskTriggerMessage}) from its live lines, grouped by the source that admitted them, and
 *       becomes OWED with its {@code message} and {@code closed_at}. A window with no live line
 *       left — the Slots section does not count — becomes REDUNDANT instead, with no message and no
 *       prompt; the next trigger opens a new window. One transaction per window, holding the
 *       row lock admit takes, so a line is either in the message or in the next window — never
 *       neither. A trigger arriving after the close opens a new window. The message of a project
 *       with delivery enabled ends with its Slots section (qits-1778, {@link
 *       VerifiedSlotHolders}), read just before that transaction: it asks qits-workspaces which
 *       agents are ACTIVE, and that hop holds no row lock. A reading that fails — the port
 *       unsatisfied or throwing — is a WARN and the message goes without it; it never keeps a
 *       window from closing.
 *   <li><b>Deliver.</b> The oldest OWED window of each project — one per project per pass, so two
 *       owed windows arrive in order and never interleave — is re-checked as above, re-rendered from
 *       the lines still live with a fresh Slots reading (stored as its {@code message}; none live
 *       left makes it REDUNDANT, unsent), and posted to the desk's daemon through
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
 * <p>The message is rendered at the close and again before every attempt, so a retry hours later
 * says only what still holds and how the slots stand then. Nothing here throws out of a pass for one
 * window's sake: a window that fails to close or record is logged and the rest go
 * on.
 *
 * <p>Gated to {@link LaunchMode#NORMAL} and {@code off} in {@code %test}, the idiom
 * every sweep here follows: the suite calls {@link #sweep()} directly.
 */
@ApplicationScoped
public class DeskTriggerSweep {

  private static final Logger LOG = Logger.getLogger(DeskTriggerSweep.class);

  @Inject @Any Instance<DeskTriggerSource> sources;

  @Inject FrontDeskPrompter prompter;

  @Inject DeskTriggerWindows windows;

  @Inject DevelopmentGuideService guide;

  @Inject VerifiedSlotHolders slotHolders;

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
    for (UUID id : open()) {
      try {
        recheck(id, at);
      } catch (RuntimeException e) {
        LOG.warnf(e, "Desk trigger window %s could not be re-checked; tried again next pass", id);
      }
    }
    for (UUID id : due(at)) {
      try {
        String slots = slots(id, at);
        QuarkusTransaction.requiringNew().run(() -> close(id, at, slots));
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

  /** Every OPEN window, due or not. */
  private List<UUID> open() {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                DeskTriggerWindow.<DeskTriggerWindow>list(
                        "state = ?1 order by closesAt", DeskTriggerWindowState.OPEN)
                    .stream()
                    .map(window -> window.id)
                    .toList());
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

  /**
   * The Slots section of the window's message, or null: the project has delivery off (a window it
   * holds is then only the desk's own waits, or was opened while the loop was on), or the reading
   * failed. Never throws — a window closes whatever this answers.
   */
  private String slots(UUID windowId, Instant now) {
    Project project = null;
    try {
      project =
          QuarkusTransaction.requiringNew()
              .call(
                  () -> {
                    DeskTriggerWindow window = DeskTriggerWindow.findById(windowId);
                    return window == null ? null : Project.<Project>findById(window.projectId);
                  });
      if (project == null || !project.deliveryEnabled) {
        return null;
      }
      Optional<VerifiedSlotHolders.Reading> reading = slotHolders.read(project);
      if (reading.isEmpty()) {
        LOG.warnf(
            "Desk trigger window %s of project %s closes without its Slots section: there is no"
                + " workspaces context to read the running agents from",
            windowId, project.id);
        return null;
      }
      return VerifiedSlotHolders.section(reading.get(), project.deliveryMaxRunning, now);
    } catch (RuntimeException e) {
      LOG.warnf(
          "Desk trigger window %s%s closes without its Slots section: the running items could not"
              + " be read (%s)",
          windowId, project == null ? "" : " of project " + project.id, e.toString());
      return null;
    }
  }

  /** Inside the window's transaction; the lock is the one admit takes on the OPEN row. */
  private void close(UUID id, Instant now, String slots) {
    DeskTriggerWindow window = DeskTriggerWindow.findById(id, LockModeType.PESSIMISTIC_WRITE);
    if (window == null || window.state != DeskTriggerWindowState.OPEN) {
      return;
    }
    Rendered rendered = render(window, slots);
    window.closedAt = now;
    if (rendered == null) {
      window.state = DeskTriggerWindowState.REDUNDANT;
      LOG.infof(
          "Desk trigger window %s of project %s closed with no line still holding; redundant, the"
              + " desk is not prompted",
          window.id, window.projectId);
      return;
    }
    window.message = rendered.message();
    window.state = DeskTriggerWindowState.OWED;
    LOG.infof(
        "Desk trigger window %s of project %s closed with %d live line(s); owed to the desk",
        window.id, window.projectId, Integer.valueOf(rendered.lines()));
  }

  /** A window's message and how many live lines it says. */
  private record Rendered(String message, int lines) {}

  /**
   * The message of {@code window} from its live lines, inside the caller's transaction; null when
   * no live line of a known source is left, which the Slots section alone does not change.
   */
  private Rendered render(DeskTriggerWindow window, String slots) {
    List<DeskTriggerEvent> events =
        DeskTriggerEvent.list(
            "windowId = ?1 and droppedAt is null order by admittedAt, occurredAt, itemRef",
            window.id);
    Map<String, DeskTriggerSource> byId = sourcesById();
    Map<DeskTriggerSource, List<DeskTriggerEventRow>> grouped = new LinkedHashMap<>();
    int lines = 0;
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
      lines++;
    }
    if (lines == 0) {
      return null;
    }
    Project project = Project.findById(window.projectId);
    String slug = project == null || project.slug == null ? window.projectId : project.slug;
    return new Rendered(
        DeskTriggerMessage.render(
            slug, guide.renderHash(DevelopmentGuideDefaults.DESK), window, grouped, slots),
        lines);
  }

  private Map<String, DeskTriggerSource> sourcesById() {
    Map<String, DeskTriggerSource> byId = new LinkedHashMap<>();
    for (DeskTriggerSource source : sources) {
      byId.put(source.id(), source);
    }
    return byId;
  }

  /** A live line of a window: its row's id, and the read its source checks and renders. */
  private record Line(UUID id, DeskTriggerEventRow row) {}

  /** A window's project and live lines, read in a transaction and checked outside it. */
  private record LiveLines(String projectId, List<Line> lines) {}

  /** Newest last: by when it happened, then when it was admitted. */
  private static final Comparator<Line> AGE =
      Comparator.comparing((Line line) -> line.row().occurredAt())
          .thenComparing(line -> line.row().admittedAt());

  /**
   * Re-checks the live lines of an OPEN or OWED window (qits-2501): what no longer holds, then what
   * a newer live line supersedes, is stamped dropped. The checks run outside any transaction; the
   * stamps in one of their own, under the row lock admit and close take.
   */
  private void recheck(UUID windowId, Instant now) {
    LiveLines read =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  DeskTriggerWindow window = DeskTriggerWindow.findById(windowId);
                  if (window == null) {
                    return null;
                  }
                  List<Line> lines =
                      DeskTriggerEvent.<DeskTriggerEvent>list(
                              "windowId = ?1 and droppedAt is null"
                                  + " order by admittedAt, occurredAt, itemRef",
                              windowId)
                          .stream()
                          .map(event -> new Line(event.id, DeskTriggerEventRow.of(event)))
                          .toList();
                  return new LiveLines(window.projectId, lines);
                });
    if (read == null || read.lines().isEmpty()) {
      return;
    }
    Map<String, DeskTriggerSource> byId = sourcesById();
    Map<String, List<Line>> bySource = new LinkedHashMap<>();
    for (Line line : read.lines()) {
      bySource.computeIfAbsent(line.row().source(), s -> new ArrayList<>()).add(line);
    }
    Map<UUID, String> dropped = new LinkedHashMap<>();
    for (Map.Entry<String, List<Line>> entry : bySource.entrySet()) {
      DeskTriggerSource source = byId.get(entry.getKey());
      if (source == null) {
        continue;
      }
      Set<DeskTriggerEventRow> resolved;
      try {
        resolved =
            source.resolved(
                read.projectId(), entry.getValue().stream().map(Line::row).toList(), now);
      } catch (RuntimeException e) {
        LOG.debugf(
            "Desk trigger source %s could not re-check window %s (%s); its lines hold",
            source.id(), windowId, e.toString());
        continue;
      }
      if (resolved == null || resolved.isEmpty()) {
        continue;
      }
      for (Line line : entry.getValue()) {
        if (resolved.contains(line.row())) {
          dropped.put(line.id(), DeskTriggerEvent.RESOLVED);
        }
      }
    }
    supersede(read.lines(), byId, dropped);
    if (dropped.isEmpty()) {
      return;
    }
    QuarkusTransaction.requiringNew().run(() -> drop(windowId, dropped, now));
  }

  /**
   * Adds to {@code dropped} the live lines a newer one supersedes: of a source that names its item
   * once, every line but the newest per {@code item_id}; then every line of an item another
   * source's live line names, where this source says that one supersedes it.
   */
  private static void supersede(
      List<Line> lines, Map<String, DeskTriggerSource> byId, Map<UUID, String> dropped) {
    Map<List<String>, Line> newest = new LinkedHashMap<>();
    for (Line line : lines) {
      DeskTriggerSource source = byId.get(line.row().source());
      if (dropped.containsKey(line.id())
          || source == null
          || line.row().itemId() == null
          || !source.supersedesOlderLinesOfItsItem()) {
        continue;
      }
      newest.merge(
          List.of(line.row().source(), line.row().itemId()),
          line,
          (a, b) -> AGE.compare(a, b) >= 0 ? a : b);
    }
    for (Line line : lines) {
      Line keep =
          line.row().itemId() == null
              ? null
              : newest.get(List.of(line.row().source(), line.row().itemId()));
      if (keep != null && keep != line && !dropped.containsKey(line.id())) {
        dropped.put(line.id(), DeskTriggerEvent.SUPERSEDED);
      }
    }
    Set<List<String>> liveItems = new HashSet<>();
    for (Line line : lines) {
      if (!dropped.containsKey(line.id()) && line.row().itemId() != null) {
        liveItems.add(List.of(line.row().source(), line.row().itemId()));
      }
    }
    for (Line line : lines) {
      DeskTriggerSource source = byId.get(line.row().source());
      if (dropped.containsKey(line.id()) || source == null || line.row().itemId() == null) {
        continue;
      }
      for (String other : source.supersededBy()) {
        if (liveItems.contains(List.of(other, line.row().itemId()))) {
          dropped.put(line.id(), DeskTriggerEvent.SUPERSEDED);
          break;
        }
      }
    }
  }

  /** Stamps {@code dropped} on a window still OPEN or OWED, under its row lock. */
  private static void drop(UUID windowId, Map<UUID, String> dropped, Instant now) {
    DeskTriggerWindow window =
        DeskTriggerWindow.findById(windowId, LockModeType.PESSIMISTIC_WRITE);
    if (window == null
        || (window.state != DeskTriggerWindowState.OPEN
            && window.state != DeskTriggerWindowState.OWED)) {
      return;
    }
    int resolved = 0;
    int superseded = 0;
    for (Map.Entry<UUID, String> entry : dropped.entrySet()) {
      DeskTriggerEvent event = DeskTriggerEvent.findById(entry.getKey());
      if (event == null || event.droppedAt != null) {
        continue;
      }
      event.droppedAt = now;
      event.droppedReason = entry.getValue();
      if (DeskTriggerEvent.SUPERSEDED.equals(entry.getValue())) {
        superseded++;
      } else {
        resolved++;
      }
    }
    if (resolved + superseded > 0) {
      LOG.infof(
          "Desk trigger window %s of project %s (%s) dropped %d resolved and %d superseded"
              + " line(s)",
          window.id,
          window.projectId,
          window.state,
          Integer.valueOf(resolved),
          Integer.valueOf(superseded));
    }
  }

  /** What is sent, read in a transaction and posted outside it. */
  private record Owed(String projectId, String message) {}

  private void deliver(UUID id, Instant now) {
    Owed stillOwed = QuarkusTransaction.requiringNew().call(() -> owedOrGivenUp(id, now));
    if (stillOwed == null) {
      return;
    }
    // qits-2501: what the window says is re-checked and re-rendered before every attempt, the
    // checks and the Slots reading outside any transaction.
    recheck(id, now);
    String slots = slots(id, now);
    Owed owed = QuarkusTransaction.requiringNew().call(() -> rerendered(id, now, slots));
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

  /**
   * The window's message re-rendered from its live lines and stored, or null: no longer owed, or no
   * live line left, which makes it REDUNDANT here instead of sent.
   */
  private Owed rerendered(UUID id, Instant now, String slots) {
    DeskTriggerWindow window = DeskTriggerWindow.findById(id, LockModeType.PESSIMISTIC_WRITE);
    if (window == null || window.state != DeskTriggerWindowState.OWED) {
      return null;
    }
    Rendered rendered = render(window, slots);
    if (rendered == null) {
      window.state = DeskTriggerWindowState.REDUNDANT;
      LOG.infof(
          "Desk trigger window %s of project %s has no line still holding after %d attempt(s);"
              + " redundant, the desk is not prompted",
          window.id, window.projectId, Integer.valueOf(window.attempts));
      return null;
    }
    window.message = rendered.message();
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

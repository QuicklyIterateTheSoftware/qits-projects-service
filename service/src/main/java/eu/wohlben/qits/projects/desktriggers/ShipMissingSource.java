package eu.wohlben.qits.projects.desktriggers;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.desktriggers.VerifiedSlotHolders.Holder;
import eu.wohlben.qits.projects.desktriggers.VerifiedSlotHolders.Reading;
import eu.wohlben.qits.projects.entity.DeskTriggerEvent;
import eu.wohlben.qits.projects.entity.Project;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/**
 * <b>VERIFIED work that no open release request carries</b> (qits-1778). {@code PhaseAdvance} asks
 * for the release of an item's branches on every move into VERIFIED, the wrapper last (qits-1224),
 * so ordinarily a VERIFIED item sits in a PENDING wrapper request and the Slots section ({@link
 * VerifiedSlotHolders#section}) says so. The ask can fail, though: the wrapper branch cannot be
 * created, or the request is WITHDRAWN or OBSOLETE and nothing asks again. {@code PhaseAdvance}
 * then writes a sentence on the item's thread, and the desk heard nothing until {@link
 * AgentIdleSource}'s two hours — or never, if the agent kept turning — while the item held a slot.
 * Like {@link RequestStalledSource} and {@link AgentIdleSource}, this source reads no bus event:
 * {@link #scan} runs on every {@link DeskTriggerSweep} pass.
 *
 * <p><b>Who counts.</b> For each project with delivery enabled, each running item ({@link
 * VerifiedSlotHolders}, the dispatch check's reading) that is VERIFIED, has been for at least
 * {@link #THRESHOLD}, and has no request in {@link VerifiedSlotHolders#OPEN} with a source in its
 * scope (its wrapper branch or a {@code <branch>-<name>} submodule branch). The line names the
 * branch nothing carries. A project whose agents cannot be read raises nothing on that pass and is
 * a WARN; an unsatisfied agent port raises nothing.
 *
 * <p><b>Once per move into VERIFIED.</b> A line's event id is a name-based UUID of (item, VERIFIED
 * since) ({@link #shipKey}), and an item some window already holds that id for is not raised
 * again; the row in {@code desk_trigger_event} is the mark, as {@link AgentIdleSource#idleKey}'s
 * is. A move back and forth into VERIFIED is a new VERIFIED-since, a new key, and a new line once
 * it, too, has lasted the threshold. Where the audit is missing the key falls back to the row's
 * {@code updated_at}, so a later write to such a row may raise it again; an item with an audit
 * (every item written through the doors) is raised once.
 *
 * <p><b>Measured from the desk's arrival</b>, {@link RequestStalledSource}'s rule: threshold and
 * {@link #MAX_AGE} count from the later of VERIFIED-since and {@code project.delivery_enabled_at},
 * so a desk turned on is not told of every item that sat VERIFIED while it was off. The clamp never
 * enters the key; the label's duration is the item's real VERIFIED time. Nothing causes a missing
 * request, so no principal: the cause is {@code verified <instant to the minute>}.
 */
@ApplicationScoped
public class ShipMissingSource implements DeskTriggerSource {

  private static final Logger LOG = Logger.getLogger(ShipMissingSource.class);

  /** Storage — see {@link DeskTriggerSource#id()}. */
  static final String ID = "verified-unshipped";

  /**
   * How long an item is VERIFIED with no request before the desk hears of it; not a setting. Long
   * enough for {@code PhaseAdvance}'s ask, made at the move, to have landed or failed.
   */
  static final Duration THRESHOLD = Duration.ofMinutes(30);

  /** An item VERIFIED longer than this from its clamp has been seen: {@code …stall.max-age}'s value. */
  static final Duration MAX_AGE = Duration.ofDays(7);

  @Inject VerifiedSlotHolders holders;

  @Override
  public String id() {
    return ID;
  }

  @Override
  public int order() {
    return 55;
  }

  /** None: an unshipped item is found by {@link #scan}. */
  @Override
  public Set<String> signatures() {
    return Set.of();
  }

  @Override
  public List<DeskTrigger> qualify(EventFrame frame) {
    return List.of();
  }

  @Override
  public List<DeskTrigger> scan(Instant now) {
    List<DeskTrigger> triggers = new ArrayList<>();
    for (Project project : Project.<Project>list("deliveryEnabled = true")) {
      String projectId = project.id;
      Instant clamp = project.deliveryEnabledAt;
      Optional<Reading> reading;
      try {
        reading = holders.read(project);
      } catch (RuntimeException e) {
        LOG.warnf(
            "The running items of project %s could not be read (%s); its VERIFIED work is looked at"
                + " again next pass",
            projectId, e.toString());
        continue;
      }
      if (reading.isEmpty()) {
        continue;
      }
      for (Holder holder : reading.get().verified()) {
        DeskTrigger trigger = unshipped(projectId, clamp, holder, now);
        if (trigger != null) {
          triggers.add(trigger);
        }
      }
    }
    return triggers;
  }

  @Override
  public String render(String projectSlug, List<DeskTriggerEventRow> events) {
    return "VERIFIED work with no open release request:\n"
        + events.stream().map(DeskTriggerEventRow::line).collect(Collectors.joining("\n"));
  }

  /** The synthetic event id of one move into VERIFIED: the item and the instant it moved. */
  static UUID shipKey(String itemId, Instant verifiedSince) {
    return UUID.nameUUIDFromBytes(
        (ID + "|" + itemId + "|" + verifiedSince.truncatedTo(ChronoUnit.MICROS))
            .getBytes(StandardCharsets.UTF_8));
  }

  /**
   * {@code - qits-1769 (VERIFIED 43m; no open release request carries ticket/qits-1769)}, or null
   * when the item has a request, is too young or too old from its clamp, or was already told.
   */
  private static DeskTrigger unshipped(
      String projectId, Instant clamp, Holder holder, Instant now) {
    if (!holder.requests().isEmpty()) {
      return null;
    }
    Instant since = later(holder.verifiedSince(), clamp);
    if (since.plus(THRESHOLD).isAfter(now) || !since.plus(MAX_AGE).isAfter(now)) {
      return null;
    }
    UUID key = shipKey(holder.itemId(), holder.verifiedSince());
    if (DeskTriggerEvent.count("source = ?1 and eventId = ?2", ID, key) > 0) {
      return null;
    }
    return new DeskTrigger(
        projectId,
        holder.qualifiedId(),
        holder.itemId(),
        "verified " + AgentIdleSource.MINUTES.format(holder.verifiedSince()),
        "VERIFIED "
            + VerifiedSlotHolders.verifiedFor(holder, now)
            + "; no open release request"
            + (holder.branch() == null ? "" : " carries " + holder.branch()),
        key,
        now,
        null,
        null);
  }

  /** The later of the move and the clamp; the move when the clamp is null. */
  private static Instant later(Instant start, Instant clamp) {
    return clamp != null && clamp.isAfter(start) ? clamp : start;
  }
}

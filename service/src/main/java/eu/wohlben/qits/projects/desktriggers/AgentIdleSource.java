package eu.wohlben.qits.projects.desktriggers;

import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.api.QualifiedEntityIds;
import eu.wohlben.qits.projects.control.WorkspaceAgentLookup;
import eu.wohlben.qits.projects.control.WorkspaceAgentLookup.ActiveAgent;
import eu.wohlben.qits.projects.control.WorkspaceAgentLookup.AgentWait;
import eu.wohlben.qits.projects.entity.DeskTriggerEvent;
import eu.wohlben.qits.projects.entity.Project;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <b>An agent has had no turn for two hours</b> (qits-1756). Nothing says "an agent went quiet", and
 * qits-workspaces' own nudge (qits-1460) leaves out an agent on an OPEN wait, so an agent on a wait
 * that can never fire would sit unseen. Like {@link RequestStalledSource}, this source reads no bus
 * event: {@link #scan} runs on every {@link DeskTriggerSweep} pass and reads the ACTIVE agents
 * through {@link WorkspaceAgentLookup#active()}.
 *
 * <p><b>Who counts.</b> An ACTIVE agent whose {@code activity} is not {@code BUSY} — {@code
 * WAITING}, {@code IDLE}, {@code ENDED} and none at all (yielded, or no daemon) count — and whose
 * {@code lastActivityAt} is at least {@link #THRESHOLD} ago. BUSY is left out however stale its
 * {@code lastActivityAt}: hooks fire only at turn boundaries, so a long turn's stamp ages while it
 * works, and {@code activity} is the only sign that a turn runs. An agent on an open wait counts,
 * and so does one on a blocked item. An agent with no {@code lastActivityAt} is never idle.
 *
 * <p><b>The item is this service's own row</b>: the agent's {@code workId} names a {@link
 * WorkEntity}, which gives the project, the qualified id, the title, the status and the block. An
 * agent whose item this service does not know is skipped. A list that cannot be read
 * (qits-workspaces away, the port absent) raises nothing on that pass.
 *
 * <p><b>Once per idle stretch.</b> A stretch starts at the agent's {@code lastActivityAt}, which
 * qits-workspaces stamps at every turn boundary and nothing else moves. A line's event id is a
 * name-based UUID of (agent, {@code lastActivityAt}) ({@link #idleKey}), and an agent some window
 * already holds that id for is not raised again; the row in {@code desk_trigger_event} is the mark,
 * so it survives a restart. A later turn is a new {@code lastActivityAt}, a new key, and a new line
 * once it, too, has lasted the threshold. The once rule is this source's alone: the same item still
 * shows in every other section.
 *
 * <p><b>Measured from the desk's arrival</b>, {@link RequestStalledSource}'s rule: threshold and
 * {@link #MAX_AGE} count from the later of {@code lastActivityAt} and {@code
 * project.delivery_enabled_at}, so a desk turned on is not told of every agent that went quiet
 * while it was off. The clamp never enters the key; the label's duration is the agent's real idle
 * time. Nothing causes idleness, so no principal.
 */
@ApplicationScoped
public class AgentIdleSource implements DeskTriggerSource {

  /** Storage — see {@link DeskTriggerSource#id()}. */
  static final String ID = "agent-idle";

  /** How long an agent goes without a turn before the desk hears of it; not a setting (owner). */
  static final Duration THRESHOLD = Duration.ofHours(2);

  /** A stretch older than this from its clamp has been seen: {@code …stall.max-age}'s value. */
  static final Duration MAX_AGE = Duration.ofDays(7);

  /** The activity of an agent whose turn runs. */
  static final String BUSY = "BUSY";

  /** {@code 2026-10-30T07:03Z}: an instant to the minute. */
  private static final DateTimeFormatter MINUTES =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm'Z'").withZone(ZoneOffset.UTC);

  /** The agent's item as this source reads it, out of the epics transaction. */
  private record Item(
      String id,
      String projectId,
      long number,
      String title,
      String status,
      boolean blocked,
      String blockedReason) {}

  @Inject Instance<WorkspaceAgentLookup> lookup;

  @Override
  public String id() {
    return ID;
  }

  @Override
  public int order() {
    return 55;
  }

  /** None: an idle agent is found by {@link #scan}. */
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
    if (!lookup.isResolvable()) {
      return List.of();
    }
    Optional<List<ActiveAgent>> listed = lookup.get().active();
    if (listed.isEmpty()) {
      return List.of();
    }
    List<ActiveAgent> quiet =
        listed.get().stream()
            .filter(agent -> agent.workId() != null && agent.lastActivityAt() != null)
            .filter(agent -> !BUSY.equals(agent.activity()))
            .filter(agent -> !agent.lastActivityAt().plus(THRESHOLD).isAfter(now))
            .toList();
    if (quiet.isEmpty()) {
      return List.of();
    }
    Map<String, Item> items = items(quiet.stream().map(ActiveAgent::workId).distinct().toList());
    Map<String, Optional<Project>> projects = new HashMap<>();
    List<DeskTrigger> triggers = new ArrayList<>();
    for (ActiveAgent agent : quiet) {
      Item item = items.get(agent.workId());
      if (item == null || item.projectId() == null) {
        continue;
      }
      Project project =
          projects
              .computeIfAbsent(
                  item.projectId(), id -> Optional.ofNullable(Project.<Project>findById(id)))
              .orElse(null);
      if (project == null) {
        continue;
      }
      DeskTrigger trigger = idle(agent, item, project, now);
      if (trigger != null) {
        triggers.add(trigger);
      }
    }
    return triggers;
  }

  @Override
  public String render(String projectSlug, List<DeskTriggerEventRow> events) {
    return "Agents idle for 2h or more:\n"
        + events.stream().map(DeskTriggerEventRow::line).collect(Collectors.joining("\n"));
  }

  /** The synthetic event id of one idle stretch: the agent and the turn boundary it started at. */
  static UUID idleKey(String agentId, Instant lastActivityAt) {
    return UUID.nameUUIDFromBytes(
        (ID + "|" + agentId + "|" + lastActivityAt.truncatedTo(ChronoUnit.MICROS))
            .getBytes(StandardCharsets.UTF_8));
  }

  private static DeskTrigger idle(ActiveAgent agent, Item item, Project project, Instant now) {
    Instant since = later(agent.lastActivityAt(), project.deliveryEnabledAt);
    if (since.plus(THRESHOLD).isAfter(now) || !since.plus(MAX_AGE).isAfter(now)) {
      return null;
    }
    UUID key = idleKey(agent.agentId(), agent.lastActivityAt());
    if (DeskTriggerEvent.count("source = ?1 and eventId = ?2", ID, key) > 0) {
      return null;
    }
    String itemRef =
        project.slug == null || item.number() < 1
            ? item.id()
            : QualifiedEntityIds.render(project.slug, item.number());
    return new DeskTrigger(
        project.id,
        itemRef,
        agent.agentId(),
        "agent " + agent.agentId().substring(0, Math.min(8, agent.agentId().length())),
        label(agent, item, now),
        key,
        now,
        null,
        null);
  }

  /**
   * {@code <title>; <STATUS>; idle 2h 19m since 2026-10-30T07:03Z; waits on: <label> (<subject
   * state or state>, expires <instant | never>)[; …] | no open wait[; blocked: <reason>]}.
   */
  private static String label(ActiveAgent agent, Item item, Instant now) {
    StringBuilder label = new StringBuilder();
    if (item.title() != null && !item.title().isBlank()) {
      label.append(item.title().strip()).append("; ");
    }
    label
        .append(item.status())
        .append("; idle ")
        .append(RequestStalledSource.human(Duration.between(agent.lastActivityAt(), now)))
        .append(" since ")
        .append(MINUTES.format(agent.lastActivityAt()))
        .append("; ");
    List<String> open =
        agent.waits().stream().filter(AgentWait::open).map(AgentIdleSource::wait).toList();
    label.append(open.isEmpty() ? "no open wait" : "waits on: " + String.join("; ", open));
    if (item.blocked()) {
      label
          .append("; ")
          .append(
              item.blockedReason() == null || item.blockedReason().isBlank()
                  ? "blocked"
                  : "blocked: " + RequestFailedSource.cut(item.blockedReason().strip()));
    }
    return label.toString();
  }

  /** {@code <label> (<subject state, else state>, expires <instant to the minute | never>)}. */
  private static String wait(AgentWait wait) {
    String state =
        wait.subjectState() == null || wait.subjectState().isBlank()
            ? wait.state()
            : wait.subjectState();
    return RequestFailedSource.cut(wait.label().strip())
        + " ("
        + state
        + ", expires "
        + (wait.expiresAt() == null ? "never" : MINUTES.format(wait.expiresAt()))
        + ")";
  }

  /**
   * The items of {@code workIds} this service knows, by id. Work items live in the epics
   * datasource: read in a transaction of their own, never in the sweep's projects one (one non-XA
   * resource per transaction).
   */
  private static Map<String, Item> items(List<String> workIds) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                WorkEntity.<WorkEntity>list("id in ?1", workIds).stream()
                    .map(
                        row ->
                            new Item(
                                row.id,
                                row.projectId,
                                row.number,
                                row.title,
                                row.status,
                                row.blocked,
                                row.blockedReason))
                    .collect(Collectors.toMap(Item::id, Function.identity())));
  }

  /** The later of a stretch's start and the clamp; the start when the clamp is null. */
  private static Instant later(Instant start, Instant clamp) {
    return clamp != null && clamp.isAfter(start) ? clamp : start;
  }
}

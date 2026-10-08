package eu.wohlben.qits.projects.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import eu.wohlben.qits.projects.dto.DeskRunnerDto;
import eu.wohlben.qits.projects.dto.DeskRunnerHealthDto;
import eu.wohlben.qits.projects.entity.DeskRunner;
import eu.wohlben.qits.projects.entity.DeskRunnerCapabilities;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link DeskRunner} to {@link DeskRunnerDto} — qits-workspaces-service's {@code
 * WorkspaceRunnerMapper} in the desk spelling (qits-767). Written by hand rather than with
 * MapStruct, because part of the answer is read out of the capabilities object rather than copied
 * from a column, and the runner's word is read and never corrected: a key it did not send, or sent
 * in another shape, is null.
 */
@ApplicationScoped
public class DeskRunnerMapper {

  /**
   * What only the service knows about a runner: whether it is connected (the sockets live there),
   * the pinned runner version, the login command composed from its addresses, and the desks placed
   * on it. Every member may be null, "not known here".
   */
  public record Live(
      Boolean connected,
      Instant connectedSince,
      String pinnedVersion,
      String loginCommand,
      List<DeskRunnerDto.DeskRunnerDesk> desks) {

    /** Nothing known beyond the row. */
    public static final Live NONE = new Live(null, null, null, null, List.of());
  }

  /** The row alone, as {@link Live#NONE} reads it. */
  public DeskRunnerDto toDto(DeskRunner runner) {
    return toDto(runner, Live.NONE);
  }

  /** The row together with what the service knows live about it. */
  public DeskRunnerDto toDto(DeskRunner runner, Live live) {
    if (runner == null) {
      return null;
    }
    Live known = live == null ? Live.NONE : live;
    JsonNode said = DeskRunnerCapabilities.decode(runner.capabilities);
    return new DeskRunnerDto(
        runner.id,
        runner.name,
        runner.description,
        runner.slots,
        DeskRunnerCapabilities.text(said, DeskRunnerCapabilities.VERSION),
        runner.registered(),
        runner.registeredAt,
        runner.quarantined(),
        runner.quarantinedAt,
        runner.quarantineReason,
        runner.loginState,
        runner.lastSeenAt,
        runner.lastHealthCheckAt,
        runner.lastHealthCheckOk,
        runner.createdAt,
        known.connected(),
        known.connectedSince(),
        known.pinnedVersion(),
        known.loginCommand(),
        known.desks() == null ? List.of() : List.copyOf(known.desks()),
        health(said));
  }

  /** The newest health check's verdicts, without the checks' data, or null when none is recorded. */
  public static DeskRunnerDto.DeskRunnerHealth health(JsonNode said) {
    JsonNode health = healthNode(said);
    if (health == null) {
      return null;
    }
    List<DeskRunnerDto.DeskRunnerCheck> checks = new ArrayList<>();
    for (JsonNode check : checks(health)) {
      checks.add(
          new DeskRunnerDto.DeskRunnerCheck(
              DeskRunnerCapabilities.text(check, "name"),
              check.path("ok").asBoolean(false),
              DeskRunnerCapabilities.text(check, "detail")));
    }
    return new DeskRunnerDto.DeskRunnerHealth(
        instant(DeskRunnerCapabilities.text(health, "at")),
        health.path("ok").asBoolean(false),
        DeskRunnerCapabilities.text(health, "detail"),
        List.copyOf(checks));
  }

  /**
   * The newest health check in full — every check's data included — or null when none has been
   * recorded: {@code GET /runners/{id}/health}'s answer.
   */
  public DeskRunnerHealthDto toHealthDto(DeskRunner runner) {
    JsonNode health =
        runner == null ? null : healthNode(DeskRunnerCapabilities.decode(runner.capabilities));
    if (health == null) {
      return null;
    }
    List<DeskRunnerHealthDto.DeskRunnerCheckReport> checks = new ArrayList<>();
    for (JsonNode check : checks(health)) {
      JsonNode data = check.get("data");
      checks.add(
          new DeskRunnerHealthDto.DeskRunnerCheckReport(
              DeskRunnerCapabilities.text(check, "name"),
              check.path("ok").asBoolean(false),
              DeskRunnerCapabilities.text(check, "detail"),
              data != null && data.isObject() ? data : JsonNodeFactory.instance.objectNode()));
    }
    return new DeskRunnerHealthDto(
        instant(DeskRunnerCapabilities.text(health, "at")),
        health.path("ok").asBoolean(false),
        DeskRunnerCapabilities.text(health, "detail"),
        DeskRunnerCapabilities.text(health, "requestId"),
        health.path(DeskRunnerCapabilities.DATA_OMITTED).asBoolean(false),
        List.copyOf(checks));
  }

  private static JsonNode healthNode(JsonNode said) {
    JsonNode health = said == null ? null : said.get(DeskRunnerCapabilities.HEALTH);
    return health != null && health.isObject() ? health : null;
  }

  /** The report's checks that are objects; a malformed entry is skipped, never corrected. */
  private static List<JsonNode> checks(JsonNode health) {
    List<JsonNode> checks = new ArrayList<>();
    for (JsonNode check : health.path("checks")) {
      if (check.isObject()) {
        checks.add(check);
      }
    }
    return checks;
  }

  private static Instant instant(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException unparseable) {
      return null;
    }
  }
}

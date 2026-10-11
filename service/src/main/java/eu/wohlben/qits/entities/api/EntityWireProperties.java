package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.control.AcceptanceCriteria;
import eu.wohlben.qits.entities.control.EntityProperty;
import eu.wohlben.qits.entities.control.EntityStateMachine;
import eu.wohlben.qits.entities.control.EntityTransitionService;
import eu.wohlben.qits.entities.entity.TicketType;
import eu.wohlben.qits.entities.entity.WorkOutcome;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * <b>The one table from the registry's vocabulary to the wire</b> (qits-548): per {@link
 * EntityProperty} a caller may write, its wire name, the JSON Schema fragment of its value, and
 * whether a merge patch may clear it.
 *
 * <p>The registry ({@code Archetypes}, served as {@code GET /work/archetypes}) says which
 * properties a kind permits and requires, in enum constants ({@code TICKET_TYPE}); it says nothing
 * about what goes on the wire. That gap was filled twice, by hand, in two places that could disagree
 * — the patch door's list of editable names, and whatever a client kept to build a
 * payload. This is the one place it is filled now, and the three readers read it: the published
 * schemas ({@link EntitySchemas}, {@code GET /work/archetypes/{a}/schemas/{door}}), the generic
 * create door's validation (which validates <em>against</em> the schema, not beside it), and {@code
 * WorkEntityDoors}' patch, its editable and non-clearable sets. A schema and a validator built from one
 * table cannot disagree; {@code EntitySchemaApiTest} pins that the doors accept what the schemas
 * require.
 *
 * <h2>What is in it</h2>
 *
 * <ul>
 *   <li><b>Every property but the server-owned pair</b> ({@link EntityTransitionService#SERVER_OWNED}
 *       — the slug, minted from the title; {@code createdBy}, stamped from the identity). Those are
 *       never written by a caller at any door, so they have no wire slot to describe.
 *   <li><b>The wire name is the camelCase of the constant</b> — {@code TICKET_TYPE} is {@code
 *       ticketType} — derived rather than written down, which is also how {@code EntityTransition}
 *       spells every one of them. The legacy per-archetype doors' names ({@code type}, {@code
 *       dependsOnFeatureId}) are theirs and are not in here.
 *   <li><b>A value is a string by default</b>; the ticket type is its enum, the two task markers
 *       (implemented, implementing) a {@code date-time}, and the three properties an empty value would defeat — title, impetus,
 *       repository — carry the pattern {@code \S}. The status carries the lifecycle's words. The
 *       acceptance criteria (qits-887) are the one list: an {@code array} of strings, each item
 *       carrying {@code AcceptanceCriteria}' rule as its pattern.
 *   <li><b>Clearable</b> is false for the four properties that have no {@code clear*} flag behind
 *       them in {@code EntityWrite}: three because every kind that permits one requires it, so a
 *       null there is a 400 rather than an absent flag, and the implementing marker (qits-749)
 *       because it is history — kept once the work is implemented, never taken back.
 * </ul>
 *
 * <p>Which of these a door takes is the door's: the status and a drop's supersededBy are moves
 * ({@link #MOVES}), and the create door leaves out what the writer mints. Those are decisions about doors,
 * made in {@link EntitySchemas}; this table only says what each property <em>is</em>.
 */
public final class EntityWireProperties {

  /**
   * One row of the table.
   *
   * @param property the registry's word
   * @param wire how a body spells it — the camelCase of {@link #property}
   * @param clearable whether a merge patch may send it as null
   * @param schema the JSON Schema fragment of a value, without the nullability a door may add
   */
  public record Slot(
      EntityProperty property, String wire, boolean clearable, Map<String, Object> schema) {}

  /**
   * The status, and what a drop names as answering for it (qits-248): stated by a move, never by an
   * edit — the patch refuses both, and neither a create nor a patch schema lists them. The drop's
   * outcome is the third such value and no registry property ({@link #OUTCOME}).
   */
  public static final List<EntityProperty> MOVES =
      List.of(EntityProperty.STATUS, EntityProperty.SUPERSEDED_BY);

  /** The wire name of a drop's outcome (qits-248) — stated on a move and a transition, never edited. */
  public static final String OUTCOME = "outcome";

  /**
   * The JSON Schema fragment of a drop's outcome: the six words. Served on the transition door's
   * schema of every kind with a lifecycle ({@code EntitySchemas}); it is not a registry property, so
   * it is not a row of this table.
   */
  public static Map<String, Object> outcomeSchema() {
    return oneOf(
        "Why a DROPPED entity was closed: DUPLICATE, RESOLVED_ELSEWHERE or SUPERSEDED (naming the"
            + " item in supersededBy), OBSOLETE, INVALID or WONT_DO (naming none). Stated with"
            + " status DROPPED alone, which requires one — except on a row already DROPPED with"
            + " none, from before outcomes were recorded.",
        WorkOutcome.words());
  }

  private static final Map<EntityProperty, Slot> TABLE = declare();

  private EntityWireProperties() {}

  private static Map<EntityProperty, Slot> declare() {
    Map<EntityProperty, Slot> table = new EnumMap<>(EntityProperty.class);
    row(table, EntityProperty.TITLE, false, nonBlank("The label."));
    row(table, EntityProperty.DESCRIPTION, true, text("The long-form Markdown body."));
    row(
        table,
        EntityProperty.STATUS,
        true,
        oneOf(
            "The lifecycle status.",
            EntityStateMachine.states().stream().map(Enum::name).toList()));
    row(
        table,
        EntityProperty.TICKET_TYPE,
        false,
        oneOf(
            "A ticket's kind.", Arrays.stream(TicketType.values()).map(Enum::name).toList()));
    row(
        table,
        EntityProperty.IMPETUS,
        true,
        nonBlank("Why a ticket came about, in the reporter's words; usually one sentence."));
    row(table, EntityProperty.ASSIGNEE, true, text("Who is looking at it; free text."));
    row(
        table,
        EntityProperty.SUPERSEDED_BY,
        true,
        text(
            "The id of the item a DROPPED entity names as what answers for it: the duplicate it"
                + " repeats, the item whose change resolved it, or the successor that replaced it."
                + " Required with outcome DUPLICATE, RESOLVED_ELSEWHERE or SUPERSEDED, refused with"
                + " the other three; an item of the same project, never the entity itself."));
    row(
        table,
        EntityProperty.REPOSITORY_ID,
        false,
        nonBlank("The id of the one repository a task works in; it must be in the task's project."));
    row(
        table,
        EntityProperty.IMPLEMENTED_AT,
        true,
        dateTime(
            "The implemented marker, an ISO-8601 instant. Moves only while the owning epic is"
                + " READY_FOR_DEV or IMPLEMENTING."));
    row(
        table,
        EntityProperty.IMPLEMENTING_AT,
        false,
        dateTime(
            "The implementing marker, an ISO-8601 instant: when the implementation was started."
                + " Moves only while the owning epic is READY_FOR_DEV or IMPLEMENTING; history once"
                + " implementedAt is set, so it is never cleared."));
    row(
        table,
        EntityProperty.DEPENDS_ON,
        true,
        text("The id of the sibling this one waits for — ordering, never nesting."));
    row(
        table,
        EntityProperty.ACCEPTANCE_CRITERIA,
        true,
        list(
            "What the work is accepted against, in order: short Markdown statements. The whole list"
                + " is written at once; an empty list (or null on a patch) clears it. Frozen from"
                + " READY_FOR_DEV on: restating the same list passes, a changed one is a 409.",
            AcceptanceCriteria.RULES,
            AcceptanceCriteria.PATTERN));
    return Collections.unmodifiableMap(table);
  }

  private static void row(
      Map<EntityProperty, Slot> table,
      EntityProperty property,
      boolean clearable,
      Map<String, Object> schema) {
    if (EntityTransitionService.SERVER_OWNED.contains(property)) {
      throw new IllegalStateException(
          property + " is server-owned and has no wire slot — no door takes it from a caller");
    }
    table.put(property, new Slot(property, wire(property), clearable, schema));
  }

  // --- the reads ----------------------------------------------------------------------------------

  /** Every row, in the vocabulary's declaration order. */
  public static List<Slot> slots() {
    return List.copyOf(TABLE.values());
  }

  /** The row of {@code property}, or empty for a server-owned one. */
  public static Optional<Slot> of(EntityProperty property) {
    return Optional.ofNullable(TABLE.get(property));
  }

  /** The row a body's key names, or empty for a name the table does not know. */
  public static Optional<Slot> byWire(String name) {
    for (Slot slot : TABLE.values()) {
      if (slot.wire().equals(name)) {
        return Optional.of(slot);
      }
    }
    return Optional.empty();
  }

  /**
   * What a field edit writes, keyed by wire name: the table less the {@link #MOVES}. {@code
   * WorkEntityDoors}' editable set, read from here so the update schema and the PATCH
   * validator are one list.
   */
  public static Map<String, EntityProperty> editable() {
    Map<String, EntityProperty> editable = new LinkedHashMap<>();
    for (Slot slot : TABLE.values()) {
      if (!MOVES.contains(slot.property())) {
        editable.put(slot.wire(), slot.property());
      }
    }
    return Collections.unmodifiableMap(editable);
  }

  /** The wire names a merge patch may not null — {@code WorkEntityDoors}' other set. */
  public static List<String> notClearable() {
    List<String> names = new ArrayList<>();
    for (Slot slot : TABLE.values()) {
      if (!slot.clearable()) {
        names.add(slot.wire());
      }
    }
    return List.copyOf(names);
  }

  /** {@code TICKET_TYPE} → {@code ticketType}: the camelCase of the constant. */
  static String wire(EntityProperty property) {
    String[] words = property.name().toLowerCase(Locale.ROOT).split("_");
    StringBuilder out = new StringBuilder(words[0]);
    for (int i = 1; i < words.length; i++) {
      out.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1));
    }
    return out.toString();
  }

  // --- the fragments ------------------------------------------------------------------------------

  private static Map<String, Object> text(String description) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "string");
    schema.put("description", description);
    return Collections.unmodifiableMap(schema);
  }

  private static Map<String, Object> nonBlank(String description) {
    Map<String, Object> schema = new LinkedHashMap<>(text(description));
    schema.put("pattern", "\\S");
    return Collections.unmodifiableMap(schema);
  }

  private static Map<String, Object> oneOf(String description, List<String> words) {
    Map<String, Object> schema = new LinkedHashMap<>(text(description));
    schema.put("enum", List.copyOf(words));
    return Collections.unmodifiableMap(schema);
  }

  /** An array of strings, each item carrying its own rule as a description and a pattern. */
  private static Map<String, Object> list(String description, String itemRule, String pattern) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("type", "string");
    item.put("description", itemRule);
    item.put("pattern", pattern);
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "array");
    schema.put("description", description);
    schema.put("items", Collections.unmodifiableMap(item));
    return Collections.unmodifiableMap(schema);
  }

  /** Whether a fragment describes a list rather than a single value. */
  static boolean isList(Map<String, Object> schema) {
    return "array".equals(schema.get("type"));
  }

  private static Map<String, Object> dateTime(String description) {
    Map<String, Object> schema = new LinkedHashMap<>(text(description));
    schema.put("format", "date-time");
    return Collections.unmodifiableMap(schema);
  }
}

package eu.wohlben.qits.entities.api;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.entities.control.ArchetypeRegistryDocument;
import eu.wohlben.qits.entities.control.ArchetypeSpec;
import eu.wohlben.qits.entities.control.ArchetypeViolation;
import eu.wohlben.qits.entities.control.Archetypes;
import eu.wohlben.qits.entities.control.EntityProperty;
import eu.wohlben.qits.entities.control.EntityTransitionService;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * <b>The payload of every entity write door, as a JSON Schema per archetype</b> (qits-548) — built
 * from the registry ({@link Archetypes}) and the wire table ({@link EntityWireProperties}), and
 * nothing else.
 *
 * <p>Served as {@code GET /entities/archetypes/{archetype}/schemas/{door}}, so a client asks the
 * service what a new ticket needs instead of keeping its own mapping from {@code TICKET_TYPE} to
 * {@code ticketType} — the copy that drifted the old {@code qits ticket new} into a 400 on every
 * call. The generic create door validates <b>against the schema it would serve</b> ({@link
 * #refusals}), so the two cannot answer differently.
 *
 * <h2>The three doors</h2>
 *
 * <ul>
 *   <li><b>create</b> — {@code POST /entities}. The kind's permitted properties, less the
 *       server-owned pair, less what the writer mints or a later move states (the status, starting
 *       REPORTED; a supersede) and less the two task markers, implemented and implementing — which
 *       move only while the owning epic is READY_FOR_DEV or IMPLEMENTING, while a create under it needs
 *       the epic REPORTED, so no create could ever carry one. Plus the placement: {@code project} for a kind that may be a root, {@code parent}
 *       for one that sits below one, described as the id of the kind {@code WorkEntityService} looks
 *       it up as. Required: {@code requiredAtCreate} less the status, plus the placement.
 *   <li><b>update</b> — {@code PATCH /entities/{id}}, a merge patch. The same properties less the
 *       server-owned pair and the {@linkplain EntityWireProperties#MOVES moves}; a clearable one is
 *       typed {@code ["string","null"]}. Nothing is required, and at least one property must be named.
 *   <li><b>transition</b> — one entry of {@code POST /entities/transition}, the full post-state. The
 *       kind's permitted properties less the server-owned pair and the implementing marker (which
 *       that door carries rather than states), plus {@code membership}. Required:
 *       {@code requiredOnTransition} as the registry document serves it — and {@code membership} for a
 *       kind that may not be a root, because that door reads an absent membership as "a root" and the
 *       nesting rule then refuses a root feature. A registry that says {@code title} alone for a
 *       feature would publish a body that door always refuses.
 * </ul>
 */
public final class EntitySchemas {

  /** The door a schema describes, as the path spells it. */
  public enum Door {
    CREATE,
    UPDATE,
    TRANSITION;

    /** {@code create} → {@link #CREATE}; case-insensitive; empty for anything else. */
    public static Optional<Door> parse(String word) {
      if (word == null) {
        return Optional.empty();
      }
      for (Door door : values()) {
        if (door.name().equalsIgnoreCase(word.trim())) {
          return Optional.of(door);
        }
      }
      return Optional.empty();
    }

    String word() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** The placement of a new root: the project it goes in. */
  public static final String PROJECT = "project";

  /** The placement of a new node, and the member of a transition's {@code membership}. */
  public static final String PARENT = "parent";

  /** A transition entry's edge. */
  public static final String MEMBERSHIP = "membership";

  /** What a create never takes, beyond the server-owned pair: see the class javadoc. */
  private static final List<EntityProperty> NOT_AT_CREATE =
      List.of(
          EntityProperty.STATUS,
          EntityProperty.SUPERSEDED_BY,
          EntityProperty.IMPLEMENTED_AT,
          EntityProperty.IMPLEMENTING_AT);

  /**
   * What a transition entry never states: the implementing marker (qits-749). {@code
   * EntityTransition} has no slot for it, so that door carries it like the slug where the target
   * kind has room and clears it where it has none — publishing it as a property would advertise a
   * value the door ignores.
   */
  private static final List<EntityProperty> NOT_ON_TRANSITION =
      List.of(EntityProperty.IMPLEMENTING_AT);

  private static final String DRAFT = "https://json-schema.org/draft/2020-12/schema";

  private static final Pattern NON_BLANK = Pattern.compile("\\S");

  private EntitySchemas() {}

  /** The schema of {@code door} for {@code archetype}. */
  public static Map<String, Object> of(Archetype archetype, Door door) {
    return switch (door) {
      case CREATE -> create(archetype);
      case UPDATE -> update(archetype);
      case TRANSITION -> transition(archetype);
    };
  }

  // --- the three doors ----------------------------------------------------------------------------

  private static Map<String, Object> create(Archetype archetype) {
    ArchetypeSpec spec = Archetypes.spec(archetype);
    Map<String, Object> properties = new LinkedHashMap<>();
    List<String> required = new ArrayList<>();
    for (EntityWireProperties.Slot slot : writable(spec)) {
      if (NOT_AT_CREATE.contains(slot.property())) {
        continue;
      }
      properties.put(slot.wire(), slot.schema());
      if (spec.requiredAtCreate().contains(slot.property())) {
        required.add(slot.wire());
      }
    }
    if (spec.mayBeRoot()) {
      properties.put(
          PROJECT,
          reference("The project the new " + archetype + " goes in: its id or its slug."));
      required.add(PROJECT);
    }
    if (spec.depth() > 0) {
      properties.put(PARENT, reference(parentDescription(archetype)));
      required.add(PARENT);
    }
    return document(
        archetype,
        Door.CREATE,
        "The body of POST /projects/api/entities, less its archetype (\"archetype\": \""
            + archetype
            + "\" is added beside these). The status is minted REPORTED by the writer.",
        properties,
        required,
        null);
  }

  private static Map<String, Object> update(Archetype archetype) {
    ArchetypeSpec spec = Archetypes.spec(archetype);
    Map<String, Object> properties = new LinkedHashMap<>();
    for (EntityWireProperties.Slot slot : writable(spec)) {
      if (EntityWireProperties.MOVES.contains(slot.property())) {
        continue;
      }
      properties.put(slot.wire(), slot.clearable() ? nullable(slot.schema()) : slot.schema());
    }
    return document(
        archetype,
        Door.UPDATE,
        "A JSON merge patch (RFC 7396) of one "
            + archetype
            + ", sent to PATCH /projects/api/entities/{id}: an absent property is left unchanged,"
            + " null clears it. The status is moved through POST /projects/api/entities/{id}/status.",
        properties,
        List.of(),
        1);
  }

  private static Map<String, Object> transition(Archetype archetype) {
    ArchetypeSpec spec = Archetypes.spec(archetype);
    List<EntityProperty> onTransition = requiredOnTransition(archetype);
    Map<String, Object> properties = new LinkedHashMap<>();
    List<String> required = new ArrayList<>();
    for (EntityWireProperties.Slot slot : writable(spec)) {
      if (NOT_ON_TRANSITION.contains(slot.property())) {
        continue;
      }
      properties.put(slot.wire(), slot.schema());
      if (onTransition.contains(slot.property())) {
        required.add(slot.wire());
      }
    }
    properties.put(MEMBERSHIP, membership(archetype));
    if (!spec.mayBeRoot()) {
      required.add(MEMBERSHIP);
    }
    return document(
        archetype,
        Door.TRANSITION,
        "The full post-state of one row turned into a "
            + archetype
            + ", one entry of POST /projects/api/entities/transition (keyed by the row's id, with"
            + " \"archetype\": \""
            + archetype
            + "\" beside these). An absent property is CLEARED, and an absent membership means a"
            + " root.",
        properties,
        required,
        null);
  }

  // --- validation, against the schema itself -------------------------------------------------------

  /**
   * Every complaint about {@code body} as a create of {@code archetype}, judged against the create
   * schema this class serves — an unknown or unslotted property, a missing required one, a value of
   * the wrong shape — in body order and then schema order. Empty means the body is the schema's.
   * {@code archetype} itself is the door's and is skipped here.
   */
  public static List<String> createRefusals(Archetype archetype, JsonNode body) {
    Map<String, Object> schema = create(archetype);
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> properties =
        (Map<String, Map<String, Object>>) schema.get("properties");
    @SuppressWarnings("unchecked")
    List<String> required = (List<String>) schema.get("required");

    List<String> refused = new ArrayList<>();
    for (Iterator<String> names = body.fieldNames(); names.hasNext(); ) {
      String name = names.next();
      if (name.equals("archetype")) {
        continue;
      }
      Map<String, Object> property = properties.get(name);
      if (property == null) {
        refused.add(whyNotAtCreate(archetype, name));
      } else {
        value(name, property, body.get(name)).ifPresent(refused::add);
      }
    }
    for (String name : required) {
      JsonNode value = body.get(name);
      if (value == null || value.isNull()) {
        refused.add(name + " is required");
      }
    }
    return refused;
  }

  /** The complaint about one value, or none: the shape the fragment gives it. */
  private static Optional<String> value(String name, Map<String, Object> property, JsonNode value) {
    if (value == null || value.isNull()) {
      return Optional.empty(); // absence is the required check's to judge, not the value's
    }
    if (!value.isTextual()) {
      return Optional.of(name + " must be a string");
    }
    String text = value.textValue();
    Object words = property.get("enum");
    if (words instanceof List<?> legal && !legal.contains(text)) {
      return Optional.of(name + " must be one of " + legal + ": " + text);
    }
    if (property.containsKey("pattern") && !NON_BLANK.matcher(text).find()) {
      return Optional.of(name + " must not be blank");
    }
    if ("date-time".equals(property.get("format"))) {
      try {
        Instant.parse(text);
      } catch (DateTimeParseException e) {
        return Optional.of(name + " must be an ISO-8601 instant: " + text);
      }
    }
    return Optional.empty();
  }

  /** Why a name the create schema does not list is refused — the most useful sentence available. */
  private static String whyNotAtCreate(Archetype archetype, String name) {
    ArchetypeSpec spec = Archetypes.spec(archetype);
    if (name.equals(PROJECT)) {
      return "project is not taken: a "
          + archetype
          + " is never a root — name its parent ("
          + parentDescription(archetype)
          + ")";
    }
    if (name.equals(PARENT)) {
      return "parent is not taken: a " + archetype + " is a root — name its project";
    }
    for (EntityProperty owned : EntityTransitionService.SERVER_OWNED) {
      if (EntityWireProperties.wire(owned).equals(name)) {
        return name + " is server-owned and never written";
      }
    }
    Optional<EntityWireProperties.Slot> slot = EntityWireProperties.byWire(name);
    if (slot.isEmpty()) {
      return "unknown property: " + name;
    }
    EntityProperty property = slot.get().property();
    if (!spec.permits(property)) {
      return new ArchetypeViolation(archetype, property, ArchetypeViolation.Reason.NOT_PERMITTED, null)
          .message();
    }
    return switch (property) {
      case STATUS ->
          "status is not written at create: a new "
              + archetype
              + " is REPORTED, and moves through POST /projects/api/entities/{id}/status";
      case IMPLEMENTED_AT, IMPLEMENTING_AT ->
          name
              + " is not written at create: the marker moves only while the epic is READY_FOR_DEV"
              + " or IMPLEMENTING, and a create needs it REPORTED";
      default -> name + " is not written at create";
    };
  }

  // --- the pieces ---------------------------------------------------------------------------------

  /** The table's rows this kind permits, in vocabulary order — the server-owned pair has none. */
  private static List<EntityWireProperties.Slot> writable(ArchetypeSpec spec) {
    List<EntityWireProperties.Slot> slots = new ArrayList<>();
    for (EntityWireProperties.Slot slot : EntityWireProperties.slots()) {
      if (spec.permits(slot.property())) {
        slots.add(slot);
      }
    }
    return slots;
  }

  /** The registry document's {@code requiredOnTransition}, so the two answers are one list. */
  private static List<EntityProperty> requiredOnTransition(Archetype archetype) {
    for (ArchetypeRegistryDocument.DeclaredArchetype declared :
        ArchetypeRegistryDocument.describe().archetypes()) {
      if (declared.archetype() == archetype) {
        return declared.requiredOnTransition();
      }
    }
    throw new IllegalStateException("the registry document does not declare " + archetype);
  }

  /** {@code "The id of an EPIC …"}, from the kind the writer looks the parent up as. */
  private static String parentDescription(Archetype archetype) {
    Archetype parent = WorkEntityService.parentKindOf(archetype);
    return "The id of "
        + (parent == null ? "its parent" : article(parent) + " " + parent)
        + " — its UUID or its qualified id (<projectSlug>-<n>).";
  }

  private static String article(Archetype archetype) {
    return "AEIOU".indexOf(archetype.name().charAt(0)) >= 0 ? "an" : "a";
  }

  private static Map<String, Object> membership(Archetype archetype) {
    ArchetypeSpec spec = Archetypes.spec(archetype);
    Map<String, Object> parent = new LinkedHashMap<>();
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    if (spec.mayBeRoot()) {
      parent.put("type", "null");
      parent.put("description", "A " + archetype + " is a root: it has no parent.");
      schema.put(
          "description",
          "Where the row hangs. A " + archetype + " is a root, so this may be left out.");
    } else {
      parent.put("type", "string");
      parent.put("pattern", "\\S");
      parent.put(
          "description",
          "The id of "
              + article(WorkEntityService.parentKindOf(archetype))
              + " "
              + WorkEntityService.parentKindOf(archetype)
              + " (its UUID).");
      schema.put("description", "Where the row hangs: its parent, and its place among siblings.");
    }
    Map<String, Object> position = new LinkedHashMap<>();
    position.put("type", "integer");
    position.put("minimum", 0);
    position.put(
        "description", "Zero-based place among the parent's children; absent appends it last.");
    Map<String, Object> members = new LinkedHashMap<>();
    members.put(PARENT, parent);
    members.put("position", position);
    schema.put("properties", members);
    schema.put("required", spec.mayBeRoot() ? List.of() : List.of(PARENT));
    schema.put("additionalProperties", false);
    return schema;
  }

  private static Map<String, Object> reference(String description) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "string");
    schema.put("pattern", "\\S");
    schema.put("description", description);
    return schema;
  }

  /** The fragment, typed to admit null — and its enum too, where it has one. */
  private static Map<String, Object> nullable(Map<String, Object> fragment) {
    Map<String, Object> schema = new LinkedHashMap<>(fragment);
    schema.put("type", List.of("string", "null"));
    if (fragment.get("enum") instanceof List<?> words) {
      List<Object> withNull = new ArrayList<>(words);
      withNull.add(null);
      schema.put("enum", Collections.unmodifiableList(withNull));
    }
    return schema;
  }

  private static Map<String, Object> document(
      Archetype archetype,
      Door door,
      String description,
      Map<String, Object> properties,
      List<String> required,
      Integer minProperties) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("$schema", DRAFT);
    schema.put("title", archetype + " " + door.word());
    schema.put("description", description);
    schema.put("type", "object");
    schema.put("properties", Collections.unmodifiableMap(properties));
    schema.put("required", List.copyOf(required));
    if (minProperties != null) {
      schema.put("minProperties", minProperties);
    }
    schema.put("additionalProperties", false);
    return Collections.unmodifiableMap(schema);
  }
}

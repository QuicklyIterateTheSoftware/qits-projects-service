package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EpicStatus;
import eu.wohlben.qits.entities.entity.TicketStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <b>The archetype registry: what each of the four kinds is, declared as data.</b>
 *
 * <p>A merged table has a column for every archetype's every property, so the row shape says nothing
 * about which of them belong on any given row. The rule has to live somewhere, and this is that
 * somewhere — one {@link ArchetypeSpec} per kind, holding a depth, whether it may stand alone, the
 * properties it requires, the properties it permits and the status words its lifecycle is written
 * in. Everything else in the module asks this class; nothing else re-decides any of it.
 *
 * <p><b>It is a component and not a switch inside a controller because it has two callers with
 * different shapes.</b> The ordinary write judges one candidate row; the multi-entity transition
 * judges a whole intended post-state and needs {@link Nesting} on top of the same declarations. A
 * condition duplicated into both would be free to drift, and the drift would be invisible — the two
 * paths write the same table and a row admitted by one is read by the other.
 *
 * <h2>Depth</h2>
 *
 * <p>{@link Archetype#EPIC} and {@link Archetype#TICKET} are both <b>0</b>, {@link
 * Archetype#FEATURE} is <b>1</b>, {@link Archetype#TASK} is <b>2</b>. Two things about that are
 * decisions rather than notation:
 *
 * <ul>
 *   <li><b>It is a declared number, never the enum ordinal.</b> A kind above {@code EPIC} — the
 *       campaign this model is being merged in order to allow — would have to be <em>inserted at
 *       position zero</em> of an enum whose names are in a check constraint and in every row of a
 *       table for an ordinal-derived depth to work. Declaring the number costs one field and makes
 *       that change a one-line addition.
 *   <li><b>Only the ORDER of the numbers matters; the values themselves mean nothing.</b> The
 *       nesting rule is "strictly less than" and there is no arithmetic on depths anywhere — no
 *       "one level down", no maximum, no count. So a campaign declares <b>-1</b> and the existing
 *       three numbers do not move. That is the choice, made here rather than left to whoever adds
 *       it: <em>renumbering</em> the estate (campaign 0, epic 1, feature 2, task 3) would be a
 *       second answer to a question every reader of this file has already been given, and would
 *       silently invalidate anything that had written 1 down as "feature". Depths are relative, and
 *       going negative upwards is how they stay so.
 * </ul>
 *
 * <p><b>Nothing derives "may be a root" from depth</b>, and that separation is the same argument
 * one step on. Depth answers "what may contain what"; rootness answers "what may stand alone". They
 * agree today only by accident — the two roots happen to be the two shallowest kinds — and they come
 * apart the moment a campaign is declared above them: an epic stops being at the shallowest depth
 * and must go on being a legal root, while a campaign becomes shallowest and is one too. Deriving
 * rootness from "is the minimum depth" would revoke epic-as-root on the day the campaign lands, with
 * nothing in the diff saying so. So it is a declared flag, and widening it is one word.
 *
 * <h2>What "required" and "permitted" are read from</h2>
 *
 * <p>The four declarations below are what the four existing services actually enforce today, and
 * each one is that service's own rule rather than a tidier version of it:
 *
 * <ul>
 *   <li>{@code EpicService.create} requires a project and a title, and nothing else.
 *   <li>{@code TicketService.create} requires a title, an impetus and a type, and the status column
 *       is {@code not null} from the first insert.
 *   <li>{@code FeatureService.create} requires a title.
 *   <li>{@code TaskService.create} requires a title and a repository id.
 * </ul>
 *
 * <p><b>The asymmetry on {@code STATUS} is deliberate and is worth naming, because it looks like an
 * oversight.</b> A ticket <em>requires</em> a status and an epic merely permits one. The reason is
 * what each column means at the moment of the check: an epic's phase is minted by the writer (every
 * epic starts {@code REFINING}, set by the service and never by a caller), so demanding it of a
 * candidate would fail every create before the writer had run — the same reason {@link
 * EntityProperty#SLUG} is required of nobody. A ticket's status, by contrast, is a statement the
 * intake surfaces already make and that the transition API moves; the five words are the ticket's
 * whole lifecycle and a ticket without one is not a ticket in any phase.
 *
 * <h2>What a check answers</h2>
 *
 * <p>{@link #validate(EntityState)} returns <b>every</b> violation, never the first. A caller that
 * fixes one problem per round trip is the failure mode this exists to avoid — a person filling in a
 * form, or worse an agent, being told about a second missing field only after supplying the first.
 * The list is ordered by the property vocabulary so two runs over the same state produce the same
 * list, which is what makes it assertable.
 */
public final class Archetypes {

  /** {@code EpicStatus}' five words, as stored. */
  private static final Set<String> EPIC_STATUSES = names(EpicStatus.values());

  /** {@code TicketStatus}' five words, as stored. */
  private static final Set<String> TICKET_STATUSES = names(TicketStatus.values());

  private static final Map<Archetype, ArchetypeSpec> REGISTRY = declare();

  private Archetypes() {}

  /**
   * The four declarations, and the only place any of this is written down.
   *
   * <p>{@link EntityProperty#TITLE} and {@link EntityProperty#SLUG} are on every kind — a row
   * without a title cannot be listed, and every row names a branch — with only the title required,
   * because the slug is minted from it by the writer rather than supplied.
   */
  private static Map<Archetype, ArchetypeSpec> declare() {
    Map<Archetype, ArchetypeSpec> registry = new EnumMap<>(Archetype.class);

    // A plan. Root, depth 0. Its status is the four-phase epic lifecycle; supersededBy is the
    // successor draft a supersede spawns and exists on no other kind.
    registry.put(
        Archetype.EPIC,
        new ArchetypeSpec(
            Archetype.EPIC,
            0,
            true,
            EnumSet.of(EntityProperty.TITLE),
            EnumSet.of(
                EntityProperty.TITLE,
                EntityProperty.SLUG,
                EntityProperty.DESCRIPTION,
                EntityProperty.STATUS,
                EntityProperty.SUPERSEDED_BY),
            EPIC_STATUSES));

    // A small-scoped bug or improvement. Root beside the epic, depth 0 — the two are siblings and
    // a ticket under an epic is refused by exactly that equality, which is the nesting rule doing
    // what V4's "nothing joins the two tables and nothing should" says in prose.
    registry.put(
        Archetype.TICKET,
        new ArchetypeSpec(
            Archetype.TICKET,
            0,
            true,
            EnumSet.of(
                EntityProperty.TITLE,
                EntityProperty.TICKET_TYPE,
                EntityProperty.IMPETUS,
                EntityProperty.STATUS),
            EnumSet.of(
                EntityProperty.TITLE,
                EntityProperty.SLUG,
                EntityProperty.DESCRIPTION,
                EntityProperty.STATUS,
                EntityProperty.TICKET_TYPE,
                EntityProperty.IMPETUS,
                EntityProperty.ASSIGNEE,
                EntityProperty.CREATED_BY),
            TICKET_STATUSES));

    // A piece of a plan. No status of its own — a feature's phase is its epic's, which is why
    // EpicLifecycle judges a task by the phase of its feature's epic rather than by anything on the
    // row. What it carries instead is the implemented marker and a sibling dependency.
    registry.put(
        Archetype.FEATURE,
        new ArchetypeSpec(
            Archetype.FEATURE,
            1,
            false,
            EnumSet.of(EntityProperty.TITLE),
            EnumSet.of(
                EntityProperty.TITLE,
                EntityProperty.SLUG,
                EntityProperty.DESCRIPTION,
                EntityProperty.DEPENDS_ON,
                EntityProperty.IMPLEMENTED_AT),
            Set.of()));

    // Work in one concrete repository — the only kind that names one, and the reason it is required
    // rather than permitted: a task without a repository is a feature with extra steps.
    registry.put(
        Archetype.TASK,
        new ArchetypeSpec(
            Archetype.TASK,
            2,
            false,
            EnumSet.of(EntityProperty.TITLE, EntityProperty.REPOSITORY_ID),
            EnumSet.of(
                EntityProperty.TITLE,
                EntityProperty.SLUG,
                EntityProperty.DESCRIPTION,
                EntityProperty.REPOSITORY_ID,
                EntityProperty.DEPENDS_ON,
                EntityProperty.IMPLEMENTED_AT),
            Set.of()));

    verify(registry);
    return Map.copyOf(registry);
  }

  /**
   * <b>The declarations are checked at class-initialisation time, not trusted.</b> A bad one is a
   * rule that can never be satisfied or a rule that never fires, and both are silent: a required
   * property outside the permitted set makes every candidate fail with two contradictory violations
   * at once, and a status vocabulary on a kind whose {@code permitted} set has no {@code STATUS}
   * makes the status check unreachable. Failing here turns either into a refusal to start, naming
   * the archetype — which is the only moment anybody is looking.
   */
  private static void verify(Map<Archetype, ArchetypeSpec> registry) {
    for (Archetype archetype : Archetype.values()) {
      ArchetypeSpec spec = registry.get(archetype);
      if (spec == null) {
        throw new IllegalStateException(
            "archetype " + archetype + " is not declared in Archetypes — every kind must be");
      }
      if (!spec.permitted().containsAll(spec.required())) {
        Set<EntityProperty> stray = EnumSet.copyOf(spec.required());
        stray.removeAll(spec.permitted());
        throw new IllegalStateException(
            "archetype "
                + archetype
                + " requires properties it does not permit: "
                + stray
                + " — required must be a subset of permitted");
      }
      boolean declaresStatus = spec.permits(EntityProperty.STATUS);
      if (declaresStatus == spec.legalStatuses().isEmpty()) {
        throw new IllegalStateException(
            "archetype "
                + archetype
                + (declaresStatus
                    ? " permits STATUS but declares no legal status words"
                    : " declares legal status words but does not permit STATUS"));
      }
    }
  }

  /** What {@code archetype} declares. Total over the enum — {@link #verify} is what makes it so. */
  public static ArchetypeSpec spec(Archetype archetype) {
    return REGISTRY.get(archetype);
  }

  /** How deep rows of this kind sit. Only the ORDER of these numbers means anything — see the class javadoc. */
  public static int depth(Archetype archetype) {
    return spec(archetype).depth();
  }

  /** Whether a row of this kind may stand with no parent. Declared, never derived from {@link #depth}. */
  public static boolean mayBeRoot(Archetype archetype) {
    return spec(archetype).mayBeRoot();
  }

  /** The status words legal on this kind, as stored; empty for a kind with no lifecycle. */
  public static Set<String> legalStatuses(Archetype archetype) {
    return spec(archetype).legalStatuses();
  }

  /**
   * <b>Every violation in {@code candidate}, in vocabulary order, or an empty list.</b>
   *
   * <p>Three questions, asked of every candidate and all three always asked: is every required
   * property there, is every property it carries one this kind has a slot for, and — when it carries
   * a status — is that word in this kind's lifecycle. The third is the one the database cannot ask,
   * because {@code ck_entity_status} is the union of both enums.
   */
  public static List<ArchetypeViolation> validate(EntityState candidate) {
    ArchetypeSpec spec = spec(candidate.archetype());
    List<ArchetypeViolation> violations = new ArrayList<>();

    for (EntityProperty property : EntityProperty.values()) {
      boolean present = candidate.present().contains(property);
      if (spec.required().contains(property) && !present) {
        violations.add(
            new ArchetypeViolation(
                candidate.archetype(),
                property,
                ArchetypeViolation.Reason.MISSING_REQUIRED,
                null));
      } else if (present && !spec.permits(property)) {
        violations.add(
            new ArchetypeViolation(
                candidate.archetype(), property, ArchetypeViolation.Reason.NOT_PERMITTED, null));
      }
    }

    // The status VALUE, which is a different question from the status property being allowed at
    // all. A kind that does not permit STATUS has already been told so above, and saying it twice
    // would make a caller fix one field to be told about the same field again.
    String status = candidate.status();
    if (status != null && spec.permits(EntityProperty.STATUS)
        && !spec.legalStatuses().contains(status)) {
      violations.add(
          new ArchetypeViolation(
              candidate.archetype(),
              EntityProperty.STATUS,
              ArchetypeViolation.Reason.ILLEGAL_STATUS,
              status));
    }

    return List.copyOf(violations);
  }

  /** The same check over a row that is already assembled — a backfill's and a repair's question. */
  public static List<ArchetypeViolation> validate(WorkEntity entity) {
    return validate(EntityState.of(entity));
  }

  private static Set<String> names(Enum<?>[] values) {
    return Arrays.stream(values).map(Enum::name).collect(Collectors.toUnmodifiableSet());
  }
}

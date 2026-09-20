package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.Archetype;
import java.util.Set;

/**
 * <b>Everything one archetype declares</b>, as data. The registry is a map from {@link Archetype} to
 * one of these and nothing else — which is the point: adding a fifth kind is one more of these plus
 * a migration, and no rule anywhere names an archetype by hand.
 *
 * @param archetype which kind this declares
 * @param depth how deep in a tree rows of this kind sit — see {@link Archetypes} for why it is a
 *     declared number, why only the ORDER of the numbers matters, and why a kind above {@link
 *     Archetype#EPIC} declares a negative one
 * @param mayBeRoot whether a row of this kind may stand with no parent. <b>Declared, not derived
 *     from {@link #depth}</b>: depth answers "what may contain what" and this answers "what may
 *     stand alone", and the two come apart the moment something is declared above the roots — an
 *     epic stops being at the shallowest depth and must go on being a legal root
 * @param required the properties a row of this kind must carry <b>at every moment of its life</b> —
 *     the invariant, and what every UPDATE path is judged against. A subset of {@link
 *     #requiredAtCreate} and of {@link #permitted}, asserted at class-initialisation time rather
 *     than trusted
 * @param requiredAtCreate the properties <b>intake</b> demands of a row being born. A superset of
 *     {@link #required} and spelled in full for {@link #permitted}'s reason — a declaration a
 *     reader has to assemble from two lists is a declaration nobody reads. <b>The two differ
 *     wherever a property is demanded once and not owed for ever</b>, which today is {@code
 *     IMPETUS} on a {@code TICKET}: a report consists of it, and {@code entity.impetus} is nullable
 *     because clearing one afterwards is a thing a person does. See {@link Demand}
 * @param permitted every property a row of this kind may carry, required ones included. A property
 *     outside this set is <b>refused</b> on a write and never silently dropped: a caller that sent
 *     it meant something by it, and dropping it would lose the meaning and the complaint together
 * @param legalStatuses the exact status words legal on this kind, as stored — {@code EpicStatus}'
 *     five for an epic, {@code TicketStatus}' five for a ticket, none at all for a feature or a
 *     task. Empty means the kind has no status, which is why {@link EntityProperty#STATUS} is
 *     outside its {@link #permitted} set as well; the two say the same thing from two directions and
 *     {@link Archetypes} checks that they agree
 */
public record ArchetypeSpec(
    Archetype archetype,
    int depth,
    boolean mayBeRoot,
    Set<EntityProperty> required,
    Set<EntityProperty> requiredAtCreate,
    Set<EntityProperty> permitted,
    Set<String> legalStatuses) {

  public ArchetypeSpec {
    required = Set.copyOf(required);
    requiredAtCreate = Set.copyOf(requiredAtCreate);
    permitted = Set.copyOf(permitted);
    legalStatuses = Set.copyOf(legalStatuses);
  }

  /** Whether this kind may carry {@code property} at all. */
  public boolean permits(EntityProperty property) {
    return permitted.contains(property);
  }

  /**
   * The properties demanded at {@code demand}'s moment — {@link #requiredAtCreate} for intake,
   * {@link #required} for a row that already exists.
   *
   * <p>One method rather than a caller branching on the enum, so "which set answers which moment"
   * is written down once and a third moment, if there is ever one, is an addition here.
   */
  public Set<EntityProperty> requiredFor(Demand demand) {
    return switch (demand) {
      case AT_CREATE -> requiredAtCreate;
      case ON_UPDATE -> required;
    };
  }
}

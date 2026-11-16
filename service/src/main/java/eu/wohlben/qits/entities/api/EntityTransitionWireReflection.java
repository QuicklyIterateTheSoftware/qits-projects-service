package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.control.EntityTransition;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.TicketType;
import eu.wohlben.qits.entities.entity.WorkOutcome;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * What the native image owes {@code EntityTransition}, and nothing else lives here.
 *
 * <p>{@code WorkController}'s {@code PUT /work/{qualifiedId}} and {@code POST /work/transition}
 * take a {@code JsonNode} body, never {@code EntityTransition} itself, so {@code
 * WorkEntityDoors.entry(JsonNode)} can refuse the retired {@code dependsOn} by name (qits-1903)
 * before Jackson builds a record that has no slot for it. Quarkus registers a resource method's
 * own parameter and return types for reflection, and a {@code JsonNode} parameter tells it nothing
 * about the {@code json.treeToValue(value, EntityTransition.class)} that follows inside the door.
 *
 * <p><b>A JVM test cannot catch a missing entry</b>: on a JVM every class reflects whether anyone
 * registered it or not, so the guard is that this list and the record are kept the same list, by
 * {@code EntityTransitionWireReflectionTest}. Measured on the native binary without it: both doors
 * answered every request with 400 "Cannot construct instance of EntityTransition ... this appears
 * to be a native image" (qits-2276), with the whole suite green.
 *
 * <p>The three enums are on the list beside the record and its {@code Membership} because they are
 * what {@code archetype}, {@code ticketType} and {@code outcome} read as, and an enum's constants
 * are found reflectively.
 *
 * <p>It lives in the service rather than in {@code entities} for the reason {@code
 * agenthost/AgentConfigurationWireReflection}'s does: a registration is something the
 * <b>deployable</b> owes the builder about itself, and {@code entities} is a plain library jar.
 */
@RegisterForReflection(
    targets = {
      EntityTransition.class,
      EntityTransition.Membership.class,
      Archetype.class,
      TicketType.class,
      WorkOutcome.class
    })
public final class EntityTransitionWireReflection {

  private EntityTransitionWireReflection() {}
}

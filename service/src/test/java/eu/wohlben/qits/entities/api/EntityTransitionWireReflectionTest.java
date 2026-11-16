package eu.wohlben.qits.entities.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.control.EntityTransition;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.TicketType;
import eu.wohlben.qits.entities.entity.WorkOutcome;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Guards {@link EntityTransitionWireReflection}'s <em>completeness</em>, which is all a JVM test
 * can reach here.
 *
 * <p>Say it plainly, the way {@code AgentConfigurationWireReflectionTest} does: on a JVM every
 * class reflects whether anyone registered it or not, so deleting the annotation tomorrow would
 * fail nothing but the assertions that read it. Only the native artifact proves the registration
 * does its job. What is checkable is that the registered set still covers every {@code
 * eu.wohlben.qits} type {@link EntityTransition} is built from — and a missed entry is a deployed
 * binary that cannot build the record Jackson is handed, with the whole suite green.
 */
public class EntityTransitionWireReflectionTest {

  @Test
  public void theRegisteredTargetsAreExactlyTheFiveTypes() {
    RegisterForReflection registration =
        EntityTransitionWireReflection.class.getAnnotation(RegisterForReflection.class);
    assertNotNull(registration, "the annotation IS the class; without it this file is a no-op");
    assertEquals(
        Set.of(
            EntityTransition.class,
            EntityTransition.Membership.class,
            Archetype.class,
            TicketType.class,
            WorkOutcome.class),
        Set.of(registration.targets()),
        "the record, its nested Membership, and the three enums it serializes — a further type"
            + " reachable from EntityTransition means a line here");
  }

  /**
   * Walks {@link EntityTransition}'s record components recursively — a record's own components
   * recurse into their types, a {@code List} or other parameterized type recurses into its type
   * arguments — and collects every type whose package starts with {@code eu.wohlben.qits}. This is
   * the check that would catch a nested record or enum being added to the transition and not to
   * the list: a JVM test cannot prove the native image reflects a type, but it can prove the type
   * is at least on the list this class and {@link EntityTransitionWireReflection} share.
   */
  @Test
  public void everyQitsTypeReachableFromEntityTransitionIsRegistered() {
    Set<Class<?>> registered =
        Set.of(
            EntityTransitionWireReflection.class
                .getAnnotation(RegisterForReflection.class)
                .targets());

    Set<Class<?>> reachable = new HashSet<>();
    collect(EntityTransition.class, reachable);

    for (Class<?> type : reachable) {
      assertTrue(
          registered.contains(type),
          type.getName() + " is reachable from EntityTransition but is not registered");
    }
  }

  private static void collect(Class<?> type, Set<Class<?>> seen) {
    if (type == null || !type.getName().startsWith("eu.wohlben.qits") || !seen.add(type)) {
      return;
    }
    if (type.isRecord()) {
      for (RecordComponent component : type.getRecordComponents()) {
        collectType(component.getGenericType(), seen);
      }
    }
  }

  private static void collectType(Type type, Set<Class<?>> seen) {
    if (type instanceof ParameterizedType parameterized) {
      for (Type argument : parameterized.getActualTypeArguments()) {
        collectType(argument, seen);
      }
      collectType(parameterized.getRawType(), seen);
    } else if (type instanceof Class<?> clazz) {
      collect(clazz, seen);
    }
  }
}

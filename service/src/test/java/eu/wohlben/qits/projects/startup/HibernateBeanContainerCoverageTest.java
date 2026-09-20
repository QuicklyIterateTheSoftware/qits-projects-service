package eu.wohlben.qits.projects.startup;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The guard that makes {@link HibernateBeanContainerWarmup}'s list complete instead of merely
 * correct. It derives, from this application's own classes, every type Hibernate would resolve
 * through the shared bean container, and requires each one to be warmed.
 *
 * <p><b>What it is protecting.</b> The warm-up orders away a real boot race — one unsynchronised
 * {@code AbstractCdiBeanContainer} shared by every persistence unit's startup thread, measured at 2
 * failures in 7 runs — and it does so by resolving the racing types first. It therefore covers
 * exactly the types it names. When this class was written the application declared precisely one
 * such type ({@code CausationStamp}, through {@code @EntityListeners}), and that was established by
 * a hand audit. <b>A hand audit ages.</b> Adding an {@code AttributeConverter} — an entirely
 * ordinary thing to do, in a commit that has nothing to do with any of this — silently reopens the
 * race, and the symptom lands in an unrelated {@code @TestProfile}'s boot one run in three, or in a
 * deployment. This test is that audit made standing: the next person to add one gets a red build
 * naming their class.
 *
 * <p><b>Two sets, derived two ways, and their equality is the claim.</b> The discovered set is read
 * out of the bytecode; the warmed set is the list the warm-up actually iterates. Neither is written
 * down twice, so they cannot quietly drift the way a documented audit did.
 *
 * <p><b>Why the shapes below and not others.</b> Each is a type Hibernate instantiates through
 * {@code ManagedBeanRegistry}/{@code BeanContainer} rather than with {@code new}, which is what puts
 * it on the racing {@code registeredBeans.add} path. Entity listeners are the live one; the rest are
 * the ones a person is plausibly about to add. Deliberately absent: {@code StatementInspector},
 * which Quarkus resolves through Arc directly at {@code
 * FastBootEntityManagerFactoryBuilder.populate()} and not through Hibernate's container at all —
 * which is precisely why the warm-up can use it as its carrier without racing itself.
 *
 * <p><b>The shape names are checked for existence, because a name that matches nothing is a guard
 * that silently does not run.</b> They are strings so that this test does not drag Hibernate SPI
 * imports into a module that has no business naming them; the cost of a string is a typo, or a
 * Hibernate upgrade that moves a class, either of which would disable a rule while leaving it
 * looking enabled. {@link #everyShapeNameStillResolves} is what turns that into a failure. It is the
 * same lesson qits-arch-rules records about matching types by name.
 *
 * <p>Plain JUnit and no {@code @QuarkusTest}: this reads class files, so a Quarkus application would
 * cost the suite ~125 MB of retained metaspace for nothing (the test-profile budget rule).
 */
class HibernateBeanContainerCoverageTest {

  /**
   * Types Hibernate obtains from the bean container by assignability. A class here is one whose
   * instance Hibernate asks the container for, which is the whole criterion.
   */
  private static final List<String> CONTAINER_RESOLVED_SHAPES =
      List.of(
          "jakarta.persistence.AttributeConverter",
          "org.hibernate.usertype.UserType",
          "org.hibernate.usertype.CompositeUserType",
          "org.hibernate.metamodel.spi.EmbeddableInstantiator",
          "org.hibernate.Interceptor",
          "org.hibernate.generator.Generator");

  /** The same, reached by annotation rather than by interface. */
  private static final List<String> CONTAINER_RESOLVED_ANNOTATIONS =
      List.of("jakarta.persistence.Converter");

  private static final String ENTITY = "jakarta.persistence.Entity";
  private static final String ENTITY_LISTENERS = "jakarta.persistence.EntityListeners";

  /**
   * Both entity modules and the eventstream jar, because a type reaches the container by being
   * declared, not by which artifact ships it — {@code CausationStamp} itself lives in the jar.
   */
  private static final String[] ANALYSED =
      {"eu.wohlben.qits.projects", "eu.wohlben.qits.entities", "eu.wohlben.qits.eventstream"};

  @Test
  void everyTypeThatReachesHibernatesBeanContainerIsWarmed() {
    Set<String> discovered = discover();
    Set<String> warmed =
        HibernateBeanContainerWarmup.resolvedTypes().stream()
            .map(Class::getName)
            .collect(Collectors.toCollection(TreeSet::new));

    assertEquals(
        discovered,
        warmed,
        "every type this application makes Hibernate resolve through its shared, unsynchronised"
            + " bean container must be named in"
            + " HibernateBeanContainerWarmup.RESOLVED_THROUGH_THE_CONTAINER, so each persistence"
            + " unit's startup thread resolves it once under that class's lock before any"
            + " SessionFactory is built. A type discovered here but not warmed is the boot race"
            + " reopened: two JPA startup threads reach registeredBeans.add on one ArrayList and"
            + " one writes index 1 of a length-0 array, which surfaces as"
            + " ArrayIndexOutOfBoundsException in AbstractCdiBeanContainer.createBean during an"
            + " unrelated test's boot, about one run in three. Add the class to that list. A type"
            + " warmed but not discovered is the opposite and is also worth fixing: it means the"
            + " list outlived whatever declared it");
  }

  @Test
  void everyShapeNameStillResolves() {
    for (String name : CONTAINER_RESOLVED_SHAPES) {
      assertDoesNotThrow(
          () -> Class.forName(name),
          name
              + " names no class on the test classpath, so the rule matching it can never fire and"
              + " this guard is quietly narrower than it reads. Either the SPI moved in a Hibernate"
              + " upgrade — in which case follow it — or the name is a typo");
    }
    for (String name : CONTAINER_RESOLVED_ANNOTATIONS) {
      assertDoesNotThrow(() -> Class.forName(name), name + " names no annotation on the classpath");
    }
  }

  private static Set<String> discover() {
    JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages(ANALYSED);

    Set<String> discovered = new TreeSet<>();
    for (JavaClass type : classes) {
      if (type.isAnnotatedWith(ENTITY)) {
        listenersOf(type, discovered);
      }
      for (String shape : CONTAINER_RESOLVED_SHAPES) {
        if (!type.getName().equals(shape) && type.isAssignableTo(shape)) {
          discovered.add(type.getName());
        }
      }
      for (String annotation : CONTAINER_RESOLVED_ANNOTATIONS) {
        if (type.isAnnotatedWith(annotation)) {
          discovered.add(type.getName());
        }
      }
    }
    return discovered;
  }

  /** Reads the classes an entity's {@code @EntityListeners} names — the live container path. */
  private static void listenersOf(JavaClass entity, Set<String> into) {
    entity
        .tryGetAnnotationOfType(ENTITY_LISTENERS)
        .flatMap(annotation -> annotation.get("value"))
        .ifPresent(
            value -> {
              if (value instanceof Object[] listeners) {
                for (Object listener : listeners) {
                  if (listener instanceof JavaClass named) {
                    into.add(named.getName());
                  }
                }
              }
            });
  }
}

package eu.wohlben.qits.projects.startup;

import eu.wohlben.qits.eventstream.CausationStamp;
import io.quarkus.hibernate.orm.PersistenceUnitExtension;
import io.quarkus.hibernate.orm.runtime.cdi.QuarkusManagedBeanRegistry;
import jakarta.enterprise.context.Dependent;
import java.io.Serial;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Resolves {@link CausationStamp} through Hibernate's bean container ONCE, under this class's own
 * lock, on each persistence unit's startup thread before that thread builds its {@code
 * SessionFactory}. It exists for that side effect alone; the {@link StatementInspector} it
 * implements is the carrier, not the purpose, and {@link #inspect} returns the statement unchanged.
 *
 * <p><b>The failure it removes.</b> {@code ./mvnw clean verify} failed about one run in three, at
 * Quarkus boot, always the same way and never in the same test twice — whichever {@code
 * @TestProfile}'s application happened to lose the race reported {@code Failed to start quarkus} /
 * {@code Unable to build Hibernate SessionFactory} / {@code ArrayIndexOutOfBoundsException: Index 1
 * out of bounds for length 0} at {@code AbstractCdiBeanContainer.createBean}. It was measured on the
 * {@code epics} unit and on the {@code projects} unit, in {@code EpicDispatchWithNoWorkspacesTest}
 * and in {@code BearerJwksTest}. That wandering is the signature: the defect is not in any test and
 * not in any entity, it is in what two threads do to one list.
 *
 * <p><b>The mechanism, read out of the shipped bytecode rather than inferred.</b> Four facts
 * compose, and no one of them is a bug on its own:
 *
 * <ul>
 *   <li>{@code JPAConfig.startAll()} starts <b>one bare thread per persistence unit</b> and joins
 *       only after the loop. This application has three — {@code projects}, {@code epics} and the
 *       eventstream jar's {@code eventstream} — so three threads boot at once. The {@code
 *       synchronized} inside {@code JPAConfig$LazyPersistenceUnit.get()} is a monitor on that one
 *       unit and gives no exclusion between units.
 *   <li>Every unit's {@code QuarkusManagedBeanRegistry} constructor does {@code
 *       Arc.container().instance(QuarkusArcBeanContainer.class).get()}, and that class is {@code
 *       @Singleton} — so all three units share <b>one</b> bean container object.
 *   <li>That container extends Hibernate's {@code AbstractCdiBeanContainer}, whose {@code beanCache}
 *       is a plain {@code HashMap} and whose {@code registeredBeans} is a plain {@code ArrayList}.
 *       Nothing in the class is synchronized. {@code getCacheableBean} is a check-then-act — miss
 *       the cache, {@code createBean}, then {@code put} — and {@code createBean}'s last act is
 *       {@code registeredBeans.add(bean)}.
 *   <li>{@code @EntityListeners(CausationStamp.class)} resolves through that container: Hibernate
 *       turns it into a {@code ListenerCallback} whose {@code createCallback} calls {@code
 *       registry.getBean(...)}. Entities in <b>both</b> application units carry it, so both threads
 *       reach that {@code add} on a still-empty {@code ArrayList} and one of them writes index 1 of
 *       a length-0 array.
 * </ul>
 *
 * <p>Commit 2c6b3c0 did not introduce this. It added two more entities carrying the listener to the
 * {@code epics} unit, which widened a window that had been open since the second unit did.
 *
 * <p><b>Why the warm-up is a fix and not a narrowing.</b> After one completed resolution the cache
 * holds the entry, so every later {@code getBean} for this type — from any thread, for the rest of
 * the boot — returns at {@code getCacheableBean}'s {@code existing != null} branch and never reaches
 * {@code createBean}. {@code registeredBeans.add} therefore runs <b>exactly once, on one thread</b>.
 * The lock is what makes that true rather than likely: the first unit's thread publishes the cache
 * entry before releasing it, and every other unit's thread acquires it before looking, so the read
 * that decides is ordered behind the write that satisfies it.
 *
 * <p><b>Why this seam and not a better-named one.</b> Three routes were measured and rejected. No
 * configuration exists: {@code QuarkusManagedBeanRegistryInitiator.initiateService} never reads its
 * settings map and the registry resolves the singleton unconditionally, so no property gives a unit
 * its own container or serialises the startup threads. Making {@code CausationStamp} a real CDI bean
 * does not help — {@code registeredBeans.add} runs inside {@code createBean} whether Arc holds the
 * bean or not, so a resolvable bean is still a racing add. And no ordinary lifecycle hook is early
 * enough: the build step that calls {@code startAll()} <i>produces</i> {@code ServiceStartBuildItem},
 * and {@code StartupEvent} is fired only after all of those. Hibernate's own {@code Integrator} seam
 * is too late for the opposite reason — {@code SessionFactoryImpl} builds its {@code EventEngine}
 * before it runs integrators.
 *
 * <p>What is left is {@code FastBootEntityManagerFactoryBuilder.populate()}, which resolves a {@code
 * @PersistenceUnitExtension}-qualified {@code StatementInspector} <b>eagerly</b> through Arc, on the
 * unit's own {@code JPA Startup Thread}, inside {@code LazyPersistenceUnit.get()} and strictly
 * before {@code new SessionFactoryImpl(...)}. That is the only application-reachable point with all
 * three properties, and it was verified by making this constructor throw: the stack read {@code
 * FastBootEntityManagerFactoryBuilder.populate} under {@code JPAConfig$1.run}, on {@code JPA Startup
 * Thread: PersistenceUnitKey[name=projects]}. {@code Interceptor} sits beside it in the same method
 * and is deliberately NOT used: it is wrapped in a {@code Supplier} there and so is not instantiated
 * at that point.
 *
 * <p><b>One bean per unit, because a repeated qualifier does not work.</b> {@code
 * @PersistenceUnitExtension} is {@code @Repeatable}, and stacking three of them on one bean class
 * resolves it for <b>none</b> of them — measured here, and silently: the suite went green with the
 * constructor never running once. That is the trap this arrangement exists to avoid, so the three
 * subclasses below are one per unit and the base class is abstract (an abstract class is not a
 * bean). {@code CausationStampWarmupTest} is what keeps them in step with the configured units.
 *
 * <p><b>The scope is {@code @Dependent} on purpose.</b> A singleton would run the constructor only
 * on whichever unit asked first, and the other units' threads would then be relying on Arc's
 * internal happens-before to see the populated cache. Dependent means every unit's thread runs it
 * and therefore every unit's thread takes the lock.
 *
 * <p><b>The jar's unit is warmed too.</b> {@code eventstream}'s two entities carry no {@code
 * @EntityListeners} today, so its thread resolves nothing and needs no warm-up — but naming it makes
 * the invariant "every persistence unit in this application warms before it builds" rather than "the
 * two that currently happen to need it", which is the version a reader can check and a test can
 * hold.
 *
 * <p><b>Why the entities were not changed instead.</b> A {@code @PrePersist} declared on the entity
 * or on a {@code @MappedSuperclass} compiles to an {@code EntityCallback}, whose {@code
 * createCallback} ignores the registry entirely — that would delete the hazard rather than order it,
 * and it is the better fix. It is not available here: the shared rule {@code
 * CausationRowRules.everyCausedRowAttachesTheStamp}, pinned in qits-arch-rules and run by both
 * modules' {@code ArchRulesTest}, requires {@code @EntityListeners(CausationStamp.class)} declared
 * on each {@code CausedRow} entity, and {@code @EntityListeners} is not {@code @Inherited} so a
 * mapped superclass does not satisfy it. Moving the callback means changing that rule in another
 * repository first. The real home for either fix is the eventstream jar, which could ship the
 * callback on a {@code @MappedSuperclass} and retire the listener for the whole estate.
 */
public abstract class CausationStampWarmup implements StatementInspector {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Guards the one resolution. It is this class's own monitor rather than the container's because
   * the container exposes none — the whole defect is that nothing in {@code
   * AbstractCdiBeanContainer} is synchronized.
   */
  private static final Object RESOLUTION = new Object();

  /**
   * Which units actually got here. This is the only way to tell a working warm-up from one that
   * silently never ran — the failure mode that a repeated {@code @PersistenceUnitExtension}
   * produced, and which a suite is green under by construction. {@code CausationStampWarmupTest}
   * reads it.
   */
  private static final Set<String> WARMED = ConcurrentHashMap.newKeySet();

  protected CausationStampWarmup(String persistenceUnit) {
    synchronized (RESOLUTION) {
      // Exactly the call Hibernate makes for a listener callback, so the cache is populated under
      // the key its own lookup will use. The registry is a thin, stateless wrapper minted per
      // persistence unit by Quarkus itself; what is shared — and what is being warmed — is the
      // @Singleton bean container it resolves.
      new QuarkusManagedBeanRegistry().getBean(CausationStamp.class);
      WARMED.add(persistenceUnit);
    }
  }

  /** The persistence units whose startup thread has run the warm-up in this application. */
  public static Set<String> warmed() {
    return Set.copyOf(WARMED);
  }

  /** Never inspects anything. These classes are here for their constructor. */
  @Override
  public final String inspect(String sql) {
    return sql;
  }

  /** The projects unit — domain's entities, every one of them a {@code CausedRow}. */
  @Dependent
  @PersistenceUnitExtension("projects")
  public static final class Projects extends CausationStampWarmup {
    @Serial private static final long serialVersionUID = 1L;

    public Projects() {
      super("projects");
    }
  }

  /** The epics unit — the planning entities, and the pair commit 2c6b3c0 added. */
  @Dependent
  @PersistenceUnitExtension("epics")
  public static final class Epics extends CausationStampWarmup {
    @Serial private static final long serialVersionUID = 1L;

    public Epics() {
      super("epics");
    }
  }

  /** The eventstream jar's own unit. Resolves no listener today; see the class javadoc. */
  @Dependent
  @PersistenceUnitExtension("eventstream")
  public static final class Eventstream extends CausationStampWarmup {
    @Serial private static final long serialVersionUID = 1L;

    public Eventstream() {
      super("eventstream");
    }
  }
}

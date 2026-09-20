package eu.wohlben.qits.projects.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * The one thing about {@link HibernateBeanContainerWarmup} that can silently stop being true: a
 * persistence unit whose startup thread never ran the warm-up. Read that class's javadoc for the
 * race — the
 * short version is that Quarkus starts every unit on its own thread against one shared,
 * unsynchronised Hibernate bean container, so a unit that reaches its {@code SessionFactory} without
 * having gone through the warm-up is free to corrupt that container's list, and the symptom is a
 * boot failure in an unrelated test one run in three.
 *
 * <p><b>This asserts that the warm-up RAN, not that it was declared.</b> The distinction is the
 * whole value of the class: the first arrangement of it stacked three repeatable {@code
 * @PersistenceUnitExtension} qualifiers on one bean, which resolves for none of them, and the
 * constructor never executed once — with the suite green throughout, because a warm-up that does
 * nothing is indistinguishable from a warm-up that was not needed on a run that happened to win the
 * race. A test reading only the annotations would have passed on that arrangement too.
 *
 * <p>The expectation is derived from configuration rather than written down, so it is the broad
 * invariant — <b>every</b> persistence unit this application configures is warmed, not merely the
 * ones whose entities carry a listener today. That does not go stale when an entity gains or loses
 * an annotation, and it fails the day a fourth unit arrives rather than the day that unit's thread
 * happens to lose a race.
 *
 * <p>No {@code @TestProfile}: this joins the default application, so it costs the suite nothing.
 */
@QuarkusTest
class HibernateBeanContainerWarmupTest {

  /** {@code quarkus.hibernate-orm.<unit>.datasource} — the key every named unit here declares. */
  private static final Pattern NAMED_UNIT =
      Pattern.compile("^quarkus\\.hibernate-orm\\.\"?([^.\"]+)\"?\\.datasource$");

  @Test
  void everyPersistenceUnitWarmedTheListenerBeforeItBuiltItsSessionFactory() {
    Set<String> configured = new TreeSet<>();
    for (String name : ConfigProvider.getConfig().getPropertyNames()) {
      Matcher m = NAMED_UNIT.matcher(name);
      if (m.matches()) {
        configured.add(m.group(1));
      }
    }

    assertEquals(
        configured,
        new TreeSet<>(HibernateBeanContainerWarmup.warmed()),
        "every persistence unit this application configures must have run"
            + " HibernateBeanContainerWarmup on its own startup thread, so the CausationStamp"
            + " listener is resolved once under that"
            + " class's lock before any SessionFactory is built. A unit missing here reaches the"
            + " shared, unsynchronised Hibernate bean container with nothing ordering it, which"
            + " fails as an ArrayIndexOutOfBoundsException in AbstractCdiBeanContainer.createBean"
            + " somewhere else entirely. Note this is about the warm-up having EXECUTED: declaring"
            + " the qualifier is not enough, as a repeated @PersistenceUnitExtension proved");
  }
}

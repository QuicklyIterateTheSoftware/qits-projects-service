package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import org.junit.jupiter.api.Test;

/**
 * The single reading of a wrapper path, under the project's one grammar. Pure, so it is tested
 * against strings — the same treatment {@link WrapperGitmodules} gets, and for the same reason: this
 * is where the project's configuration is interpreted.
 */
public class WrapperPathTest {

  @Test
  public void aComponentPathNamesItsComponentAndDeclaresNoArchetype() {
    WrapperPath parsed = WrapperPath.parse("components/qits-ci/qits-ci");

    assertEquals("components/qits-ci/qits-ci", parsed.path());
    assertEquals(
        "qits-ci",
        parsed.component(),
        "the first segment is the marker and the second is the component");
    assertEquals("qits-ci", parsed.name(), "the third segment is what ../<name>.git resolves to");
  }

  /**
   * There is no second grammar to fall back to, so a path that is not {@code
   * components/<component>/<name>} is not a path at all. Guessing what {@code vendor/x} or {@code
   * components/a/b/c} means would be inventing taxonomy, and the reconcile skips exactly what comes
   * back null here.
   */
  @Test
  public void everythingThatIsNotAComponentPathIsNotAPathAtAll() {
    assertNull(
        WrapperPath.parse("services/qits-ci"),
        "the retired archetype layout reads as nothing now, which is the whole of this ticket");
    assertNull(WrapperPath.parse("vendor/vendored"), "and so does anything else two-segment");
    assertNull(WrapperPath.parse("components/orphan"), "a component with nothing mounted under it");
    assertNull(WrapperPath.parse("components/a/b/c"), "a tree deeper than the grammar");
    assertNull(WrapperPath.parse("single"), "one segment mounts nowhere");
    assertNull(
        WrapperPath.parse("components/x/"),
        "a trailing slash leaves a blank name rather than reading as the two-segment"
            + " 'components/x'");
    assertNull(WrapperPath.parse("trailing/"), "the same, two segments deep");
    assertNull(
        WrapperPath.parse("components/a/b/"),
        "and this is the one the split's -1 limit is actually for: without it the trailing empty"
            + " segment is dropped, three segments are counted and a path with a blank name under"
            + " it parses as the perfectly good 'components/a/b'");
    assertNull(WrapperPath.parse("  "));
    assertNull(WrapperPath.parse(null));
  }

  @Test
  public void theRoleSuffixOfANameIsTheOnlyKindDerivationThereIs() {
    assertEquals(
        RepositoryArchetype.SERVICE, RepositoryArchetype.fromRepositoryName("qits-ci-service"));
    assertEquals(
        RepositoryArchetype.SERVICE,
        RepositoryArchetype.fromRepositoryName("qits-deployments-platform-service"),
        "a tier modifier sits before the role, so the suffix still decides");
    assertEquals(
        RepositoryArchetype.DAEMON, RepositoryArchetype.fromRepositoryName("qits-workspace-daemon"));
    assertEquals(
        RepositoryArchetype.FRONTEND, RepositoryArchetype.fromRepositoryName("qits-ci-frontend"));
    assertEquals(
        RepositoryArchetype.IMAGE, RepositoryArchetype.fromRepositoryName("qits-workspace-oci"));
    assertEquals(RepositoryArchetype.CLI, RepositoryArchetype.fromRepositoryName("qits-bootstrap-cli"));
    assertEquals(
        RepositoryArchetype.LIBRARY,
        RepositoryArchetype.fromRepositoryName("qits-eventstream-javalib"));
    assertEquals(
        RepositoryArchetype.LIBRARY,
        RepositoryArchetype.fromRepositoryName("qits-ui-components-jslib"));
  }

  /** Every name the renames have not reached, which is still most of them. */
  @Test
  public void aNameWithNoRoleSuffixDeclaresNoKind() {
    assertNull(RepositoryArchetype.fromRepositoryName("qits-ci"));
    assertNull(RepositoryArchetype.fromRepositoryName("qits-spa-ci"));
    assertNull(RepositoryArchetype.fromRepositoryName("cli"), "a bare role word is not a suffix");
    assertNull(RepositoryArchetype.fromRepositoryName(""));
    assertNull(RepositoryArchetype.fromRepositoryName(null));
  }
}

package eu.wohlben.qits.projects.control.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link GitPathspec} (task qits-1046): the {@code :(glob)} and plain forms the automation kinds
 * write — the live catalogue's paths first — and every other form reported, never guessed.
 */
class GitPathspecTest {

  private static void matches(String spec, String... paths) {
    GitPathspec pathspec = GitPathspec.parse(spec);
    for (String path : paths) {
      assertTrue(pathspec.matches(path), spec + " should match " + path);
    }
  }

  private static void misses(String spec, String... paths) {
    GitPathspec pathspec = GitPathspec.parse(spec);
    for (String path : paths) {
      assertFalse(pathspec.matches(path), spec + " should not match " + path);
    }
  }

  // ---- the live catalogue ----------------------------------------------------------------------

  @Test
  void screenshotBaselinesAtAnyDepth() {
    String spec = ":(glob)**/__screenshots__/**";
    matches(
        spec,
        "__screenshots__/home.png",
        "src/__screenshots__/home.png",
        "src/app/pages/__screenshots__/chromium/release-request.png",
        "a/__screenshots__/b/__screenshots__/c.png");
    misses(
        spec,
        "__screenshots__",
        "src/__screenshots__",
        "src/__screenshots__old/home.png",
        "src/my__screenshots__/home.png",
        "src/home.png");
  }

  @Test
  void theRendererStampAtAnyDepth() {
    String spec = ":(glob)**/testing/browser/renderer.txt";
    matches(spec, "testing/browser/renderer.txt", "webui/src/testing/browser/renderer.txt");
    misses(
        spec,
        "testing/browser/renderer.txt.bak",
        "testing/browser/other/renderer.txt",
        "xtesting/browser/renderer.txt",
        "testing/browser/renderer-txt");
  }

  @Test
  void theEntityDiagramDirectory() {
    String spec = ":(glob)docs/database/**";
    matches(spec, "docs/database/schema.md", "docs/database/diagrams/projects.svg");
    misses(
        spec,
        "docs/database",
        "docs/databases/schema.md",
        "service/docs/database/schema.md",
        "docs/schema.md");
  }

  @Test
  void everyPomInTheTree() {
    String spec = ":(glob)**/pom.xml";
    matches(spec, "pom.xml", "service/pom.xml", "a/b/c/pom.xml");
    misses(spec, "pom.xml.bak", "service/xpom.xml", "service/pom.xml/x");
  }

  @Test
  void dockerfilesWithASuffix() {
    String spec = ":(glob)**/Dockerfile.*";
    matches(spec, "Dockerfile.native", "docker/Dockerfile.jvm", "docker/Dockerfile.");
    misses(spec, "Dockerfile", "docker/Dockerfile", "docker/Dockerfile.d/x", "docker/xDockerfile.jvm");
  }

  @Test
  void dockerfilesWithAPrefix() {
    String spec = ":(glob)**/*.Dockerfile";
    matches(spec, "native.Dockerfile", "docker/jvm.Dockerfile", "a/b/.Dockerfile");
    misses(spec, "Dockerfile", "docker/jvm.Dockerfile.bak", "jvm.Dockerfile/x");
  }

  // ---- glob semantics --------------------------------------------------------------------------

  @Test
  void aSingleStarDoesNotCrossASlash() {
    matches(":(glob)src/*.java", "src/Main.java", "src/.java");
    misses(":(glob)src/*.java", "src/a/Main.java", "Main.java", "src/Main.javax");
    matches(":(glob)*", "README.md");
    misses(":(glob)*", "docs/README.md");
  }

  @Test
  void aQuestionMarkIsOneCharacterOtherThanASlash() {
    matches(":(glob)v?.txt", "v1.txt", "vx.txt");
    misses(":(glob)v?.txt", "v.txt", "v12.txt", "v/.txt");
  }

  @Test
  void aDoubleStarInTheMiddleIsZeroOrMoreDirectories() {
    matches(":(glob)a/**/b", "a/b", "a/x/b", "a/x/y/b");
    misses(":(glob)a/**/b", "a/xb", "ab", "a/b/c");
  }

  @Test
  void aDoubleStarAloneIsEverything() {
    matches(":(glob)**", "README.md", "a/b/c");
  }

  @Test
  void aDoubleStarNotBetweenSlashesIsAPlainStar() {
    matches(":(glob)a**b", "ab", "axxb");
    misses(":(glob)a**b", "a/b", "ax/yb");
    matches(":(glob)**.md", "README.md");
    misses(":(glob)**.md", "docs/README.md");
  }

  @Test
  void anEscapedWildcardIsLiteral() {
    matches(":(glob)a\\*b", "a*b");
    misses(":(glob)a\\*b", "axb");
  }

  @Test
  void aGlobWithoutAWildcardMatchesThePathAndEverythingUnderIt() {
    matches(":(glob)docs/database", "docs/database", "docs/database/schema.md");
    misses(":(glob)docs/database", "docs/database-old/x", "docs");
  }

  @Test
  void regexCharactersInAPatternAreLiteral() {
    matches(":(glob)**/a+b(1).txt", "x/a+b(1).txt");
    misses(":(glob)**/a+b(1).txt", "x/aab1.txt");
  }

  // ---- plain pathspecs -------------------------------------------------------------------------

  @Test
  void aPlainPathspecIsAPrefixByWholeSegments() {
    matches("docs/database", "docs/database", "docs/database/schema.md", "docs/database/a/b.svg");
    misses("docs/database", "docs/database-old/x", "docs/databases", "docs", "x/docs/database");
    matches("docs/database/", "docs/database/schema.md");
    misses("docs/database/", "docs/database");
    matches("pom.xml", "pom.xml");
    misses("pom.xml", "service/pom.xml");
  }

  @Test
  void aColonWithNoMagicIsAPlainPathspec() {
    matches(":docs/database", "docs/database/schema.md");
    matches("::docs/database", "docs/database/schema.md");
  }

  @Test
  void dotIsTheWholeTree() {
    matches(".", "README.md", "a/b/c");
    matches(":(glob).", "README.md");
  }

  @Test
  void nothingMatchesAnEmptyPath() {
    misses(":(glob)**", "");
    assertFalse(GitPathspec.parse("docs").matches(null));
  }

  @Test
  void itPrintsAsWritten() {
    assertEquals(":(glob)**/pom.xml", GitPathspec.parse(":(glob)**/pom.xml").toString());
  }

  // ---- unsupported -----------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(
      strings = {
        ":(icase)README.md",
        ":(exclude)docs/**",
        ":(glob,exclude)docs/**",
        ":(attr:binary)*.png",
        ":(top)docs",
        ":(literal)docs",
        ":(frobnicate)docs",
        ":()docs",
        ":(glob",
        ":!docs",
        ":^docs",
        ":/docs",
        ":!:docs",
        "docs/*.md",
        "src?",
        "[ab].txt",
        ":(glob)[ab].txt",
        ":(glob)a\\",
        ":(glob)",
        "",
        ":"
      })
  void everyOtherFormIsReportedSoTheCallerFailsClosed(String spec) {
    GitPathspec.Unsupported unsupported =
        assertThrows(GitPathspec.Unsupported.class, () -> GitPathspec.parse(spec));
    assertTrue(unsupported.getMessage().contains("'" + spec + "'"), unsupported.getMessage());
  }

  @Test
  void theUnsupportedMagicIsNamed() {
    for (String magic : List.of("icase", "exclude")) {
      assertTrue(
          assertThrows(GitPathspec.Unsupported.class, () -> GitPathspec.parse(":(" + magic + ")x"))
              .getMessage()
              .contains("magic '" + magic + "'"));
    }
  }
}

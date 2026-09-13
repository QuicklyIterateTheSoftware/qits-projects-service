package eu.wohlben.qits.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * This repository declares its own configuration keys, and this test holds the file's shape.
 *
 * <p><b>Why the file exists.</b> qits-configuration flags a stored entry {@code orphaned} only when
 * the application has a declaration that does not list the key. So {@code
 * .config/qits/configuration.yml} lists every key this service still reads, and the leftovers show
 * up as orphans a person can remove (service-client-identity-plan.md, C10).
 *
 * <p><b>What this test is NOT.</b> It is not a parser. qits-configuration's {@code DeclarationParser}
 * owns the grammar, and a second copy here would disagree with it the day the grammar grows. The file
 * was checked against that parser by running it. This test holds only what a hand edit can break
 * without anybody noticing until a release is refused: the file is at the path the deployer fetches,
 * the top level is {@code keys:} alone, and no key is declared twice.
 */
class OwnDeclarationTest {

  /** The exact path the deployer fetches from the git host at the released tag. */
  static final String DECLARATION_PATH = ".config/qits/configuration.yml";

  /**
   * One declared key: indented under {@code keys:}, spelled in the store's key grammar ({@code
   * env.<VAR>} or one of the four indexed families), optionally quoted, and ending in a colon.
   */
  private static final Pattern KEY_LINE =
      Pattern.compile(
          "^ +\"?(env\\.[A-Za-z_][A-Za-z0-9_]*|(?:mounts|publishes|groups|aliases)\\[[0-9]{1,4}])\"?:\\s*$");

  /** The document, found by walking up from the directory surefire started this module in. */
  private static Path declaration() {
    Path at = Path.of("").toAbsolutePath();
    for (int up = 0; up < 4 && at != null; up++, at = at.getParent()) {
      Path candidate = at.resolve(DECLARATION_PATH);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new AssertionError("no " + DECLARATION_PATH + " above " + Path.of("").toAbsolutePath());
  }

  @Test
  void theDeclarationIsWhereTheDeployerLooksForIt() throws IOException {
    String raw = Files.readString(declaration());

    assertFalse(raw.isBlank(), "the store refuses an empty document");
  }

  @Test
  void theTopLevelIsTheOneKeyTheStoreAccepts() throws IOException {
    // The store refuses a second top-level key rather than ignoring it. A stray unindented line is
    // the easiest way to break this file by hand and the hardest to see.
    List<String> topLevel = new ArrayList<>();
    for (String line : Files.readAllLines(declaration())) {
      if (line.isBlank() || line.startsWith("#") || line.startsWith(" ")) {
        continue;
      }
      topLevel.add(line);
    }

    assertEquals(List.of("keys:"), topLevel);
  }

  @Test
  void noKeyIsDeclaredTwice() throws IOException {
    // The store's loader refuses duplicate keys, so a repeated name is a refused release, not a
    // harmless extra line.
    Set<String> seen = new LinkedHashSet<>();
    for (String line : Files.readAllLines(declaration())) {
      Matcher key = KEY_LINE.matcher(line);
      if (key.matches()) {
        assertTrue(seen.add(key.group(1)), "declared twice: " + key.group(1));
      }
    }

    assertFalse(seen.isEmpty(), "the document declares no keys at all");
  }
}

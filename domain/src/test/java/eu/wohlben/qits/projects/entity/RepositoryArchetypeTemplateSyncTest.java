package eu.wohlben.qits.projects.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps the archetype taxonomy and the project template skeleton from drifting apart. There is one
 * path grammar now, {@code components/<component>/<name>}, and both halves of the agreement it
 * needs are here:
 *
 * <ul>
 *   <li><b>The template seeds one directory, {@code components/}</b>. Seeding anything else would
 *       teach every new project a grammar the platform does not read, on its first clone.
 *   <li><b>The NAME is what says the kind</b>, and it is the only thing that does: every role
 *       suffix {@link RepositoryArchetype#fromRepositoryName} reads is taught by {@code
 *       components/README.md}, and every suffix that README teaches derives back to an archetype.
 * </ul>
 *
 * <p>The archetype layout — a mount directory that <em>was</em> the kind — is retired, so nothing
 * here asserts a directory-to-archetype mapping any more; there is none to assert. What replaced it
 * as the question the rest of the service asks of a value is {@link
 * RepositoryArchetype#isComponentOfItsProject}, whose membership split is pinned below.
 *
 * <p>A plain JUnit test — no Quarkus — since it only reads the enum and the built resources.
 */
public class RepositoryArchetypeTemplateSyncTest {

  /** The template as the build copied it, which is what actually ships. */
  private static final Path TEMPLATE = Path.of("target/classes/project-template");

  /** The one directory the skeleton seeds; {@code WrapperPath.COMPONENTS_DIRECTORY} is its reader. */
  private static final String COMPONENTS = "components";

  /**
   * The six directories the retired archetype layout mounted entries under. Spelled out here
   * because the enum no longer knows them — that is the point of the retirement — and this test is
   * the last place on the estate that still has to name them, to prove they are gone from what the
   * template and the starter config teach.
   */
  private static final List<String> RETIRED_ARCHETYPE_DIRECTORIES =
      List.of("services", "daemons", "libs", "frontends", "cli", "images");

  private static Set<String> templateDirectories() throws Exception {
    try (Stream<Path> entries = Files.list(TEMPLATE)) {
      return entries
          .filter(Files::isDirectory)
          .map(p -> p.getFileName().toString())
          .collect(Collectors.toCollection(TreeSet::new));
    }
  }

  @Test
  public void theSkeletonSeedsComponentsAndNothingElse() throws Exception {
    assertTrue(
        Files.isDirectory(TEMPLATE), "the project template is missing from the build output");

    assertEquals(
        Set.of(COMPONENTS),
        templateDirectories(),
        "the wrapper skeleton seeds the one directory the one grammar mounts under, and nothing"
            + " else: a second seeded directory is a second grammar nothing reads");
  }

  /**
   * Git cannot commit an empty directory, so each one needs a placeholder anyway; a README makes
   * the skeleton teach the convention instead of merely reserving the path.
   */
  @Test
  public void everyTemplateDirectoryCarriesAReadme() throws Exception {
    for (String directory : templateDirectories()) {
      assertTrue(
          Files.isRegularFile(TEMPLATE.resolve(directory).resolve("README.md")),
          directory + "/ needs a README.md, or git cannot commit it");
    }
  }

  /**
   * The membership split, pinned per constant — and the reason {@code APP} needed no guard narrowed
   * when it arrived. Everything that asks whether the wrapper is expected
   * to declare a row — the write guard, the wrapper removal on delete, the listing's {@code
   * declared} flag, the reconcile's undeclared report — reads this one predicate, so a value
   * quietly changing sides changes four behaviours at once. It is stated at the declaration rather
   * than derived, which is exactly why a test has to say what the declarations are.
   */
  @Test
  public void exactlySevenArchetypesAreComponentsOfTheirProject() {
    assertEquals(
        List.of("SERVICE", "DAEMON", "LIBRARY", "FRONTEND", "APP", "CLI", "IMAGE"),
        Stream.of(RepositoryArchetype.values())
            .filter(RepositoryArchetype::isComponentOfItsProject)
            .map(Enum::name)
            .toList(),
        "these seven are what a project is built out of, so its wrapper is expected to declare"
            + " them");
    assertEquals(
        List.of("PROJECT", "SERVICE_TEMPLATE", "FORK"),
        Stream.of(RepositoryArchetype.values())
            .filter(a -> !a.isComponentOfItsProject())
            .map(Enum::name)
            .toList(),
        "the wrapper IS the tree, a template is what a component is generated from, and a fork is"
            + " somebody else's repository — none of the three is a member of the project");
  }

  // --- the name is the kind: the two-way sync that replaced directory <-> archetype ---

  /** ``-service`` in the README's table, one per row. */
  private static final Pattern SUFFIX_CELL = Pattern.compile("`(-[a-z]+)`");

  private static Set<String> suffixesTheReadmeTeaches() throws Exception {
    String readme = Files.readString(TEMPLATE.resolve(COMPONENTS).resolve("README.md"));
    Matcher matcher = SUFFIX_CELL.matcher(readme);
    Set<String> found = new TreeSet<>();
    while (matcher.find()) {
      found.add(matcher.group(1));
    }
    return found;
  }

  @Test
  public void theTemplateTeachesExactlyTheRoleSuffixesTheEnumReads() throws Exception {
    assertEquals(
        new TreeSet<>(RepositoryArchetype.roleSuffixes()),
        suffixesTheReadmeTeaches(),
        "components/README.md is what tells a person which names qits can read the kind out of, so"
            + " a suffix in one and not the other is a promise nothing keeps");
  }

  @Test
  public void everySuffixTheTemplateTeachesDerivesAComponentOfItsProject() throws Exception {
    for (String suffix : suffixesTheReadmeTeaches()) {
      RepositoryArchetype derived = RepositoryArchetype.fromRepositoryName("payments" + suffix);
      assertTrue(
          derived != null && derived.isComponentOfItsProject(),
          suffix
              + " is taught as a name a component of a project takes, so it must derive an"
              + " archetype that is one");
    }
    // A name that is only the suffix declares nothing — there is no component left in it.
    assertEquals(null, RepositoryArchetype.fromRepositoryName("-service"));
    assertEquals(null, RepositoryArchetype.fromRepositoryName("qits-ci"));
    assertEquals(null, RepositoryArchetype.fromRepositoryName(null));
  }

  /**
   * The taxonomy is these ten and no more. INTEGRATION and APPLICATION rode through release A as
   * deprecated aliases so Hibernate could read pre-rework rows; V4 retired those rows and dropped
   * them, and this is what stops one being reintroduced without a migration to widen the check
   * constraint for it. APP is the tenth, admitted by {@code
   * V27__repository_archetype_app.sql} — which is the whole of it, since {@code
   * db/projects/migration} is the one lineage this repository has.
   */
  @Test
  public void theTaxonomyIsExactlyTheTenValuesTheCheckConstraintAllows() {
    assertEquals(
        List.of(
            "PROJECT",
            "SERVICE",
            "DAEMON",
            "LIBRARY",
            "FRONTEND",
            "APP",
            "CLI",
            "IMAGE",
            "SERVICE_TEMPLATE",
            "FORK"),
        Stream.of(RepositoryArchetype.values()).map(Enum::name).toList());
  }

  /**
   * And the migration that widened the check constraint says the same ten, in the same words. The
   * enum and the column are two halves of one taxonomy: a value Hibernate writes that the
   * constraint refuses is an insert that fails at runtime, which no compiler and no other test here
   * can see. Read off the source tree rather than {@code target/classes}, because it is the file
   * that ships in the jar either way and the source is where the mistake is made.
   */
  @Test
  public void theMigrationWidensTheCheckConstraintToExactlyThoseTenValues() throws Exception {
    Path migration =
        Path.of(
            "src/main/resources/db/projects/migration/V27__repository_archetype_app.sql");
    String sql = Files.readString(migration);
    for (RepositoryArchetype archetype : RepositoryArchetype.values()) {
      assertTrue(
          sql.contains("'" + archetype.name() + "'"),
          archetype
              + " is a value Hibernate can write, so the re-added CK_repository_archetype has to"
              + " allow it");
    }
    assertTrue(
        sql.contains("drop constraint CK_repository_archetype"),
        "a named check cannot be widened in place — the migration drops it and adds it back");
  }

  /**
   * The trap this guards: plexus' archiver default-excludes, which maven-jar-plugin applies, drop
   * {@code .gitignore} and {@code .gitattributes} from every jar. A template resource named with a
   * leading dot therefore reaches {@code target/classes} — so tests and dev mode see it — and
   * silently vanishes from the packaged artifact, producing wrappers missing a file with no error
   * anywhere. Every dotfile is stored {@code dot-}-prefixed and un-prefixed at commit time.
   */
  @Test
  public void noTemplateResourceIsStoredWithALeadingDot() throws Exception {
    try (Stream<Path> all = Files.walk(TEMPLATE)) {
      List<String> dotted =
          all.map(p -> p.getFileName().toString()).filter(n -> n.startsWith(".")).sorted().toList();
      assertTrue(
          dotted.isEmpty(),
          "store these dot-prefixed instead, or they will not survive jar packaging: " + dotted);
    }
  }

  @Test
  public void theAgentContractSlotIsReservedWithItsSymlink() throws Exception {
    assertTrue(
        Files.isRegularFile(TEMPLATE.resolve("AGENTS.md")),
        "the agent-contract slot exists from the start, so a later step fills a path that is"
            + " already in every wrapper");
    Path symlink = TEMPLATE.resolve("CLAUDE.md.symlink");
    assertTrue(Files.isRegularFile(symlink), "CLAUDE.md is declared, not a real symlink resource");
    assertEquals(
        "AGENTS.md",
        Files.readString(symlink).strip(),
        "the declared link target is what gets committed as a 120000 blob");
  }

  @Test
  public void theStarterConfigAndGitignoreArePresent() {
    assertTrue(Files.isRegularFile(TEMPLATE.resolve("dot-qits-config.yml")));
    assertTrue(Files.isRegularFile(TEMPLATE.resolve("dot-gitignore")));
  }

  /** The starter config's examples must show the layout the skeleton actually seeds. */
  @Test
  public void theStarterConfigsExamplePathsAreComponentPaths() throws Exception {
    String config = Files.readString(TEMPLATE.resolve("dot-qits-config.yml"));
    assertTrue(config.contains("components/payments/payments-service"));
    for (String directory : RETIRED_ARCHETYPE_DIRECTORIES) {
      assertFalse(
          config.contains(" " + directory + "/"),
          "the example paths still name " + directory + "/, a retired archetype directory");
    }
  }
}

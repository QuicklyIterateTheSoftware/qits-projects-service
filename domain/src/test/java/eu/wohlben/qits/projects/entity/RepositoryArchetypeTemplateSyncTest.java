package eu.wohlben.qits.projects.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
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
 *   <li><b>The kind is stated, never read off the name</b> (owner ruling 2026-11-03): {@code
 *       components/README.md} keeps the naming grammar as guidance for a reader and says where the
 *       kind is stated instead — the repository's own {@code .config/qits/repository.yml} {@code
 *       repository.archetype} — and the blank repository template shows that key.
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

  /** The one MOUNT directory the skeleton seeds; {@code WrapperPath.COMPONENTS_DIRECTORY} reads it. */
  private static final String COMPONENTS = "components";

  /**
   * The committed-configuration directory, stored {@code dot-}-prefixed like every dotfile here and
   * committed as {@code .config/}. It is not a mount directory and no grammar reads it: what lives
   * under it is {@code qits/project.yml}, the project's own declaration ({@code
   * ProjectConfigParser}), seeded so the file a person edits is already there to edit.
   */
  private static final String CONFIG = "dot-config";

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
        Set.of(COMPONENTS, CONFIG),
        templateDirectories(),
        "the wrapper skeleton seeds the one directory the one grammar MOUNTS under, plus the"
            + " committed-configuration directory — and nothing else: a second mount directory is a"
            + " second grammar nothing reads");
  }

  /**
   * The project's own declaration ships in the skeleton, carrying the default a new project gets.
   *
   * <p><b>Both halves are the assertion.</b> The value is {@code true} because that is what a new
   * project is — deployed once per environment, the behaviour every project on this platform had
   * before the flag existed — and the comment has to teach that absent, empty and {@code true} are
   * one answer, because the file is a template a person edits and the only value that changes
   * anything is {@code false}. {@code .yml} and never {@code .yaml}: every file under {@code
   * .config/qits/} on this estate is {@code .yml}, and a reader looks for exactly one spelling.
   */
  @Test
  public void theSkeletonCarriesTheProjectDeclarationWithTheDefaultANewProjectGets()
      throws Exception {
    Path declaration = TEMPLATE.resolve(CONFIG).resolve("qits").resolve("project.yml");
    assertTrue(
        Files.isRegularFile(declaration),
        "the project template seeds .config/qits/project.yml — the file the reconcile reads");

    String content = Files.readString(declaration);
    assertTrue(
        content.contains("supports_environments: true"),
        "a new project supports environments; false is the value somebody opts into");
    assertTrue(
        content.contains("Absent, empty and `true` are the same answer"),
        "the template has to say that absence, an empty file and true are one answer, because that"
            + " is the whole of what the parser does with them");
  }

  /**
   * Git cannot commit an empty directory, so each one needs a placeholder anyway; a README makes
   * the skeleton teach the convention instead of merely reserving the path.
   */
  @Test
  public void everyTemplateDirectoryCarriesAReadme() throws Exception {
    for (String directory : templateDirectories()) {
      if (CONFIG.equals(directory)) {
        // Not a mount directory and not a place a person browses: it carries a config file, which
        // is content enough for git to commit it and the wrong place to teach a convention.
        continue;
      }
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
        List.of("PROJECT", "SERVICE_TEMPLATE", "FORK", "AGENT_CONFIG"),
        Stream.of(RepositoryArchetype.values())
            .filter(a -> !a.isComponentOfItsProject())
            .map(Enum::name)
            .toList(),
        "the wrapper IS the tree, a template is what a component is generated from, a fork is"
            + " somebody else's repository and the agent configuration is platform state — none of"
            + " the four is a member of the project");
  }

  // --- the kind is stated: what the templates teach about it ---

  /** The blank component skeleton, as the build copied it. */
  private static final Path REPOSITORY_TEMPLATE = Path.of("target/classes/repository-template");

  /**
   * The README keeps the role-suffix table for a reader and says the name decides nothing: the kind
   * lives in {@code .config/qits/repository.yml}. A sentence promising that qits reads the kind off
   * a name, or restamps it on a rename, would be a promise nothing keeps any more.
   */
  @Test
  public void theComponentsReadmeSaysTheKindIsStatedAndNeverReadOffTheName() throws Exception {
    String readme = Files.readString(TEMPLATE.resolve(COMPONENTS).resolve("README.md"));
    assertTrue(readme.contains("`-service`"), "the naming grammar stays, as guidance for a reader");
    assertTrue(
        readme.contains(".config/qits/repository.yml") && readme.contains("archetype: DAEMON"),
        "the README has to say where the kind is stated instead");
    assertTrue(readme.contains("The name decides nothing"));
    assertFalse(readme.contains("restamps"), "a rename no longer restamps the kind");
    assertFalse(readme.contains("qits reads it"), "qits no longer reads the kind off the name");
  }

  /** The blank repository's own config shows the one place a repository states its kind. */
  @Test
  public void theRepositoryTemplateShowsWhereTheKindIsStated() throws Exception {
    String config =
        Files.readString(REPOSITORY_TEMPLATE.resolve("dot-config/qits/repository.yml"));
    assertTrue(
        config.contains("# archetype:"),
        "the starter repository.yml carries the archetype key as a commented example");
    assertFalse(
        config.contains("\n  archetype:"),
        "only commented: a blank repository states no kind until somebody does");
  }

  /**
   * The taxonomy is these eleven and no more. INTEGRATION and APPLICATION rode through release A as
   * deprecated aliases so Hibernate could read pre-rework rows; V4 retired those rows and dropped
   * them, and this is what stops one being reintroduced without a migration to widen the check
   * constraint for it. APP is the tenth, admitted by {@code V27__repository_archetype_app.sql}, and
   * AGENT_CONFIG the eleventh, admitted by {@code V46__agent_config_archetype.sql} — which is the
   * whole of it, since {@code db/projects/migration} is the one lineage this repository has.
   */
  @Test
  public void theTaxonomyIsExactlyTheElevenValuesTheCheckConstraintAllows() {
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
            "FORK",
            "AGENT_CONFIG"),
        Stream.of(RepositoryArchetype.values()).map(Enum::name).toList());
  }

  /**
   * And the latest migration that widened the check constraint says the same eleven, in the same
   * words. The
   * enum and the column are two halves of one taxonomy: a value Hibernate writes that the
   * constraint refuses is an insert that fails at runtime, which no compiler and no other test here
   * can see. Read off the source tree rather than {@code target/classes}, because it is the file
   * that ships in the jar either way and the source is where the mistake is made.
   */
  @Test
  public void theMigrationWidensTheCheckConstraintToExactlyThoseElevenValues() throws Exception {
    Path migration =
        Path.of("src/main/resources/db/projects/migration/V46__agent_config_archetype.sql");
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

  /**
   * The same trap in the agent configuration skeleton (qits-1382), which carries two dot-directories
   * and a {@code .gitkeep} — {@code AgentConfigRepositoryTest} holds that they come out un-prefixed.
   */
  @Test
  public void noAgentConfigTemplateResourceIsStoredWithALeadingDot() throws Exception {
    Path agentConfig = Path.of("target/classes/agent-config-template");
    assertTrue(Files.isDirectory(agentConfig), "the agent configuration template is missing");
    try (Stream<Path> all = Files.walk(agentConfig)) {
      List<String> dotted =
          all.map(p -> p.getFileName().toString()).filter(n -> n.startsWith(".")).sorted().toList();
      assertTrue(dotted.isEmpty(), "store these dot-prefixed instead: " + dotted);
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

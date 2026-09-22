package eu.wohlben.qits.projects.entity;

import java.util.Map;
import java.util.Set;

/**
 * What kind of part of its project a repository is.
 *
 * <p><b>The name says the kind, and nothing else does.</b> A wrapper mounts every submodule at
 * {@code components/<component>/<name>}, where the path names the <em>component</em> — see {@link
 * eu.wohlben.qits.projects.control.WrapperPath} — so a directory decides nothing about taxonomy any
 * more. {@link #fromRepositoryName} is the single derivation of kind, over the role suffix of the
 * name grammar ({@code <component>[-<modifier>]-<role>[-<tech>]}): {@code qits-ci-service} is a
 * {@link #SERVICE}, {@code qits-eventstream-javalib} a {@link #LIBRARY}. It answers null for a name
 * carrying no role suffix, and null is what the reconcile stores rather than a guess, since nothing
 * in this service can correct a wrong archetype afterwards.
 *
 * <p>The <b>archetype layout</b> — a mount directory that <em>was</em> the kind, {@code services/},
 * {@code daemons/}, {@code libs/}, {@code frontends/}, {@code cli/}, {@code images/} — is retired
 * and its reading is gone with it. What it decided is not: the taxonomy is this enum, unchanged, and
 * so is the column it is stored in.
 *
 * <p><b>{@link #isComponentOfItsProject} is what the rest of the service asks of a value here.</b>
 * It means exactly what it says — a repository of this kind is a <em>component of its project</em>,
 * something the project's wrapper is expected to declare as a submodule and which the membership
 * rules therefore apply to (the write guard, the wrapper removal on delete, the listing's {@code
 * declared} flag, the reconcile's undeclared report). It is a per-constant fact stated at the
 * declaration and derived from nothing, because it is a statement about the archetype rather than a
 * consequence of one.
 *
 * <p>Three values answer false, each for its own reason: {@link #PROJECT} because it <em>is</em> the
 * tree — the wrapper cannot be a submodule of itself; {@link #SERVICE_TEMPLATE} because it is
 * scaffolding a component is generated <em>from</em> rather than part of the application; and {@link
 * #FORK} because it is an external downstream fork of somebody else's repository, which this project
 * carries but is not built out of. The other seven are components and answer true.
 *
 * <p>These ten are the whole set. {@code INTEGRATION} and {@code APPLICATION} were carried through
 * release A as deprecated aliases so Hibernate could read rows written before the rework; the H2
 * lineage's V4 retired the last of those rows and they are gone. {@link #APP} is the tenth, added
 * for a standalone web application — see its own doc for why it is not a {@link #FRONTEND}.
 *
 * <p>Adding a value here also requires a Flyway migration: {@code Repository.archetype} carries a DB
 * check constraint over the value set, written <b>inline and named</b> in {@code
 * db/projects/migration/V1__init.sql} as {@code CK_repository_archetype}. Inline is what makes it a
 * migration rather than an edit — an applied file is checksummed and must never be touched, and a
 * named constraint cannot be widened in place, so a new value needs a new migration that drops
 * {@code CK_repository_archetype} and adds it back over the wider set. {@code
 * db/projects/migration} is the one lineage there is here — main and test read the same location —
 * so one migration is the whole of it; {@code V27__repository_archetype_app.sql} is the one that
 * admitted {@link #APP}.
 */
public enum RepositoryArchetype {
  /** The project's wrapper repository — the root superproject. At most one per project. */
  PROJECT(false),
  /** A deployable component. */
  SERVICE(true),
  /** A long-running background agent — deployed rather than served. */
  DAEMON(true),
  /** Shared technical code consumed by the components. */
  LIBRARY(true),
  /** A microfrontend a service carries and serves — no deployment of its own. */
  FRONTEND(true),
  /**
   * A standalone web application — its own server, its own image, its own deployment.
   *
   * <p>The distinction from {@link #FRONTEND} is who runs it, not what it renders: a {@code
   * -frontend} is a microfrontend a service carries and serves, so it has no deployment of its own,
   * while an {@code -app} is deployed in its own right and answers on its own address.
   */
  APP(true),
  /** A command-line entry point into the application. */
  CLI(true),
  /** A build definition consumed through its published OCI image. */
  IMAGE(true),
  /** Scaffolding a component is generated <em>from</em>, not part of the application. */
  SERVICE_TEMPLATE(false),
  /** A downstream fork — an external repository, never inline. */
  FORK(false);

  private final boolean componentOfItsProject;

  RepositoryArchetype(boolean componentOfItsProject) {
    this.componentOfItsProject = componentOfItsProject;
  }

  /**
   * Whether a repository of this kind is a <b>component of its project</b> — part of what the
   * project is built out of, and therefore something the project's wrapper is expected to declare.
   *
   * <p>This is the whole of what membership rests on, and the class doc says which three values
   * answer false and why. It is stated per constant rather than derived, so that widening the enum
   * is a decision somebody makes at the declaration instead of a side effect of some other field.
   */
  public boolean isComponentOfItsProject() {
    return componentOfItsProject;
  }

  /**
   * The role suffixes of the name grammar, mapped to the archetype each one declares. Insertion
   * order is the matching order; no suffix is a suffix of another, so the order is documentation
   * rather than precedence.
   */
  private static final Map<String, RepositoryArchetype> ROLE_SUFFIXES =
      Map.of(
          "-service", SERVICE,
          "-daemon", DAEMON,
          "-frontend", FRONTEND,
          "-app", APP,
          "-oci", IMAGE,
          "-cli", CLI,
          "-javalib", LIBRARY,
          "-jslib", LIBRARY);

  /**
   * Every role suffix {@link #fromRepositoryName} reads, in no particular order — the set the
   * project template's {@code components/README.md} teaches, and which {@code
   * RepositoryArchetypeTemplateSyncTest} holds against that file in both directions. It is also what
   * the create flow's refusal names when neither the request nor the name says what kind of
   * component it is.
   */
  public static Set<String> roleSuffixes() {
    return ROLE_SUFFIXES.keySet();
  }

  /**
   * The archetype a repository <em>name</em> declares through its role suffix, or null when it
   * declares none.
   *
   * <p>This is the only derivation of kind there is: {@code qits-ci-service} is a {@link #SERVICE},
   * {@code qits-eventstream-javalib} a {@link #LIBRARY}, {@code qits-workspace-oci} an {@link
   * #IMAGE}. A tier modifier sits <em>before</em> the role ({@code
   * qits-deployments-platform-service}), so the suffix still decides.
   *
   * <p><b>Null is the answer for every name the renames have not reached</b> — {@code qits-ci},
   * {@code qits-spa-ci} — and it is a real answer, not a failure: the reconcile only ever asks this
   * for a row it is creating, and it stores the null rather than inventing a kind.
   */
  public static RepositoryArchetype fromRepositoryName(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    String trimmed = name.trim().toLowerCase(java.util.Locale.ROOT);
    for (Map.Entry<String, RepositoryArchetype> role : ROLE_SUFFIXES.entrySet()) {
      if (trimmed.endsWith(role.getKey()) && trimmed.length() > role.getKey().length()) {
        return role.getValue();
      }
    }
    return null;
  }
}

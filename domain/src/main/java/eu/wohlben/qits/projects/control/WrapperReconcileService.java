package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.control.ProjectConfig.DeliverySettings;
import eu.wohlben.qits.projects.control.ProjectConfig.ReleaseRequestsSettings;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.error.BadRequestException;
import eu.wohlben.qits.projects.persistence.RepositoryNameRepository;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Brings a project's rows in line with its wrapper's {@code .gitmodules}.
 *
 * <p>The wrapper <b>is</b> the project's configuration, and this is what makes that true of the
 * database too: every entry gets a row, and every row that is a component of the project and has no
 * entry is reported as undeclared. Importing a wrapper url and running this is how a whole project
 * is restored.
 *
 * <p><b>One path grammar, {@code components/<component>/<name>}</b> ({@link WrapperPath}), and it
 * states which component an entry belongs to rather than what kind of thing it is:
 *
 * <ul>
 *   <li>the second segment becomes the row's {@code component}, on every pass;
 *   <li><b>the kind is the repository's own statement, never the path's or the name's</b> (owner
 *       ruling 2026-11-03): its committed {@code .config/qits/repository.yml} {@code
 *       repository.archetype}, read from the repository's mirror at its main branch. A row this
 *       reconcile <em>mints</em> takes that value, or null when none can be read; an existing row is
 *       brought in line with it on every pass when it states one that differs, and keeps what it has
 *       when it states none — a reconcile never nulls a kind somebody stated. Null is the honest
 *       answer for a repository nobody has stated a kind for, and the least destructive one, since a
 *       null archetype is never reported undeclared and so is never offered for the delete that would
 *       destroy the repository.
 * </ul>
 *
 * <p>It replaces the recursive submodule import, which had the relationship backwards: it walked
 * whatever a repository happened to reference, at any depth, and registered all of it as siblings
 * with a guessed archetype. What belongs to a project is a decision somebody makes and commits, not
 * a graph a crawler found.
 *
 * <p>Per entry, and each one is a decision this class is allowed to make on its own:
 *
 * <ol>
 *   <li>a path that is not {@code components/<component>/<name>} is <b>skipped</b> with a warning —
 *       guessing what {@code vendor/x} or {@code components/a/b/c} means would be inventing
 *       taxonomy;
 *   <li>a matching row keeps its repository, its kind and its history, and takes the entry's
 *       component;
 *   <li>no row, but the git host already serves a repository under that name — <b>adopted</b>, id
 *       and history intact;
 *   <li>no row and nothing served, but the entry's url resolves to a reachable backend — <b>cloned
 *       </b>, one level, because the wrapper is the whole manifest and its children's own submodules
 *       are their own business;
 *   <li>anything else is skipped with a warning that says what was missing.
 * </ol>
 *
 * <p><b>This reconcile deletes nothing (2026-08-26).</b> A row that is a component of the project
 * and that no entry names is reported {@code UNDECLARED} and left exactly as it is. Deleting a repository destroys its history
 * on the git host now, and an edit to one file is not consent to that — so the answer names the
 * rows, and a person decides in the UI whether to delete one or put its wrapper entry back.
 *
 * <p>Every item is reconciled in its own try/catch. One entry that cannot be resolved never denies
 * the rest, and the outcome list is the answer rather than an exception — the same shape as {@code
 * ProjectReconcileService}'s dns re-assert.
 */
@ApplicationScoped
public class WrapperReconcileService {

  private static final Logger LOG = Logger.getLogger(WrapperReconcileService.class);

  /** What happened to one wrapper entry, or to one row the wrapper no longer names. */
  public enum Outcome {
    /** No row and nothing served: the entry's backend was cloned in. */
    CREATED,
    /** The git host already served it; the existing repository was registered as it stands. */
    ADOPTED,
    /** A row already matched and already agreed. */
    KEPT,
    /**
     * A row already matched and kept its kind — it always does — and the wrapper moved it to a
     * different <b>component</b>.
     */
    COMPONENT_UPDATED,
    /**
     * A row already matched and sat in the component the wrapper names; its <b>backup target</b>
     * was wrong or missing and now names the forge twin the wrapper implies.
     */
    SYNC_TARGET_UPDATED,
    /**
     * A row that is a component of its project and that no entry matched: nothing was done to it,
     * and somebody has to decide.
     */
    UNDECLARED,
    /** Nothing could be decided — see the warning. */
    SKIPPED
  }

  /**
   * One line of the reconcile's answer.
   *
   * <p>Most lines are about a wrapper entry and carry all six fields. Two shapes are not, and a
   * client has to read {@code outcome} to tell them apart:
   *
   * <ul>
   *   <li>an <b>{@code UNDECLARED}</b> line is about a row the wrapper does <em>not</em> name, so
   *       there is no path: {@code path} is null, {@code name} is the row's alias, {@code
   *       repositoryId} is the row still standing and {@code archetype} is the one it carries.
   *   <li>a wrapper that declares nothing answers with a <b>single {@code SKIPPED}</b> line about
   *       the wrapper itself: {@code path}, {@code name} and {@code archetype} are all null and
   *       {@code repositoryId} is the wrapper's own id.
   * </ul>
   *
   * @param path the wrapper path this is about, or null when the line is not about an entry
   * @param name the addressable name, which is what {@code ../<name>.git} resolves to
   * @param repositoryId the repository the entry now maps to, or null when there is none
   * @param archetype the archetype the row now carries — the committed {@code repository.archetype}
   *     when the repository states one (applied to an existing row, taken by a minted one), else the
   *     row's own (null for a minted row nothing states a kind for), and the row's own when the line
   *     is about a row the wrapper does not name
   * @param component the component the entry's path names, or null when the line is not about an
   *     entry
   * @param warning why an outcome is what it is, when the outcome does not say it; else null
   */
  public record EntryOutcome(
      String path,
      String name,
      String repositoryId,
      RepositoryArchetype archetype,
      String component,
      Outcome outcome,
      String warning) {}

  /**
   * @param wrapperRepositoryId the wrapper the manifest was read from
   * @param branch the branch it was read at
   */
  public record Reconciliation(
      String projectId, String wrapperRepositoryId, String branch, List<EntryOutcome> entries) {}

  @Inject ProjectService projectService;

  @Inject RepositoryService repositoryService;

  @Inject RepositoryRepository repositoryRepository;

  @Inject RepositoryNameRepository repositoryNameRepository;

  @Inject WrapperSubmoduleWriter wrapperWriter;

  @Inject QitsConfigParser configParser;

  @Inject GitMirrorRegistry gitMirrors;

  @Inject GitSubmoduleParser submoduleParser;

  @Inject ProjectConfigParser projectConfigParser;

  /** Absent is a supported configuration — see {@link ProjectAnnouncer}. */
  @Inject Instance<ProjectAnnouncer> projectAnnouncers;

  /** Told when the stored front desk lifecycle moves; {@link NoopFrontDeskLifecycleChanged} ships. */
  @Inject Instance<FrontDeskLifecycleChanged> lifecycleListeners;

  /**
   * Reconciles {@code projectId} against its wrapper and answers with what it came to.
   *
   * <p>{@link ActivateRequestContext} because the self-seed calls this on a worker thread with no
   * ambient request context; the individual service calls still own their transactions.
   */
  @ActivateRequestContext
  public Reconciliation reconcile(String projectId) {
    Project project = projectService.get(projectId);
    Repository wrapper =
        projectService
            .findWrapper(projectId)
            .orElseThrow(
                () ->
                    new BadRequestException(
                        "Project '"
                            + project.name
                            + "' has no wrapper repository, so there is no manifest to reconcile"
                            + " against."));
    String branch = wrapper.mainBranch == null || wrapper.mainBranch.isBlank() ? "main" : wrapper.mainBranch;

    reconcileProjectConfig(project, wrapper);

    List<WrapperGitmodules.Entry> declared =
        WrapperGitmodules.entries(wrapperWriter.readGitmodules(wrapper));

    List<EntryOutcome> outcomes = new ArrayList<>();
    Set<String> matchedRepoIds = new HashSet<>();
    matchedRepoIds.add(wrapper.id);

    for (WrapperGitmodules.Entry entry : declared) {
      try {
        outcomes.add(reconcileEntry(project, entry, matchedRepoIds));
      } catch (RuntimeException e) {
        LOG.errorf(e, "Reconcile of wrapper entry '%s' failed", entry.path());
        outcomes.add(
            new EntryOutcome(
                entry.path(), entry.name(), null, null, null, Outcome.SKIPPED, e.getMessage()));
      }
    }

    if (declared.isEmpty()) {
      // An empty manifest is not a manifest. Calling every component of a project undeclared
      // because its wrapper simply has not started declaring them yet would report the whole
      // project as strays — so the one entry is what turns the wrapper into the configuration,
      // exactly as the membership guard reads it.
      outcomes.add(
          new EntryOutcome(
              null,
              null,
              wrapper.id,
              null,
              null,
              Outcome.SKIPPED,
              "The wrapper declares no submodules, so nothing was registered and nothing was"
                  + " reported undeclared. Commit a .gitmodules entry per component and run this"
                  + " again."));
    } else {
      outcomes.addAll(reportUndeclared(project, matchedRepoIds));
    }
    return new Reconciliation(projectId, wrapper.id, branch, List.copyOf(outcomes));
  }

  // -------------------------------------------------------------------------------------------
  // the project's own declaration
  // -------------------------------------------------------------------------------------------

  /**
   * Brings the project's stored configuration — {@code Project.supportsEnvironments}, {@code
   * Project.frontDeskLifecycle}, the {@code Project.delivery*} settings and {@code
   * Project.releaseFeatureFreeze} (epic qits-1580) — in line with its wrapper's {@code .config/qits/project.yml}, and
   * announces the change when there is one. Callable alone: it reads the one file and does none of
   * the {@code .gitmodules} work.
   *
   * <p><b>Its triggers</b> (qits-767): {@code ReleaseRequests.onMainMoved} for a project's wrapper —
   * {@code main} only moves with finalized content, so this reads the <em>released</em> wrapper; one
   * boot pass over every project ({@code service/…/startup/ProjectConfigBootPass}, through {@link
   * #reassertProjectConfig} since qits-386); and the full {@link #reconcile} behind the manual door,
   * which calls this first.
   *
   * <p><b>An absent file is the default and an unparseable one is left alone.</b> Absence and
   * absent keys are one answer ({@link ProjectConfig#DEFAULT}). A file that will not parse throws
   * out of {@link ProjectConfigParser} — deliberately, so a typo cannot re-route a project — and
   * this catches it, logs it and keeps the stored values rather than reverting to a default the file
   * did not ask for. A project with no wrapper has no declaration and is left alone too.
   *
   * <p>{@code ProjectChanged} is published <b>only when a stored value actually moves</b>, after
   * the transaction that moved it, fire-and-forget like every other announcement here; a moved
   * front desk lifecycle additionally reaches {@link FrontDeskLifecycleChanged}, best effort, and so
   * do moved delivery settings ({@link FrontDeskLifecycleChanged#onDeliveryChanged}) — which alone
   * announce nothing, since {@code ProjectChanged} carries none of them. A moved feature freeze is
   * stored and announces nothing either: it is read where a source is added, never pushed.
   *
   * <p><b>Delta-only is this method's contract and not the whole story (qits-386).</b> The platform
   * edge learns {@code supportsEnvironments} and {@code frontDeskLifecycle} from the event log and
   * from nothing else, so one lost frame — a crash between the commit and the publish, an outbox
   * that gave up — left the edge wrong for ever: the next pass finds nothing moved and says nothing.
   * {@link #reassertProjectConfig} is the level-triggered half: the same reconcile, then the stored
   * values restated whether or not they moved. Its callers are the boot pass and the person's
   * project reconcile door; this method, the {@code main}-moved trigger and the full {@link
   * #reconcile} stay delta-only, so a timer or a release never restates anything.
   *
   * <p>{@link ActivateRequestContext} because the boot pass and the bus consumers call this on
   * threads with no ambient request context.
   */
  @ActivateRequestContext
  public void reconcileProjectConfig(String projectId) {
    Project project = projectService.get(projectId);
    Optional<Repository> wrapper = projectService.findWrapper(projectId);
    if (wrapper.isEmpty()) {
      LOG.debugf("Project %s has no wrapper, so there is no project.yml to read", projectId);
      return;
    }
    reconcileProjectConfig(project, wrapper.get());
  }

  /**
   * {@link #reconcileProjectConfig(String)}, and then {@code ProjectChanged} restating the values as
   * stored afterwards whether or not anything moved (qits-386) — level-triggered where the reconcile
   * is edge-triggered, so a frame lost between a commit and its publish is restated the next time
   * this runs rather than never.
   *
   * <p><b>Exactly one frame, never two.</b> A pass that moved a value has already announced it with
   * the new values, and that frame is the re-assertion; only a pass that announced nothing restates.
   * A {@code project.yml} that will not parse keeps the stored values, as the reconcile always does,
   * and those stored values are what is restated — the edge must still hear what the project is. A
   * project with no wrapper has no declaration and is restated nothing, exactly as the reconcile
   * leaves it alone.
   *
   * <p>The announcement is the same fire-and-forget {@link #announceChanged} the delta uses, so a
   * consumer cannot tell a restatement from a move; the edge's projection is idempotent on the
   * values, which is what makes saying it again harmless. Only {@code ProjectChanged} is restated:
   * the {@link FrontDeskLifecycleChanged} hooks act on this process's own rows, which no lost frame
   * can leave behind, and stay delta-only.
   *
   * <p>{@link ActivateRequestContext} for the boot pass's virtual thread, as on the reconcile.
   */
  @ActivateRequestContext
  public void reassertProjectConfig(String projectId) {
    Project project = projectService.get(projectId);
    Optional<Repository> wrapper = projectService.findWrapper(projectId);
    if (wrapper.isEmpty()) {
      LOG.debugf("Project %s has no wrapper, so there is no project.yml to re-assert", projectId);
      return;
    }
    if (!reconcileProjectConfig(project, wrapper.get())) {
      LOG.debugf(
          "Re-asserting project %s: supports_environments=%s, front_desk.lifecycle=%s as stored.",
          project.id, project.supportsEnvironments, project.frontDeskLifecycle);
      announceChanged(project);
    }
  }

  /**
   * @return whether this pass announced {@code ProjectChanged} — true exactly when {@code
   *     supportsEnvironments} or {@code frontDeskLifecycle} moved, which {@link
   *     #reassertProjectConfig} reads so a moved value is said once and not twice
   */
  private boolean reconcileProjectConfig(Project project, Repository wrapper) {
    ProjectConfig config;
    try {
      config = projectConfigParser.parse(wrapperWriter.readFile(wrapper, ProjectConfigParser.CONFIG_PATH));
    } catch (RuntimeException e) {
      LOG.warnf(
          "%s in the wrapper of project %s could not be read or does not parse; keeping"
              + " supports_environments=%s, front_desk.lifecycle=%s, front_desk.delivery=%s and"
              + " release_requests=%s as stored: %s",
          ProjectConfigParser.CONFIG_PATH,
          project.id,
          project.supportsEnvironments,
          project.frontDeskLifecycle,
          DeliverySettings.of(project),
          ReleaseRequestsSettings.of(project),
          e.getMessage());
      return false;
    }
    boolean environmentsMoved = config.supportsEnvironments() != project.supportsEnvironments;
    boolean lifecycleMoved = config.frontDeskLifecycle() != project.frontDeskLifecycle;
    DeliverySettings storedDelivery = DeliverySettings.of(project);
    boolean deliveryMoved = !config.delivery().equals(storedDelivery);
    ReleaseRequestsSettings storedReleaseRequests = ReleaseRequestsSettings.of(project);
    boolean releaseRequestsMoved = !config.releaseRequests().equals(storedReleaseRequests);
    if (!environmentsMoved && !lifecycleMoved && !deliveryMoved && !releaseRequestsMoved) {
      return false;
    }
    LOG.infof(
        "Reconcile: project %s declares supports_environments=%s, front_desk.lifecycle=%s,"
            + " front_desk.delivery=%s, release_requests=%s; stored as %s, %s, %s, %s — updating.",
        project.id,
        config.supportsEnvironments(),
        config.frontDeskLifecycle(),
        config.delivery(),
        config.releaseRequests(),
        project.supportsEnvironments,
        project.frontDeskLifecycle,
        storedDelivery,
        storedReleaseRequests);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project row = projectService.get(project.id);
              row.supportsEnvironments = config.supportsEnvironments();
              row.frontDeskLifecycle = config.frontDeskLifecycle();
              config.delivery().applyTo(row);
              config.releaseRequests().applyTo(row);
            });
    project.supportsEnvironments = config.supportsEnvironments();
    project.frontDeskLifecycle = config.frontDeskLifecycle();
    config.delivery().applyTo(project);
    config.releaseRequests().applyTo(project);
    if (lifecycleMoved) {
      notifyLifecycleChanged(project);
    }
    if (deliveryMoved) {
      // Every delivery move reaches the hook, not only the toggle; only `enabled` is in the desk's
      // spec, so a move of the others rolls nothing (the roll finds the composed spec applied).
      notifyDeliveryChanged(project);
    }
    if (environmentsMoved || lifecycleMoved) {
      // ProjectChanged carries neither delivery fact, so a delivery move alone has nothing to say.
      announceChanged(project);
      return true;
    }
    return false;
  }

  /** Best effort, outside every transaction and never able to fail a reconcile. */
  private void notifyDeliveryChanged(Project project) {
    if (!lifecycleListeners.isResolvable()) {
      return;
    }
    try {
      lifecycleListeners.get().onDeliveryChanged(project.id, DeliverySettings.of(project));
    } catch (RuntimeException e) {
      LOG.warnf(
          e,
          "Could not act on the front desk delivery settings %s of project %s",
          DeliverySettings.of(project),
          project.id);
    }
  }

  /** Best effort, outside every transaction and never able to fail a reconcile. */
  private void notifyLifecycleChanged(Project project) {
    if (!lifecycleListeners.isResolvable()) {
      return;
    }
    try {
      lifecycleListeners.get().onChanged(project.id, project.frontDeskLifecycle);
    } catch (RuntimeException e) {
      LOG.warnf(
          e,
          "Could not act on the front desk lifecycle %s of project %s",
          project.frontDeskLifecycle,
          project.id);
    }
  }

  /** Fire and forget, outside every transaction and never able to fail a reconcile. */
  private void announceChanged(Project project) {
    if (!projectAnnouncers.isResolvable()) {
      return;
    }
    try {
      projectAnnouncers
          .get()
          .onProjectChanged(
              project.id,
              project.slug,
              project.name,
              project.supportsEnvironments,
              project.frontDeskLifecycle,
              Instant.now());
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not announce the change of project %s", project.id);
    }
  }

  // -------------------------------------------------------------------------------------------
  // one entry
  // -------------------------------------------------------------------------------------------

  private EntryOutcome reconcileEntry(
      Project project, WrapperGitmodules.Entry entry, Set<String> matchedRepoIds) {
    String path = entry.path();
    WrapperPath parsed = WrapperPath.parse(path);
    if (parsed == null) {
      // One arm for every path the grammar refuses — a retired archetype directory, a path with
      // nothing under the component, a deeper tree, a bare name. There is no second grammar to try
      // them against any more, so the reason a skip states is the grammar itself.
      return new EntryOutcome(
          path,
          entry.name(),
          null,
          null,
          null,
          Outcome.SKIPPED,
          "'"
              + path
              + "' is not '"
              + WrapperPath.COMPONENTS_DIRECTORY
              + "/<component>/<name>', so there is no component and no name to read out of it.");
    }
    String name = parsed.name();
    String component = parsed.component();

    SyncTarget syncTarget = syncTargetFor(project, entry);
    Repository existing = match(project, entry, name);
    if (existing != null) {
      matchedRepoIds.add(existing.id);
      return adjustExisting(project, existing, parsed, syncTarget);
    }

    // The host already serves it under the entry name as its storage id — a host seeded before this
    // service existed, where the two coordinates happen to coincide (a name is a valid opaque id).
    // Adoption is what gives such a repository a row without disturbing the storage key its bare
    // already lives under; it registers the entry name as the addressable alias in the same
    // transaction. A host that stores UUIDs is reached the other way round: the bootstrap registers
    // the rows itself, so the reconcile finds them by alias in match() and never gets here.
    if (repositoryService.hasExistingOrigin(name)) {
      Repository adopted =
          QuarkusTransaction.requiringNew()
              .call(
                  () -> {
                    // Adoption is idempotent on the storage id and answers a pre-existing row
                    // untouched, so whether the row is this call's to write has to be asked first —
                    // otherwise the minted row's kind would be written over a real one.
                    boolean minted = repositoryRepository.findByIdOptional(name).isEmpty();
                    // With the derived backup target, not null: an adopted row that never learns
                    // where its forge twin is is a repository nothing backs up, and the derivation
                    // is the same one every other row's target comes from.
                    Repository repo =
                        repositoryService.adoptExistingOrigin(
                            project, name, name, syncTarget.url(), null);
                    repositoryNameRepository.ensureAlias(project, name, repo);
                    repo.component = component;
                    if (minted) {
                      // The repository's own committed statement of its kind, or null when none
                      // can be read — an adopted row usually has no mirror yet, and the next
                      // reconcile that can read one applies it (applyCommittedArchetype).
                      repo.archetype = committedArchetype(repo.id, repo.mainBranch);
                    }
                    return repo;
                  });
      matchedRepoIds.add(adopted.id);
      return new EntryOutcome(
          path,
          name,
          adopted.id,
          adopted.archetype,
          component,
          Outcome.ADOPTED,
          syncTarget.warning());
    }

    // Nothing here yet: clone the backend the entry names. This is the restore path — a wrapper url
    // imported into an empty deployment brings the whole project back one entry at a time.
    Optional<String> backend = resolveBackendUrl(project, entry);
    if (backend.isEmpty()) {
      return new EntryOutcome(
          path,
          name,
          null,
          null,
          component,
          Outcome.SKIPPED,
          "Nothing is served as '"
              + name
              + "' and '"
              + entry.url()
              + "' does not resolve to a backend this service can clone from"
              + " (a relative url needs the wrapper's own backup remote to fold against).");
    }
    Repository created =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  // The entry name is the row's addressable name: it is what the git host serves
                  // the repository as and what the deployer's image name repeats — never the url
                  // basename, which an entry is free to differ from. The row's id is minted.
                  Repository repo =
                      repositoryService.cloneRepository(backend.get(), null, project, name);
                  repositoryNameRepository.ensureAlias(project, name, repo);
                  repo.component = component;
                  // A clone always mints its row and leaves a mirror behind, so the repository's
                  // own committed statement of its kind is readable here — null when it states
                  // none, stored as the null it is rather than as a guess.
                  repo.archetype = committedArchetype(repo.id, repo.mainBranch);
                  return repo;
                });
    matchedRepoIds.add(created.id);
    return new EntryOutcome(
        path, name, created.id, created.archetype, component, Outcome.CREATED, null);
  }

  /**
   * The row this entry is about: by the name it is addressable under first, then by the url the
   * entry resolves to.
   *
   * <p>There is no third arm reading the name as a repository id. An id is an opaque storage key
   * now, so a name matching one would be a coincidence rather than a resolution — and the alias
   * table is written by every creation path, adoption included, so an entry with a row always has
   * an alias to be found by.
   */
  private Repository match(Project project, WrapperGitmodules.Entry entry, String name) {
    Repository byName =
        repositoryNameRepository.findRepositoryByProjectAndName(project.id, name).orElse(null);
    if (byName != null) {
      return byName;
    }
    return resolveBackendUrl(project, entry)
        .flatMap(url -> repositoryRepository.findByUrlInProject(url, project.id))
        .orElse(null);
  }

  /**
   * The row kept, with the wrapper having the last word on where it is backed up to (the entry's
   * url, folded against the wrapper's own) and on which component it belongs to. Its alias is
   * re-asserted so {@code ../<name>.git} resolves.
   *
   * <p><b>The path never touches the archetype</b> — it states a component, never a kind. The
   * repository's own committed {@code repository.archetype} does: when it states one that differs
   * from the row's, it is applied ({@link #applyCommittedArchetype}) and said in the line's warning;
   * when it states none, the row keeps what it has.
   *
   * <p>A row can need both corrections at once. Both are applied; the outcome reports the bigger
   * statement about the repository, because a client shows one label per row: a component move
   * outranks a retarget.
   */
  private EntryOutcome adjustExisting(
      Project project, Repository existing, WrapperPath parsed, SyncTarget syncTarget) {
    String name = parsed.name();
    String beforeComponent = existing.component;
    RepositoryArchetype[] after = new RepositoryArchetype[1];
    boolean[] retargeted = new boolean[1];
    String warning =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Repository repo = repositoryService.get(existing.id);
                  repositoryNameRepository.ensureAlias(project, name, repo);
                  repo.component = parsed.component();
                  if (syncTarget.url() != null && !syncTarget.url().equals(repo.url)) {
                    LOG.infof(
                        "Reconcile: %s backs up to %s but the wrapper implies %s — repointing it.",
                        name, repo.url, syncTarget.url());
                    repo.url = syncTarget.url();
                    retargeted[0] = true;
                  }
                  String applied = applyCommittedArchetype(repo);
                  after[0] = repo.archetype;
                  return applied;
                });
    Outcome outcome =
        !java.util.Objects.equals(beforeComponent, parsed.component())
            ? Outcome.COMPONENT_UPDATED
            : retargeted[0] ? Outcome.SYNC_TARGET_UPDATED : Outcome.KEPT;
    return new EntryOutcome(
        parsed.path(),
        name,
        existing.id,
        after[0],
        parsed.component(),
        outcome,
        join(warning, syncTarget.warning()));
  }

  /** Both reasons a row can be worth a note, in one field. */
  private static String join(String first, String second) {
    if (first == null) {
      return second;
    }
    return second == null ? first : first + " " + second;
  }

  /**
   * Brings the row's archetype in line with the repository's own committed {@code
   * .config/qits/repository.yml} {@code repository.archetype} — the one statement of a kind a
   * reconcile listens to (owner ruling 2026-11-03).
   *
   * <p>A readable committed value that differs from the row's — a null row included — is applied,
   * logged, and answered as the line's warning so the reconcile says what it changed. With no
   * committed value (no mirror yet, no file, no key, a file that will not parse) the row keeps what
   * it has: a reconcile never nulls a kind somebody stated at creation. The wrapper row is never
   * changed, and {@link RepositoryArchetype#PROJECT} is never applied (the parser refuses it too).
   *
   * <p>Either way it clears {@code config_warning}, which only the retired "the row wins" note ever
   * wrote: that disagreement can no longer stand.
   *
   * @return the sentence saying what was applied, or null when nothing changed
   */
  private String applyCommittedArchetype(Repository repo) {
    // The retired "the row wins" note was this column's only writer; that disagreement can no
    // longer stand, so whatever it left is stale.
    repo.configWarning = null;
    if (repo.archetype == RepositoryArchetype.PROJECT) {
      return null;
    }
    RepositoryArchetype committed = committedArchetype(repo.id, repo.mainBranch);
    if (committed == null
        || committed == RepositoryArchetype.PROJECT
        || committed == repo.archetype) {
      return null;
    }
    RepositoryArchetype before = repo.archetype;
    repo.archetype = committed;
    LOG.infof(
        "Reconcile: %s states archetype %s in its committed repository config; the row carried %s"
            + " — applied.",
        repo.id, committed, before);
    return "The committed repository config declares archetype "
        + committed
        + "; the row carried "
        + before
        + " and now carries "
        + committed
        + ".";
  }

  /**
   * The archetype a repository states for itself in its committed {@code .config/qits/repository.yml}
   * at {@code branch}, read from its mirror — or null when there is nothing to read.
   *
   * <p>Best effort and read-only: a mirror that is not there yet, a file or key that is absent, and
   * a file that will not parse all answer null, and nothing here fetches. The mint arms ask it right
   * after the row exists (a clone leaves a mirror behind; an adoption usually does not), {@link
   * #applyCommittedArchetype} on every pass over an existing row.
   */
  RepositoryArchetype committedArchetype(String repoId, String branch) {
    try {
      java.nio.file.Path gitDir = gitMirrors.of(repoId).gitDir();
      if (!java.nio.file.Files.isDirectory(gitDir)) {
        return null;
      }
      QitsConfig config =
          configParser.readConfig(
              gitDir.toFile(), branch == null || branch.isBlank() ? "main" : branch);
      return config.repository() == null ? null : config.repository().archetype();
    } catch (RuntimeException e) {
      LOG.debugf("Could not read the committed config of %s: %s", repoId, e.getMessage());
      return null;
    }
  }

  /**
   * Where a component is backed up to, derived from the wrapper rather than stored per row.
   *
   * <p>{@code Repository.url} has always been the <b>backup twin</b> and never a clone source — the
   * platform clones through its own git host, always — and deriving it is what makes it uniform: the
   * wrapper's own url folded with the entry's relative one is, by construction, the sibling a
   * {@code ../<name>.git} resolves to at the forge. The same fold every clone of the superproject
   * performs, so the answer cannot disagree with what a person sees.
   *
   * @param url the target, or null when the wrapper names no forge to fold against (a project whose
   *     wrapper is greenfield has no twin for anything, and that is a normal state)
   * @param warning why there is no target when the reason is worth saying out loud
   */
  private record SyncTarget(String url, String warning) {
    static final SyncTarget NONE = new SyncTarget(null, null);

    static SyncTarget of(String url) {
      return new SyncTarget(url, null);
    }

    static SyncTarget refused(String url) {
      return new SyncTarget(
          null,
          "The backup target this entry implies ("
              + url
              + ") is qits' own git host, so it was not applied — a repository cannot be its own"
              + " backup. Commit the entry's url as a relative one and give the wrapper a forge"
              + " remote.");
    }
  }

  /** See {@link SyncTarget}. */
  private SyncTarget syncTargetFor(Project project, WrapperGitmodules.Entry entry) {
    String raw = entry.url() == null ? "" : entry.url().trim();
    if (raw.isBlank()) {
      return SyncTarget.NONE;
    }
    if (!raw.startsWith("./") && !raw.startsWith("../")) {
      // An absolute entry names its backend outright; there is nothing to fold.
      return submoduleParser.isQitsHostUrl(raw) ? SyncTarget.refused(raw) : SyncTarget.of(raw);
    }
    String wrapperUrl =
        projectService.findWrapper(project.id).map(wrapper -> wrapper.url).orElse(null);
    if (wrapperUrl == null || wrapperUrl.isBlank()) {
      return SyncTarget.NONE;
    }
    String folded = submoduleParser.resolveSubmoduleUrl(wrapperUrl, raw);
    return submoduleParser.isQitsHostUrl(folded) ? SyncTarget.refused(folded) : SyncTarget.of(folded);
  }

  /**
   * The absolute backend url an entry names, folded against the wrapper's own backup remote for a
   * relative one. Empty when there is nothing to fold against, or when the result would point back
   * at qits' own git host (which would make the clone mirror this platform's cache instead of the
   * real backend).
   */
  private Optional<String> resolveBackendUrl(Project project, WrapperGitmodules.Entry entry) {
    String raw = entry.url();
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    String trimmed = raw.trim();
    if (!trimmed.startsWith("./") && !trimmed.startsWith("../")) {
      return Optional.of(trimmed).filter(url -> !submoduleParser.isQitsHostUrl(url));
    }
    return projectService
        .findWrapper(project.id)
        .map(wrapper -> wrapper.url)
        .filter(url -> url != null && !url.isBlank())
        .map(url -> submoduleParser.resolveSubmoduleUrl(url, trimmed))
        .filter(url -> !submoduleParser.isQitsHostUrl(url));
  }

  // -------------------------------------------------------------------------------------------
  // the other direction
  // -------------------------------------------------------------------------------------------

  /**
   * Reports every row of the project that is a component of it and that no wrapper entry claimed,
   * and <b>changes nothing</b>. A row whose archetype is not a component of its project ({@code
   * FORK}, {@code SERVICE_TEMPLATE}) and the wrapper itself are left out — they were never expected
   * in the manifest.
   *
   * <p>A row with <b>no</b> archetype is left out too, and that is the whole reason a minted row
   * stores null rather than a guess: an UNDECLARED line is what offers a person the delete that
   * destroys the repository on the git host, and a row nobody has said the kind of must never be
   * put in front of that decision on the strength of a guess.
   *
   * <p><b>That null exclusion is deliberately the opposite of what the other three membership sites
   * do</b>, and the asymmetry is the point rather than an oversight. {@code
   * RepositoryService.requireWrapperMembership}, {@code RepositoryService.removeFromWrapper} and the
   * listing's {@code declared} flag all treat a null archetype as a member, because the worst thing
   * each of them can do to such a row is refuse a write, take a gitlink out of a file, or draw a
   * badge. This one's consequence is a person being offered an irreversible delete, so it is the one
   * site where "we do not know what this is" has to mean "leave it alone".
   */
  private List<EntryOutcome> reportUndeclared(Project project, Set<String> matchedRepoIds) {
    return repositoryRepository.find("project.id", project.id).list().stream()
        .filter(repo -> !matchedRepoIds.contains(repo.id))
        .filter(repo -> repo.archetype != null && repo.archetype.isComponentOfItsProject())
        .map(
            stray ->
                new EntryOutcome(
                    null,
                    repositoryNameRepository.nameFor(stray).orElse(stray.id),
                    stray.id,
                    stray.archetype,
                    stray.component,
                    Outcome.UNDECLARED,
                    "No wrapper entry names this repository, so it is not part of the project."
                        + " Delete it from the project setup page, or add the entry back to the"
                        + " wrapper."))
        .toList();
  }

  /**
   * The wrapper's manifest as the UI reads it: what the file says, joined to the rows it resolved
   * to. An entry with a null {@code repositoryId} is one the project has no repository for, which is
   * exactly the "out of sync" the reconcile button is for.
   */
  public WrapperView view(String projectId) {
    Optional<Repository> wrapper = projectService.findWrapper(projectId);
    if (wrapper.isEmpty()) {
      return null;
    }
    Repository repo = wrapper.get();
    String branch = repo.mainBranch == null || repo.mainBranch.isBlank() ? "main" : repo.mainBranch;
    List<WrapperView.Entry> entries = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (WrapperGitmodules.Entry entry : WrapperGitmodules.entries(readQuietly(repo))) {
      String path = entry.path();
      if (path == null || path.isBlank() || !seen.add(path)) {
        continue;
      }
      String name = path.substring(path.lastIndexOf('/') + 1);
      // The shared resolution, not a copy of it: the by-name read serves the same answer, and a
      // second spelling here would let the two disagree about what belongs to the project.
      String repositoryId =
          repositoryService.findByProjectAndName(projectId, name).map(r -> r.id).orElse(null);
      entries.add(new WrapperView.Entry(path, name, repositoryId));
    }
    return new WrapperView(repo.id, branch, List.copyOf(entries));
  }

  /**
   * The wrapper's manifest as a read surface — see {@link #view}.
   *
   * @param repositoryId the wrapper repository itself, so the UI can show its sync status
   */
  public record WrapperView(String repositoryId, String branch, List<Entry> entries) {
    /**
     * @param repositoryId the repository this entry resolved to, or null when nothing answers to
     *     its name in this project
     */
    public record Entry(String path, String name, String repositoryId) {}
  }

  /**
   * Whether {@code wrapper} declares the repository {@code repositoryId} — the one reading of
   * membership both listings take, {@code GET /projects/{projectId}/repositories}' {@code declared}
   * and the catalogue's ({@link eu.wohlben.qits.projects.dto.RepositoryCoordinatesDto}), so the two
   * cannot drift. False is a row no wrapper entry names; everything membership does not apply to is
   * true:
   *
   * <ul>
   *   <li>a project with no wrapper ({@code wrapper} null), and a manifest that declares nothing or
   *       could not be read — {@link #view} answers both with no entries, and an empty manifest is
   *       not a manifest;
   *   <li>an archetype a repository is not a component of its project with ({@code FORK}, {@code
   *       SERVICE_TEMPLATE}, {@code AGENT_CONFIG});
   *   <li>the wrapper itself.
   * </ul>
   *
   * <p>A <b>null</b> archetype is judged like any other member, as the write guard judges it. The
   * reconcile's undeclared report is the one site that reads null the other way — see {@link
   * #reportUndeclared}.
   *
   * @param wrapper the project's {@link #view}, or null for a project with no wrapper
   */
  public static boolean declares(
      WrapperView wrapper, String repositoryId, RepositoryArchetype archetype) {
    if (wrapper == null || wrapper.entries().isEmpty()) {
      return true;
    }
    if (archetype != null && !archetype.isComponentOfItsProject()) {
      return true;
    }
    if (repositoryId == null) {
      return false;
    }
    return repositoryId.equals(wrapper.repositoryId())
        || wrapper.entries().stream().anyMatch(e -> repositoryId.equals(e.repositoryId()));
  }

  /** Reading the manifest must never fail a listing — an unreachable wrapper simply has none. */
  private String readQuietly(Repository wrapper) {
    try {
      return wrapperWriter.readGitmodules(wrapper);
    } catch (RuntimeException e) {
      LOG.warnf("Could not read the wrapper's .gitmodules for %s: %s", wrapper.id, e.getMessage());
      return "";
    }
  }
}

package eu.wohlben.qits.projects.control;

import java.util.List;
import java.util.Optional;

/**
 * The git host's lifecycle verbs this context needs — does it hold a repository, can one be created,
 * and can one be deleted — over the wire contract qits-githost's {@code GitHostRoutes} serves
 * (projects-volume-decoupling-plan.md §2.1, §2.3, §3.2).
 *
 * <p><b>Mandatory, unlike every other port in this repo's README table.</b> Every one of those is
 * injected as {@code Instance<T>} because absence is a supported, documented configuration; this one
 * is a plain {@code @Inject} because a repository this service creates has nowhere to exist without
 * it — the same reasoning qits-workspaces' {@code RepositoryLookup} javadoc gives for being the one
 * mandatory port there. An application that pulls this jar in with no implementation is
 * misconfigured and should fail at startup rather than 404 every repository create at runtime.
 *
 * <p>{@code ensure}, {@code find}, {@code delete} and {@code putNames} are the whole of what this
 * context needs: no enumerate (nothing walks the host any more), no set-HEAD (creation takes the
 * default branch and never changes it afterwards).
 *
 * <p><b>Delete is real now (2026-08-26), and it overrules ⚖2.</b> A repository deleted here is
 * deleted on the host, in the same request, and a host that fails fails the delete — the row stays
 * so the two sides cannot disagree. There is no tombstone, no retention and no promise that putting
 * the wrapper entry back brings the history home.
 */
public interface GitHostRepositories {

  /** {@code GET /git/<repoId>}'s 200 body: the repository's id and current default branch. */
  record HostRepository(String repoId, String defaultBranch) {}

  /**
   * {@code PUT /git/<repoId>} — creates the repository with {@code defaultBranch} if the
   * host does not hold it yet. <b>Idempotent</b>: a repeat call against an id that already exists
   * succeeds as a no-op, which is what makes create-then-publish safely re-runnable after a push
   * that failed partway (§2.2).
   *
   * @return {@code true} when this call created the repository (the host answered 201), {@code
   *     false} when one already existed (200)
   * @throws GitHostException the host rejected the request, answered unexpectedly, or was
   *     unreachable
   */
  boolean ensure(String repoId, String defaultBranch);

  /**
   * {@code GET /git/<repoId>} — present means the host holds this repository, which is
   * what {@code adoptExistingOrigin} asks instead of a {@code Files.isDirectory} on the old shared
   * volume.
   *
   * @throws GitHostException the host answered with a status other than 200 or 404, or was
   *     unreachable
   */
  Optional<HostRepository> find(String repoId);

  /**
   * {@code DELETE /git/<repoId>} — removes the repository and everything it holds. The
   * caller is a repository delete, and it runs inside that delete's transaction: a failure here
   * rolls the row back, so a repository never survives as a row the host has already dropped, nor
   * as a bare no row names.
   *
   * @return {@code true} when the host deleted it (204), {@code false} when it held nothing under
   *     that id (404) — already gone is not a failure
   * @throws GitHostException the host refused, answered unexpectedly, or was unreachable
   */
  boolean delete(String repoId);

  /** What the git host made of a {@link #putNames}. */
  enum NamesOutcome {
    /** 200 with {@code applied: true}: the host now resolves exactly these names to the id. */
    APPLIED,
    /**
     * 200 with {@code applied: false}: the host already held this revision or a newer one and kept
     * what it had — the answer to a repeat push, and to one a later push overtook.
     */
    ALREADY_CURRENT,
    /** 404: the host stores no repository under this id, and wrote nothing. */
    NOT_STORED
  }

  /**
   * {@code PUT /git/<repoId>/names} (qits-1215) — replaces the git host's name index entry for the
   * repository with exactly {@code names} under {@code (projectId, projectSlug)}, ordered by {@code
   * revision}: the host keeps what it holds when it already has an equal or newer revision, so the
   * call is idempotent and pushes may race. Guarded like {@code PUT/DELETE /git/<repoId>}.
   *
   * <p>A 404 is an answer, not a fault — a repository this service names but the host does not store
   * (yet, or any more) — and is reported as {@link NamesOutcome#NOT_STORED}.
   *
   * @throws GitHostException the host refused the body (400), answered anything else unexpected, or
   *     was unreachable
   */
  NamesOutcome putNames(
      String repoId, String projectId, String projectSlug, List<String> names, long revision);
}

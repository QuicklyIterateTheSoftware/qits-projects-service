package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/**
 * The released tags of every repository and whether each has reached {@code main}. The questions are
 * the flow's own: what is still in flight for a repository (the implicit source set), the row for
 * one tag, the rows a version names across the whole platform (the publish phase's correlation), and
 * what is owed a merge (the publish phase's sweep).
 */
@ApplicationScoped
public class ReleasedTagPendingMergeRepository
    implements PanacheRepositoryBase<ReleasedTagPendingMerge, String> {

  /**
   * The repository's releases still in flight — released, not yet on {@code main} — oldest first.
   * This <b>is</b> the implicit source set: every open request of the repository folds these in, so
   * that a release cannot be a step backwards from one already shipping.
   */
  public List<ReleasedTagPendingMerge> listPending(String repoId) {
    return list("repoId = ?1 and mergedAt is null order by releasedAt", repoId);
  }

  public Optional<ReleasedTagPendingMerge> find(String repoId, String tagName) {
    return find("repoId = ?1 and tagName = ?2", repoId, tagName).firstResultOptional();
  }

  /**
   * Every repository's row for one tag name, whatever state it is in — the correlation {@code
   * DeploymentActive} forces, since that event names an application and a version and no repository.
   *
   * <p>A list rather than an {@code Optional} because the uniqueness is a fact about the platform
   * (one calver, stamped to the second, refused as {@code tag-exists} on a collision) and not a
   * constraint in this table, whose unique key is {@code (repo_id, tag_name)}. The caller decides
   * what a second row means; answering the first would be a guess.
   */
  public List<ReleasedTagPendingMerge> listByTag(String tagName) {
    return list("tagName = ?1 order by releasedAt", tagName);
  }

  /**
   * Everything gated and not landed: the merges this service owes {@code main}. The publish phase's
   * sweep is the only caller, and a row is here exactly while the git host has not applied it.
   */
  public List<ReleasedTagPendingMerge> listOwedMerges() {
    return list("mergeRequestedAt is not null and mergedAt is null order by mergeRequestedAt");
  }

  /**
   * Everything released and not yet gated at all — no deployment reported, no shortcut taken —
   * oldest first. The publish phase's <b>catch-up</b> is the only caller: it re-asks the
   * deployability question for each of these, which is what heals a tag whose fork never ran (this
   * service died between the tag and the fork, or the tag predates the fork living here at all).
   *
   * <p>Bounded by construction: a row leaves this list the moment anything gates it, so the set is
   * the releases genuinely in flight plus whatever is stuck — a handful, not a history.
   */
  public List<ReleasedTagPendingMerge> listUngated() {
    return list("mergeRequestedAt is null and mergedAt is null order by releasedAt");
  }

  /** The tags a page of release requests produced, for naming what reached {@code main}. */
  public List<ReleasedTagPendingMerge> listByRequests(List<String> requestIds) {
    if (requestIds.isEmpty()) {
      return List.of();
    }
    return list("releaseRequestId in ?1", requestIds);
  }

  /**
   * {@code list} and not {@code find}, deliberately: this class declares its own two-String {@code
   * find(repoId, tagName)}, which a {@code find("… = ?1", one)} call silently resolves to.
   */
  public Optional<ReleasedTagPendingMerge> findByRequest(String requestId) {
    return list("releaseRequestId = ?1", requestId).stream().findFirst();
  }

  /**
   * <b>The newest release this service knows of for one repository</b>, merged or not — which is to
   * say, the version a wrapper's gitlink should be pinned at.
   *
   * <p>The newest row is the answer because <b>rows here are never deleted</b>. Reaching {@code
   * main} stamps {@code mergedAt} and leaves the row standing, so this table is the record of which
   * releases happened rather than only of which are in flight; {@link #listPending} narrows to the
   * second question and this one deliberately does not. Ordering is by {@code releasedAt} rather
   * than by {@code tagName}, because a calver is a name and only the instant is a time — string
   * ordering happens to agree with it today and would stop agreeing the moment a version is stamped
   * any other way.
   *
   * <p><b>The writer's caveat travels with the reader.</b> {@code ReleaseRequests} populates this
   * table going forward only, so a platform that released before it existed holds tags with no row
   * at all. Such a repository answers empty here — "no release yet" — and a caller pinning an estate
   * skips it. That is the safe direction and the only honest one: the alternative is inventing a
   * version for a release this service cannot see, and a pin is not a thing to guess at.
   *
   * <p>{@code list} and not {@code find}, for {@link #findByRequest}'s reason one method up: the
   * one-argument {@code find("repoId = ?1", x)} binds to this class's own two-String {@code find}
   * overload instead of to Panache's query form.
   */
  public Optional<ReleasedTagPendingMerge> latestReleased(String repoId) {
    return list("repoId = ?1 order by releasedAt desc", repoId).stream().findFirst();
  }
}

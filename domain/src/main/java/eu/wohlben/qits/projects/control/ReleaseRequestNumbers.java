package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.persistence.ReleaseRequestRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * <b>The per-repository release request number allocator</b> (qits-1158, V41): the one place a
 * {@code ReleaseRequest.number} comes from. Unique within the repository, never reused, gaps
 * allowed.
 *
 * <p>A copy of the epics lineage's {@code EntityNumbers}, keyed by repository id instead of project
 * id, and its reasoning holds unchanged: a counter row in {@code release_request_number_sequence},
 * bumped with {@code update … set next_number = next_number + 1} (a row lock, so two creates cannot
 * collide) in <b>a transaction of its own</b> (so a create that rolls back leaves a gap instead of
 * handing the number out twice). A repository with no counter row gets one at 1 on first use; V41
 * seeded every repository that already had requests with {@code max + 1}.
 *
 * <p>Keyed by the repository <b>id</b>, not its name: a rename keeps counting, and the old ids keep
 * the old name.
 */
@ApplicationScoped
public class ReleaseRequestNumbers {

  @Inject ReleaseRequestRepository requests;

  /** The next number for {@code repoId}. */
  public long next(String repoId) {
    if (repoId == null || repoId.isBlank()) {
      throw new IllegalArgumentException("repoId is required");
    }
    return QuarkusTransaction.requiringNew().call(() -> bump(repoId));
  }

  private long bump(String repoId) {
    var em = requests.getEntityManager();
    em.createNativeQuery(
            "insert into release_request_number_sequence (repo_id, next_number) values (:repo, 1)"
                + " on conflict (repo_id) do nothing")
        .setParameter("repo", repoId)
        .executeUpdate();
    em.createNativeQuery(
            "update release_request_number_sequence set next_number = next_number + 1"
                + " where repo_id = :repo")
        .setParameter("repo", repoId)
        .executeUpdate();
    Object taken =
        em.createNativeQuery(
                "select next_number from release_request_number_sequence where repo_id = :repo")
            .setParameter("repo", repoId)
            .getSingleResult();
    return ((Number) taken).longValue() - 1;
  }
}

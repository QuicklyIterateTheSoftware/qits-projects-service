package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.control.GitHostRepositories.NamesOutcome;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.persistence.RepositoryNameRepository;
import eu.wohlben.qits.projects.persistence.RepositoryNamesChanged;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * <b>Keeps the git host's name index equal to the {@code repository_name} table</b> (qits-1215).
 * qits-githost resolves {@code /git/<projectId>/<name>} from an index of its own, and this is the
 * one writer of it: a repository's whole name set, with its project id, its project's slug and its
 * {@code names_revision}, sent as {@code PUT /git/<repoId>/names} through {@link
 * GitHostRepositories#putNames}.
 *
 * <p><b>Two ways in, one push.</b> {@link #onChanged} observes {@link RepositoryNamesChanged} {@code
 * during = AFTER_SUCCESS}, so a rename, an alias a wrapper reconcile registers, a self-name minted
 * lazily — every committed change — moves the index at once, on the committing thread, before that
 * thread's caller is answered; a rolled-back write pushes nothing. The reconciler in {@code service}
 * calls {@link #push} for every repository on an interval, which is what heals a push that failed
 * or never ran (a crash between commit and push), and whose first pass after deploy is the one-time
 * backfill of the whole index.
 *
 * <p><b>The revision is what makes that safe to do from anywhere.</b> It is read in the same fresh
 * transaction as the names, so a push never pairs a newer set with an older number; and the git
 * host keeps what it holds against an equal or older revision, so two pushes that overtake each
 * other, or a reconciler pass racing a rename, cannot put an old name set back.
 *
 * <p><b>Nothing here throws into its caller.</b> The observer runs inside the committing
 * transaction's completion, where an exception would reach a caller whose write has already
 * committed and read as a failure it is not; the reconciler wants a count, not a stop. Every fault
 * is a WARN naming the reconciler as the retry. The HTTP call is bounded by the port's own timeout
 * ({@code qits.projects.git.network-timeout-ms}), which is what a committing thread waits at worst.
 *
 * <p>The port is resolved through {@link Instance} although it is mandatory elsewhere: a sync that
 * finds no git host to talk to has nothing to do, which is not a reason to fail a rename.
 */
@ApplicationScoped
public class GitHostNameSync {

  private static final Logger LOG = Logger.getLogger(GitHostNameSync.class);

  /** The git host's own bound on a name ({@code GitHostRoutes}' names validation). */
  static final int MAX_NAME_LENGTH = 255;

  /** What one {@link #push} came to — the reconciler's counters. */
  public enum Result {
    /** The host took this name set. */
    APPLIED,
    /** The host already held this revision or a newer one. */
    ALREADY_CURRENT,
    /** The host stores no repository under this id; nothing was written there. */
    NOT_STORED,
    /** Nothing was sent: the repository is gone, has no name the host accepts, or there is no port. */
    SKIPPED,
    /** The read or the call failed; logged, and the next reconcile pass retries it. */
    FAILED
  }

  @Inject Instance<GitHostRepositories> gitHost;
  @Inject RepositoryRepository repositoryRepository;
  @Inject RepositoryNameRepository repositoryNameRepository;

  /**
   * The newest revision the host has acknowledged per repository, as far as this process knows.
   * Only the observer consults it: a transaction that changed the names twice (a rename forgets,
   * then inserts) fires twice, and the second would push the same set again. The reconciler ignores
   * it — the host is the authority on what it holds, and a lost index is exactly what it repairs.
   */
  private final Map<String, Long> acknowledged = new ConcurrentHashMap<>();

  /** The after-commit push: the transaction that changed the names has committed. */
  void onChanged(@Observes(during = TransactionPhase.AFTER_SUCCESS) RepositoryNamesChanged changed) {
    push(changed.repositoryId(), false);
  }

  /** Pushes {@code repositoryId}'s current name set unconditionally — the reconciler's call. */
  public Result push(String repositoryId) {
    return push(repositoryId, true);
  }

  /** One repository's id, project, slug, revision and names, read together. */
  private record Snapshot(
      String repositoryId, String projectId, String projectSlug, long revision, List<String> names) {}

  private Result push(String repositoryId, boolean always) {
    if (gitHost.isUnsatisfied()) {
      return Result.SKIPPED;
    }
    try {
      Optional<Snapshot> read =
          QuarkusTransaction.requiringNew().call(() -> snapshot(repositoryId));
      if (read.isEmpty()) {
        LOG.debugf("githost name push for %s skipped: the repository is gone", repositoryId);
        return Result.SKIPPED;
      }
      Snapshot snapshot = read.get();
      if (!always && acknowledged.getOrDefault(repositoryId, -1L) >= snapshot.revision()) {
        return Result.ALREADY_CURRENT;
      }
      List<String> names = addressable(repositoryId, snapshot.names());
      if (names.isEmpty()) {
        LOG.warnf(
            "githost name push for %s skipped: none of its names %s is one the git host accepts",
            repositoryId, snapshot.names());
        return Result.SKIPPED;
      }
      NamesOutcome outcome =
          gitHost
              .get()
              .putNames(
                  repositoryId,
                  snapshot.projectId(),
                  snapshot.projectSlug(),
                  names,
                  snapshot.revision());
      return switch (outcome) {
        case APPLIED -> {
          acknowledged.merge(repositoryId, snapshot.revision(), Math::max);
          yield Result.APPLIED;
        }
        case ALREADY_CURRENT -> {
          acknowledged.merge(repositoryId, snapshot.revision(), Math::max);
          yield Result.ALREADY_CURRENT;
        }
        case NOT_STORED -> Result.NOT_STORED;
      };
    } catch (RuntimeException e) {
      LOG.warnf(
          "githost name push for %s failed: %s; the reconciler retries", repositoryId, e.toString());
      return Result.FAILED;
    }
  }

  private Optional<Snapshot> snapshot(String repositoryId) {
    Repository repository = repositoryRepository.findById(repositoryId);
    if (repository == null || repository.project == null) {
      return Optional.empty();
    }
    return Optional.of(
        new Snapshot(
            repository.id,
            repository.project.id,
            repository.project.slug,
            repository.namesRevision,
            repositoryNameRepository.namesFor(repository)));
  }

  /**
   * The names the git host would accept, sorted (the host answers them sorted, and a stable body is
   * a stable pact). A name it would refuse — a {@code /}, whitespace, a {@code .git} suffix, blank
   * or over {@value #MAX_NAME_LENGTH} characters — is dropped with a WARN rather than failing the
   * whole set with a 400: the repository's other names still deserve to resolve.
   */
  private static List<String> addressable(String repositoryId, List<String> names) {
    return names.stream()
        .filter(
            name -> {
              boolean accepted = isAcceptedName(name);
              if (!accepted) {
                LOG.warnf(
                    "githost name push for %s leaves out '%s': the git host does not accept it as a"
                        + " name",
                    repositoryId, name);
              }
              return accepted;
            })
        .distinct()
        .sorted()
        .toList();
  }

  /** The git host's rule for a name in its index. */
  static boolean isAcceptedName(String name) {
    return name != null
        && !name.isBlank()
        && name.length() <= MAX_NAME_LENGTH
        && name.indexOf('/') < 0
        && name.chars().noneMatch(Character::isWhitespace)
        && !name.endsWith(".git");
  }
}

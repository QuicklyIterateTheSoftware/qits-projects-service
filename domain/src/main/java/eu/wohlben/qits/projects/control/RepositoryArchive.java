package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.dto.ReleaseRequestDto;
import eu.wohlben.qits.projects.dto.RepositoryArchiveDto;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.projects.persistence.RepositoryNameRepository;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * <b>Archiving a repository is a platform action that goes through the normal flow</b> (qits-1784):
 * the archived state is {@code repository.archived} in the repository's own {@code
 * .config/qits/repository.yml}, and the action writes it as a commit on a branch that the
 * repository's release request then releases — never a push to main. qits-maintenance reads the
 * released file: an archived repository gets no dependency bumps unless its {@code maintenance.yml}
 * asks for them.
 *
 * <p>The steps, each through a port this service already uses for releases:
 *
 * <ol>
 *   <li>read the file at main ({@link ReleaseGitHost#tree} then {@link ReleaseGitHost#file}, the
 *       read {@code ReleaseGates} makes; a file the tree does not list is absent, which is an
 *       answer) — and answer {@code unchanged} when main already says what was asked;
 *   <li>edit the text ({@link RepositoryArchivedEdit}), refusing with a 422 before anything is
 *       written when it cannot be edited safely;
 *   <li>create {@code archive/<name>} (or {@code unarchive/<name>}) at main, or bring an existing
 *       one up to date with main, through the merge door ({@link BackingBranchMerger}: an absent
 *       target is created by fast-forward, the way {@code PhaseAdvance} creates a wrapper branch);
 *   <li>re-read the file at the branch's tip and commit the edit there ({@link
 *       ReleaseGitHost#commit}, pinned to that tip) — nothing, when the branch already carries it;
 *   <li>ask for the branch's release ({@link ReleaseRequests#ask}), which creates or joins the
 *       repository's open request exactly as {@code POST …/release-requests} does.
 * </ol>
 *
 * <p>A port failure is a 502, a port with no address or no credential a 503 — the dispatch door's
 * split; a branch that conflicts with main a 409.
 */
@ApplicationScoped
public class RepositoryArchive {

  private static final Logger LOG = Logger.getLogger(RepositoryArchive.class);

  private static final String DEFAULT_MAIN = "main";

  @Inject Instance<ReleaseGitHost> gitHosts;

  @Inject Instance<BackingBranchMerger> mergers;

  @Inject ReleaseRequests releaseRequests;

  @Inject RepositoryRepository repositories;

  @Inject RepositoryNameRepository names;

  /** The repository as the action needs it, read in one short transaction. */
  private record Target(String repoId, String projectId, String name, String main) {}

  /**
   * Archive ({@code archive} true) or unarchive a repository.
   *
   * @param requester the caller's principal, who becomes the release request's participant
   */
  public RepositoryArchiveDto apply(String repoId, boolean archive, String requester) {
    Target target = target(repoId);
    if (!gitHosts.isResolvable() || !mergers.isResolvable()) {
      throw new DomainException(
          503, "No git host is configured, so the repository's configuration cannot be written.");
    }
    ReleaseGitHost host = gitHosts.get();
    String verb = archive ? "archive" : "unarchive";

    // 1. What main says now.
    String mainRev = "refs/heads/" + target.main();
    Optional<String> onMain = read(host, target.repoId(), mainRev);
    // 2. The edit, decided (and refused) before any ref moves.
    RepositoryArchivedEdit.Edit atMain =
        RepositoryArchivedEdit.apply(onMain.orElse(null), archive);
    if (!atMain.changed()) {
      return new RepositoryArchiveDto(
          RepositoryArchiveDto.UNCHANGED,
          archive,
          null,
          null,
          null,
          null,
          "main already "
              + (archive ? "says repository.archived: true" : "does not say repository.archived: true")
              + "; nothing was opened.");
    }
    if (target.name() == null) {
      throw new DomainException(
          409,
          "Repository "
              + target.repoId()
              + " has no registered name, so there is no "
              + verb
              + "/<name> branch to write the edit on.");
    }

    // 3. The branch, created at main or brought up to date with it.
    String branch = verb + "/" + target.name();
    String ref = "refs/heads/" + branch;
    BackingBranchMerger.Outcome outcome;
    try {
      outcome =
          mergers
              .get()
              .merge(
                  target.repoId(),
                  target.projectId(),
                  target.name(),
                  ref,
                  List.of(mainRev),
                  "Bring " + branch + " up to date with " + target.main());
    } catch (RuntimeException e) {
      // The port must not throw; a throw is a port bug and reads as "could not reach".
      outcome = BackingBranchMerger.Outcome.unreachable("merger error: " + e.getMessage());
    }
    if (!outcome.folded() || outcome.sha() == null) {
      throw branchFailure(branch, target.main(), outcome);
    }
    String tip = outcome.sha();

    // 4. The commit, onto whatever the branch holds now.
    Optional<String> onBranch = read(host, target.repoId(), tip);
    RepositoryArchivedEdit.Edit atTip =
        RepositoryArchivedEdit.apply(onBranch.orElse(null), archive);
    String commit = tip;
    if (atTip.changed()) {
      ReleaseGitHost.Answer<String> written;
      try {
        written =
            host.commit(
                target.repoId(),
                target.projectId(),
                target.name(),
                ref,
                tip,
                tip,
                "chore(platform): " + verb + " " + target.name(),
                Map.of(RepositoryArchivedEdit.PATH, atTip.content()),
                Map.of());
      } catch (RuntimeException e) {
        written = ReleaseGitHost.Answer.failedRetryable("git host error: " + e.getMessage());
      }
      if (!written.ok()) {
        throw hostFailure("commit the edit onto " + branch, written);
      }
      commit = written.value();
    } else {
      LOG.infof(
          "%s of %s: %s already carries the edit at %s; committing nothing",
          verb, target.repoId(), branch, tip);
    }

    // 5. The release request, created or joined like any other ask.
    ReleaseRequestDto request =
        releaseRequests.ask(target.repoId(), branch, requester, null).request();
    LOG.infof(
        "%s of %s (%s) requested: %s at %s, release request %s",
        verb, target.repoId(), target.name(), branch, commit, request.qualifiedId());
    return new RepositoryArchiveDto(
        RepositoryArchiveDto.REQUESTED,
        archive,
        branch,
        commit,
        request.id(),
        request.qualifiedId(),
        branch
            + " carries the edit and is a source of "
            + request.qualifiedId()
            + "; repository.archived changes when that request releases.");
  }

  private Target target(String repoId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Repository repository =
                  repositories
                      .findByIdOptional(repoId)
                      .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));
              String main =
                  repository.mainBranch == null || repository.mainBranch.isBlank()
                      ? DEFAULT_MAIN
                      : repository.mainBranch;
              return new Target(
                  repository.id,
                  repository.project == null ? null : repository.project.id,
                  names.nameFor(repository).orElse(null),
                  main);
            });
  }

  /**
   * The file at {@code rev}: empty when the tree does not list it, a 502/503 when the host could not
   * be asked. The tree is read first because the file read answers "failed" both for an absent path
   * and for a host that could not be asked.
   */
  private static Optional<String> read(ReleaseGitHost host, String repoId, String rev) {
    ReleaseGitHost.Answer<List<String>> tree;
    try {
      tree = host.tree(repoId, rev);
    } catch (RuntimeException e) {
      tree = ReleaseGitHost.Answer.failedRetryable("git host error: " + e.getMessage());
    }
    if (!tree.ok()) {
      throw hostFailure("read the tree at " + rev, tree);
    }
    if (!tree.value().contains(RepositoryArchivedEdit.PATH)) {
      return Optional.empty();
    }
    ReleaseGitHost.Answer<String> file;
    try {
      file = host.file(repoId, rev, RepositoryArchivedEdit.PATH);
    } catch (RuntimeException e) {
      file = ReleaseGitHost.Answer.failedRetryable("git host error: " + e.getMessage());
    }
    if (!file.ok()) {
      throw hostFailure("read " + RepositoryArchivedEdit.PATH + " at " + rev, file);
    }
    return Optional.of(file.value());
  }

  private static DomainException hostFailure(String what, ReleaseGitHost.Answer<?> answer) {
    String detail = answer.detail();
    if (detail != null && detail.contains("ref-moved")) {
      return new DomainException(
          409, "Could not " + what + ": the branch moved meanwhile; ask again. (" + detail + ")");
    }
    return new DomainException(statusOf(detail), "Could not " + what + ": " + detail);
  }

  private static DomainException branchFailure(
      String branch, String main, BackingBranchMerger.Outcome outcome) {
    return switch (outcome.result()) {
      case CONFLICT ->
          new DomainException(
              409,
              branch
                  + " exists and conflicts with "
                  + main
                  + " on "
                  + outcome.conflicts().stream()
                      .map(BackingBranchMerger.Conflict::path)
                      .distinct()
                      .toList()
                  + "; resolve or delete the branch and ask again.");
      case MISSING_SOURCE ->
          new DomainException(
              502, "Could not create " + branch + ": the git host has no " + outcome.detail());
      default ->
          new DomainException(
              statusOf(outcome.detail()),
              "Could not create or update " + branch + " at " + main + ": " + outcome.detail());
    };
  }

  /** 503 for a hop with no address or no credential, 502 for the exchange. */
  private static int statusOf(String detail) {
    if (detail != null
        && (detail.contains("is not configured") || detail.contains("No machine bearer"))) {
      return 503;
    }
    return 502;
  }
}

package eu.wohlben.qits.projects.entitieshost;

import eu.wohlben.qits.projects.control.CommitService;
import eu.wohlben.qits.projects.control.CommitService.SubjectLine;
import eu.wohlben.qits.projects.control.CommitService.SubjectLog;
import eu.wohlben.qits.projects.entitieshost.CommitSubjectEntities.QualifiedId;
import eu.wohlben.qits.projects.error.BadRequestException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>How a branch's recent commits fit the commit-message variants</b> (qits-302, reworked by
 * qits-706) — what qits-githost's receive hook, which refuses on every repository any new commit
 * whose first line fits none of {@link CommitMessageVariants}, would have made of the history so
 * far.
 *
 * <p>Each commit lands in exactly one variant — {@link CommitMessageVariants#classify}'s order,
 * MERGE, BUMP, RELEASE, PLATFORM, SUBJECT, first match wins — or in none, which is NON-COMPLYING.
 * Who wrote a commit decides nothing: there is no machine author and no exemption, exactly as the
 * hook has none. <b>Commits from before the rule are ordinarily non-complying</b>, and that is the
 * expected reading of an old history, not a fault to clear: the hook checks new commits only.
 *
 * <p>The id grammar is {@link CommitSubjectEntities#references}' and nobody else's; a SUBJECT
 * commit's ids are read there. "No subject" stays an answer and never a complaint, per that class:
 * nothing here logs.
 */
@ApplicationScoped
public class CommitSubjectCompliance {

  /** The default and the ceiling on how many commits one read classifies. */
  public static final int DEFAULT_LIMIT = 100;

  public static final int MAX_LIMIT = 1000;

  @Inject CommitService commits;

  /**
   * One commit's variant, or null when it fits none (NON-COMPLYING). {@code ids} is every id a
   * SUBJECT commit's scope names — one or more — and empty for every other answer.
   */
  public record Verdict(CommitMessageVariants variant, List<QualifiedId> ids) {

    public boolean complies() {
      return variant != null;
    }
  }

  /** The rule, as a pure function of one commit. Static so a test can walk it without a mirror. */
  public static Verdict classify(SubjectLine commit) {
    CommitMessageVariants variant =
        CommitMessageVariants.classify(commit.subject(), commit.parents()).orElse(null);
    return new Verdict(
        variant,
        variant == CommitMessageVariants.SUBJECT
            ? CommitSubjectEntities.references(commit.subject())
            : List.of());
  }

  /**
   * Measures the newest {@code limit} commits on {@code branch}.
   *
   * @param branch null or blank for the repository's main branch
   * @param limit null for {@value #DEFAULT_LIMIT}; 1 to {@value #MAX_LIMIT}, otherwise a 400
   */
  public CommitSubjectsDto measure(String repoId, String branch, Integer limit) {
    int n = limit == null ? DEFAULT_LIMIT : limit;
    if (n < 1 || n > MAX_LIMIT) {
      throw new BadRequestException(
          "limit must be between 1 and " + MAX_LIMIT + ", was " + limit);
    }
    SubjectLog log = commits.readSubjectLog(repoId, branch, n);

    Map<CommitMessageVariants, Integer> counts = new EnumMap<>(CommitMessageVariants.class);
    List<CommitSubjectsDto.NonComplyingCommit> nonComplying = new ArrayList<>();
    Set<String> ids = new LinkedHashSet<>();
    for (SubjectLine commit : log.commits()) {
      Verdict verdict = classify(commit);
      if (!verdict.complies()) {
        nonComplying.add(
            new CommitSubjectsDto.NonComplyingCommit(
                commit.hash(),
                commit.shortHash(),
                commit.subject(),
                commit.authorName(),
                commit.authorEmail(),
                commit.date()));
        continue;
      }
      counts.merge(verdict.variant(), 1, Integer::sum);
      for (QualifiedId id : verdict.ids()) {
        ids.add(id.rendered());
      }
    }
    return new CommitSubjectsDto(
        repoId,
        log.branch(),
        n,
        new CommitSubjectsDto.CommitSubjectCounts(
            log.commits().size(),
            counts.getOrDefault(CommitMessageVariants.MERGE, 0),
            counts.getOrDefault(CommitMessageVariants.BUMP, 0),
            counts.getOrDefault(CommitMessageVariants.RELEASE, 0),
            counts.getOrDefault(CommitMessageVariants.PLATFORM, 0),
            counts.getOrDefault(CommitMessageVariants.SUBJECT, 0),
            nonComplying.size()),
        nonComplying,
        List.copyOf(ids));
  }
}

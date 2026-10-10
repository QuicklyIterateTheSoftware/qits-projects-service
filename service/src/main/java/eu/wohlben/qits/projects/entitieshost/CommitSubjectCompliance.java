package eu.wohlben.qits.projects.entitieshost;

import eu.wohlben.qits.projects.control.CommitService;
import eu.wohlben.qits.projects.control.CommitService.SubjectLine;
import eu.wohlben.qits.projects.control.CommitService.SubjectLog;
import eu.wohlben.qits.projects.entitieshost.CommitSubjectEntities.QualifiedId;
import eu.wohlben.qits.projects.error.BadRequestException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * <b>How many of a branch's recent commits name their subject</b> (qits-302, epic qits-297) — the
 * read a person takes before turning on qits-githost's commit-subject receive guard for a
 * repository, and the one that shows what the guard would have made of the history so far.
 *
 * <p>Each commit lands in exactly one class, decided in this order:
 *
 * <ol>
 *   <li><b>EXEMPT, {@link ExemptReason#MERGE MERGE}</b> — more than one parent. A merge message is
 *       machine-written; the guard does not check it either, only the commits it brings in.
 *   <li><b>EXEMPT, {@link ExemptReason#MACHINE MACHINE}</b> — authored by one of {@code
 *       qits.projects.commit-subjects.machine-authors}: platform automation, which the guard exempts
 *       by <em>pusher</em> identity ({@code qits:system}, {@code qits:ci-run}). A commit carries no
 *       pusher, so the author's address stands in for it here.
 *   <li><b>COMPLYING</b> — {@link CommitSubjectEntities#reference} finds a qualified id. Syntax only,
 *       as the guard's own check is: whether the id names an entity is not this read's question.
 *   <li><b>NON_COMPLYING</b> — everything else.
 * </ol>
 *
 * <p>The grammar is {@link CommitSubjectEntities#reference}'s and nobody else's; this class only
 * asks it. "No subject" stays an answer and never a complaint, per that class: nothing here logs.
 *
 * <p><b>{@code qits@local} is deliberately NOT a machine author.</b> It is {@code GitIdentity}'s
 * default, which is the merges this service writes (exempt anyway, as merges) — but it is also the
 * identity every workspace and agent container commits as, so a coding agent's own work is
 * authored by it. Listing it would exempt exactly the commits the convention is for.
 */
@ApplicationScoped
public class CommitSubjectCompliance {

  /** The default and the ceiling on how many commits one read classifies. */
  public static final int DEFAULT_LIMIT = 100;

  public static final int MAX_LIMIT = 1000;

  @Inject CommitService commits;

  /**
   * The authors whose commits are platform automation: qits-maintenance's bumps ({@code
   * maintenance@qits.local}, and {@code release-train@qits.local} before it), and the release
   * flow's fold and {@code release(<v>)} commits, which qits-projects writes through qits-githost's
   * commit primitives ({@code qits-projects@qits.internal}). Compared case-insensitively.
   */
  @ConfigProperty(
      name = "qits.projects.commit-subjects.machine-authors",
      defaultValue =
          "maintenance@qits.local,qits-projects@qits.internal,release-train@qits.local")
  List<String> machineAuthors;

  public enum Classification {
    COMPLYING,
    NON_COMPLYING,
    EXEMPT
  }

  public enum ExemptReason {
    MERGE,
    MACHINE
  }

  /**
   * One commit's class, and the reason when it is EXEMPT (null otherwise). {@code ids} is every
   * id the subject's scope named (empty for EXEMPT and for NON_COMPLYING; one or more for
   * COMPLYING — a scope naming several ids is one COMPLYING commit with several).
   */
  public record Verdict(Classification classification, ExemptReason reason, List<QualifiedId> ids) {}

  /**
   * The rule, as a pure function of one commit — merge, then machine, then the grammar. Static so a
   * test can walk it without a mirror.
   */
  public static Verdict classify(SubjectLine commit, Set<String> machineAuthors) {
    if (commit.parents() > 1) {
      return new Verdict(Classification.EXEMPT, ExemptReason.MERGE, List.of());
    }
    String email = commit.authorEmail() == null ? "" : commit.authorEmail().trim();
    if (machineAuthors.contains(email.toLowerCase(Locale.ROOT))) {
      return new Verdict(Classification.EXEMPT, ExemptReason.MACHINE, List.of());
    }
    List<QualifiedId> ids = CommitSubjectEntities.references(commit.subject());
    return !ids.isEmpty()
        ? new Verdict(Classification.COMPLYING, null, ids)
        : new Verdict(Classification.NON_COMPLYING, null, List.of());
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
    Set<String> machine = machineAuthorSet();

    int complying = 0;
    int merges = 0;
    int machines = 0;
    List<CommitSubjectsDto.NonComplyingCommit> nonComplying = new ArrayList<>();
    Set<String> ids = new LinkedHashSet<>();
    for (SubjectLine commit : log.commits()) {
      Verdict verdict = classify(commit, machine);
      switch (verdict.classification()) {
        case EXEMPT -> {
          if (verdict.reason() == ExemptReason.MERGE) {
            merges++;
          } else {
            machines++;
          }
        }
        case COMPLYING -> {
          complying++;
          for (QualifiedId id : verdict.ids()) {
            ids.add(id.rendered());
          }
        }
        case NON_COMPLYING ->
            nonComplying.add(
                new CommitSubjectsDto.NonComplyingCommit(
                    commit.hash(),
                    commit.shortHash(),
                    commit.subject(),
                    commit.authorName(),
                    commit.authorEmail(),
                    commit.date()));
      }
    }
    return new CommitSubjectsDto(
        repoId,
        log.branch(),
        n,
        log.guardConfig() != null && enforceTrue(log.guardConfig()),
        new CommitSubjectsDto.CommitSubjectCounts(
            log.commits().size(),
            complying,
            nonComplying.size(),
            merges + machines,
            merges,
            machines),
        nonComplying,
        List.copyOf(ids));
  }

  private Set<String> machineAuthorSet() {
    return (machineAuthors == null ? List.<String>of() : machineAuthors)
        .stream()
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(s -> s.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Whether the guard file holds a top-level {@code enforce: true} — <b>the reading qits-githost's
   * {@code CommitSubjectHook.enforceTrue} takes, line for line</b>, so this read and the guard agree
   * on whether a repository is opted in: top-level keys only, a {@code " #"} comment stripped, the
   * last occurrence wins, and any value other than exactly {@code true} is off. Not a YAML library,
   * because one would accept {@code yes} and {@code True} where the guard does not.
   */
  static boolean enforceTrue(String yaml) {
    Boolean answer = null;
    for (String raw : yaml.split("\\R")) {
      if (raw.isEmpty() || Character.isWhitespace(raw.charAt(0))) {
        continue;
      }
      String line = raw;
      int comment = line.indexOf(" #");
      if (comment >= 0) {
        line = line.substring(0, comment);
      }
      line = line.strip();
      int colon = line.indexOf(':');
      if (colon < 0 || !line.substring(0, colon).strip().equals("enforce")) {
        continue;
      }
      answer = line.substring(colon + 1).strip().equals("true");
    }
    return Boolean.TRUE.equals(answer);
  }
}

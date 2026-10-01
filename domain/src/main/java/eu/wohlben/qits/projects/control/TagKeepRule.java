package eu.wohlben.qits.projects.control;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which released tags may go — the whole of the tag collection's judgement, as functions of facts
 * somebody else read.
 *
 * <p>Pure on purpose: nothing here touches git, the database or the network, so every rule is
 * provable in a unit test with no Quarkus and no repository. {@link TagCollector} reads the facts,
 * calls this, and does the deleting.
 *
 * <h2>Only calver tags, and they are numbers</h2>
 *
 * <p>A tag is judged only if its name is a release version, {@code YYYY.MDD.HHMMSS} — {@link
 * #CALVER}. Anything else a repository carries ({@code v1.0}, a person's marker) is never touched.
 * The segments are <b>not zero-padded</b>, so the order is numeric per segment and never the
 * string's: {@code 2026.930.9 < 2026.930.10 < 2026.1001.5}, which string order gets wrong twice.
 *
 * <h2>The five reasons to keep a tag</h2>
 *
 * <ol>
 *   <li>{@link Reason#NEWEST} — among the repository's newest {@code keepNewest} calver tags on the
 *       git host. Counted on the host only: a tag only the twin holds is older than everything the
 *       host has, and can never be among the newest.
 *   <li>{@link Reason#PINNED_VERSION} — its name is a version some pin source names. Matched across
 *       every repository: there is no reliable map from an application to the repository it was
 *       built from, and a calver is unique on the platform anyway.
 *   <li>{@link Reason#GITLINK} — its commit is a gitlink in a tree that is kept: some repository's
 *       {@code main}, or a tag kept for any reason. Transitive, so a kept wrapper release keeps the
 *       service release it pins, and that keeps the frontend release the service pins in turn.
 *   <li>{@link Reason#IN_FLIGHT} — released and not on {@code main} yet, or its request not
 *       finalized: a release still being published, deployed or merged.
 *   <li>{@link Reason#YOUNG} — created within {@code minAge}. Whatever the pin sources say, a tag cut
 *       a minute ago may be about to be named by one of them.
 * </ol>
 *
 * Every other calver tag goes.
 */
public final class TagKeepRule {

  /** A release version, and the only kind of tag ever judged. */
  public static final Pattern CALVER = Pattern.compile("^\\d{4}\\.\\d{3,4}\\.\\d{1,6}$");

  /** Ascending release order, numeric per segment. Only meaningful between two calvers. */
  public static final Comparator<String> RELEASE_ORDER =
      Comparator.<String>comparingLong(name -> segment(name, 0))
          .thenComparingLong(name -> segment(name, 1))
          .thenComparingLong(name -> segment(name, 2));

  private TagKeepRule() {}

  /** Why a tag is kept, in the order a kept tag is counted under. */
  public enum Reason {
    NEWEST,
    PINNED_VERSION,
    GITLINK,
    IN_FLIGHT,
    YOUNG
  }

  /**
   * One tag on the git host.
   *
   * @param name the tag's name
   * @param objectSha what the ref names — the tag object for an annotated tag
   * @param commitSha the commit it peels to
   * @param createdAt the tagger date of an annotated tag, the commit date of a lightweight one
   */
  public record HostTag(String name, String objectSha, String commitSha, Instant createdAt) {}

  /**
   * One tag on a backup twin, as {@code ls-remote} answered it.
   *
   * @param name the tag's name
   * @param objectSha what the ref names, which is what a lease on its deletion must say
   * @param commitSha the commit it peels to; the same as {@code objectSha} for a lightweight tag
   */
  public record TwinTag(String name, String objectSha, String commitSha) {}

  /**
   * Everything the rule reads besides the tags themselves.
   *
   * @param pinnedVersions every version a pin source names
   * @param inFlight every version still in flight
   * @param gitlinks every commit some kept tree mounts as a gitlink
   * @param now the instant age is measured from
   * @param minAge how young a tag is kept regardless
   * @param keepNewest how many of a repository's newest calver tags are kept regardless
   */
  public record Facts(
      Set<String> pinnedVersions,
      Set<String> inFlight,
      Set<String> gitlinks,
      Instant now,
      Duration minAge,
      int keepNewest) {

    Facts withGitlinks(Set<String> next) {
      return new Facts(pinnedVersions, inFlight, Set.copyOf(next), now, minAge, keepNewest);
    }
  }

  /**
   * One repository's host-side verdict.
   *
   * @param kept every kept calver tag, with the first reason that kept it
   * @param deleted every calver tag that may go, oldest first
   */
  public record Verdicts(Map<String, Reason> kept, List<String> deleted) {}

  /** Whether {@code name} is a release version. */
  public static boolean isCalVer(String name) {
    return name != null && CALVER.matcher(name).matches();
  }

  /**
   * Every string anywhere in {@code pins} that is a release version — the pin set, read without
   * modelling any source's schema. A version in a field nobody meant as a pin is kept too, which is
   * the safe direction: the cost of over-keeping is one tag, the cost of a missed pin is a release
   * nobody can rebuild.
   */
  public static Set<String> pinnedVersions(JsonNode pins) {
    Set<String> versions = new HashSet<>();
    collect(pins, versions);
    return versions;
  }

  private static void collect(JsonNode node, Set<String> into) {
    if (node == null) {
      return;
    }
    if (node.isTextual()) {
      String text = node.textValue().trim();
      if (isCalVer(text)) {
        into.add(text);
      }
      return;
    }
    node.forEach(child -> collect(child, into));
  }

  /** Judges one repository's host tags; non-calver tags are neither kept nor deleted. */
  public static Verdicts judgeHost(Collection<HostTag> tags, Facts facts) {
    List<HostTag> calver =
        tags.stream()
            .filter(tag -> isCalVer(tag.name()))
            .sorted(Comparator.comparing(HostTag::name, RELEASE_ORDER).reversed())
            .toList();
    Map<String, Reason> kept = new LinkedHashMap<>();
    List<String> deleted = new ArrayList<>();
    for (int i = 0; i < calver.size(); i++) {
      HostTag tag = calver.get(i);
      Optional<Reason> reason = keepReason(tag, i < facts.keepNewest(), facts);
      if (reason.isPresent()) {
        kept.put(tag.name(), reason.get());
      } else {
        deleted.add(tag.name());
      }
    }
    deleted.sort(RELEASE_ORDER);
    return new Verdicts(kept, deleted);
  }

  /** Why one host tag is kept, or empty when it may go. */
  static Optional<Reason> keepReason(HostTag tag, boolean amongNewest, Facts facts) {
    if (amongNewest) {
      return Optional.of(Reason.NEWEST);
    }
    if (facts.pinnedVersions().contains(tag.name())) {
      return Optional.of(Reason.PINNED_VERSION);
    }
    if (tag.commitSha() != null && facts.gitlinks().contains(tag.commitSha())) {
      return Optional.of(Reason.GITLINK);
    }
    if (facts.inFlight().contains(tag.name())) {
      return Optional.of(Reason.IN_FLIGHT);
    }
    if (tag.createdAt() == null || tag.createdAt().isAfter(facts.now().minus(facts.minAge()))) {
      // An unreadable date is young: not knowing a tag's age is no licence to delete it.
      return Optional.of(Reason.YOUNG);
    }
    return Optional.empty();
  }

  /**
   * One repository's twin-side verdict.
   *
   * @param kept the twin-only calver tags kept, with their reason — a twin tag the host also holds
   *     is the host's verdict and is not counted twice
   * @param deleted every twin calver tag that may go, name to the object sha the lease must name
   */
  public record TwinVerdicts(Map<String, Reason> kept, Map<String, String> deleted) {}

  /**
   * Judges a twin's tags against what the host decided.
   *
   * <p>A tag the host also holds follows the host: it goes from the twin only once it has gone
   * from the host ({@code goneFromHost}, or the would-be set on a dry run), because a tag the host
   * still holds is pushed straight back by the next backup. A tag only the twin holds — an old
   * deletion whose twin half failed, or a release from before the platform — can be kept by name
   * ({@link Reason#PINNED_VERSION}, {@link Reason#IN_FLIGHT}) or by commit ({@link
   * Reason#GITLINK}); never by being newest or young, which are facts about the host's tags.
   */
  public static TwinVerdicts judgeTwin(
      Collection<TwinTag> twinTags, Set<String> hostTags, Set<String> goneFromHost, Facts facts) {
    Map<String, Reason> kept = new LinkedHashMap<>();
    Map<String, String> deleted = new LinkedHashMap<>();
    twinTags.stream()
        .filter(tag -> isCalVer(tag.name()))
        .sorted(Comparator.comparing(TwinTag::name, RELEASE_ORDER))
        .forEach(
            tag -> {
              if (hostTags.contains(tag.name())) {
                if (goneFromHost.contains(tag.name())) {
                  deleted.put(tag.name(), tag.objectSha());
                }
                return;
              }
              Optional<Reason> reason = twinOnlyKeepReason(tag, facts);
              if (reason.isPresent()) {
                kept.put(tag.name(), reason.get());
              } else {
                deleted.put(tag.name(), tag.objectSha());
              }
            });
    return new TwinVerdicts(kept, deleted);
  }

  private static Optional<Reason> twinOnlyKeepReason(TwinTag tag, Facts facts) {
    if (facts.pinnedVersions().contains(tag.name())) {
      return Optional.of(Reason.PINNED_VERSION);
    }
    if (facts.gitlinks().contains(tag.commitSha()) || facts.gitlinks().contains(tag.objectSha())) {
      return Optional.of(Reason.GITLINK);
    }
    if (facts.inFlight().contains(tag.name())) {
      return Optional.of(Reason.IN_FLIGHT);
    }
    return Optional.empty();
  }

  // -----------------------------------------------------------------------------------------
  // the plan across every repository
  // -----------------------------------------------------------------------------------------

  /**
   * One repository as it was read off its refreshed mirror.
   *
   * @param repoId the row id
   * @param name the repository's name, for the report
   * @param wrapper whether it is a project's wrapper — which keeps the most elsewhere; a failure
   *     to read any repository stops the sweep all the same
   * @param tags every tag the host holds, calver or not
   * @param mainGitlinks every gitlink in {@code main}'s tree
   */
  public record RepositoryRead(
      String repoId, String name, boolean wrapper, List<HostTag> tags, Set<String> mainGitlinks) {}

  /** Reads the gitlinks of one commit of one repository; throws when it cannot. */
  @FunctionalInterface
  public interface GitlinkReader {
    Set<String> gitlinksOf(String repoId, String commitSha) throws Exception;
  }

  /**
   * The decision across every readable repository.
   *
   * @param sweep false when nothing may be deleted anywhere — some repository's gitlinks were
   *     unreadable
   * @param verdicts per repository id; empty when {@code sweep} is false
   * @param facts the facts the verdicts were reached under, gitlinks complete
   * @param errors what was skipped, and why
   */
  public record Plan(
      boolean sweep, Map<String, Verdicts> verdicts, Facts facts, List<String> errors) {}

  /**
   * Judges every repository, growing the gitlink set until it stops growing.
   *
   * <p>The gitlinks of a kept tag can keep a tag in another repository, whose gitlinks can keep a
   * third; and a tag kept only by a gitlink keeps its own gitlinks too. So the verdicts are
   * recomputed until a pass adds no gitlink — which terminates, because the set only grows and is
   * bounded by the tags there are.
   *
   * <p><b>Fails closed, globally.</b> When the gitlinks of any kept tag cannot be read, nothing
   * anywhere is swept and the repository is named in {@link Plan#errors}. Gitlink keeps are global:
   * the unread tree may be the only thing keeping a release in some other repository, and skipping
   * just the one repository would delete that release.
   */
  public static Plan plan(List<RepositoryRead> repositories, Facts base, GitlinkReader reader) {
    Set<String> gitlinks = new HashSet<>(base.gitlinks());
    repositories.forEach(repo -> gitlinks.addAll(repo.mainGitlinks()));
    Map<String, Set<String>> read = new HashMap<>();
    while (true) {
      Facts facts = base.withGitlinks(gitlinks);
      Set<String> found = new HashSet<>();
      for (RepositoryRead repo : repositories) {
        Verdicts verdicts = judgeHost(repo.tags(), facts);
        for (HostTag tag : repo.tags()) {
          if (!verdicts.kept().containsKey(tag.name()) || tag.commitSha() == null) {
            continue;
          }
          String key = repo.repoId() + ' ' + tag.commitSha();
          Set<String> links = read.get(key);
          if (links == null) {
            try {
              links = reader.gitlinksOf(repo.repoId(), tag.commitSha());
            } catch (Exception e) {
              return new Plan(
                  false,
                  Map.of(),
                  base.withGitlinks(gitlinks),
                  List.of(unreadableGitlinks(repo, tag.name(), e)));
            }
            read.put(key, links);
          }
          found.addAll(links);
        }
      }
      if (gitlinks.containsAll(found)) {
        break;
      }
      gitlinks.addAll(found);
    }
    Facts facts = base.withGitlinks(gitlinks);
    Map<String, Verdicts> verdicts = new LinkedHashMap<>();
    for (RepositoryRead repo : repositories) {
      verdicts.put(repo.repoId(), judgeHost(repo.tags(), facts));
    }
    return new Plan(true, verdicts, facts, List.of());
  }

  private static String unreadableGitlinks(RepositoryRead repo, String tag, Exception e) {
    return repo.name()
        + ": could not read the gitlinks of "
        + tag
        + ", so nothing was swept anywhere — its gitlinks may be what keeps a release elsewhere: "
        + oneLine(e.getMessage());
  }

  /**
   * A git failure on one line, capped: the report is a JSON list read by a person, and git's
   * sentence is often the last line of several rather than the first.
   */
  static String oneLine(String message) {
    if (message == null || message.isBlank()) {
      return "no detail";
    }
    String flat = message.strip().replaceAll("\\s*\\n\\s*", " / ");
    return flat.length() <= 500 ? flat : flat.substring(0, 500) + "…";
  }

  private static long segment(String name, int index) {
    String[] parts = name.split("\\.");
    if (index >= parts.length) {
      return -1;
    }
    try {
      return Long.parseLong(parts[index]);
    } catch (NumberFormatException e) {
      return -1;
    }
  }
}

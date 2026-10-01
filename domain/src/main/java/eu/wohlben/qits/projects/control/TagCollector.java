package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.control.TagKeepRule.HostTag;
import eu.wohlben.qits.projects.control.TagKeepRule.Plan;
import eu.wohlben.qits.projects.control.TagKeepRule.Reason;
import eu.wohlben.qits.projects.control.TagKeepRule.RepositoryRead;
import eu.wohlben.qits.projects.control.TagKeepRule.TwinTag;
import eu.wohlben.qits.projects.control.TagKeepRule.TwinVerdicts;
import eu.wohlben.qits.projects.control.TagKeepRule.Verdicts;
import eu.wohlben.qits.projects.dto.TagCollectionReportDto;
import eu.wohlben.qits.projects.dto.TagCollectionReportDto.DeletedTag;
import eu.wohlben.qits.projects.dto.TagCollectionReportDto.KeptTags;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.gitmirror.PushOutcome;
import eu.wohlben.qits.projects.gitmirror.PushSpec;
import eu.wohlben.qits.projects.gitmirror.RepoMirror;
import eu.wohlben.qits.projects.persistence.ReleaseRequestRepository;
import eu.wohlben.qits.projects.persistence.ReleasedTagPendingMergeRepository;
import eu.wohlben.qits.projects.persistence.RepositoryNameRepository;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Decommissions old release tags, on the git host and on every backup twin — the GC's {@code
 * tags.sweep}, behind {@code POST /projects/api/gc/tags}.
 *
 * <p>The judgement is {@link TagKeepRule}'s and is not restated here; this class reads the facts
 * that rule needs, and does the deleting. In this order, and the order is the design:
 *
 * <ol>
 *   <li><b>The database, in one short transaction</b> — every catalogued repository and every
 *       version still in flight. Nothing below holds a transaction: a run is dozens of mirror
 *       fetches and as many pushes to a forge, and a connection held across that is a pool slot
 *       held for minutes.
 *   <li><b>Every mirror, refreshed and read</b> — its tags with their dates, and the gitlinks of
 *       its {@code main}. Gitlink keeps are global, so every repository is read before any one is
 *       judged.
 *   <li><b>The plan</b>, across the estate at once ({@link TagKeepRule#plan}).
 *   <li><b>The deletions, one repository at a time, under its backup lock</b> — host first, twin
 *       second. A backup pushes {@code refs/tags/*} out of the mirror, so one that overlapped would
 *       put a just-deleted tag straight back on the twin.
 * </ol>
 *
 * <h2>The host is a push; the twin is a reconcile</h2>
 *
 * <p>On the host a tag goes the way a branch does, through receive-pack ({@link
 * PushSpec.Ref#deleteTag}), so the git host's hooks see it and announce {@code SCMDeleteTag}. The
 * backup itself never pushes a deletion and must not start to — a forge twin is a backup, and a
 * backup that mirrored every deletion would mirror a mistaken one too. So the twin is reconciled
 * here instead: its tags are listed ({@code ls-remote}), judged with the same keep set, and the
 * rejected ones deleted with a lease naming the object the listing saw — a tag somebody moved on the
 * forge in between is refused rather than lost. A tag goes from the twin only once it is gone from
 * the host (the next backup would push it straight back otherwise); a tag only the twin holds —
 * a release from before the platform, or a deletion whose twin half failed last time — is judged
 * by {@link TagKeepRule#judgeTwin}.
 *
 * <h2>Failure closes, per repository</h2>
 *
 * <p>A repository whose mirror cannot be refreshed, or whose gitlinks cannot be read, is skipped
 * and named in {@code errors}. A <b>wrapper</b> that cannot be read stops the whole sweep: its
 * gitlinks are what keep the releases a project is pinned at, in every other repository. A twin that
 * cannot be listed or pushed to is an error line and never stops the next repository.
 */
@ApplicationScoped
public class TagCollector {

  private static final Logger LOG = Logger.getLogger(TagCollector.class);

  /** How many refspecs one push carries — an argv bound, not a protocol one. */
  static final int PUSH_BATCH = 100;

  @Inject RepositoryRepository repositoryRepository;

  @Inject RepositoryNameRepository repositoryNameRepository;

  @Inject ReleasedTagPendingMergeRepository releasedTags;

  @Inject ReleaseRequestRepository releaseRequests;

  @Inject GitMirrorRegistry gitMirrors;

  @Inject GitExecutor git;

  @Inject GitRemoteAuth remoteAuth;

  @Inject BackupPushService backups;

  /** How many of a repository's newest calver tags on the host are always kept. */
  @ConfigProperty(name = "qits.projects.gc.tags.keep-newest", defaultValue = "5")
  int keepNewest;

  /** How young a tag is kept regardless of anything else. */
  @ConfigProperty(name = "qits.projects.gc.tags.min-age", defaultValue = "PT24H")
  Duration minAge;

  /** One catalogued repository, as the database knows it. */
  record CatalogueRow(String repoId, String name, String twinUrl, String mainBranch, boolean wrapper) {}

  /**
   * Judge every catalogued repository's calver tags and delete the rejected ones on the host and
   * on the twin — or, on a dry run, only say which they are.
   *
   * @param pinnedVersions every version some pin source names; the caller has already refused a
   *     pin document with a member missing
   */
  @ActivateRequestContext
  public TagCollectionReportDto collect(Set<String> pinnedVersions, boolean dryRun) {
    List<CatalogueRow> catalogue = new ArrayList<>();
    Set<String> inFlight = new HashSet<>();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              for (Repository repo : repositoryRepository.listAll()) {
                catalogue.add(
                    new CatalogueRow(
                        repo.id,
                        repositoryNameRepository.nameFor(repo).orElse(repo.id),
                        repo.url,
                        repo.mainBranch,
                        repo.archetype == RepositoryArchetype.PROJECT));
              }
              inFlight.addAll(inFlightVersions());
            });
    return collect(catalogue, pinnedVersions, inFlight, Instant.now(), dryRun);
  }

  /**
   * Every version still on its way to {@code main}: a released tag not merged yet, and a request
   * that released and has not finalized. Two sources for one question because each can know it
   * without the other — a tag recorded by another path has no request, and a request's row is
   * written before its tag's.
   */
  private Set<String> inFlightVersions() {
    Set<String> versions = new HashSet<>();
    releasedTags.list("mergedAt is null").stream()
        .map((ReleasedTagPendingMerge row) -> row.tagName)
        .forEach(versions::add);
    releaseRequests.list("state = ?1 and version is not null", ReleaseRequest.State.RELEASED).stream()
        .map((ReleaseRequest request) -> request.version)
        .forEach(versions::add);
    return versions;
  }

  /** The whole run over a given catalogue — the suite's seam, since the real catalogue is shared. */
  TagCollectionReportDto collect(
      List<CatalogueRow> catalogue,
      Set<String> pinnedVersions,
      Set<String> inFlight,
      Instant now,
      boolean dryRun) {
    List<String> errors = new ArrayList<>();
    List<RepositoryRead> reads = new ArrayList<>();
    Map<String, CatalogueRow> byId = new LinkedHashMap<>();
    boolean wrapperUnreadable = false;
    for (CatalogueRow row : catalogue) {
      byId.put(row.repoId(), row);
      try {
        reads.add(read(row));
      } catch (RuntimeException e) {
        errors.add(
            row.name()
                + ": could not read its tags or gitlinks, so none of them were judged"
                + (row.wrapper() ? " and nothing was swept anywhere, because it is a wrapper" : "")
                + ": "
                + TagKeepRule.oneLine(e.getMessage()));
        wrapperUnreadable |= row.wrapper();
      }
    }
    if (wrapperUnreadable) {
      LOG.warnf("Tag collection swept nothing: a wrapper could not be read. %s", errors);
      return new TagCollectionReportDto(
          dryRun, reads.size(), 0, List.of(), new KeptTags(0, 0, 0, 0, 0), errors);
    }

    TagKeepRule.Facts base =
        new TagKeepRule.Facts(pinnedVersions, inFlight, Set.of(), now, minAge, keepNewest);
    Plan plan = TagKeepRule.plan(reads, base, this::gitlinksOf);
    errors.addAll(plan.errors());
    if (!plan.sweep()) {
      LOG.warnf("Tag collection swept nothing: a wrapper's gitlinks could not be read. %s", errors);
      return new TagCollectionReportDto(
          dryRun, reads.size(), 0, List.of(), new KeptTags(0, 0, 0, 0, 0), errors);
    }

    Map<Reason, Integer> kept = new HashMap<>();
    List<DeletedTag> deleted = new ArrayList<>();
    int examined = 0;
    for (RepositoryRead read : reads) {
      Verdicts verdicts = plan.verdicts().get(read.repoId());
      if (verdicts == null) {
        continue;
      }
      examined += verdicts.kept().size() + verdicts.deleted().size();
      verdicts.kept().values().forEach(reason -> kept.merge(reason, 1, Integer::sum));
      CatalogueRow row = byId.get(read.repoId());
      Set<String> hostTags = read.tags().stream().map(HostTag::name).collect(Collectors.toSet());
      SweepResult result =
          backups.exclusively(
              row.repoId(), () -> sweep(row, hostTags, verdicts, plan.facts(), dryRun));
      examined += result.twinOnlyExamined();
      result.twinKept().values().forEach(reason -> kept.merge(reason, 1, Integer::sum));
      deleted.addAll(result.deleted());
      errors.addAll(result.errors());
    }
    TagCollectionReportDto report =
        new TagCollectionReportDto(
            dryRun,
            reads.size(),
            examined,
            deleted,
            new KeptTags(
                kept.getOrDefault(Reason.NEWEST, 0),
                kept.getOrDefault(Reason.PINNED_VERSION, 0),
                kept.getOrDefault(Reason.GITLINK, 0),
                kept.getOrDefault(Reason.IN_FLIGHT, 0),
                kept.getOrDefault(Reason.YOUNG, 0)),
            errors);
    LOG.infof(
        "Tag collection%s: %d repositories, %d tags examined, %d deleted, %d errors",
        dryRun ? " (dry run)" : "",
        report.repositories(),
        report.examined(),
        report.deleted().size(),
        report.errors().size());
    return report;
  }

  // -----------------------------------------------------------------------------------------
  // reading
  // -----------------------------------------------------------------------------------------

  /** Refresh the mirror and read its tags and its {@code main}'s gitlinks; throws when it cannot. */
  private RepositoryRead read(CatalogueRow row) {
    RepoMirror mirror = gitMirrors.of(row.repoId());
    mirror.refreshNow();
    List<HostTag> tags = hostTags(mirror);
    String main = row.mainBranch() == null || row.mainBranch().isBlank() ? "main" : row.mainBranch();
    Set<String> mainGitlinks =
        mirror
            .resolve("refs/heads/" + main + "^{commit}")
            .map(commit -> gitlinks(mirror.gitDir().toFile(), commit))
            .orElse(Set.of());
    return new RepositoryRead(row.repoId(), row.name(), row.wrapper(), tags, mainGitlinks);
  }

  /**
   * Every tag in the mirror, with what it peels to and when it was made. {@code creatordate} is the
   * tagger date of an annotated tag and the committer date of a lightweight one, which is exactly
   * "when was this tag created" as far as git can say.
   */
  private List<HostTag> hostTags(RepoMirror mirror) {
    String output =
        exec(
            mirror.gitDir().toFile(),
            "git",
            "for-each-ref",
            "--format=%(refname:strip=2)%09%(objectname)%09%(objecttype)%09%(*objectname)%09%(creatordate:unix)",
            "refs/tags");
    List<HostTag> tags = new ArrayList<>();
    for (String line : output.split("\n")) {
      String[] parts = line.split("\t", -1);
      if (parts.length < 5 || parts[0].isBlank()) {
        continue;
      }
      String commit = "tag".equals(parts[2]) ? blankToNull(parts[3]) : parts[1];
      Instant created = null;
      try {
        created = Instant.ofEpochSecond(Long.parseLong(parts[4].trim()));
      } catch (NumberFormatException unreadable) {
        // left null: the rule reads an unknown age as young
      }
      tags.add(new HostTag(parts[0], parts[1], commit, created));
    }
    return tags;
  }

  /** {@link TagKeepRule.GitlinkReader} over the mirrors. */
  private Set<String> gitlinksOf(String repoId, String commitSha) {
    return gitlinks(gitMirrors.of(repoId).gitDir().toFile(), commitSha);
  }

  /** Every {@code 160000} entry in {@code commit}'s tree, recursively — the commits it mounts. */
  private Set<String> gitlinks(File gitDir, String commit) {
    String output = exec(gitDir, "git", "ls-tree", "-r", commit);
    Set<String> links = new HashSet<>();
    for (String line : output.split("\n")) {
      // "<mode> <type> <sha>\t<path>"
      if (line.startsWith("160000 ")) {
        String[] fields = line.substring(0, line.indexOf('\t')).split(" ");
        links.add(fields[2]);
      }
    }
    return links;
  }

  // -----------------------------------------------------------------------------------------
  // deleting
  // -----------------------------------------------------------------------------------------

  /** What one repository's sweep did. */
  record SweepResult(
      List<DeletedTag> deleted, Map<String, Reason> twinKept, int twinOnlyExamined, List<String> errors) {}

  /** Host, then twin, for one repository. Runs under the repository's backup lock. */
  private SweepResult sweep(
      CatalogueRow row,
      Set<String> hostTags,
      Verdicts verdicts,
      TagKeepRule.Facts facts,
      boolean dryRun) {
    List<String> errors = new ArrayList<>();
    Set<String> goneFromHost =
        dryRun ? new LinkedHashSet<>(verdicts.deleted()) : deleteOnHost(row, verdicts.deleted(), errors);

    Map<String, boolean[]> sides = new LinkedHashMap<>();
    goneFromHost.forEach(tag -> sides.put(tag, new boolean[] {true, false}));

    Map<String, Reason> twinKept = Map.of();
    int twinOnlyExamined = 0;
    if (row.twinUrl() != null && !row.twinUrl().isBlank()) {
      try {
        List<TwinTag> twinTags = twinTags(row);
        TwinVerdicts twin = TagKeepRule.judgeTwin(twinTags, hostTags, goneFromHost, facts);
        twinKept = twin.kept();
        twinOnlyExamined =
            (int)
                twinTags.stream()
                    .filter(tag -> TagKeepRule.isCalVer(tag.name()))
                    .filter(tag -> !hostTags.contains(tag.name()))
                    .count();
        Set<String> goneFromTwin =
            dryRun
                ? twin.deleted().keySet()
                : deleteOnTwin(row, twin.deleted(), errors);
        goneFromTwin.forEach(
            tag -> sides.computeIfAbsent(tag, name -> new boolean[] {false, false})[1] = true);
      } catch (RuntimeException e) {
        errors.add(
            row.name() + ": the backup twin could not be read: " + TagKeepRule.oneLine(e.getMessage()));
      }
    }
    List<DeletedTag> deleted = new ArrayList<>();
    sides.entrySet().stream()
        .sorted(Map.Entry.comparingByKey(TagKeepRule.RELEASE_ORDER))
        .forEach(
            entry ->
                deleted.add(
                    new DeletedTag(row.name(), entry.getKey(), entry.getValue()[0], entry.getValue()[1])));
    return new SweepResult(deleted, twinKept, twinOnlyExamined, errors);
  }

  /** Deletes {@code tags} on the git host; answers the ones receive-pack confirmed gone. */
  private Set<String> deleteOnHost(CatalogueRow row, List<String> tags, List<String> errors) {
    Set<String> gone = new LinkedHashSet<>();
    RepoMirror mirror = gitMirrors.of(row.repoId());
    for (List<String> batch : batches(tags)) {
      try {
        PushOutcome outcome =
            mirror.push(PushSpec.of(batch.stream().map(PushSpec.Ref::deleteTag).toArray(PushSpec.Ref[]::new)));
        Set<String> confirmed = confirmedDeletions(outcome.output());
        gone.addAll(confirmed);
        if (!outcome.accepted() || confirmed.size() < batch.size()) {
          errors.add(
              row.name()
                  + ": the git host kept "
                  + (batch.size() - confirmed.size())
                  + " of "
                  + batch.size()
                  + " tags it was asked to delete: "
                  + TagKeepRule.oneLine(
                      outcome.remoteRefusal() != null ? outcome.remoteRefusal() : outcome.output()));
        }
      } catch (RuntimeException e) {
        errors.add(
            row.name()
                + ": could not delete tags on the git host: "
                + TagKeepRule.oneLine(e.getMessage()));
      }
    }
    return gone;
  }

  /** Lists the twin's tags: {@code ls-remote --tags}, peeled lines folded onto their tag. */
  private List<TwinTag> twinTags(CatalogueRow row) {
    String output =
        exec(
            gitMirrors.of(row.repoId()).gitDir().toFile(),
            remoteAuth.gitWithCredentials(
                "ls-remote", "--tags", "--end-of-options", row.twinUrl()));
    return parseLsRemote(output);
  }

  /**
   * The tags of an {@code ls-remote --tags} answer. An annotated tag is two lines — the tag object
   * under its own ref and the commit under {@code ^{}} — and the lease must name the first, so the
   * peeled line only ever fills in the commit.
   */
  static List<TwinTag> parseLsRemote(String output) {
    Map<String, String> objects = new LinkedHashMap<>();
    Map<String, String> peeled = new HashMap<>();
    for (String line : output.split("\n")) {
      int tab = line.indexOf('\t');
      if (tab < 0) {
        continue;
      }
      String sha = line.substring(0, tab).trim();
      String ref = line.substring(tab + 1).trim();
      if (!ref.startsWith("refs/tags/")) {
        continue;
      }
      String name = ref.substring("refs/tags/".length());
      if (name.endsWith("^{}")) {
        peeled.put(name.substring(0, name.length() - 3), sha);
      } else {
        objects.put(name, sha);
      }
    }
    List<TwinTag> tags = new ArrayList<>();
    objects.forEach((name, sha) -> tags.add(new TwinTag(name, sha, peeled.getOrDefault(name, sha))));
    return tags;
  }

  /** Deletes tags on the twin, each leased on the object the listing saw; answers the ones gone. */
  private Set<String> deleteOnTwin(
      CatalogueRow row, Map<String, String> tags, List<String> errors) {
    Set<String> gone = new LinkedHashSet<>();
    for (List<String> batch : batches(new ArrayList<>(tags.keySet()))) {
      List<String> args = new ArrayList<>(List.of("push", "--porcelain"));
      batch.forEach(tag -> args.add("--force-with-lease=refs/tags/" + tag + ":" + tags.get(tag)));
      args.add("--end-of-options");
      args.add(row.twinUrl());
      batch.forEach(tag -> args.add(":refs/tags/" + tag));
      try {
        GitExecutor.ExecResult result =
            git.execAllowNonZero(
                gitMirrors.of(row.repoId()).gitDir().toFile(),
                remoteAuth.gitWithCredentials(args.toArray(String[]::new)));
        Set<String> confirmed = confirmedDeletions(result.output());
        gone.addAll(confirmed);
        if (result.exitCode() != 0 || confirmed.size() < batch.size()) {
          errors.add(
              row.name()
                  + ": the backup twin kept "
                  + (batch.size() - confirmed.size())
                  + " of "
                  + batch.size()
                  + " tags it was asked to delete: "
                  + TagKeepRule.oneLine(result.output()));
        }
      } catch (Exception e) {
        errors.add(
            row.name()
                + ": could not delete tags on the backup twin: "
                + TagKeepRule.oneLine(e.getMessage()));
      }
    }
    return gone;
  }

  /**
   * The tags {@code git push --porcelain} reports deleted: a line {@code -\t:refs/tags/<tag>\t…}.
   * Anything else — {@code !} for a refusal, a lease that did not hold — is a tag still there.
   */
  static Set<String> confirmedDeletions(String porcelain) {
    Set<String> gone = new LinkedHashSet<>();
    if (porcelain == null) {
      return gone;
    }
    for (String line : porcelain.split("\n")) {
      String[] fields = line.split("\t");
      if (fields.length >= 2 && "-".equals(fields[0]) && fields[1].startsWith(":refs/tags/")) {
        gone.add(fields[1].substring(":refs/tags/".length()));
      }
    }
    return gone;
  }

  private static List<List<String>> batches(List<String> all) {
    List<List<String>> batches = new ArrayList<>();
    for (int i = 0; i < all.size(); i += PUSH_BATCH) {
      batches.add(all.subList(i, Math.min(all.size(), i + PUSH_BATCH)));
    }
    return batches;
  }

  private String exec(File cwd, String... argv) {
    try {
      return git.exec(cwd, argv);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e.getMessage(), e);
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}

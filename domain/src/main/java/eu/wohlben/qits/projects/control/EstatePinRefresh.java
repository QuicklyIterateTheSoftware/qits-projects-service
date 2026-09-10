package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestSource;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.persistence.ReleaseRequestRepository;
import eu.wohlben.qits.projects.persistence.ReleaseRequestSourceRepository;
import eu.wohlben.qits.projects.persistence.ReleasedTagPendingMergeRepository;
import eu.wohlben.qits.projects.persistence.RepositoryNameRepository;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Bring one wrapper release request's <b>estate pins</b> up to date, or say why that could not be
 * established — the writer of {@link EstatePinLedger}'s notes, and the only thing in this service
 * that asks {@link EstatePins} anything.
 *
 * <h2>What it computes</h2>
 *
 * <p>For each <b>branch this request releases</b> — its named source branches <em>minus</em> the
 * repository's default branch — it reads that branch's {@code .gitmodules}, and for every entry it
 * declares it compares the gitlink the branch's tree holds against the sha of the sibling's latest
 * released version. The difference is the ask. A branch whose pins already name what its members
 * released asks for nothing; a branch with differences gets one bump carrying all of them.
 *
 * <p><b>Only named sources.</b> The implicit tag sources have no branch to write to — they are tags,
 * and a tag is not somewhere pins get committed — and their content reaches the fold through the
 * merge anyway. {@code ReleaseRequestSource.Kind.BRANCH} is therefore the whole population, which is
 * also why an implicit source having no row at all costs this class nothing.
 *
 * <p><b>And not the default branch, which is a source and never a target.</b> Every request folds
 * {@code main} first — it is the head the fold starts from — so it is a BRANCH source like any
 * other, and it is the one source no release consumes; {@code ReleaseRequests}' own "the branches
 * this release deleted" reading subtracts it for the same reason. Asking qits-maintenance to write
 * pins onto it is wrong twice over: {@code main} is not a branch that wants to be released, and it
 * is protected, so the bump's push fails and the request waits for ever on a commit that is never
 * coming. Measured live on {@code qits-qits}: one TARGETED bump onto {@code epic/…} succeeded with
 * four pins while the one onto {@code main} failed, and the request sat on "the estate pins are
 * being written" with nothing left to re-arm it. What is pinned is the work.
 *
 * <p><b>A sibling with no release yet is simply not in the estate.</b> A submodule that has never
 * been released has no version to pin at, and pinning it to a branch head would be this service
 * inventing a release nobody made — a wrapper version naming a commit no tag names, which is exactly
 * the thing the whole estate model is for. It is skipped, silently and on purpose. The same answer
 * covers a repository whose releases predate {@code released_tag_pending_merge} (see {@link
 * ReleasedTagPendingMergeRepository#latestReleased}) and an entry naming no member of this project at
 * all.
 *
 * <p><b>Any git-host read that is not {@code ok()} makes the whole refresh UNKNOWN.</b> Not just that
 * entry, and not a partial answer: what the ledger records is a statement about the fold, and "these
 * eleven pins are current and I could not read the twelfth" is not one. {@link
 * ReleaseGitHost.Answer#retryable()} is deliberately not branched on here — a fact about the moment
 * and a fact about the ask hold the request in exactly the same way, and the sweep re-asks either
 * way.
 *
 * <h2>Why it does not poll, and why the loop terminates</h2>
 *
 * <p>A bump answers 202 and the commit lands later. This class does <b>not</b> wait for it and does
 * not re-fold by hand, because the mechanism that notices is already there and is better than a poll:
 * the bump's commit moves the source branch, {@code SCMPublishCommit} arrives, {@link
 * ReleaseRequests#onBranchMoved} re-folds the request, and the re-fold lands a new {@code mergedSha}.
 * That is the re-arm — which correctly invalidates any approval given for the previous fold, since a
 * person who approved an estate must not have their yes transferred to one whose pins have moved
 * underneath it. Nothing has to correlate a bump id to a request, and the far side's status route is
 * read by nobody here.
 *
 * <p><b>The loop is self-terminating, and that is why the "already carrying its pins" case must ask
 * for nothing.</b> The bump's commit makes the branch's gitlinks equal to the released shas; the
 * re-arm runs this refresh again at the new fold; the comparison now finds no differences; a FRESH
 * note is written and the gate opens. If a branch that already carried its pins still asked for a
 * bump — an empty change list, which qits-maintenance would cheerfully accept and answer
 * NOTHING_TO_DO — nothing would ever move and the request would alternate between PENDING_BUMP and a
 * re-ask for ever. So the empty case is the terminating case, and it is the one worth being careful
 * about.
 *
 * <h2>Idempotence, and what a re-ask means</h2>
 *
 * <p>Idempotent per {@code (requestId, foldSha)}: a fold that already has a FRESH or PENDING_BUMP
 * note is left alone, so the sweep's re-ask every thirty seconds costs one map lookup rather than a
 * second bump on a branch that already has one in flight. An UNKNOWN note is <em>not</em> a stopping
 * answer — it is the one that is re-attempted — which is what makes a qits-maintenance outage heal on
 * the existing sweep with no new schedule.
 *
 * <p>The consequence of PENDING_BUMP being sticky is worth stating rather than discovering: a bump
 * accepted by qits-maintenance and then failing on its own side leaves this request holding, saying
 * the pins are being written, until something re-arms it. That is the safe direction and it is not a
 * dead end — any push to a named source re-folds the request, which invalidates the note, and the
 * far side's own bump record is where a failed bump is diagnosed.
 *
 * <p><b>Nothing here throws.</b> It is called from the arming seam, after a fold has already landed,
 * and from the gate's tail. Both belts are the caller's; this one is its own.
 */
@ApplicationScoped
public class EstatePinRefresh {

  private static final Logger LOG = Logger.getLogger(EstatePinRefresh.class);

  /** The one path that says a fold declares submodules. It is the file git itself reads. */
  private static final String GITMODULES = ".gitmodules";

  /** What a repository's default branch is when its row does not say — {@code ReleaseRequests}'. */
  private static final String DEFAULT_MAIN = "main";

  @Inject ReleaseRequestRepository requests;

  @Inject ReleaseRequestSourceRepository sources;

  @Inject ReleasedTagPendingMergeRepository pendingTags;

  @Inject RepositoryRepository repositories;

  @Inject RepositoryNameRepository names;

  @Inject ApprovalPolicy approvalPolicy;

  @Inject EstatePinLedger ledger;

  @Inject Instance<ReleaseGitHost> gitHosts;

  @Inject Instance<EstatePins> estatePins;

  /**
   * Everything this refresh needs out of the database, read once.
   *
   * @param repoName the wrapper as the catalogue names it — the address {@link EstatePins} takes,
   *     and the reason this is read here rather than at the adapter
   * @param branches the branches this request releases — its named sources in the order they were
   *     put on it, without the repository's default branch, which is a source and not a target
   */
  private record Facts(
      String requestId,
      String repoId,
      String projectId,
      String repoName,
      String foldSha,
      List<String> branches) {}

  /** One {@code .gitmodules} section of one source branch, as the file declares it. */
  private record Declared(String branch, String path, String name) {}

  /** A declared entry whose sibling has a release, so there is a version to pin at. */
  private record Candidate(
      String branch, String path, String name, String version, String releasedSha) {}

  /**
   * Establish, or fail to establish, that {@code requestId}'s current fold pins a current estate.
   *
   * <p>The only entry point, and it never throws: a port bug, a lazy-loading surprise or anything
   * else costs one WARN and leaves the ledger as it was — which is a hold, because absence is the
   * hold.
   */
  public void refresh(String requestId) {
    try {
      attempt(requestId);
    } catch (RuntimeException e) {
      LOG.warnf(
          e,
          "Could not refresh the estate pins of release request %s; it holds until the next sweep",
          requestId);
    }
  }

  private void attempt(String requestId) {
    Facts facts = QuarkusTransaction.requiringNew().call(() -> gather(requestId));
    if (facts == null) {
      // Not a wrapper, not open, or not folded yet: there is no estate question to answer, and a
      // note about a request that never needed one would be a note nothing ever drops.
      return;
    }
    if (settledFor(requestId, facts.foldSha())) {
      return;
    }
    if (facts.repoName() == null) {
      // A wrapper is a name-addressed thing — its members resolve through relative urls against its
      // own name, and the bump door takes that name and nothing else — so a wrapper with no alias is
      // one nothing here can ask about. It holds and says so rather than passing.
      unknown(facts, "this repository answers to no name, so qits-maintenance cannot be addressed");
      return;
    }
    if (!gitHosts.isResolvable()) {
      unknown(facts, "no git host is configured, so the wrapper's pins cannot be read");
      return;
    }
    ReleaseGitHost gitHost = gitHosts.get();

    List<Declared> declared = new ArrayList<>();
    for (String branch : facts.branches()) {
      String rev = "refs/heads/" + branch;
      // THE TREE, and not the file, and that is the same distinction ReleaseFinalization.deployability
      // makes one gate over: `file` answers "failed" both to "this branch declares no submodules" and
      // to "this branch could not be read", and those are opposite answers here — the first is a
      // branch with no estate to be stale about, the second is a hold. A tree listing separates them,
      // and it is also the one reading that agrees with the release executor's own pin guard, which
      // keys off the presence of this path rather than off any repository's archetype.
      ReleaseGitHost.Answer<List<String>> tree = gitHost.tree(facts.repoId(), rev);
      if (!tree.ok()) {
        unknown(facts, "the tree of " + branch + " could not be read: " + tree.detail());
        return;
      }
      if (!tree.value().contains(GITMODULES)) {
        // A source branch that declares no submodules pins nothing, so there is nothing here that
        // could be out of date. It contributes no changes and does not stop the fold being FRESH.
        continue;
      }
      ReleaseGitHost.Answer<String> content = gitHost.file(facts.repoId(), rev, GITMODULES);
      if (!content.ok()) {
        unknown(
            facts,
            "the submodule declaration of " + branch + " could not be read: " + content.detail());
        return;
      }
      for (WrapperGitmodules.Entry entry : WrapperGitmodules.entries(content.value())) {
        if (entry.path() == null || entry.path().isBlank() || entry.name() == null) {
          // A section somebody is still writing. The caller of a declaration decides what an
          // incomplete one means, and here it means there is no pin to compare.
          continue;
        }
        declared.add(new Declared(branch, entry.path(), entry.name()));
      }
    }

    List<Candidate> candidates =
        QuarkusTransaction.requiringNew().call(() -> released(facts.projectId(), declared));

    Map<String, List<EstatePins.GitlinkChange>> wanted = new LinkedHashMap<>();
    for (String branch : facts.branches()) {
      wanted.put(branch, new ArrayList<>());
    }
    for (Candidate candidate : candidates) {
      ReleaseGitHost.Answer<String> pinned =
          gitHost.gitlinkAt(
              facts.projectId(),
              facts.repoName(),
              "refs/heads/" + candidate.branch(),
              candidate.path());
      if (!pinned.ok()) {
        unknown(
            facts,
            "the pin at "
                + candidate.path()
                + " on "
                + candidate.branch()
                + " could not be read: "
                + pinned.detail());
        return;
      }
      // An ok answer carrying null is "nothing is pinned here" — a declaration whose gitlink has
      // never been committed — and that is a difference like any other: the entry wants the
      // released sha and does not have it.
      if (Objects.equals(pinned.value(), candidate.releasedSha())) {
        continue;
      }
      wanted
          .get(candidate.branch())
          .add(
              new EstatePins.GitlinkChange(
                  candidate.path(), candidate.name(), pinned.value(), candidate.version()));
    }

    if (wanted.values().stream().allMatch(List::isEmpty)) {
      ledger.record(
          facts.requestId(),
          facts.foldSha(),
          EstatePinLedger.State.FRESH,
          "the estate pins already name what the members released");
      LOG.debugf(
          "Release request %s: the estate pins at %s are current", requestId, facts.foldSha());
      return;
    }

    if (!estatePins.isResolvable()) {
      unknown(facts, "no maintenance context is configured to write them");
      return;
    }
    EstatePins pins = estatePins.get();
    int asked = 0;
    int moving = 0;
    for (Map.Entry<String, List<EstatePins.GitlinkChange>> entry : wanted.entrySet()) {
      if (entry.getValue().isEmpty()) {
        // The terminating case. Never a bump with an empty change list, however willingly the far
        // side would accept one — see the class javadoc.
        continue;
      }
      Optional<String> bump = pins.bump(facts.repoName(), entry.getKey(), entry.getValue());
      if (bump.isEmpty()) {
        unknown(
            facts,
            "qits-maintenance could not be asked to write "
                + entry.getValue().size()
                + " pin(s) on "
                + entry.getKey());
        return;
      }
      asked++;
      moving += entry.getValue().size();
      LOG.infof(
          "Release request %s: asked qits-maintenance for bump %s, moving %d estate pin(s) on %s",
          requestId, bump.get(), entry.getValue().size(), entry.getKey());
    }
    ledger.record(
        facts.requestId(),
        facts.foldSha(),
        EstatePinLedger.State.PENDING_BUMP,
        moving
            + " estate pin(s) are being written across "
            + asked
            + " source branch(es); the commit re-arms this request");
  }

  /**
   * Whether this fold's question is already answered. FRESH and PENDING_BUMP are both settled — one
   * releases and one holds, and neither is improved by asking again — while UNKNOWN and no note at
   * all are the two that re-attempt. That single line is the whole of the outage self-heal.
   */
  private boolean settledFor(String requestId, String foldSha) {
    return ledger
        .noteFor(requestId)
        .filter(note -> foldSha.equals(note.foldSha()))
        .filter(note -> note.state() != EstatePinLedger.State.UNKNOWN)
        .isPresent();
  }

  private void unknown(Facts facts, String reason) {
    LOG.warnf(
        "Release request %s holds: the estate pins at %s could not be established — %s",
        facts.requestId(), facts.foldSha(), reason);
    ledger.record(
        facts.requestId(), facts.foldSha(), EstatePinLedger.State.UNKNOWN, reason);
  }

  /**
   * The database half, in one short transaction. Null for every request this feature has no opinion
   * about — one that is gone, settled, not a wrapper, or not folded yet — and returning null rather
   * than an empty answer matters: nothing is written to the ledger for those, so nothing has to be
   * cleaned up for them either.
   */
  private Facts gather(String requestId) {
    ReleaseRequest row = requests.findByIdOptional(requestId).orElse(null);
    if (row == null
        || row.state != ReleaseRequest.State.PENDING
        || row.mergedSha == null
        || !approvalPolicy.isEstateWrapper(row.repoId)) {
      return null;
    }
    Repository repository = repositories.findByIdOptional(row.repoId).orElse(null);
    if (repository == null || repository.project == null) {
      return null;
    }
    // Null is carried rather than turned into a null Facts: an unnamed wrapper is a wrapper this
    // gate applies to and cannot answer for, which is a HOLD with a reason and not "no opinion".
    String repoName = names.nameFor(repository).orElse(null);
    // THE DEFAULT BRANCH IS A SOURCE AND IS NOT A TARGET — the class javadoc has the whole of why.
    // Read the way ReleaseRequests reads it, blank included, so the two cannot disagree about which
    // branch this is.
    String main =
        repository.mainBranch == null || repository.mainBranch.isBlank()
            ? DEFAULT_MAIN
            : repository.mainBranch;
    List<String> branches =
        sources.listByRequest(requestId).stream()
            .filter(source -> source.kind == ReleaseRequestSource.Kind.BRANCH)
            .map(source -> source.name)
            .filter(name -> !main.equals(name))
            .toList();
    return new Facts(
        row.id, row.repoId, repository.project.id, repoName, row.mergedSha, branches);
  }

  /**
   * Turn declarations into the entries that actually have a version to be pinned at — the second
   * short transaction, and the only place a {@code .gitmodules} name becomes a repository.
   *
   * <p><b>{@link RepositoryNameRepository#findRepositoryByProjectAndName} is what resolves a name,
   * and that choice is worth a sentence</b>, because {@code namesFor} exists one method over
   * precisely because a member can be addressed by several aliases. That method answers the reverse
   * question — every name one repository owns — and is the right tool when you hold a repository and
   * are matching it against a file. Here the file is what is held and the repository is what is
   * wanted, and the alias table's unique {@code (project, name)} index means the forward lookup is
   * exact: whichever of a member's aliases the wrapper declares it under resolves to the same row.
   * Walking every member's alias list to find the one that matches would be the same answer computed
   * the long way round.
   *
   * <p>An entry naming nothing in this project is skipped rather than refused. The wrapper is edited
   * by people and reconciled asynchronously, so a section for a member this service has not adopted
   * yet is an ordinary intermediate state — and a pin cannot be written for a repository whose
   * releases are unknown anyway.
   */
  private List<Candidate> released(String projectId, List<Declared> declared) {
    List<Candidate> out = new ArrayList<>();
    for (Declared entry : declared) {
      Repository sibling =
          names.findRepositoryByProjectAndName(projectId, entry.name()).orElse(null);
      if (sibling == null) {
        LOG.debugf(
            "The wrapper declares '%s' at %s, which names no repository of project %s; it is not"
                + " part of the estate this release pins",
            entry.name(), entry.path(), projectId);
        continue;
      }
      ReleasedTagPendingMerge latest = pendingTags.latestReleased(sibling.id).orElse(null);
      if (latest == null || latest.tagName == null || latest.releasedSha == null) {
        // Never released, or released before this service kept the record. Either way there is no
        // version to name, and inventing one is the one thing a pin must never be.
        continue;
      }
      out.add(
          new Candidate(
              entry.branch(), entry.path(), entry.name(), latest.tagName, latest.releasedSha));
    }
    return out;
  }
}

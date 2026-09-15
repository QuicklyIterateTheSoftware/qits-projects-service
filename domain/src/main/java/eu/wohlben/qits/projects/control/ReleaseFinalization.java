package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.persistence.ReleasedTagPendingMergeRepository;
import eu.wohlben.qits.projects.persistence.RepositoryNameRepository;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/**
 * <b>The publish phase: a released tag reaches {@code main} once what it released is live.</b> The
 * far end of the flow {@code ReleaseRequests} opens — a release is a tag, {@code main} is finalized
 * afterwards — and the only thing on this platform that advances {@code main} at all.
 *
 * <h2>The gates, and there are two of them</h2>
 *
 * <p><b>A release is finalized when every post-release gate the RELEASED TREE declares has passed</b>
 * (ticket b27384a3). The released tag's own tree is read once — {@link #releasedTree} — and what it
 * carries says which gates apply to this release:
 *
 * <ul>
 *   <li><b>The publish gate</b>, where the tree declares a release pipeline ({@code
 *       .config/qits/ci-event-release.yml}, or a {@code .config/qits/release.yml} naming an
 *       archetype qits-ci composes one from). The tag's own release run has to finish green, which
 *       arrives here as a {@code BuildSuccessful} whose <em>branch is the version</em> — see {@link
 *       #onPublishVerdict}. A red one is a <b>failed gate on an open request</b>, retried with
 *       {@code qits ci retry}; it never moves the request out of RELEASED, because the tag is cut
 *       and cannot be taken back.
 *   <li><b>The deployment gate</b>, where the tree declares {@code .config/qits/deployments.yml}.
 *       qits-deployments announces {@code DeploymentActive} for an application and a version, and
 *       that version is a released tag of some repository — see {@link #onDeploymentActive}.
 * </ul>
 *
 * <p><b>They pass in either order and each keeps its own fact on the row</b> ({@code publish_state},
 * {@code deployment_active_at}, V23), which is why the deployment's stamp is no longer {@code
 * merge_requested_at} itself. That column now means what its name always said: every configured
 * gate passed and the merge is owed. A merge that could not be applied stays owed and the sweep
 * keeps asking, so a git host that was unreachable for an hour costs a delay rather than a release
 * that never lands on {@code main}.
 *
 * <h2>The repositories nothing else will ever finalize</h2>
 *
 * <p>A library publishes nothing and deploys nothing, and neither does a docs repository: no
 * pipeline will report and no deployment will come, so its {@code main} would never move again.
 * {@link #onReleased} is the arm for exactly that — a release whose tree declares <b>neither</b> of
 * the two gates is finalized at the tag, there and then.
 *
 * <p><b>That is a narrower claim than the arm used to make, and the narrowing is the ticket's.</b>
 * It read one file ({@code deployments.yml}) and finalized every repository that did not declare it
 * — which finalized an SPA whose publish run had not run yet, and swallowed the failure when it went
 * red. The question is not "does this deploy" any more, it is "will anything at all finish this",
 * and the answer is read from the same single tree listing.
 *
 * <p>It is still <b>temporary</b>, in the sense that its replacement is already named: when
 * qits-maintenance becomes the lifecycle for libraries the way qits-deployments is for services, a
 * consumer taking the new version <em>is</em> the deployment, and this arm goes.
 *
 * <p><b>It hangs off this service's own release and not off qits-ci's {@code SoftwareRelease}</b>,
 * which is where it used to hang and why the arm quietly did nothing for half the platform: that
 * event is emitted by a repository's {@code ci-event-release.yml} recipe, so a repository without
 * one published no artifact event, was never forked on, and left its released tag stranded off
 * {@code main} for ever. A release, by contrast, is something this service performs itself and
 * therefore always knows about.
 *
 * <p>No two paths can double-gate one tag: {@link #advance} is the only writer of {@code
 * merge_requested_at}, it stamps once, and every arrival — the release, a verdict, a deployment, the
 * sweep — reaches it by the same route.
 *
 * <h2>Correlating a deployment back to a release</h2>
 *
 * <p><b>The version is the key, and it is the only key there is.</b> {@code DeploymentActive} names
 * an <em>application</em> and never a repository, and the two are not the same string — the platform
 * builds the application {@code qits-ci} out of the repository {@code qits-ci-service} — so a lookup
 * by repository identity has nothing to look up. A lookup by tag name alone is sound instead,
 * because the calver is unique platform-wide by construction: it is stamped to the second and a
 * collision comes back from the git host as {@code tag-exists}, which restarts the release with a
 * fresh stamp rather than reusing the name. Where two repositories nonetheless hold the same pending
 * tag, the application name is the tie-break and an unbreakable tie merges <b>nothing</b>: a wrong
 * repository's {@code main} is not a thing a later event can take back.
 *
 * <h2>Idempotence</h2>
 *
 * <p>Three layers, and each is load-bearing on its own: a stamped {@code merged_at} short-circuits
 * before any call is made; a repeated gate stamps {@code merge_requested_at} once; and the merge
 * itself is content-idempotent — a tag already contained in {@code main} answers {@code unchanged},
 * which moves no ref and creates no commit. So a replayed {@code DeploymentActive}, a re-deployment
 * of the same version and a sweep racing an event are all free.
 *
 * <h2>Failure</h2>
 *
 * <p>Every failed attempt writes its words onto the row and leaves it owed. A {@code 409
 * merge-conflict} is the loud one and is logged {@code ERROR} on every attempt: {@code main} only
 * ever advances through this class, and every release request folds the repository's pending tags
 * in, so a released tag that will not merge into {@code main} is an anomaly and not traffic. It is
 * still retried, because the thing that resolves it — a push to {@code main}, a sibling release
 * landing — is a change this class cannot see and must not have to be told about.
 */
@ApplicationScoped
public class ReleaseFinalization {

  private static final Logger LOG = Logger.getLogger(ReleaseFinalization.class);

  /** The fallback default branch, for a repository row that names none — {@code ReleaseRequests}'. */
  private static final String DEFAULT_MAIN = "main";

  @Inject ReleasedTagPendingMergeRepository pendingTags;

  @Inject RepositoryRepository repositories;

  @Inject RepositoryNameRepository names;

  @Inject ReleaseRequests releaseRequests;

  @Inject Instance<BackingBranchMerger> mergers;

  @Inject Instance<ReleaseGitHost> gitHosts;

  /** Reads {@link #RELEASE_SLOT_CONFIG} for the one fact the publish gate needs: an archetype. */
  @Inject ReleaseArchetypeParser archetypeParser;

  /**
   * The platform's declaration that a repository is deployed, at the path every service and every
   * frontend of it carries. Its presence is the deployment gate.
   */
  static final String DEPLOYMENTS_MANIFEST = ".config/qits/deployments.yml";

  /** A repository's own release pipeline. Its presence at the tag is the publish gate. */
  static final String RELEASE_PIPELINE = ReleaseArtifacts.RELEASE_RECIPE;

  /**
   * A migrated repository's release declaration: qits-ci composes the release pipeline from the
   * archetype it names, so naming one is the publish gate too — {@link ReleaseGates}' own reading of
   * the same file for the CI gate, applied to the released tree.
   */
  static final String RELEASE_SLOT_CONFIG = ReleaseArtifacts.SLOT_CONFIG;

  /**
   * A deployment of {@code version} is live, so the tag it deployed is owed {@code main}.
   *
   * <p><b>Any environment counts, and the first one wins.</b> A version reaching {@code dev} is the
   * same commit that will reach every other tier — the deployment pulls one immutable coordinate —
   * so waiting for a particular environment would leave {@code main} behind whatever is actually
   * shipping, and every later deployment of the same version is a no-op through {@code merged_at}.
   *
   * @param applicationName the deployed application, used only to break a tie between two
   *     repositories holding the same pending tag; null is tolerated
   * @param version the released coordinate, which is the tag's own name
   * @param environmentName where it went live — for the log line and the merge message
   */
  public void onDeploymentActive(String applicationName, String version, String environmentName) {
    Owed owed = correlate(applicationName, version);
    if (owed == null) {
      return;
    }
    boolean first =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    pendingTags
                        .findByIdOptional(owed.id())
                        .filter(row -> row.mergedAt == null && row.deploymentActiveAt == null)
                        .map(
                            row -> {
                              row.deploymentActiveAt = Instant.now();
                              return true;
                            })
                        .orElse(false));
    if (first) {
      LOG.infof(
          "The deployment gate passed for %s of %s: %s %s is active%s",
          owed.tagName(),
          owed.repoId(),
          applicationName == null ? "the application" : applicationName,
          version,
          environmentName == null ? "" : " in " + environmentName);
    }
    // Loud, because a deployment is news: a tag whose tree cannot be read here says so once per
    // deployment rather than once per sweep.
    advance(owed, true);
  }

  /**
   * <b>The publish gate's verdict: qits-ci finished the release run of a tag.</b> Called from the
   * build-status consumption for every terminal verdict whose branch names a version this service
   * released — for a publish run the branch <em>is</em> the tag, which is what makes this
   * correlation possible without qits-ci knowing anything about release requests.
   *
   * <p><b>A red verdict does not move the request out of RELEASED</b>, and that is the whole point
   * of the ticket this method belongs to. The tag is cut; it cannot be un-cut. What a failure means
   * is that a gate on an <em>open</em> request went red — the request keeps saying so, the run id is
   * on the row for {@code qits ci retry} to address, and the retry's green verdict arrives here and
   * finalizes. Nothing is rolled back and nothing is rejected.
   *
   * <p><b>Gating is not consulted.</b> A publish run's {@code gating} flag is about the fold gate,
   * which this is not; what selects this arm is the branch naming a released version of the same
   * repository with a finalization still owed. A run for anything else — a QA run on {@code
   * release/<id>}, a build of {@code main} — names no such version and settles nothing here.
   *
   * <p>It never throws: the caller is a durable consumption whose watermark must not be held behind
   * one repository's publish run.
   *
   * @param branch the verdict's branch, which for a publish run is the released version's own name
   * @param green whether the run finished {@code SUCCESS}
   */
  public void onPublishVerdict(String repoId, String branch, String runId, boolean green) {
    if (repoId == null || repoId.isBlank() || branch == null || branch.isBlank()) {
      return;
    }
    String version = branch.trim();
    String said =
        green
            ? "The release run " + runId + " of " + version + " finished green"
            : "The release run "
                + runId
                + " of "
                + version
                + " FAILED; the release stays open until it is retried and passes";
    Owed owed =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    pendingTags
                        .find(repoId, version)
                        .filter(row -> row.mergedAt == null && row.abandonedAt == null)
                        .map(
                            row -> {
                              row.publishState =
                                  green
                                      ? ReleasedTagPendingMerge.PublishState.PASSED
                                      : ReleasedTagPendingMerge.PublishState.FAILED;
                              row.publishDetail = said;
                              row.publishRunId = runId;
                              return owedOf(row);
                            })
                        .orElse(null));
    if (owed == null) {
      // The overwhelmingly ordinary case: a verdict for a branch, not for a tag of ours.
      LOG.debugf("No release of %s is waiting on a run for %s", repoId, version);
      return;
    }
    if (green) {
      LOG.infof("The publish gate passed for %s of %s: %s", version, repoId, said);
      advance(owed, true);
      return;
    }
    LOG.warnf(
        "The publish gate FAILED for %s of %s and the release request stays open: %s",
        version, repoId, said);
  }

  /**
   * <b>A release landed, so decide which kind of repository it belongs to.</b> Called on the release
   * worker the instant {@code ReleaseRequests} has recorded the tag, and again by the catch-up for
   * any released tag nothing has decided about yet.
   *
   * <p><b>It hangs off the RELEASE, not off a published artifact</b> — and that move is this
   * method's whole point (2026-09-04). It used to be {@code onSoftwareRelease}, driven by qits-ci's
   * per-artifact publication event, which sounded like the closest thing a library has to "it is
   * live" and was in fact a gate only some repositories can pass: {@code SoftwareRelease} is emitted
   * by a repository's {@code ci-event-release.yml} recipe, and every repository without one — every
   * SPA, for a start — announced nothing, so its released tag never reached {@code main} and its
   * default branch fell one commit behind for ever (qits-deployments-platform-frontend
   * 2026.904.151913, measured). A release is a fact this service produces itself, so this is now
   * hung off that.
   *
   * <p><b>It finalizes a repository NOTHING ELSE WILL EVER FINALIZE, and nothing more</b> — that is
   * the ticket b27384a3 narrowing of what this arm used to do. It used to be "finalize a
   * non-deployable at once", one file read, and it therefore finalized an SPA whose publish run had
   * not even started: a red run afterwards had nothing left to hold open. The arm exists for the
   * 2026-09-04 stranding case and for that alone — a release whose tree declares <b>neither</b> a
   * release pipeline nor {@code deployments.yml} has no gate that could ever come, and without this
   * its {@code main} would never move again.
   *
   * <p><b>TEMPORARY, and the shape of what replaces it is known.</b> Until qits-maintenance becomes
   * the lifecycle for libraries the way qits-deployments is for services — a consumer taking the new
   * version <em>is</em> the deployment — the release itself has to stand in for it. When that lands,
   * this arm goes and the two real gates are the only ones again.
   *
   * <p>What is declared is read at the released tag, not at {@code main}: those files are the
   * platform's own declaration of "this publishes" and "this is deployed", and the tag is the only
   * tree that is certainly the release's own. See {@link ReleaseGates}' "Configured at the tag".
   *
   * <p><b>It never throws.</b> The caller is a release that has already happened and must not be
   * failed by anything after the tag; a git host that could not answer leaves the row ungated, which
   * is precisely what {@link #sweep()}'s catch-up picks up.
   */
  public void onReleased(String repoId, String version) {
    if (repoId == null || repoId.isBlank() || version == null || version.isBlank()) {
      return;
    }
    Owed owed =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    pendingTags
                        .find(repoId, version.trim())
                        .filter(
                            row ->
                                row.mergedAt == null
                                    && row.mergeRequestedAt == null
                                    && row.abandonedAt == null)
                        .map(ReleaseFinalization::owedOf)
                        .orElse(null));
    if (owed == null) {
      // Either this service did not release that tag, or it is already merged, or something has
      // already gated it — the two gates, or an earlier pass of this very method — or a later
      // release has superseded it whole.
      LOG.debugf("Nothing to gate for %s of %s on its release", version, repoId);
      return;
    }
    advance(owed, true);
  }

  /**
   * <b>The whole post-release state machine, and the ONLY copy of it.</b> Every arrival reaches it —
   * the release, a publish verdict, a deployment, the catch-up — and it always asks the same
   * question: has every gate the released tree declares passed? If so the merge is owed and made; if
   * not, nothing happens and whatever is still owed will arrive later.
   *
   * <p>One tree listing per pass, which is what it costs to know which gates apply at all. The
   * alternative — remembering the answer on the row — was rejected for {@link ReleaseGates}' own
   * reason: it is the same bytes the git host would answer with again, and a stored requirement
   * would be a second copy of a fact the tag already carries immutably.
   *
   * @param loud whether an unanswerable git host is worth a WARN. It is on the arrival paths, where
   *     it is news; it is not on the catch-up, which re-asks about the same row every sweep and
   *     would otherwise turn one unreadable tag into a log nobody reads.
   */
  private void advance(Owed owed, boolean loud) {
    ReleasedTree declared = releasedTree(owed.repoId(), owed.tagName());
    if (!declared.read()) {
      // The released tag stays visibly unfinished — merge_requested_at null beside a null merged_at
      // — and the catch-up, a verdict, a deployment or a person can still complete it.
      String sentence =
          "What "
              + owed.tagName()
              + " of "
              + owed.repoId()
              + " declares cannot be established, so its gates cannot be decided; it stays ungated"
              + " and the catch-up will ask again";
      if (loud) {
        LOG.warn(sentence);
      } else {
        LOG.debug(sentence);
      }
      return;
    }
    Owing owing = owingOf(owed, declared.publishes());
    if (owing == null) {
      // The row went, or landed, between the listing and this read.
      return;
    }
    if (declared.publishes()
        && owing.publishState() != ReleasedTagPendingMerge.PublishState.PASSED) {
      LOG.debugf(
          "%s of %s declares a release pipeline whose run is %s; main waits for it",
          owed.tagName(), owed.repoId(), owing.publishState());
      return;
    }
    if (declared.deploys() && owing.deploymentActiveAt() == null) {
      LOG.debugf(
          "%s declares a deployment, so its release %s reaches main when that deployment does",
          owed.repoId(), owed.tagName());
      return;
    }
    gate(owed, why(owed, declared));
    merge(owed.id());
  }

  /** Why this tag became owed {@code main} — the sentence the gate stamp is logged with. */
  private static String why(Owed owed, ReleasedTree declared) {
    List<String> passed = new ArrayList<>();
    if (declared.publishes()) {
      passed.add("the release run of " + owed.tagName() + " is green");
    }
    if (declared.deploys()) {
      passed.add("a deployment of " + owed.tagName() + " is active");
    }
    if (passed.isEmpty()) {
      return "the released tree declares neither a release pipeline nor "
          + DEPLOYMENTS_MANIFEST
          + ", so nothing else will ever finalize "
          + owed.tagName();
    }
    return "every post-release gate passed — " + String.join(" and ", passed);
  }

  /** What the row itself says about the two gates, and the PENDING stamp that goes with asking. */
  private record Owing(
      ReleasedTagPendingMerge.PublishState publishState, Instant deploymentActiveAt) {}

  /**
   * Read the row's gate facts, stamping the publish gate PENDING the first time a release is found
   * to declare a pipeline. The stamp is what makes the gate <b>visible</b> — it is where {@code
   * gateReport} reads the PUBLISH gate's existence from, since nothing at {@code main} says whether
   * the released commit declared one — and it is never written over a verdict that has already
   * answered.
   */
  private Owing owingOf(Owed owed, boolean publishes) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                pendingTags
                    .findByIdOptional(owed.id())
                    .filter(row -> row.mergedAt == null && row.abandonedAt == null)
                    .map(
                        row -> {
                          if (publishes && row.publishState == null) {
                            row.publishState = ReleasedTagPendingMerge.PublishState.PENDING;
                            row.publishDetail =
                                "Waiting for the release run of " + row.tagName + " to finish";
                          }
                          return new Owing(row.publishState, row.deploymentActiveAt);
                        })
                    .orElse(null));
  }

  /**
   * The belt under every gate, in two arms.
   *
   * <p><b>The catch-up</b> re-asks the gate question for every released tag nothing has gated yet.
   * It is what makes the release-path arm crash-safe — a process that died between the tag and it
   * loses nothing but time — and it is what heals a tag nothing ever decided about, which is every
   * non-deployable release made while this decision hung off {@code SoftwareRelease}. A release
   * still waiting on a gate is looked at and left alone on every pass, which is one cheap tree
   * listing per release in flight.
   *
   * <p><b>The retry</b> re-attempts each merge this service already owes {@code main}, turning an
   * unreachable git host, a merge that raced a concurrent writer and a conflict somebody has since
   * resolved into delays instead of stalls. <b>It selects on {@code merge_requested_at} and nothing
   * else</b>: a released tag whose deployment has not happened is not owed anything, and sweeping it
   * would be this class merging on no gate at all.
   */
  public void sweep() {
    List<String> owed =
        QuarkusTransaction.requiringNew()
            .call(() -> pendingTags.listOwedMerges().stream().map(row -> row.id).toList());
    owed.forEach(this::merge);
    List<Owed> ungated =
        QuarkusTransaction.requiringNew()
            .call(() -> pendingTags.listUngated().stream().map(ReleaseFinalization::owedOf).toList());
    ungated.forEach(row -> advance(row, false));
  }

  // -----------------------------------------------------------------------------------------------
  // The merge
  // -----------------------------------------------------------------------------------------------

  /** One row's identity, carried out of the transaction that read it. */
  record Owed(String id, String repoId, String tagName) {}

  /**
   * Stamp the gate, once. A second gate for the same tag — a re-deployment, a replayed frame, the
   * publish path and the deployment path racing — finds the stamp and changes nothing.
   */
  private void gate(Owed owed, String why) {
    boolean stamped =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    pendingTags
                        .findByIdOptional(owed.id())
                        .filter(
                            row ->
                                row.mergedAt == null
                                    && row.mergeRequestedAt == null
                                    && row.abandonedAt == null)
                        .map(
                            row -> {
                              row.mergeRequestedAt = Instant.now();
                              return true;
                            })
                        .orElse(false));
    if (stamped) {
      LOG.infof(
          "The released tag %s of %s is owed main: %s", owed.tagName(), owed.repoId(), why);
    }
  }

  /**
   * Merge one owed tag into its repository's default branch, and act on what came back.
   *
   * <p>The git-host call is made <b>outside</b> every transaction, the shape {@code
   * ReleaseRequests.remerge} already takes: a short read, a round trip, a short write.
   *
   * <p><b>The source is the released sha, never the tag ref.</b> The branches a release consumed are
   * deleted when it lands, and the tag is a ref somebody could move or delete; the sha recorded when
   * the release happened is the fact, and it is what {@code released_tag_pending_merge} exists to
   * remember.
   */
  private void merge(String rowId) {
    record Ask(String repoId, String tagName, String sha, String target, boolean owed) {}
    Ask ask =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    pendingTags
                        .findByIdOptional(rowId)
                        .filter(
                            row ->
                                row.mergedAt == null
                                    && row.mergeRequestedAt != null
                                    && row.abandonedAt == null)
                        .map(
                            row ->
                                new Ask(
                                    row.repoId,
                                    row.tagName,
                                    row.releasedSha,
                                    "refs/heads/" + mainOf(row.repoId),
                                    true))
                        .orElse(new Ask(null, null, null, null, false)));
    if (!ask.owed()) {
      return;
    }
    String what = ask.tagName() + " of " + ask.repoId();
    if (!mergers.isResolvable()) {
      failed(
          rowId,
          what,
          "No git host is configured; the released tag "
              + ask.tagName()
              + " cannot be merged into "
              + ask.target(),
          false);
      return;
    }
    BackingBranchMerger.Outcome outcome;
    try {
      outcome =
          mergers
              .get()
              .merge(
                  ask.repoId(),
                  ask.target(),
                  List.of(ask.sha()),
                  "Release " + ask.tagName() + " is deployed; finalizing " + ask.target());
    } catch (RuntimeException e) {
      // The port says it must not throw; a throw is a port bug and must not lose the merge.
      LOG.warnf(e, "The merger threw finalizing %s of %s", ask.tagName(), ask.repoId());
      outcome = BackingBranchMerger.Outcome.unreachable("merger error: " + e.getMessage());
    }
    if (!outcome.folded()) {
      failed(
          rowId,
          what,
          detailOf(outcome, ask.target()),
          outcome.result() == BackingBranchMerger.Result.CONFLICT);
      return;
    }
    landed(rowId);
    // The row's own merged_at is stamped THERE and only there — one writer of that column — and the
    // repository's open requests re-fold without this tag, which is content-idempotent and usually
    // answers `unchanged`.
    releaseRequests.onReleasedTagMerged(ask.repoId(), ask.tagName());
    LOG.infof(
        "The released tag %s of %s reached %s (%s)",
        ask.tagName(), ask.repoId(), ask.target(), outcome.result());
  }

  /** The attempt landed: the row keeps no stale reason for a failure it recovered from. */
  private void landed(String rowId) {
    QuarkusTransaction.requiringNew()
        .run(() -> pendingTags.findByIdOptional(rowId).ifPresent(row -> row.mergeDetail = null));
  }

  /**
   * The attempt did not land: the reason goes on the row, the row stays owed, and the sweep asks
   * again.
   *
   * <p>Loud on a conflict every time, and loud once on anything else — a git host that is down for
   * ten minutes is twenty sweeps, and twenty ERRORs about one outage is a log nobody reads.
   */
  private void failed(String rowId, String what, String detail, boolean conflict) {
    boolean first =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    pendingTags
                        .findByIdOptional(rowId)
                        .map(
                            row -> {
                              boolean changed = !detail.equals(row.mergeDetail);
                              row.mergeDetail = detail;
                              return changed;
                            })
                        .orElse(false));
    if (conflict) {
      LOG.errorf(
          "A released tag CANNOT be merged into main and that is an anomaly, not traffic (%s): %s",
          what, detail);
    } else if (first) {
      LOG.errorf("A released tag is owed main and the merge did not apply (%s): %s", what, detail);
    } else {
      LOG.warnf("Still owed main (%s): %s", what, detail);
    }
  }

  private static String detailOf(BackingBranchMerger.Outcome outcome, String target) {
    if (outcome.result() == BackingBranchMerger.Result.CONFLICT) {
      return "The release conflicts with "
          + target
          + ": "
          + outcome.conflicts().stream()
              .map(BackingBranchMerger.Conflict::path)
              .collect(Collectors.joining(", "));
    }
    return outcome.detail() == null ? "The git host could not be asked" : outcome.detail();
  }

  // -----------------------------------------------------------------------------------------------
  // What the released tree declares — ONE listing, and the only copy of the reading
  // -----------------------------------------------------------------------------------------------

  /**
   * Which post-release gates a released tag declares, or the fact that its tree could not be read.
   *
   * <p>Both questions come out of <b>one</b> listing on purpose. They used to be one question
   * (deployability) and are now two, and asking them separately would double this service's git-host
   * traffic per sweep for two membership tests on the same list of paths.
   *
   * @param readability whether the tree was read at all; the two failure words are kept apart for
   *     the reason stated on {@link Readability#UNREADABLE}
   * @param publishes the tree declares a release pipeline, so the publish gate applies
   * @param deploys the tree declares {@link #DEPLOYMENTS_MANIFEST}, so the deployment gate applies
   */
  record ReleasedTree(Readability readability, boolean publishes, boolean deploys) {

    static ReleasedTree unread(Readability readability) {
      return new ReleasedTree(readability, false, false);
    }

    boolean read() {
      return readability == Readability.READ;
    }
  }

  /**
   * Whether the released tree could be read. Neither failure is ever an answer about the release —
   * "could not ask" resolving to "declares nothing" would merge a commit to {@code main} on no gate
   * at all.
   */
  enum Readability {
    READ,
    /** The git host could not be asked. Retrying is exactly what fixes it. */
    UNKNOWN_FOR_NOW,
    /** The git host answered, and its answer was a refusal that will not change: settle. */
    UNREADABLE
  }

  /**
   * What does this release declare? Read as the released tag's own tree, through the git host, and
   * nowhere else.
   *
   * <p><b>The tree rather than the file.</b> {@code ReleaseGitHost.file} answers "failed" for a blob
   * that is absent, one that is binary and a rev that does not resolve alike, and the difference
   * between "this repository declares nothing" and "the git host could not tell us" is the whole
   * decision here. A tree listing separates them: a successful listing without the path is an
   * answer, and an unsuccessful one is not an answer at all.
   *
   * <p>A refusal that is <b>not</b> about the moment — a tag the git host does not know — is kept
   * apart as {@link Readability#UNREADABLE} even though {@link #advance} treats the two alike today:
   * the same bytes really would fail identically forever, and only the volume of the retry
   * distinguishes them. Neither is ever read as an answer.
   *
   * <p><b>The one file read, and only where the listing already said the file is there</b>: {@link
   * #RELEASE_SLOT_CONFIG} has to be opened to see whether it names an archetype. A read that fails
   * is "could not ask" and holds the whole answer; a file that will not <em>parse</em> is {@link
   * ReleaseGates}' case exactly and takes its answer — the gate applies. Waiting on a run that then
   * has to be fixed is recoverable; finalizing a release whose pipeline was never checked is not.
   *
   * <p><b>Its failures are logged at DEBUG</b>, because the catch-up asks about the same row on
   * every sweep; {@link #advance} is what says something once, on the path where it is news.
   */
  private ReleasedTree releasedTree(String repoId, String version) {
    if (!gitHosts.isResolvable()) {
      LOG.debugf("No git host is configured, so what %s declares cannot be read", repoId);
      return ReleasedTree.unread(Readability.UNKNOWN_FOR_NOW);
    }
    String rev = "refs/tags/" + version;
    ReleaseGitHost host = gitHosts.get();
    ReleaseGitHost.Answer<List<String>> tree;
    try {
      tree = host.tree(repoId, rev);
    } catch (RuntimeException e) {
      // The port says it must not throw; a throw is a port bug and must not be read as an answer.
      LOG.debugf(e, "The git host threw reading the tree of %s at %s", repoId, version);
      return ReleasedTree.unread(Readability.UNKNOWN_FOR_NOW);
    }
    if (!tree.ok()) {
      LOG.debugf(
          "Could not read the tree of %s at %s (%s): %s",
          repoId, rev, tree.retryable() ? "retryable" : "final", tree.detail());
      return ReleasedTree.unread(
          tree.retryable() ? Readability.UNKNOWN_FOR_NOW : Readability.UNREADABLE);
    }
    List<String> paths = tree.value();
    boolean deploys = paths.contains(DEPLOYMENTS_MANIFEST);
    if (paths.contains(RELEASE_PIPELINE)) {
      return new ReleasedTree(Readability.READ, true, deploys);
    }
    if (!paths.contains(RELEASE_SLOT_CONFIG)) {
      return new ReleasedTree(Readability.READ, false, deploys);
    }
    ReleaseGitHost.Answer<String> config;
    try {
      config = host.file(repoId, rev, RELEASE_SLOT_CONFIG);
    } catch (RuntimeException e) {
      LOG.debugf(e, "The git host threw reading %s of %s at %s", RELEASE_SLOT_CONFIG, repoId, rev);
      return ReleasedTree.unread(Readability.UNKNOWN_FOR_NOW);
    }
    if (config == null || !config.ok()) {
      LOG.debugf(
          "%s is declared at %s of %s and could not be read", RELEASE_SLOT_CONFIG, rev, repoId);
      return ReleasedTree.unread(Readability.UNKNOWN_FOR_NOW);
    }
    try {
      return new ReleasedTree(Readability.READ, archetypeParser.declaresArchetype(config.value()), deploys);
    } catch (RuntimeException e) {
      LOG.warnf(
          "%s at %s of %s does not parse; treating %s as publish-gated rather than ungated: %s",
          RELEASE_SLOT_CONFIG, rev, repoId, version, e.getMessage());
      return new ReleasedTree(Readability.READ, true, deploys);
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Correlation
  // -----------------------------------------------------------------------------------------------

  /**
   * Which pending released tag a deployed {@code (application, version)} is, or null when none is.
   *
   * <p>Three answers and all three are ordinary. <b>No row</b> is a version this service did not
   * release — every deployment on the platform passes through here — or one whose merge already
   * landed, and both are a DEBUG and a return. <b>One row</b> is the answer. <b>Several rows</b> is
   * the same calver held by two repositories, which the platform's stamp makes vanishingly unlikely
   * and does not make impossible; the application name breaks the tie, and where it cannot, nothing
   * is merged and the rows stay visibly unfinished. A wrong {@code main} is not recoverable and a
   * late one is.
   */
  private Owed correlate(String applicationName, String version) {
    if (version == null || version.isBlank()) {
      return null;
    }
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              List<ReleasedTagPendingMerge> rows = pendingTags.listByTag(version.trim());
              List<ReleasedTagPendingMerge> open =
                  rows.stream()
                      .filter(row -> row.mergedAt == null && row.abandonedAt == null)
                      .toList();
              if (open.isEmpty()) {
                LOG.debugf(
                    "Nothing here owes main for version %s (%d released rows carry that tag)",
                    version, rows.size());
                return null;
              }
              if (open.size() == 1) {
                return owedOf(open.get(0));
              }
              List<ReleasedTagPendingMerge> named =
                  open.stream().filter(row -> answersTo(row.repoId, applicationName)).toList();
              if (named.size() == 1) {
                LOG.infof(
                    "Version %s is pending in %d repositories; the application name %s names one of"
                        + " them",
                    version, open.size(), applicationName);
                return owedOf(named.get(0));
              }
              LOG.errorf(
                  "Version %s is pending a merge to main in %d repositories and the application"
                      + " name %s tells them apart in %d of them; NOTHING is merged, because a"
                      + " wrong main cannot be taken back. Merge one by hand and the rest follow.",
                  version, open.size(), applicationName, named.size());
              return null;
            });
  }

  private static Owed owedOf(ReleasedTagPendingMerge row) {
    return new Owed(row.id, row.repoId, row.tagName);
  }

  /**
   * Whether a repository is plausibly what an application was built from. The platform's own
   * grammar and nothing cleverer: the application {@code qits-ci} is built from {@code
   * qits-ci-service}, a repository with no role suffix answers to its own name, and this is only
   * ever asked to break a tie between rows that already share a version.
   */
  private boolean answersTo(String repoId, String applicationName) {
    if (applicationName == null || applicationName.isBlank()) {
      return false;
    }
    String application = applicationName.trim();
    return repositories
        .findByIdOptional(repoId)
        .flatMap(names::nameFor)
        .map(name -> name.equals(application) || name.startsWith(application + "-"))
        .orElse(false);
  }

  /** The repository's default branch — {@code ReleaseRequests}' reading, which is private there. */
  private String mainOf(String repoId) {
    return repositories
        .findByIdOptional(repoId)
        .map(repository -> repository.mainBranch)
        .filter(branch -> branch != null && !branch.isBlank())
        .orElse(DEFAULT_MAIN);
  }
}

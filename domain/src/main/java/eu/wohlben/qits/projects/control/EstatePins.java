package eu.wohlben.qits.projects.control;

import java.util.List;
import java.util.Optional;

/**
 * Ask qits-maintenance to move a repository's <b>gitlink pins</b> to named versions on a named
 * branch — the one write this context makes into another context's mechanism, and the reason a
 * wrapper release request can promise that what it releases is the estate as it stands.
 *
 * <h2>Why the ask leaves this service at all</h2>
 *
 * <p>A wrapper's {@code .gitmodules} declares its members and its <b>tree</b> pins each one at a
 * commit. A release of the wrapper is the estate's own version moving, so a fold whose pins are
 * older than what the members have already released ships a version of the platform that names
 * repositories nobody is running. Writing those pins is a real piece of machinery — resolve a
 * sibling's clone url from its name, fetch {@code refs/tags/<version>}, write the resolved commit as
 * a mode-160000 entry, commit it through CI so the recipe that gates the branch runs — and
 * qits-maintenance has all of it, because bumping a declared dependency to a released version is
 * what that service <em>is</em>. This context has none of it and must not grow it: {@link
 * ReleaseGitHost#commit} carries a {@code gitlinks} parameter precisely so a writer of pins can
 * exist, and its javadoc already says the release path passes none.
 *
 * <p>So this port asks, and asking is the whole of it. What comes back is an <b>id</b>, not a
 * result: the far side answers 202 and the commit lands later, discovered by this side the way every
 * other commit is — the branch moves, {@code SCMPublishCommit} arrives, the request re-folds. There
 * is a status route on the far side and this port deliberately does not model it, because polling it
 * would be a second, slower way of learning what a branch head already says.
 *
 * <h2>The three rules every port in this package carries, restated because they bind here</h2>
 *
 * <p><b>Nothing here may throw.</b> This is asked on the arming path, after a fold has already
 * landed, and an arming must never fail over an enrichment — an implementation turns every failure
 * into {@link Optional#empty()} itself rather than leaving the caller to catch it, exactly as {@link
 * DownstreamComponents} does.
 *
 * <p><b>It must be bounded</b> — one short request, never a retry loop. The caller is a fold, and a
 * fold that sat on a retry ladder would hold the bus consumption that produced it. Asking again is
 * the sweep's job and costs nothing, because the caller's record of the ask is positive (see {@link
 * EstatePinLedger}) and an ask that could not be made simply is not one.
 *
 * <p><b>Absent is a supported configuration</b>, and here that sentence needs its consequence spelled
 * out rather than left implied. Injected as an {@code Instance<T>} like every port here; a platform
 * with no qits-maintenance configured releases <em>every ordinary repository</em> exactly as it
 * always did, because nothing but a wrapper request ever asks. What a wrapper request does on such a
 * platform is <b>wait, and say so</b>: it holds PENDING with a sentence naming the reason.
 *
 * <p>That is the deliberate cost of the guarantee, and the alternative was considered and rejected.
 * "No implementation" cannot mean "release the stale pins silently", because that is precisely the
 * failure this feature exists to remove and it would arrive through the one path nobody configures
 * and therefore nobody tests. It cannot mean "reject" either: a refusal destroys a legitimate
 * request over a fact about this platform's configuration rather than about the request's content.
 * Holding is the only answer that is wrong in a direction somebody can see and fix.
 */
public interface EstatePins {

  /**
   * One gitlink to move, as this context knows it. Six plain fields on the wire; four here, because
   * the other two are the far side's own conventions and an implementation supplies them.
   *
   * @param manifestPath the submodule's <b>directory</b> path in the superproject's tree — {@code
   *     components/qits-ci/qits-ci-frontend}, exactly the {@code path} its {@code .gitmodules}
   *     section declares. It is where the mode-160000 entry is written and it is what the far side
   *     validates: never absolute, never containing {@code ..}.
   * @param name the <b>sibling repository's name</b>, and the load-bearing field of the four. The
   *     far side derives the sibling's clone url from it and fetches {@code refs/tags/<to>} out of
   *     that repository, so a name that resolves to nothing there is a bump that writes nothing. It
   *     is the name, not this service's row id: nothing on the other side of this hop knows what a
   *     row id of ours is.
   * @param from the sha the branch's tree holds at {@code manifestPath} right now. <b>Not a
   *     precondition</b> — the far side does not compare against it and will not refuse a moved
   *     tree over it — and nullable: it exists so the commit message can say what moved to what.
   * @param to the released <b>version</b>, which is the tag name. A calver here; the far side
   *     resolves it to a commit itself.
   */
  record GitlinkChange(String manifestPath, String name, String from, String to) {}

  /**
   * Ask that {@code branch} of {@code repositoryName} have these pins written.
   *
   * @param repositoryName the repository as <b>qits-maintenance's catalogue</b> names it. Not a row
   *     id: see {@code maintenancehost/HttpEstatePins}, whose javadoc carries the argument for why
   *     this one hop is addressed differently from its neighbour in the same package.
   * @param branch the bare branch name — {@code work}, never {@code refs/heads/work}.
   * @param changes the pins to move. An empty list is accepted by the far side and answers "nothing
   *     to do", so a caller with nothing to ask for should not call at all rather than rely on it.
   * @return the bump's id, or <b>empty when the ask could not be made</b> — no implementation, no
   *     address, an unreachable or refusing qits-maintenance, a bump already in flight on that
   *     branch, an answer that will not parse. Empty is one answer on purpose: every one of those is
   *     "this side does not know that the pins are being written", which is the only distinction the
   *     caller acts on. Never null, and never thrown.
   */
  Optional<String> bump(String repositoryName, String branch, List<GitlinkChange> changes);
}

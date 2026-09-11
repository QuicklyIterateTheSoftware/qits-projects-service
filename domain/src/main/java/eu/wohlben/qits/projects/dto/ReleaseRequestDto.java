package eu.wohlben.qits.projects.dto;

import java.time.Instant;
import java.util.List;

/**
 * One release request as the API answers it. {@code state} is the stored word — {@code PENDING},
 * {@code READY}, {@code RELEASED}, {@code REJECTED}, {@code FAILED}, {@code CONFLICTED} or {@code
 * WITHDRAWN} today, and the vocabulary may grow. {@code detail} is the sentence explaining a request
 * that is not simply pending or released; {@code version} is the calver the release answered with,
 * once it did. {@code retryable} says, of a FAILED request, whether the sweep keeps retrying the
 * execution or the refusal stands until something re-arms it.
 *
 * <p><b>A request is a merge of {@code sources}, not a branch head.</b> {@code backingBranch} is
 * {@code release/<id>} — the ref the git host folds them into — and {@code mergedSha} is the tip of
 * that fold: what the gates evaluate and what an execution is pinned to. {@code mergedSha} is
 * <b>null until the first fold lands</b>, and null on a CONFLICTED request whose first fold never
 * did; a caller reads that as "nothing is gated yet", never as "nothing to release".
 *
 * <p>{@code sources} carries both kinds — the named branches somebody put on the request and the
 * implicit released tags the repository has in flight — with {@code implicit} telling them apart.
 * Only the named ones are caller-managed.
 *
 * <p><b>{@code priority} is the request's EFFECTIVE priority: the max over its named branch
 * sources.</b> Urgency is stated per participating branch — each was put on the request by somebody
 * with their own reason — so the request's own answer is the highest of them, which is the only
 * reading that cannot lose an escalation. Derived on every read rather than stored, never null
 * ({@code MEDIUM} where nothing says otherwise), and a word rather than a closed set, because the
 * vocabulary may grow. The implicit tag sources are not in the max: they carry no priority at all.
 *
 * <p>{@code conflict} is populated on a CONFLICTED request and null on every other, so a caller
 * never has to ask a second question to find out what to resolve.
 *
 * <p><b>{@code releasedSha} is what the tag points at</b> — the commit the version bump produced,
 * which is the fold plus the rewritten manifests and is therefore <em>not</em> {@code mergedSha}. It
 * is what a reader needs to open the release in the code browser, and it is the sha the merge to
 * {@code main} is made from. Null on every request that has not released, and null on a release made
 * before the column existed (V13): a platform cannot invent what it did not record.
 *
 * <p><b>{@code mergedToMainAt} is the end of the lifecycle</b>, and it is the only place a reader
 * can see it: a release is a tag and {@code main} is finalized after the deployment succeeds, so a
 * RELEASED request whose {@code version} is set and whose {@code mergedToMainAt} is null is a
 * release that shipped and has not reached {@code main} yet — waiting for its deployment, or stuck
 * on a merge that will not apply. Null on every request that has not released, where there is
 * nothing to have reached {@code main}.
 *
 * <p><b>{@code unattended} says nobody is waiting on this request.</b> It is derived, never stored:
 * true where the {@code requester} is one of the platform's machine identities — a maintenance bump
 * asked for this release and stopped — and it is the difference between a REJECTED request somebody
 * is answering and one that is simply stuck with no reader. A listing that shows both as "rejected"
 * is how a repository stops moving for four hours without anybody noticing. It is true whatever the
 * state, because who asked does not change when the gate does; a caller that wants "unowned AND
 * blocked" reads it together with {@code state}. {@code gateTicketId} is the ticket filed for such a
 * rejection — null until one is, and null for ever on a request a person opened.
 *
 * <p><b>The approval fields are the SECOND gate, and every one of them is derived at the request's
 * current {@code mergedSha} and never stored on the request row.</b> {@code approvalRequired} is
 * {@code ApprovalPolicy}'s answer about the repository; {@code approvalState} is {@code
 * NOT_REQUIRED}, {@code WAITING}, {@code APPROVED} or {@code DECLINED}, a word rather than a closed
 * set like {@code state} and {@code priority} beside it, because the vocabulary may grow. Deriving
 * rather than storing is the whole point: a policy change has to reach the requests that are already
 * open — the day a second archetype starts needing a person, every PENDING request of one does, at
 * once — and a stored copy would be a second answer that went on saying {@code NOT_REQUIRED} until
 * something remembered to rewrite it. A stored copy of the <em>decision</em> would be worse still,
 * because it would have to be cleared on every re-fold, and the whole design of the approval record
 * is that the sha on it makes that unnecessary.
 *
 * <p>{@code approvedBy}, {@code approvedAt} and {@code approvalNote} are the newest decision at that
 * same sha, and they are <b>null together</b> where there is none — a repository nobody has to ask,
 * a fold nobody has judged, and a fold whose earlier decisions were all made against a sha the
 * request has moved past, which are one answer on purpose. <b>They carry whichever decision is
 * current, a decline included</b>, and they keep the approving names rather than gaining a second
 * neutral trio: {@code approvalState} already says which it was, and two sets of the same three
 * fields is how a caller comes to read one and miss the other.
 *
 * <p><b>{@code gates} is the whole set, and it is what the approval fields are one member of.</b>
 * Each entry is a gate the repository <em>configures</em> together with what that gate says about
 * this request's current fold — see {@link ReleaseGateDto}. It is derived on every read out of the
 * repository's {@code main}, the ledger and the approval table, and no part of it is a column on the
 * request row, for the approval fields' own reason one paragraph up. Three request states a reader
 * can now tell apart that did not exist before: waiting on a build, waiting on a person, and waiting
 * on nothing — the last being a repository that configured no gate, which is releasable at once and
 * is not the same as unfinished.
 *
 * <p>{@code repoName} is the repository's public name — null where it has none. It rides along
 * because a request read outside its repository's own page (the project-wide list) has nothing else
 * to name the repository with, and an opaque id is not a thing to show a person. The project list
 * resolves it live from the alias table so a rename is reflected; every other read answers the name
 * recorded when the request was made.
 */
public record ReleaseRequestDto(
    String id,
    String repoId,
    String repoName,
    String backingBranch,
    List<ReleaseRequestSourceDto> sources,
    String priority,
    String mergedSha,
    String state,
    String summary,
    String requester,
    boolean unattended,
    String gateTicketId,
    String detail,
    boolean approvalRequired,
    String approvalState,
    String approvedBy,
    Instant approvedAt,
    String approvalNote,
    List<ReleaseGateDto> gates,
    MergeConflictDto conflict,
    String version,
    String releasedSha,
    Instant mergedToMainAt,
    boolean retryable,
    Instant createdAt,
    Instant updatedAt) {}

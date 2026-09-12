package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.control.ReleaseArtifacts;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.dto.CommitFileDiffDto;
import eu.wohlben.qits.projects.dto.ReleaseArtifactsDto;
import eu.wohlben.qits.projects.dto.ReleaseRequestApprovalDto;
import eu.wohlben.qits.projects.dto.ReleaseRequestChangesDto;
import eu.wohlben.qits.projects.dto.ReleaseRequestCommitsDto;
import eu.wohlben.qits.projects.dto.ReleaseRequestDto;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projects.security.AgentAccess;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * The release requests: the asynchronous ask that replaces calling the release door blind. A
 * request is an <b>octopus merge of N sources</b> — {@code main}, the branches somebody put on it,
 * and the repository's released tags not yet merged to {@code main} — folded onto {@code
 * release/<id>}; it is created PENDING, the build gate settles it off the commit ledger against the
 * merged sha, and the execution arm performs the release once it is READY. See {@code
 * control/ReleaseRequests} for the state machine, which this controller only fronts.
 *
 * <p><b>Named sources are caller-managed; implicit ones are not.</b> A create names one branch and
 * implies {@code main}; {@code POST …/{requestId}/sources} adds more. The released tags are derived
 * from what the repository has in flight, are reported in every read, and cannot be added or dropped
 * — an API that let a caller drop one would let somebody release a step backwards from what is
 * already shipping.
 *
 * <p><b>Priority is a property of a participating branch, not of the request.</b> It is declared
 * when the branch is put on ({@code priority} on the create and the add-source bodies, {@code
 * MEDIUM} where nobody says) and re-stated afterwards through {@code …/sources/priority}; what the
 * request answers with is the max over its named branches. Nothing acts on it yet — it is carried
 * down the chain as data for the queue-ordering feature to read.
 *
 * <p><b>Four reads hang off a single request and not one of them is a column.</b> {@code …/commits}
 * is the fold's own range and {@code …/changes} (plus {@code …/changes/diff} for one file) is the
 * same release seen as a tree difference, both read out of the repository's mirror; {@code
 * …/artifacts} is what the released tag's tree declares was published. They exist because "the
 * release landed" is the beginning of a person's question rather than the end of it, and every one
 * of them answers 200 with a sentence where it cannot answer with a list — see their operations
 * below.
 *
 * <p><b>The commits and the changes share one invariant and not one implementation.</b> Both take
 * "already shipped" to be "reachable from a release tag", because {@code main} only ever advances by
 * merging released tags; the commit list can express that as N negative tips, a diff has one base
 * tree and must resolve them to a single commit. Where the two ever differ, the commit list is the
 * authority and the changes read names the base it used.
 *
 * <p><b>Two callers, two roles, on almost every route</b>: a person driving a release from a browser,
 * and the machine peers the door split brings (the maintenance bump, qits-workspaces creating
 * requests on behalf of its callers, the train's scripts). A method-level {@code @RolesAllowed}
 * <b>replaces</b> the class-level one rather than adding to it — the defect class this repository
 * watches — so both are spelled at the class and every read and every ordinary write is open to
 * both.
 *
 * <p><b>The two exceptions are {@code approve} and {@code decline}, which are {@code qits:admin}
 * alone, and the narrowing is the feature rather than a hardening.</b> {@code qits:system} can ask
 * for a release and can withdraw one — those are asks, and a machine is entitled to make them — but
 * it cannot sign off the estate. A wrapper release moves the whole platform's version, and the
 * approval gate exists precisely because a green build is not, on its own, a decision that it should
 * happen; <b>a gate a machine could satisfy is not this gate</b>. Leaving these two on the class pair
 * would have let the same bump robot that opened the request approve it, which is a gate whose
 * subject is also its judge. {@code GET /approvals} stays on the class pair: reading who decided is
 * not deciding, and a machine assembling a release report has an honest reason to ask.
 *
 * <p><b>An agent reads everything here and writes only for its own work.</b> {@code qits:agent} is
 * on the class list, so every read is open to it with no restriction. The four writes it reaches —
 * create, sources, priority and withdraw — bind a caller that holds it and neither of the other
 * two: the repository must be in the project its token names ({@code project}) and a request must
 * be one of that repository; on create and sources the branch must also be one its token may push
 * ({@code git_refs}). Anything else is a 403. Approve and decline stay {@code qits:admin}: an
 * agent never judges its own release.
 */
@Path("/repositories/{repoId}/release-requests")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:system", "qits:agent"})
public class ReleaseRequestController {

  @Inject ReleaseRequests releaseRequests;

  @Inject RepositoryService repositories;

  @Inject ReleaseArtifacts releaseArtifacts;

  @Inject SecurityIdentity identity;

  /**
   * @param branch the branch to release. {@code main} is <b>implied</b> and is never asked for — a
   *     release that does not contain what is already on main is not a release anybody wants — so a
   *     fresh request's named sources are the repository's default branch and this one. Naming the
   *     default branch itself makes a main-only request.
   * @param summary the release's summary line, which is also the fold's commit message
   * @param requester whom the caller acts for — attribution as data, for the machine peers whose
   *     bearer names the service rather than the person at the door. Blank falls back to the
   *     caller's own identity. Every caller here already holds admin or system, so stating an
   *     actor is not an escalation.
   * @param priority how urgently the named branch wants to be released — {@code LOWEST}, {@code
   *     LOW}, {@code MEDIUM}, {@code HIGH}, {@code HIGHER} or {@code BLOCKING}. Optional: absent is
   *     {@code MEDIUM}, and a word naming no priority is a 400. It is stated <b>per branch</b>, so
   *     the implied {@code main} takes the default rather than this value. On the converge arm —
   *     asking again for a branch that already participates — an absent priority leaves the stored
   *     one alone, so a re-ask never downgrades an escalation. A branch joining a wrapper's open
   *     request states its own urgency and no sibling's; the request's effective priority is the
   *     max over its sources.
   */
  public static record CreateReleaseRequest(
      @NotBlank String branch, @NotBlank String summary, String requester, String priority) {
    public record Response(ReleaseRequestDto request) {}
  }

  /**
   * <b>Creates, converges on, or joins</b> the open release request this branch participates in.
   *
   * <p>Which of the three happened is not a field on the answer and does not need to be: the
   * response is the whole {@link ReleaseRequestDto}, so a caller that kept the id it was given last
   * time can see it is the same one, and a caller that has never seen this request can see the
   * source list carrying branches it never named.
   *
   * <p><b>The wrapper case is the one worth reading about.</b> Two workspaces of one project
   * releasing on the same night are two asks about one estate, not two releases, so on a {@code
   * PROJECT}-archetype repository the second ask becomes a named source of the first's request
   * instead of minting a rival — one calver tag, one gating build, one approval, one deployment.
   * Two consequences a caller has to expect: the summary and requester they sent do <b>not</b>
   * become the request's (they reach their own source row, and the opening ask's words stand), and
   * a red gate or a decline on the shared request holds every participant at once. {@code
   * ReleaseRequests.request} is where the rule lives and argues for itself.
   */
  @POST
  @Operation(
      summary = "Ask for a branch to be released once its builds are green",
      description =
          "Creates (or converges on) the open release request the branch participates in. The"
              + " request's sources are main plus the named branch, plus every released tag of the"
              + " repository not yet merged to main; they are folded onto release/<id> and it is"
              + " that MERGE the gates evaluate — mergedSha on the answer. A branch that already"
              + " participates in an open request answers that request rather than opening a"
              + " second. On the project's WRAPPER repository convergence is per repository rather"
              + " than per branch: a branch nothing has asked about JOINS the estate's one open"
              + " request as a further source, so the answer may carry an id you did not create,"
              + " sources you did not name and somebody else's summary — the words of the ask that"
              + " opened the request stand, and yours are recorded on your own source row. Poll"
              + " until RELEASED, REJECTED, CONFLICTED or FAILED; detail says why, and conflict"
              + " says what to resolve.")
  public CreateReleaseRequest.Response create(
      @PathParam("repoId") String repoId, CreateReleaseRequest body) {
    requireAgentProject(repoId);
    requireAgentBranch(body == null ? null : body.branch());
    return new CreateReleaseRequest.Response(
        releaseRequests.request(
            repoId, body.branch(), body.summary(), actorFor(body.requester()), body.priority()));
  }

  /**
   * @param branch another branch to fold into this request.
   * @param priority how urgently that branch wants to be released, or absent for {@code MEDIUM}.
   *     Adding a branch already on the request with a priority re-states it; adding it with none
   *     leaves the stored one alone, so a retried add never downgrades an escalation.
   */
  public static record AddReleaseRequestSource(
      @NotBlank String branch, String requester, String priority) {
    public record Response(ReleaseRequestDto request) {}
  }

  @POST
  @Path("/{requestId}/sources")
  @Operation(
      summary = "Add a branch to an open release request",
      description =
          "The request is re-folded with the new source and, if the fold produces a new commit, the"
              + " gates are re-armed onto it. Idempotent: a branch already on the request answers"
              + " the request unchanged. A RELEASED or WITHDRAWN request answers 409. Implicit tag"
              + " sources are not addable — they are derived from what the repository has in"
              + " flight.")
  public AddReleaseRequestSource.Response addSource(
      @PathParam("repoId") String repoId,
      @PathParam("requestId") String requestId,
      AddReleaseRequestSource body) {
    requireAgentRequest(repoId, requestId);
    requireAgentBranch(body == null ? null : body.branch());
    return new AddReleaseRequestSource.Response(
        releaseRequests.addSource(
            requestId, body.branch(), actorFor(body.requester()), body.priority()));
  }

  /**
   * @param branch which named source to re-price — <b>in the body and not the path</b>, because a
   *     branch name contains slashes ({@code feature/checkout}) and a path segment that carried one
   *     would have to be encoded by every caller and decoded by this one.
   * @param priority the new urgency: {@code LOWEST}, {@code LOW}, {@code MEDIUM}, {@code HIGH},
   *     {@code HIGHER} or {@code BLOCKING}. Required here — this route exists to state a value, so
   *     there is no "said nothing" arm to fall back to.
   * @param requester whom the caller acts for, the same attribution-as-data the other routes take.
   */
  public static record SetReleaseSourcePriority(
      @NotBlank String branch, @NotBlank String priority, String requester) {
    public record Response(ReleaseRequestDto request) {}
  }

  @POST
  @Path("/{requestId}/sources/priority")
  @Operation(
      summary = "Re-state how urgently one of a request's branches wants to be released",
      description =
          "Priority is per participating branch and the request answers with the max over them, so"
              + " raising one branch raises the request. Nothing is re-folded and no event is"
              + " published: the same branches are folded onto the same backing branch, so the sha"
              + " the gates are evaluating has not moved. The value reaches the platform with the"
              + " release itself, which reads the sources live. A branch the request does not name"
              + " is a 404, a word naming no priority is a 400, and a RELEASED or WITHDRAWN request"
              + " is a 409. Implicit tag sources carry no priority and cannot be addressed here.")
  public SetReleaseSourcePriority.Response setSourcePriority(
      @PathParam("repoId") String repoId,
      @PathParam("requestId") String requestId,
      SetReleaseSourcePriority body) {
    requireAgentRequest(repoId, requestId);
    return new SetReleaseSourcePriority.Response(
        releaseRequests.updateSourcePriority(
            requestId, body.branch(), body.priority(), actorFor(body.requester())));
  }

  /** The stated actor, or the caller's own identity when none is stated. */
  private String actorFor(String stated) {
    if (stated != null && !stated.isBlank()) {
      return stated.trim();
    }
    return identity.isAnonymous() ? null : identity.getPrincipal().getName();
  }

  /** @param reason optional sentence recorded on the request; a default names the caller. */
  public static record WithdrawReleaseRequest(String reason) {
    public record Response(ReleaseRequestDto request) {}
  }

  @POST
  @Path("/{requestId}/withdraw")
  @Operation(
      summary = "Withdraw an open release request",
      description =
          "The ask is moot — nothing should land this branch. WITHDRAWN is terminal and frees the"
              + " branch: the next release ask mints a fresh request. A request already RELEASED or"
              + " WITHDRAWN answers 409. A deleted branch withdraws its open request"
              + " automatically; this route is the operator's spelling for every other reason.")
  public WithdrawReleaseRequest.Response withdraw(
      @PathParam("repoId") String repoId,
      @PathParam("requestId") String requestId,
      WithdrawReleaseRequest body) {
    requireAgentRequest(repoId, requestId);
    String actor = identity.isAnonymous() ? null : identity.getPrincipal().getName();
    return new WithdrawReleaseRequest.Response(
        releaseRequests.withdraw(requestId, body == null ? null : body.reason(), actor));
  }

  /**
   * @param mergedSha the fold being approved — <b>required</b>, and the whole reason this body
   *     exists rather than the route being a bare POST. A person approves content: they read what
   *     the fold brought in and said yes to <em>that</em>. A push landing between the reading and
   *     the click re-folds the request onto content they have never seen, and a door that took the
   *     sha off the row would transfer their yes to it silently. So the caller states what it is
   *     approving, and a sha that is no longer current is a 409 <b>naming the one that is</b> — so
   *     the SPA can say what changed and offer the re-read rather than reporting a conflict.
   * @param note what to put on the record, or absent for nothing. Optional here and rarely used: an
   *     approval usually says nothing, which is why only the sha is {@code @NotBlank}.
   */
  public static record ApproveReleaseRequest(@NotBlank String mergedSha, String note) {
    public record Response(ReleaseRequestDto request) {}
  }

  @POST
  @Path("/{requestId}/approve")
  @jakarta.annotation.security.RolesAllowed("qits:admin")
  @Operation(
      summary = "Sign off this request's current fold, so it may release",
      description =
          "The person's half of the second gate: where the repository's releases have to be approved"
              + " — today the project wrapper — a request that has passed every build gate still"
              + " waits for this. mergedSha is required and names the fold being approved; a stale"
              + " one answers 409 naming the fold the request is on now, because an approval is a"
              + " statement about content and a push may have landed while the page was open. The"
              + " gate is re-asked immediately, so a fold whose build is already green releases on"
              + " the click. 409 also for a request that has concluded or is already being"
              + " released, for one with no fold yet, and for a repository that needs no approval at"
              + " all — approving what has no gate is a caller error, not a no-op. qits:admin only:"
              + " a machine may ask for a release and withdraw one, and may not sign off the"
              + " estate.")
  public ApproveReleaseRequest.Response approve(
      @PathParam("repoId") String repoId,
      @PathParam("requestId") String requestId,
      ApproveReleaseRequest body) {
    return new ApproveReleaseRequest.Response(
        releaseRequests.approve(
            requestId,
            body == null ? null : body.mergedSha(),
            body == null ? null : body.note(),
            decider()));
  }

  /**
   * @param mergedSha the fold being declined, on exactly the terms {@link ApproveReleaseRequest}
   *     states — a no is a statement about content too, and refusing a fold the decider never read
   *     is as wrong as approving one.
   * @param note why. Optional, like the approval's, and this is the one that should usually be
   *     there: the sentence becomes the request's own {@code detail}, so it is what the person who
   *     has to answer the decline reads first.
   */
  public static record DeclineReleaseRequest(@NotBlank String mergedSha, String note) {
    public record Response(ReleaseRequestDto request) {}
  }

  @POST
  @Path("/{requestId}/decline")
  @jakarta.annotation.security.RolesAllowed("qits:admin")
  @Operation(
      summary = "Refuse this request's current fold, answerably",
      description =
          "The request is REJECTED carrying the decider's own sentence as its detail. This is NOT a"
              + " withdrawal and the two must not be read as degrees of the same thing: a decline"
              + " judges CONTENT and is answerable by a new fold — push a fix onto a participating"
              + " branch, the request re-folds, the decision no longer names the fold it is on, and"
              + " it is pending both gates again — while withdraw judges the ASK, is terminal, frees"
              + " the branches and makes the next release ask mint a fresh request. Same body and"
              + " same refusals as approve, mergedSha included. No unattended-gate ticket is filed:"
              + " a person just said no, so somebody is watching by definition. qits:admin only.")
  public DeclineReleaseRequest.Response decline(
      @PathParam("repoId") String repoId,
      @PathParam("requestId") String requestId,
      DeclineReleaseRequest body) {
    return new DeclineReleaseRequest.Response(
        releaseRequests.decline(
            requestId,
            body == null ? null : body.mergedSha(),
            body == null ? null : body.note(),
            decider()));
  }

  /**
   * Who is deciding. Deliberately <b>not</b> {@link #actorFor(String)}: the other routes take an
   * optional stated actor because their machine callers act on somebody's behalf and attribution is
   * data there, while a decision's actor is the gate's whole subject and a body field would let a
   * caller sign somebody else's name to it. There is no anonymous arm either — these two routes are
   * {@code qits:admin}, so an unauthenticated call is refused at the mechanism and never reaches
   * this method.
   */
  private String decider() {
    return identity.getPrincipal().getName();
  }

  public static record ListReleaseRequestApprovals() {
    public record Response(List<ReleaseRequestApprovalDto> approvals) {}
  }

  @GET
  @Path("/{requestId}/approvals")
  @Operation(
      summary = "Every decision made about this request, newest first",
      description =
          "The trail across every fold the request has ever had, superseded ones included — what was"
              + " refused, what was changed in answer to it and what was accepted in the end. Each"
              + " entry names the mergedSha it judged, which is what says which fold it was about;"
              + " compare it against the request's current mergedSha to see which entry still"
              + " counts (at most one does, the newest at that sha). Rows are never edited and never"
              + " deleted, so a change of mind is a further entry rather than a correction. An empty"
              + " list is the ordinary answer for a request nobody has decided on and for one whose"
              + " repository is not approval-gated; only an unknown request is a 404.")
  public ListReleaseRequestApprovals.Response approvals(
      @PathParam("repoId") String repoId, @PathParam("requestId") String requestId) {
    return new ListReleaseRequestApprovals.Response(releaseRequests.approvals(requestId));
  }

  public static record ListReleaseRequests() {
    public record Response(List<ReleaseRequestDto> requests) {}
  }

  /**
   * @param state which requests to answer, in the vocabulary the project-wide route uses: omitted
   *     means the open ones plus the last ten released, {@code all} means every state, and a state's
   *     own name narrows to it. A word naming no state is a 400.
   */
  @GET
  @Operation(
      summary = "This repository's release requests",
      description =
          "Newest first. With no state the answer is the open requests — everything that can still"
              + " move — plus the last 10 released, so that a release does not vanish off the page"
              + " the moment it lands. Pass state=all for the whole history (WITHDRAWN included), or"
              + " a state name (PENDING, READY, RELEASED, REJECTED, FAILED, CONFLICTED, WITHDRAWN)"
              + " to narrow to one.")
  public ListReleaseRequests.Response list(
      @PathParam("repoId") String repoId, @QueryParam("state") String state) {
    return new ListReleaseRequests.Response(releaseRequests.listByRepo(repoId, state));
  }

  public static record GetReleaseRequest() {
    public record Response(ReleaseRequestDto request) {}
  }

  @GET
  @Path("/{requestId}")
  public GetReleaseRequest.Response get(
      @PathParam("repoId") String repoId, @PathParam("requestId") String requestId) {
    return new GetReleaseRequest.Response(releaseRequests.get(requestId));
  }

  @GET
  @Path("/{requestId}/commits")
  @Operation(
      summary = "The commits this request's fold brought in",
      description =
          "The fold minus every release tag that does not contain it — main only ever advances by"
              + " merging released tags, so what is left is exactly what the request's sources"
              + " contributed over what was already shipped. It stays the same answer after the"
              + " release reaches main, which neither mergedSha^1 nor a live read of main does. The"
              + " version"
              + " bump is not in the list: the release commits the rewritten manifests ON TOP of the"
              + " fold. An empty list is never an error — detail says whether nothing has been"
              + " folded yet, the fold is no longer in the repository's history, or the fold"
              + " genuinely added nothing.")
  public ReleaseRequestCommitsDto commits(
      @PathParam("repoId") String repoId, @PathParam("requestId") String requestId) {
    return releaseRequests.mergedCommits(repoId, requestId);
  }

  @GET
  @Path("/{requestId}/changes")
  @Operation(
      summary = "The files this request's fold changed",
      description =
          "Diffed against the newest release tag that does not contain the fold, resolved to one"
              + " commit with merge-base — never mergedSha^1 (a re-fold's first parent is the"
              + " previous fold, and a fast-forwarded fold is no merge commit at all) and never"
              + " merge-base(mergedSha, main), which reports nothing once the release reaches main."
              + " base and baseTag name what was used; a repository that has never released is"
              + " diffed against the empty tree. An empty list is never an error — detail says"
              + " whether nothing has been folded yet, the fold is no longer in the repository's"
              + " history, or the fold changed nothing. Over 2000 paths the answer is the first 2000"
              + " with truncated set and the total in detail.")
  public ReleaseRequestChangesDto changes(
      @PathParam("repoId") String repoId, @PathParam("requestId") String requestId) {
    return releaseRequests.foldChanges(repoId, requestId);
  }

  @GET
  @Path("/{requestId}/changes/diff")
  @Operation(
      summary = "The patch of one file in this request's fold",
      description =
          "The unified diff of path, against the same base …/changes lists: the newest release tag"
              + " that does not contain the fold, resolved with merge-base, or the empty tree for a"
              + " repository that has never released. An empty diff is never an error — a binary"
              + " file, a pure rename, a fold that is not there any more and a patch over ~1 MiB all"
              + " answer their change type with no text.")
  public CommitFileDiffDto changeDiff(
      @PathParam("repoId") String repoId,
      @PathParam("requestId") String requestId,
      @QueryParam("path") @NotBlank String path) {
    return releaseRequests.foldFileDiff(repoId, requestId, path);
  }

  @GET
  @Path("/{requestId}/artifacts")
  @Operation(
      summary = "What this release published, and whether anything deploys it",
      description =
          "Read out of the released tag's own tree: deployable is whether it declares"
              + " .config/qits/deployments.yml, and artifacts is what its release recipe declares"
              + " plus the userflow bundle its QA pipeline publishes (at the fold's sha, not at the"
              + " version). A request that has not released answers 200 with version null and a"
              + " detail saying so; a git host that cannot be asked and a recipe that will not parse"
              + " do the same. A repository that declares no recipe published nothing, and says so"
              + " with an empty list and no detail at all.")
  public ReleaseArtifactsDto artifacts(
      @PathParam("repoId") String repoId, @PathParam("requestId") String requestId) {
    return releaseArtifacts.of(repoId, requestId);
  }

  // ---- what an agent may reach ---------------------------------------------------------------

  /** A caller holding one of these is judged as before, even if it also holds the agent role. */
  private static final String[] WIDER = {AgentAccess.ADMIN_ROLE, AgentAccess.SYSTEM_ROLE};

  /** A bound agent reaches only the repositories of its own project. */
  private void requireAgentProject(String repoId) {
    if (!AgentAccess.isBoundAgent(identity, WIDER)) {
      return;
    }
    Repository repository = repositories.get(repoId); // 404 if absent
    String projectId = repository.project == null ? null : repository.project.id;
    if (!AgentAccess.coversProject(identity, projectId)) {
      throw new DomainException(
          403, "An agent may reach only the release requests of its own project.");
    }
  }

  /** A bound agent reaches only the requests of that repository. */
  private void requireAgentRequest(String repoId, String requestId) {
    if (!AgentAccess.isBoundAgent(identity, WIDER)) {
      return;
    }
    requireAgentProject(repoId);
    if (!repoId.equals(releaseRequests.get(requestId).repoId())) {
      throw new DomainException(
          403, "Release request " + requestId + " is not a request of repository " + repoId + ".");
    }
  }

  /** A bound agent may put on a request only a branch its token may push. */
  private void requireAgentBranch(String branch) {
    if (AgentAccess.isBoundAgent(identity, WIDER) && !AgentAccess.coversBranch(identity, branch)) {
      throw new DomainException(
          403, "An agent may ask to release only a branch in its git_refs; " + branch + " is not.");
    }
  }
}

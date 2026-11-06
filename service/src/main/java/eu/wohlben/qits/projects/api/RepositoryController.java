package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.control.BackupPushService;
import eu.wohlben.qits.projects.control.BuildStatusLedger;
import eu.wohlben.qits.projects.control.CommitService;
import eu.wohlben.qits.projects.control.TechnicalProcessRegistry;
import eu.wohlben.qits.projects.control.RepositoryArchive;
import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.control.WrapperReconcileService;
import eu.wohlben.qits.projects.dto.BranchDto;
import eu.wohlben.qits.projects.dto.CommitBuildStatusDto;
import eu.wohlben.qits.projects.dto.CommitChangesDto;
import eu.wohlben.qits.projects.dto.CommitFileDiffDto;
import eu.wohlben.qits.projects.dto.CommitLogDto;
import eu.wohlben.qits.projects.dto.RepositoryArchiveDto;
import eu.wohlben.qits.projects.dto.RepositoryBackupResetDto;
import eu.wohlben.qits.projects.dto.RepositoryCoordinatesDto;
import eu.wohlben.qits.projects.dto.RepositoryDto;
import eu.wohlben.qits.projects.dto.SyncStatusDto;
import eu.wohlben.qits.projects.entitieshost.CommitSubjectCompliance;
import eu.wohlben.qits.projects.entitieshost.CommitSubjectsDto;
import eu.wohlben.qits.projects.mapper.RepositoryMapper;
import eu.wohlben.qits.projects.security.AgentAccess;
import eu.wohlben.qits.projects.security.PersonCheck;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.resteasy.reactive.ResponseStatus;

@Path("/repositories")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin"})
public class RepositoryController {

  @Inject RepositoryService repositoryService;

  /** Each catalogue row's wrapper membership — see {@link #list}. */
  @Inject WrapperReconcileService wrapperReconcileService;

  /** The debounced backup runner both trigger routes hand off to. */
  @Inject BackupPushService backupPushService;

  @Inject CommitService commitService;

  /** The commit-subject compliance read (qits-302). */
  @Inject CommitSubjectCompliance commitSubjectCompliance;

  /** The per-commit build-status ledger the bus keeps — see {@code bus/BuildStatusListener}. */
  @Inject BuildStatusLedger buildStatusLedger;

  @Inject RepositoryMapper repositoryMapper;

  /** The archive action (qits-1784) — see {@link #archive}. */
  @Inject RepositoryArchive repositoryArchive;

  @Inject SecurityIdentity identity;

  /** A verified person, or the project's own delivery desk — the backup reset's proof. */
  @Inject PersonCheck persons;

  /**
   * The project's delivery desk ({@code qits:desk}, qits-1596) holds the coordinator's repository
   * writes that move no git ref — the rename and the main-branch setting, both rows of this service
   * — bound to its token's {@code project}: the repository is read first, so an unknown id is still
   * a 404, then another project's is a 403. Every other caller passes untouched. Everything here
   * that writes or deletes a ref stays off the desk, which never commits or pushes: the branch
   * delete, the repository delete (its git-host bare and its wrapper entry), pull, push, sync and
   * the backup push.
   */
  private void requireDeskProject(String repoId) {
    if (AgentAccess.isDesk(identity)) {
      var repository = repositoryService.get(repoId); // 404 for an unknown repository
      AgentAccess.requireDeskProject(
          identity, repository.project == null ? null : repository.project.id);
    }
  }

  /** SEAM: the technical-process framework is an optional port here (see the interface). */
  @Inject Instance<TechnicalProcessRegistry> technicalProcesses;

  public static record ListRepositoriesRequest() {
    /**
     * @param repositories every repository this service holds, sorted by project then name. A row
     *     with no registered name is present with a null {@code name}, and one nobody has stated a
     *     kind for with a null {@code archetype}; {@code declared} false is a row its
     *     project's wrapper no longer names — see {@link RepositoryCoordinatesDto}.
     */
    public record Response(List<RepositoryCoordinatesDto> repositories) {}
  }

  /**
   * The flat catalogue: every repository with the coordinates that address it publicly.
   *
   * <p><b>Two callers, two roles</b>, the same pair the by-id read below serves. qits-ci's trigger
   * engine reads it with its machine identity — it is the replacement for walking the git host's
   * internal {@code GET /git}, which answers opaque storage ids — and a browser session reads it
   * too. A method-level {@code @RolesAllowed} <b>replaces</b> the class-level {@code qits:admin}, so
   * naming only {@code qits:system} here would be a 403 for every browser.
   *
   * <p>This is a collection GET on the controller's own path; {@code /repositories/{repoId}} below
   * is a different template and neither shadows the other.
   *
   * <p>A read that fails is a 5xx and never an empty list: "no repositories" is an answer a caller
   * acts on. {@code RepositoryService#listCoordinates} holds it through a postgres cutover for the
   * same reason.
   *
   * <p><b>The answer carries each repository's archetype</b>, which is what makes one read enough
   * for a consumer keyed on the kind — qits-maintenance's release-train reader. It is an added
   * optional field and nothing had to change to keep reading this route: an existing client ignores
   * the key, and a null archetype is a row nobody has stated a kind for.
   *
   * <p><b>And whether the project's wrapper declares it</b> (qits-1695), so qits-maintenance can
   * stop bumping a repository its wrapper dropped. It is {@code listProjectRepositories}' {@code
   * declared}, from the same rule ({@code WrapperReconcileService.declares}), with each project's
   * wrapper read once per call.
   */
  @GET
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:system", "qits:agent"})
  @Operation(
      operationId = "listRepositories",
      summary = "Every repository with its public coordinates",
      description =
          "The machine-readable catalogue: row id, project, addressable name, main branch,"
              + " archetype and wrapper membership, sorted by project then name. A repository that"
              + " owns no name is listed with a null name; one nobody has stated a kind for (at"
              + " creation or in its own .config/qits/repository.yml) carries a null archetype. declared is false when no entry of the project's wrapper .gitmodules"
              + " names this repository, so it is no longer part of its project — the same reading"
              + " as declared on listProjectRepositories, and true for the wrapper itself, for"
              + " archetypes that are not components of their project, and for a project whose"
              + " wrapper declares nothing.")
  public ListRepositoriesRequest.Response list() {
    return new ListRepositoriesRequest.Response(
        repositoryService.listCoordinates(wrapperReconcileService::view));
  }

  public static record GetRepositoryRequest() {
    public record Response(RepositoryDto repository) {}
  }

  /**
   * One repository by id — <b>two callers, two roles</b>, the same pair {@code
   * ProjectController#listRepositories} serves. qits-workspaces reads it with its machine identity
   * ({@code RepositoryLookup}, which is what tells a release which repository it is releasing), and
   * the workspaces detail screen reads it through a browser session for the repository's main
   * branch — the deep link carries no project id, so this by-id read is the only way in.
   *
   * <p>A method-level {@code @RolesAllowed} <b>replaces</b> the class-level {@code qits:admin}
   * rather than adding to it. {@code qits:system} alone was therefore a refusal of every browser
   * that reached this route.
   */
  @GET
  @Path("/{repoId}")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:system", "qits:agent"})
  @Operation(operationId = "getRepository")
  public GetRepositoryRequest.Response get(@PathParam("repoId") String repoId) {
    var repo = repositoryService.get(repoId);
    return new GetRepositoryRequest.Response(repositoryMapper.toDto(repo));
  }

  public static record ListBranchesRequest() {
    public record Response(List<BranchDto> branches) {}
  }

  @GET
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:agent"})
  @Path("/{repoId}/branches")
  @Operation(operationId = "listRepositoryBranches", summary = "The repository's branches, each with its parent, how far it is ahead and behind, and whether it can be cleaned up")
  public ListBranchesRequest.Response branches(@PathParam("repoId") String repoId) {
    return new ListBranchesRequest.Response(repositoryService.listBranchesWithCleanup(repoId));
  }

  @GET
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:agent"})
  @Path("/{repoId}/commits")
  @Operation(operationId = "listBranchCommits", summary = "The commits on a branch that are not on its parent, newest first")
  public CommitLogDto commits(
      @PathParam("repoId") String repoId, @QueryParam("branch") @NotBlank String branch) {
    return commitService.listCommits(repoId, branch);
  }

  @GET
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:agent"})
  @Path("/{repoId}/commits/{commitHash}/changes")
  @Operation(operationId = "listCommitChanges", summary = "The files one commit changed, against its first parent or a named parent")
  public CommitChangesDto commitChanges(
      @PathParam("repoId") String repoId,
      @PathParam("commitHash") String commitHash,
      @QueryParam("parent") String parent) {
    return commitService.listCommitChanges(repoId, commitHash, parent);
  }

  @GET
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:agent"})
  @Path("/{repoId}/commits/{commitHash}/diff")
  @Operation(operationId = "getCommitFileDiff", summary = "The patch of one file in one commit, against its first parent or a named parent")
  public CommitFileDiffDto commitFileDiff(
      @PathParam("repoId") String repoId,
      @PathParam("commitHash") String commitHash,
      @QueryParam("parent") String parent,
      @QueryParam("path") @NotBlank String path) {
    return commitService.commitFileDiff(repoId, commitHash, parent, path);
  }

  /**
   * <b>How a branch's newest commits fit the commit-message variants</b> (qits-302, qits-706): each
   * commit is counted under the first of MERGE, BUMP, RELEASE, PLATFORM and SUBJECT ({@code
   * term(<project>-<n>): message}) it fits, and those fitting none are listed — the commits
   * qits-githost now refuses on every repository. History from before that rule ordinarily lists
   * there. See {@link CommitSubjectCompliance}.
   *
   * <p>A read, so the three readers every repository read admits — a person, an agent, a platform
   * service. A method-level {@code @RolesAllowed} replaces the class-level {@code qits:admin}.
   */
  @GET
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:agent", "qits:system"})
  @Path("/{repoId}/commit-subjects")
  @Operation(
      operationId = "measureCommitSubjects",
      summary = "How a branch's newest commits fit the commit-message variants",
      description =
          "Of the newest `limit` commits on `branch` (default: the repository's main branch; limit"
              + " default 100, at most 1000), how many fit each commit-message variant the git host"
              + " accepts — the first that fits, in order: MERGE (two or more parents, any"
              + " message), BUMP (`bump(<group>): <n> dependencies`), RELEASE (`release(<version>):"
              + " …`), PLATFORM (`chore(platform): …`) and SUBJECT (`term(<project>-<n>): message`,"
              + " several comma-separated ids allowed). Only the message's first line counts."
              + " Lists every commit fitting none: the git host refuses such a commit on every"
              + " repository, so commits from before that rule ordinarily appear here, which is"
              + " expected.")
  public CommitSubjectsDto commitSubjects(
      @PathParam("repoId") String repoId,
      @QueryParam("branch") String branch,
      @QueryParam("limit") Integer limit) {
    return commitSubjectCompliance.measure(repoId, branch, limit);
  }

  public static record ListCommitBuildsRequest() {
    /**
     * @param builds every CI run's terminal verdict about this commit, newest first, from the
     *     ledger the bus keeps. Empty means "no verdict recorded", which covers both a commit
     *     nothing built and a run still queued or running — only terminal runs announce.
     */
    public record Response(List<CommitBuildStatusDto> builds) {}
  }

  /**
   * The per-commit build-status ledger's read — <b>two callers, two roles</b>: a browser session
   * reading a commit's verdicts, and the machine peers the release-quality-gates work brings (the
   * release flow asking "is this sha green"). A method-level {@code @RolesAllowed} <b>replaces</b>
   * the class-level {@code qits:admin}, so both are spelled.
   */
  @GET
  @Path("/{repoId}/commits/{commitHash}/builds")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:system", "qits:agent"})
  @Operation(
      operationId = "listCommitBuilds",
      summary = "Every CI verdict recorded for one commit",
      description =
          "One entry per terminal CI run of this commit, newest first, fed from qits-ci's build"
              + " events. Queued and running builds do not appear; an empty list means no verdict"
              + " yet, never that the commit is fine.")
  public ListCommitBuildsRequest.Response commitBuilds(
      @PathParam("repoId") String repoId, @PathParam("commitHash") String commitHash) {
    return new ListCommitBuildsRequest.Response(buildStatusLedger.verdictsOf(repoId, commitHash));
  }

  // SEAM (migration-plan.md §6, repository <-> workspace). Two routes stood here —
  // POST /repositories/{repoId}/branches/merge and .../branches/cleanup — and both were thin
  // forwards to WorkspaceService.mergeBranch / cleanupBranch, which is WS_REPO (qits-workspaces').
  // Branch integration is a workspace operation exposed on the repository path; it is cut rather
  // than ported, because a port whose whole body is "call the other context's service" is just a
  // dependency with extra steps.
  //
  // They are now owned: qits-workspaces serves both from its own BranchController, over the
  // WorkspaceService methods they always called — under its own segment, not this one.

  public static record DeleteBranchRequest() {
    public record Response(boolean success) {}
  }

  @DELETE
  @Path("/{repoId}/branches")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent"})
  public DeleteBranchRequest.Response deleteBranch(
      @PathParam("repoId") String repoId, @QueryParam("branch") @NotBlank String branch) {
    repositoryService.deleteBranch(repoId, branch);
    return new DeleteBranchRequest.Response(true);
  }

  public static record DeleteRepositoryRequest() {
    public record Response(boolean success) {}
  }

  @DELETE
  @Path("/{repoId}")
  @Operation(
      summary = "Delete a repository, on the git host as well",
      description =
          "Deletes the row, the repository's workspaces, its local mirror, its entry in the"
              + " project's wrapper if it is still there, and the repository itself on"
              + " qits-githost. The history is gone: there is no tombstone and no retention, and"
              + " a git host that fails the delete fails the whole request, leaving the row. The"
              + " project's wrapper repository is refused — delete the project instead.")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent"})
  public DeleteRepositoryRequest.Response delete(@PathParam("repoId") String repoId) {
    repositoryService.delete(repoId);
    return new DeleteRepositoryRequest.Response(true);
  }

  public static record PullRepositoryRequest() {
    public record Response(String technicalProcessId) {}
  }

  @POST
  @Path("/{repoId}/pull")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent"})
  public PullRepositoryRequest.Response pull(@PathParam("repoId") String repoId) {
    String technicalProcessId = repositoryService.beginPullRepository(repoId);
    return new PullRepositoryRequest.Response(technicalProcessId);
  }

  public static record PushRepositoryRequest() {
    public record Response(String technicalProcessId) {}
  }

  @POST
  @Path("/{repoId}/push")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent"})
  public PushRepositoryRequest.Response push(@PathParam("repoId") String repoId) {
    String technicalProcessId = repositoryService.beginPushRepository(repoId);
    return new PushRepositoryRequest.Response(technicalProcessId);
  }

  public static record SyncRepositoryRequest() {
    public record Response(String technicalProcessId) {}
  }

  @POST
  @Path("/{repoId}/sync")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent"})
  public SyncRepositoryRequest.Response sync(@PathParam("repoId") String repoId) {
    String technicalProcessId = repositoryService.beginSyncRepository(repoId);
    return new SyncRepositoryRequest.Response(technicalProcessId);
  }

  public static record BackupSyncRequest() {
    /**
     * @param scheduled always true — the run is queued, not finished. What it came to lands on the
     *     repository's {@code lastBackup}, which is what a client polls or refetches.
     */
    public record Response(String repositoryId, boolean scheduled) {}
  }

  /**
   * Ask for this repository to be backed up to its forge twin now, rather than waiting for its next
   * push or the hourly sweep — the button beside a red backup status.
   *
   * <p>202 and not 200: the answer is "queued", and the run itself is debounced per repository, so
   * an impatient second click folds into the first rather than starting a second push against the
   * same mirror. A repository with no twin is accepted and does nothing, for the same reason the
   * git host's intake accepts one: the caller cannot act on that distinction.
   */
  @POST
  @Path("/{repoId}/backup-sync")
  @ResponseStatus(202)
  @Operation(
      summary = "Back this repository up to its forge twin now",
      description =
          "Schedules the same backup the git host's post-receive triggers, debounced per repository."
              + " The outcome lands on the repository's lastBackup rather than in this response.")
  @APIResponse(
      responseCode = "202",
      description = "Queued",
      content =
          @Content(schema = @Schema(implementation = BackupSyncRequest.Response.class)))
  @APIResponse(responseCode = "404", description = "No such repository")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent"})
  public BackupSyncRequest.Response backupSync(@PathParam("repoId") String repoId) {
    repositoryService.get(repoId); // 404 for an unknown id, like every other repository route
    backupPushService.onPush(repoId);
    return new BackupSyncRequest.Response(repoId, true);
  }

  /** @param reason why the twin's main may be replaced; required, recorded with the reset */
  @Schema(
      name = "RepositoryBackupResetRequest",
      description = "Why the backup twin's main may be replaced.")
  public static record BackupResetRequest(String reason) {}

  /**
   * <b>Reset this repository's backup twin when its main holds unrelated history</b> (qits-1885).
   *
   * <p>The backup never forces, so a twin seeded by its forge with a commit of its own — a README
   * nobody on the platform wrote — refuses every backup as non-fast-forward, for good. This door is
   * the one way out, and it is deliberately narrow: it fast-forwards a twin that is merely behind,
   * force-updates main under a lease only when the two histories share no commit at all, and
   * refuses a twin that is ahead or diverged from a common base, which holds work the platform does
   * not have. See {@code RepositoryService#resetBackupTwin}.
   *
   * <p><b>A person or the project's own delivery desk</b> ({@link PersonCheck#requireAdminOrDesk}),
   * the upstream override's roles: the decision is "the forge copy's main may be discarded", which
   * a machine must not make. Plain agents, CI and service clients answer 403; so does another
   * project's desk. The repository is read first, so an unknown id is a 404.
   */
  @POST
  @Path("/{repoId}/backup-reset")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:desk"})
  @Operation(
      operationId = "backupResetRepository",
      summary = "Reset this repository's backup twin whose main holds unrelated history",
      description =
          "Probes the backup twin's main branch (the repository's url) and pushes ours onto it,"
              + " under the repository's backup lock. A twin with no main, at ours, or behind ours is"
              + " fast-forwarded with the backup's own push (kind FAST_FORWARD, no force). A twin"
              + " whose main shares no commit with ours (a forge's seed commit) has its main"
              + " force-updated with a lease on the sha probed, tags pushed without force (kind"
              + " UNRELATED_RESET); only main is ever forced. A twin ahead of ours or diverged from"
              + " a common base is refused with 409 naming both shas, and nothing is pushed. On"
              + " success the backup outcome reads SUCCEEDED and the reset is recorded with who"
              + " decided and why (GET .../backup-resets). Only a person — qits:admin, verified by"
              + " this service from a browser session or a person's qits CLI token — or the"
              + " project's delivery desk (its own delivery token, in its own project; recorded"
              + " under its token subject). Asserted identity headers, agents, CI, service clients"
              + " and another project's desk answer 403.")
  @APIResponse(
      responseCode = "200",
      description = "The twin's main is ours now",
      content = @Content(schema = @Schema(implementation = RepositoryBackupResetDto.class)))
  @APIResponse(responseCode = "400", description = "A missing or blank reason")
  @APIResponse(
      responseCode = "403",
      description = "Neither a verified person nor this project's delivery desk")
  @APIResponse(responseCode = "404", description = "No such repository")
  @APIResponse(
      responseCode = "409",
      description =
          "No backup twin; the git host holds no main; the twin is ahead or diverged (nothing"
              + " pushed); or the twin moved between the probe and the push")
  @APIResponse(responseCode = "500", description = "The twin or the git host could not be read")
  public RepositoryBackupResetDto backupReset(
      @PathParam("repoId") String repoId, BackupResetRequest body) {
    var repository = repositoryService.get(repoId); // 404 for an unknown repository
    String actor =
        persons.requireAdminOrDesk(repository.project == null ? null : repository.project.id);
    return backupPushService.resetTwin(repoId, body == null ? null : body.reason(), actor);
  }

  public static record ListBackupResetsRequest() {
    /** @param resets the repository's backup resets, newest first */
    @Schema(
        name = "RepositoryBackupResets",
        description = "A repository's backup twin resets, newest first.")
    public record Response(List<RepositoryBackupResetDto> resets) {}
  }

  /** The repository's backup resets, newest first — a read, so every repository reader's roles. */
  @GET
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:agent"})
  @Path("/{repoId}/backup-resets")
  @Operation(
      operationId = "listRepositoryBackupResets",
      summary = "The resets of this repository's backup twin, newest first",
      description =
          "Every POST .../backup-reset that pushed: its kind, the twin's main before"
              + " (twinShaBefore, null when it had none) and after (pushedSha), who decided, when and"
              + " why. A refused reset is not listed.")
  @APIResponse(
      responseCode = "200",
      description = "The resets",
      content = @Content(schema = @Schema(implementation = ListBackupResetsRequest.Response.class)))
  @APIResponse(responseCode = "404", description = "No such repository")
  public ListBackupResetsRequest.Response backupResets(@PathParam("repoId") String repoId) {
    return new ListBackupResetsRequest.Response(repositoryService.backupResets(repoId));
  }

  @GET
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:agent"})
  @Path("/{repoId}/sync-status")
  public SyncStatusDto syncStatus(@PathParam("repoId") String repoId) {
    return repositoryService.syncStatus(repoId);
  }

  public static record ActiveProcessRequest() {
    /**
     * The repository's currently-running technical process (pull/sync/push), or null when none is
     * live.
     */
    public record Response(String technicalProcessId) {}
  }

  /**
   * The id of the repository's live pull/sync process, if any — the reattach discovery endpoint for
   * the repository detail route (a reload / second tab reopens the streamed log from it). Returns
   * null when the repository exists but no process is live; an unknown/deleted repo is a 404 like
   * the sibling repository GETs, so a stale tab surfaces the gone repository rather than a silent
   * "none". Activeness changes ride the repository {@code PROCESS} SSE hint.
   */
  @GET
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:agent"})
  @Path("/{repoId}/active-process")
  public ActiveProcessRequest.Response activeProcess(@PathParam("repoId") String repoId) {
    repositoryService.get(repoId); // 404 on an unknown/deleted repository
    return new ActiveProcessRequest.Response(
        technicalProcesses.isUnsatisfied()
            ? null
            : technicalProcesses.get().activeForRepository(repoId).orElse(null));
  }

  /**
   * @param name the new project-scoped addressable name — what {@code /git/<projectId>/<name>} will
   *     serve and what a committed {@code ../<name>.git} will resolve to. Same shape rule as
   *     creation: 1-64 characters of letters, digits and inner dashes.
   */
  public static record RenameRepositoryRequest(@NotBlank String name) {
    /**
     * @param previousName what it answered to before — null for a row that answered to nothing, and
     *     equal to the repository's current name when the rename was a no-op
     * @param changed false when the repository already answered to exactly this name and nothing
     *     was written or announced
     */
    public record Response(RepositoryDto repository, String previousName, boolean changed) {}
  }

  /**
   * Renames a repository — the only operation on this platform that changes a repository's public
   * identity, and the phase-2 prerequisite the rename effort is ordered after.
   *
   * <p><b>PATCH and not PUT</b>, because the body is one field of the repository rather than a
   * replacement for it: {@code PUT /{repoId}/main-branch} is a sub-resource whose whole state is the
   * branch, and there is no such sub-resource for a name — the name is the row's own coordinate, so
   * the verb belongs on the row.
   *
   * <p><b>Nothing is asked of the git host.</b> A bare is keyed by the row's opaque id, so the same
   * bare answers under the new name the moment this returns; see {@code RepositoryService#rename}
   * for what that costs and what it does not.
   */
  @jakarta.ws.rs.PATCH
  @Path("/{repoId}")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:desk"})
  @Operation(
      summary = "Rename a repository",
      description =
          "Gives the repository a new project-scoped addressable name. Nothing moves on the git"
              + " host — a bare is keyed by the repository's id — so /git/<project>/<newName>"
              + " serves it immediately and the old name stops resolving. The archetype is left"
              + " as it was: a name decides nothing about the kind. The"
              + " project's wrapper is NOT rewritten: update the .gitmodules entry to the new name"
              + " and push, or the repository reads as UNDECLARED until you do. The backup twin"
              + " self-heals from the wrapper on the next reconcile.")
  @APIResponse(responseCode = "200", description = "The repository answers to the new name")
  @APIResponse(
      responseCode = "400",
      description =
          "An invalid name, a name another repository in this project already answers to, or the"
              + " project's wrapper repository (whose name is derived from the immutable slug)")
  @APIResponse(
      responseCode = "403",
      description = "The delivery desk naming another project's repository")
  @APIResponse(responseCode = "404", description = "No such repository")
  public RenameRepositoryRequest.Response rename(
      @PathParam("repoId") String repoId, @Valid RenameRepositoryRequest request) {
    requireDeskProject(repoId);
    var renamed = repositoryService.rename(repoId, request.name());
    // Re-read rather than mapping what the rename handed back: that write owns its own transaction,
    // so the row it saw is detached by now and the mapper follows a relation off it.
    return new RenameRepositoryRequest.Response(
        repositoryMapper.toDto(repositoryService.get(repoId)),
        renamed.previousName(),
        renamed.changed());
  }

  public static record SetMainBranchRequest(@NotBlank String branch) {
    public record Response(RepositoryDto repository) {}
  }

  @PUT
  @Path("/{repoId}/main-branch")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:desk"})
  public SetMainBranchRequest.Response setMainBranch(
      @PathParam("repoId") String repoId, @Valid SetMainBranchRequest request) {
    requireDeskProject(repoId);
    var repo = repositoryService.setMainBranch(repoId, request.branch());
    return new SetMainBranchRequest.Response(repositoryMapper.toDto(repo));
  }

  private static final String ARCHIVE_DESCRIPTION =
      " Never a push to main: the edit is committed onto a branch and released through the"
          + " repository's release request. Reads .config/qits/repository.yml at main; when it"
          + " already says what was asked the answer is 200 with outcome unchanged and nothing is"
          + " opened. Otherwise the file is edited as text, comments and every other key kept, and"
          + " the edit is refused with 422 (error ARCHIVE_EDIT_REFUSED, nothing written) when the"
          + " result would change anything but repository.archived or the file cannot be edited"
          + " safely (a flow-style repository: {...}, a document that does not parse). The branch is"
          + " created at main (or an existing one brought up to date with it), the edit committed"
          + " onto it as chore(platform): <verb> <repository name>, and the branch asked for"
          + " exactly as POST .../release-requests asks: it creates or JOINS the repository's open"
          + " request. 202 with outcome requested, the branch, the commit and the release request's"
          + " id and qualifiedId. The delivery desk (qits:desk) acts in its own project only.";

  /**
   * <b>Archive a repository</b> (qits-1784): ask for {@code repository.archived: true} in its {@code
   * .config/qits/repository.yml}, as a commit on {@code archive/<name>} released through the
   * repository's release request — the normal flow, never a push to main. qits-maintenance then
   * gives the repository no dependency bumps unless its {@code maintenance.yml} asks for them.
   * {@code RepositoryArchive} is the whole of it.
   *
   * <p>The desk holds it in its own project, as it holds the rename: the action writes no ref of
   * its own making outside the normal flow, and the release it asks for is gated like any other.
   */
  @POST
  @Path("/{repoId}/archive")
  // No body: a bodiless POST carries no Content-Type, which the class-level JSON would 415.
  @Consumes(MediaType.WILDCARD)
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:desk"})
  @Operation(
      operationId = "archiveRepository",
      summary = "Archive a repository through its release request",
      description =
          "Asks for repository.archived: true in the repository's .config/qits/repository.yml,"
              + " on the branch archive/<repository name>; an absent file is created."
              + ARCHIVE_DESCRIPTION)
  @APIResponse(
      responseCode = "202",
      description = "The edit is committed on the branch and the branch is on a release request",
      content = @Content(schema = @Schema(implementation = RepositoryArchiveDto.class)))
  @APIResponse(
      responseCode = "200",
      description = "main already says repository.archived: true; nothing was opened",
      content = @Content(schema = @Schema(implementation = RepositoryArchiveDto.class)))
  @APIResponse(
      responseCode = "403",
      description = "The delivery desk naming another project's repository")
  @APIResponse(responseCode = "404", description = "No such repository")
  @APIResponse(
      responseCode = "409",
      description =
          "The repository has no name, the existing branch conflicts with main, or the branch"
              + " moved during the write")
  @APIResponse(
      responseCode = "422",
      description = "The file cannot be edited safely; nothing was written")
  @APIResponse(responseCode = "502", description = "The git host could not be asked")
  @APIResponse(responseCode = "503", description = "No git host address or credential")
  public jakarta.ws.rs.core.Response archive(@PathParam("repoId") String repoId) {
    return archived(repoId, true);
  }

  /**
   * <b>Unarchive a repository</b> (qits-1784): {@link #archive}'s mirror — removes {@code
   * repository.archived} on {@code unarchive/<name>}. A file that does not say {@code archived: true}
   * is already unarchived and answers 200 unchanged.
   */
  @POST
  @Path("/{repoId}/unarchive")
  // No body: a bodiless POST carries no Content-Type, which the class-level JSON would 415.
  @Consumes(MediaType.WILDCARD)
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:desk"})
  @Operation(
      operationId = "unarchiveRepository",
      summary = "Unarchive a repository through its release request",
      description =
          "Asks for repository.archived to be removed from the repository's"
              + " .config/qits/repository.yml, on the branch unarchive/<repository name>; a file"
              + " that is absent or does not say archived: true is already unarchived."
              + ARCHIVE_DESCRIPTION)
  @APIResponse(
      responseCode = "202",
      description = "The edit is committed on the branch and the branch is on a release request",
      content = @Content(schema = @Schema(implementation = RepositoryArchiveDto.class)))
  @APIResponse(
      responseCode = "200",
      description = "main does not say repository.archived: true; nothing was opened",
      content = @Content(schema = @Schema(implementation = RepositoryArchiveDto.class)))
  @APIResponse(
      responseCode = "403",
      description = "The delivery desk naming another project's repository")
  @APIResponse(responseCode = "404", description = "No such repository")
  @APIResponse(
      responseCode = "409",
      description =
          "The repository has no name, the existing branch conflicts with main, or the branch"
              + " moved during the write")
  @APIResponse(
      responseCode = "422",
      description = "The file cannot be edited safely; nothing was written")
  @APIResponse(responseCode = "502", description = "The git host could not be asked")
  @APIResponse(responseCode = "503", description = "No git host address or credential")
  public jakarta.ws.rs.core.Response unarchive(@PathParam("repoId") String repoId) {
    return archived(repoId, false);
  }

  private jakarta.ws.rs.core.Response archived(String repoId, boolean archive) {
    requireDeskProject(repoId);
    RepositoryArchiveDto answer =
        repositoryArchive.apply(
            repoId, archive, identity.isAnonymous() ? null : identity.getPrincipal().getName());
    int status = RepositoryArchiveDto.UNCHANGED.equals(answer.outcome()) ? 200 : 202;
    return jakarta.ws.rs.core.Response.status(status)
        .entity(answer)
        .type(MediaType.APPLICATION_JSON)
        .build();
  }
}

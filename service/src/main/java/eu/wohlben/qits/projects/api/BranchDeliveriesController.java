package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.control.BranchDeliveries;
import eu.wohlben.qits.projects.entitieshost.EntityIdResolver;
import eu.wohlben.qits.projects.entity.BranchDelivery;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * <b>Where a branch stands on its way to production</b> (qits-1720): the {@code branch_delivery}
 * rows {@code BranchDeliveryChanged} is published from (qits-1719), read back by branch. The
 * question an agent asks before it waits — "is my branch released yet, and in which request" —
 * answered without knowing a request id, which is the whole point of keying the ledger by branch.
 *
 * <p>The roles match {@link ProjectReleaseRequestsController}'s, {@code qits:agent} included: the
 * branch's own agent is the commonest reader, and what this tells it is no more than the release
 * requests it reads already.
 *
 * <p><b>A branch nothing has happened to answers 200</b>, with no deliveries and {@code everywhere}
 * null — unlike the project-scoped reads' 404 for an unknown project, because a branch is not a
 * thing this service registers: a fresh branch and a mistyped one look the same here, and "nothing
 * yet" is the honest answer to both. A {@code project} that names no project is still a 404.
 *
 * <p>The schemas are <b>named</b> ({@code BranchDeliveries}, {@code BranchDelivery}), so no
 * anonymous {@code Response} schema in {@code docs/openapi.yml} is renumbered by their arrival —
 * {@link ProjectReleaseRequestsController.GetProjectReleaseRequest}'s reason. Every member is
 * always present; one with no value is {@code null}.
 */
@Path("/branch-deliveries")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:system", "qits:agent"})
public class BranchDeliveriesController {

  @Inject BranchDeliveries deliveries;

  @Inject EntityIdResolver resolver;

  /** The read's envelope. */
  @Schema(
      name = "BranchDeliveries",
      description = "Where one branch stands in each repository of its project.")
  public record Answer(
      @Schema(description = "The branch asked about") String branch,
      @Schema(
              description =
                  "The branch's least advanced step across every repository of its project —"
                      + " PUSHED, JOINED, RELEASED or LIVE — however `repository` narrowed the"
                      + " deliveries; null when the branch has no row",
              nullable = true)
          String everywhere,
      @Schema(description = "One row per repository the branch was pushed to or delivered in")
          List<Delivery> deliveries) {

    static Answer of(BranchDeliveries.Answer read) {
      return new Answer(
          read.branch(), read.everywhere(), read.deliveries().stream().map(Delivery::of).toList());
    }
  }

  /** One repository's row. */
  @Schema(
      name = "BranchDelivery",
      description = "The last delivery step one branch reached in one repository.")
  public record Delivery(
      String projectId,
      String repoId,
      @Schema(description = "The repository's name") String repository,
      @Schema(
              description =
                  "PUSHED, JOINED, MOVED, FAILED, CONFLICTED, WITHDRAWN, RELEASED or LIVE")
          String step,
      @Schema(description = "The request carrying the branch; null on PUSHED", nullable = true)
          String requestId,
      @Schema(description = "That request's qualified id", nullable = true) String qualifiedId,
      @Schema(description = "The request's fold at the step; null before its first fold",
          nullable = true)
          String foldSha,
      @Schema(description = "FAILED only: the red CI run or the newest automation run",
          nullable = true)
          String runId,
      @Schema(description = "RELEASED and LIVE: the tag", nullable = true) String version,
      @Schema(description = "When the step was written") Instant changedAt) {

    static Delivery of(BranchDelivery row) {
      return new Delivery(
          row.projectId,
          row.repoId,
          row.repoName,
          row.step,
          row.requestId,
          row.qualifiedId,
          row.foldSha,
          row.runId,
          row.version,
          row.changedAt);
    }
  }

  /**
   * @param branch the branch, as its own name ({@code ticket/qits-1615}); required, else 400
   * @param repository a repository's name or id: narrows {@code deliveries}, never {@code
   *     everywhere}
   * @param project the project's id or slug; required when the branch has rows in more than one
   *     project, which is otherwise a 400
   */
  @GET
  @Operation(
      operationId = "getBranchDeliveries",
      summary = "Where a branch stands on its way to production, in each repository",
      description =
          "The branch's delivery ledger rows: per repository the last step it reached (PUSHED,"
              + " JOINED, MOVED, FAILED, CONFLICTED, WITHDRAWN, RELEASED, LIVE), the request"
              + " carrying it and the fold, run and version of that step. `everywhere` is the least"
              + " advanced step over every repository of the project. An unknown branch answers"
              + " 200 with no deliveries and `everywhere` null; a branch in several projects needs"
              + " `project`, else 400.")
  public Answer get(
      @QueryParam("branch") String branch,
      @QueryParam("repository") String repository,
      @QueryParam("project") String project) {
    String projectId =
        project == null || project.isBlank() ? null : resolver.resolveProject(project).id;
    return Answer.of(deliveries.read(branch, repository, projectId));
  }
}

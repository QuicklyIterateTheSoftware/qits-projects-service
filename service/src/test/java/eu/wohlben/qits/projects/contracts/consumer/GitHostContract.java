package eu.wohlben.qits.projects.contracts.consumer;

import static eu.wohlben.qits.projects.contracts.consumer.ConsumerRow.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.contracts.consumer.ConsumerRow.Trigger;
import eu.wohlben.qits.projects.control.BackingBranchMerger;
import eu.wohlben.qits.projects.control.ConfiguredGitHostAddress;
import eu.wohlben.qits.projects.control.GitHostBearer;
import eu.wohlben.qits.projects.control.ReleaseGitHost;
import eu.wohlben.qits.projects.releasehost.HttpBackingBranchMerger;
import eu.wohlben.qits.projects.releasehost.HttpReleaseGitHost;
import eu.wohlben.qits.projects.wiring.HttpGitHostRepositories;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>What qits-projects asks qits-githost</b> (ticket qits-1149): the repository lifecycle on
 * {@code /git/{repositoryId}} ({@link HttpGitHostRepositories}), the release primitives under
 * {@code /githost/api/repositories/{repositoryId}} ({@link HttpReleaseGitHost}, {@link
 * HttpBackingBranchMerger}) and the by-name tree read under {@code /git/{projectId}/{repoName}}.
 *
 * <p>{@code ReleaseGitHost.head} ({@code GET .../branches/{name}}) has no caller in the shipped
 * code, so it has no row.
 */
final class GitHostContract {

  static final String PROVIDER = "qits-githost-service";
  static final String APP = "qits-githost";

  private static final GitHostBearer BEARER = () -> Optional.of("machine-token");

  private GitHostContract() {}

  private static HttpGitHostRepositories repositories(String base) {
    ConfiguredGitHostAddress address = Fields.with(new ConfiguredGitHostAddress(), "gitHostUrl", base);
    return Fields.with(
        new HttpGitHostRepositories(),
        "gitHost", address,
        "gitHostBearer", BEARER,
        "objectMapper", new ObjectMapper(),
        "networkTimeoutMs", 5_000L);
  }

  private static HttpReleaseGitHost releaseHost(String base) {
    return Fields.with(new HttpReleaseGitHost(), "githostUrl", Optional.of(base), "bearer", BEARER);
  }

  private static HttpBackingBranchMerger merger(String base) {
    return Fields.with(new HttpBackingBranchMerger(), "githostUrl", Optional.of(base), "bearer", BEARER);
  }

  private static ConsumerRow row(
      String operationId,
      String state,
      String method,
      String path,
      Map<String, String> query,
      String body,
      List<String> consumes,
      int status,
      Trigger trigger,
      ConsumerRow.Call call,
      String needs) {
    return new ConsumerRow(
        PROVIDER, APP, operationId, state, method, path, query, body == null ? null : json(body),
        consumes, status, trigger, call, needs);
  }

  static final List<ConsumerRow> ROWS =
      List.of(
          row(
              "createRepository",
              "no repository with the given id",
              "PUT",
              "/git/{repositoryId}",
              Map.of(),
              "{\"defaultBranch\":\"main\"}",
              List.of(),
              201,
              Trigger.operation("ProjectController.createRepository"),
              (base, p) -> assertTrue(repositories(base).ensure(p.get("repositoryId"), "main")),
              "PUT /git/{repositoryId} with {defaultBranch: main} answering 201 for an id it does not"
                  + " hold"),
          row(
              "describeRepository",
              "a repository with a default branch",
              "GET",
              "/git/{repositoryId}",
              Map.of(),
              null,
              List.of("$.defaultBranch"),
              200,
              Trigger.operation("listCommitChanges"),
              (base, p) ->
                  assertNotNull(
                      repositories(base).find(p.get("repositoryId")).orElseThrow().defaultBranch()),
              "GET /git/{repositoryId} answering 200 with defaultBranch"),
          row(
              "deleteRepository",
              "a repository with a default branch",
              "DELETE",
              "/git/{repositoryId}",
              Map.of(),
              null,
              List.of(),
              204,
              Trigger.operation("RepositoryController.delete"),
              (base, p) -> assertTrue(repositories(base).delete(p.get("repositoryId"))),
              "DELETE /git/{repositoryId} answering 204"),
          row(
              "tree",
              "a repository with files on main",
              "GET",
              "/githost/api/repositories/{repositoryId}/tree",
              Map.of("rev", "{rev}"),
              null,
              List.of("$.paths[*]"),
              200,
              Trigger.schedule("GitHostReleaseExecutor.announce"),
              (base, p) -> {
                ReleaseGitHost.Answer<List<String>> answer =
                    releaseHost(base).tree(p.get("repositoryId"), p.get("rev"));
                assertTrue(answer.ok(), answer.detail());
                assertFalse(answer.value().isEmpty());
              },
              "GET .../tree?rev={rev} answering 200 with paths[] of at least one file"),
          row(
              "file",
              "a repository with files on main",
              "GET",
              "/githost/api/repositories/{repositoryId}/file",
              Map.of("rev", "{rev}", "path", "{path}"),
              null,
              List.of("$.binary", "$.content"),
              200,
              Trigger.schedule("GitHostReleaseExecutor.announce"),
              (base, p) -> {
                ReleaseGitHost.Answer<String> answer =
                    releaseHost(base).file(p.get("repositoryId"), p.get("rev"), p.get("path"));
                assertTrue(answer.ok(), answer.detail());
              },
              "GET .../file?rev={rev}&path={path} answering 200 with binary=false and content for a"
                  + " text file"),
          row(
              "serveTreeByName",
              "a wrapper repository with a submodule",
              "GET",
              "/git/{projectId}/{repoName}/tree/{rev}/{directory}",
              Map.of(),
              null,
              List.of("$.entries[*].name", "$.entries[*].sha", "$.entries[*].mode"),
              200,
              Trigger.schedule("GitHostReleaseExecutor.unresolvablePin"),
              (base, p) -> {
                ReleaseGitHost.Answer<String> answer =
                    releaseHost(base)
                        .gitlinkAt(
                            p.get("projectId"),
                            p.get("repoName"),
                            p.get("rev"),
                            p.get("directory") + "/" + p.get("submodule"));
                assertTrue(answer.ok(), answer.detail());
                assertNotNull(answer.value(), "the recorded directory holds the submodule's gitlink");
              },
              "GET /git/{projectId}/{repoName}/tree/{rev}/{directory} answering 200 with entries[]"
                  + " holding a mode 160000 entry named {submodule}"),
          row(
              "serveTreeByName",
              "a repository with files on main",
              "GET",
              "/git/{projectId}/{repoName}/tree/{sha}",
              Map.of(),
              null,
              List.of(),
              200,
              Trigger.schedule("GitHostReleaseExecutor.unresolvablePin (resolves)"),
              (base, p) -> {
                ReleaseGitHost.Answer<Boolean> answer =
                    releaseHost(base).resolves(p.get("projectId"), p.get("repoName"), p.get("sha"));
                assertTrue(answer.ok(), answer.detail());
                assertTrue(answer.value());
              },
              "GET /git/{projectId}/{repoName}/tree/{sha} answering 200 for a commit it holds"),
          row(
              "contains",
              "a repository whose main contains a commit",
              "GET",
              "/githost/api/repositories/{repositoryId}/contains",
              Map.of("commit", "{commit}", "in", "{in}"),
              null,
              List.of("$.contains"),
              200,
              Trigger.schedule("ReleaseRequests.settleTagsOnMain"),
              (base, p) -> {
                ReleaseGitHost.Answer<Boolean> answer =
                    releaseHost(base).contains(p.get("repositoryId"), p.get("commit"), p.get("in"));
                assertTrue(answer.ok(), answer.detail());
              },
              "GET .../contains?commit={commit}&in={in} answering 200 with contains"),
          row(
              "commit",
              "a repository with files on main",
              "POST",
              "/githost/api/repositories/{repositoryId}/commits",
              Map.of(),
              "{\"ref\":\"refs/heads/main\",\"message\":\"release(1.0.0)\","
                  + "\"files\":{\"VERSION\":\"1.0.0\\n\"},"
                  + "\"author\":{\"name\":\"qits-projects\",\"email\":\"qits-projects@qits.internal\"},"
                  + "\"projectId\":\"{projectId}\",\"repoName\":\"{repoName}\"}",
              List.of("$.sha"),
              200,
              Trigger.schedule("GitHostReleaseExecutor.release"),
              (base, p) -> {
                ReleaseGitHost.Answer<String> answer =
                    releaseHost(base)
                        .commit(
                            p.get("repositoryId"),
                            p.get("projectId"),
                            p.get("repoName"),
                            "refs/heads/main",
                            "release(1.0.0)",
                            Map.of("VERSION", "1.0.0\n"),
                            Map.of());
                assertTrue(answer.ok(), answer.detail());
              },
              "POST .../commits answering 200 with the new commit's sha"),
          row(
              "tag",
              "a repository with files on main",
              "POST",
              "/githost/api/repositories/{repositoryId}/tags",
              Map.of(),
              "{\"name\":\"1.0.0\",\"sha\":\"{sha}\",\"message\":\"release 1.0.0\","
                  + "\"author\":{\"name\":\"qits-projects\",\"email\":\"qits-projects@qits.internal\"},"
                  + "\"projectId\":\"{projectId}\",\"repoName\":\"{repoName}\"}",
              List.of("$.sha"),
              201,
              Trigger.schedule("GitHostReleaseExecutor.release"),
              (base, p) -> {
                ReleaseGitHost.TagAnswer answer =
                    releaseHost(base)
                        .tag(
                            p.get("repositoryId"),
                            p.get("projectId"),
                            p.get("repoName"),
                            "1.0.0",
                            p.get("sha"),
                            "release 1.0.0");
                assertEquals(ReleaseGitHost.TagResult.CREATED, answer.result(), answer.detail());
              },
              "POST .../tags answering 201 with the tag's sha"),
          row(
              "deleteBranch",
              "a repository with a consumed branch",
              "DELETE",
              "/githost/api/repositories/{repositoryId}/branches/{branch}",
              Map.of("projectId", "{projectId}", "repoName", "{repoName}"),
              null,
              List.of(),
              204,
              Trigger.schedule("GitHostReleaseExecutor.deleteConsumedBranches"),
              (base, p) ->
                  releaseHost(base)
                      .deleteBranch(
                          p.get("repositoryId"), p.get("projectId"), p.get("repoName"), p.get("branch")),
              "DELETE .../branches/{branch}?projectId&repoName answering 204"),
          row(
              "merge",
              "a repository with a branch to fold",
              "POST",
              "/githost/api/repositories/{repositoryId}/merges",
              Map.of(),
              "{\"target\":\"{target}\",\"sources\":[\"{source}\"],\"message\":\"fold\","
                  + "\"author\":{\"name\":\"qits-projects\",\"email\":\"qits-projects@qits.internal\"},"
                  + "\"projectId\":\"{projectId}\",\"repoName\":\"{repoName}\"}",
              List.of(
                  "$.outcome",
                  "$.sha",
                  "$.parents[*]",
                  "$.resolved",
                  "$.resolvedVersions"),
              200,
              Trigger.schedule("ReleaseRequests.fold"),
              (base, p) -> {
                BackingBranchMerger.Outcome outcome =
                    merger(base)
                        .merge(
                            p.get("repositoryId"),
                            p.get("projectId"),
                            p.get("repoName"),
                            p.get("target"),
                            List.of(p.get("source")),
                            "fold",
                            List.of(),
                            false,
                            false);
                assertTrue(outcome.folded(), outcome.detail());
              },
              "POST .../merges answering 200 with outcome merged or fast-forward, sha and parents[]"),
          row(
              "merge",
              "a repository with a branch that conflicts with main",
              "POST",
              "/githost/api/repositories/{repositoryId}/merges",
              Map.of(),
              "{\"target\":\"{target}\",\"sources\":[\"{source}\"],\"message\":\"fold\","
                  + "\"author\":{\"name\":\"qits-projects\",\"email\":\"qits-projects@qits.internal\"},"
                  + "\"projectId\":\"{projectId}\",\"repoName\":\"{repoName}\"}",
              List.of(
                  "$.error",
                  "$.target",
                  "$.conflicts[*].path",
                  "$.conflicts[*].head",
                  "$.conflicts[*].headSha",
                  "$.conflicts[*].reason",
                  "$.conflicts[*].kind"),
              409,
              Trigger.schedule("ReleaseRequests.fold"),
              (base, p) -> {
                BackingBranchMerger.Outcome outcome =
                    merger(base)
                        .merge(
                            p.get("repositoryId"),
                            p.get("projectId"),
                            p.get("repoName"),
                            p.get("target"),
                            List.of(p.get("source")),
                            "fold",
                            List.of(),
                            false,
                            false);
                assertEquals(BackingBranchMerger.Result.CONFLICT, outcome.result(), outcome.detail());
                assertFalse(outcome.conflicts().isEmpty());
              },
              "POST .../merges answering 409 with error merge-conflict, target and conflicts[]"));
}

package eu.wohlben.qits.projects.releasehost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.control.GitHostBearer;
import eu.wohlben.qits.projects.control.ReleaseGitHost;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The {@link ReleaseGitHost} port over qits-githost's content and ref primitives — the five calls a
 * tag-only release is made of:
 *
 * <pre>
 *   GET    /githost/api/repositories/{repoId}/tree?rev=&lt;sha&gt;
 *   GET    /githost/api/repositories/{repoId}/file?rev=&lt;sha&gt;&amp;path=&lt;path&gt;
 *   POST   /githost/api/repositories/{repoId}/commits   {ref, message, files, author}
 *   POST   /githost/api/repositories/{repoId}/tags      {name, sha, message, author}
 *   DELETE /githost/api/repositories/{repoId}/branches/{name}
 * </pre>
 *
 * <p>And <b>one route off that plane entirely</b>, the git plane's name-addressed directory listing:
 *
 * <pre>
 *   GET    /git/{projectId}/{repoName}/tree/{rev}[/&lt;dir&gt;]
 * </pre>
 *
 * It is the only read that can answer what a submodule gitlink pins and whether a pinned commit
 * exists, and {@link #gitlinkAt} carries the argument for why the api plane cannot. It shares this
 * class's credential and its classification and nothing else.
 *
 * Hand-rolled {@code java.net.http}, this package's standing shape ({@link HttpBackingBranchMerger},
 * {@link HttpActiveBuilds}), and the same address and credential as the merge primitive:
 * {@code qits.projects.release-requests.githost-url}, <b>unset shipped</b>, and the {@code githost}
 * named OIDC client's bearer through {@link GitHostBearer}. Every one of these routes is guarded
 * {@code qits:system} on the far side, which a forwarded {@code X-Qits-*} pair cannot carry, so
 * there is deliberately <b>no header fallback</b>: with the client disabled a release refuses rather
 * than sending an anonymous request the git host would answer 401 to.
 *
 * <p><b>Never throws, and every answer is classified.</b> The port's {@code retryable} is the whole
 * of what the release request's own retry flag becomes, so the classification is where the value is:
 *
 * <ul>
 *   <li><b>Retryable</b> — an unconfigured address, no bearer, a timeout, an unreachable host, any
 *       5xx, and the git host's {@code 409 ref-moved} (a concurrent writer moved the backing branch;
 *       the answer to that is to read the tip again, not to stop).
 *   <li><b>Not</b> — {@code no-such-repository}, {@code no-such-rev}, {@code no-such-ref}, {@code
 *       no-such-path}, {@code no-such-object}, {@code protected-branch} and every 400. Each is a
 *       fact about the ask that answers the same until the request is re-armed.
 * </ul>
 *
 * <p>{@code 409 tag-exists} is neither: it is the platform's version-uniqueness guarantee arriving,
 * and it comes back as {@link ReleaseGitHost.TagResult#ALREADY_EXISTS} so the executor can stamp a
 * fresh calver and ask again.
 *
 * <p>The {@link HttpClient} is an <b>instance</b> field, not static — the native-image rule {@code
 * HttpGitHostRepositories} carries.
 */
@ApplicationScoped
@DefaultBean
public class HttpReleaseGitHost implements ReleaseGitHost {

  private static final Logger LOG = Logger.getLogger(HttpReleaseGitHost.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** A tree walk and an in-core commit are bounded, but a large repository is not instant. */
  private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);

  /** The author both halves or neither, the far side's rule. This service is the one releasing. */
  private static final Map<String, String> AUTHOR =
      Map.of("name", "qits-projects", "email", "qits-projects@qits.internal");

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  @ConfigProperty(name = "qits.projects.release-requests.githost-url")
  Optional<String> githostUrl;

  @Inject GitHostBearer bearer;

  // -----------------------------------------------------------------------------------------------
  // Reads
  // -----------------------------------------------------------------------------------------------

  @Override
  public Answer<List<String>> tree(String repoId, String rev) {
    return call(
        builder -> builder.GET(),
        "/tree?rev=" + encode(rev),
        repoId,
        body -> {
          List<String> paths = new ArrayList<>();
          MAPPER.readTree(body).path("paths").forEach(node -> paths.add(node.asText()));
          return Answer.of(List.copyOf(paths));
        });
  }

  @Override
  public Answer<String> file(String repoId, String rev, String path) {
    return call(
        builder -> builder.GET(),
        "/file?rev=" + encode(rev) + "&path=" + encode(path),
        repoId,
        body -> {
          JsonNode answer = MAPPER.readTree(body);
          if (answer.path("binary").asBoolean(false)) {
            // The git host answers 200 with no content for a binary blob and for one past its cap.
            // A manifest is neither, so this is a fact about the ask rather than about the moment.
            return Answer.failed(
                path + " is binary or larger than the git host will answer with; it cannot be bumped");
          }
          String content = answer.path("content").asText(null);
          return content == null
              ? Answer.failed(path + ": the git host answered 200 with no content")
              : Answer.of(content);
        });
  }

  @Override
  public Answer<String> head(String repoId, String branch) {
    return call(
        builder -> builder.GET(),
        "/branches/" + branch,
        repoId,
        body -> {
          String sha = MAPPER.readTree(body).path("sha").asText(null);
          return sha == null || sha.isBlank()
              ? Answer.failedRetryable(
                  "qits-githost answered 200 to a branch read with no sha: " + clip(body))
              : Answer.of(sha);
        });
  }

  /**
   * The pinned commit at one path, read off the git plane's <b>name-addressed</b> directory listing
   * — {@code GET /git/{projectId}/{repoName}/tree/{rev}[/<dir>]}, whose entries carry {@code sha}
   * and {@code mode} for a gitlink and nothing extra for anything else.
   *
   * <p><b>Not the api-plane {@code /tree} this class's other reads use</b>, and it cannot be: that
   * route walks recursively and drops every mode-160000 entry on the way out ("a submodule has no
   * blob to show and no tree to descend into"), so a pin is not on its answer at any revision. The
   * listing that carries pins is the one qits-platform-maintenance already scans them with, and it
   * is served on the name scheme. Both content reads on the storage scheme are additionally gated
   * behind {@code qits.githost.content-readers}, which is a configured list this service is not
   * obliged to be on; the name-addressed pair carries no such gate, so reading by name is the
   * cheaper posture as well as the only one that can reach a sibling repository at all.
   *
   * <p>One request per declared entry rather than one recursive walk, because the caller asks about
   * the handful of paths {@code .gitmodules} names and a wrapper's tree is otherwise large. A 404 is
   * the directory or the entry not being there, which is "nothing is pinned here" — a null value on
   * an ok answer, exactly as the port promises.
   */
  @Override
  public Answer<String> gitlinkAt(String projectId, String repoName, String rev, String path) {
    int slash = path.lastIndexOf('/');
    String directory = slash < 0 ? "" : path.substring(0, slash);
    String name = slash < 0 ? path : path.substring(slash + 1);
    return git(
        "/tree/" + encode(rev) + (directory.isEmpty() ? "" : "/" + encodePath(directory)),
        projectId,
        repoName,
        Answer.<String>of(null),
        body -> {
          for (JsonNode entry : MAPPER.readTree(body).path("entries")) {
            if (!name.equals(entry.path("name").asText(null))) {
              continue;
            }
            String sha = entry.path("sha").asText(null);
            return "160000".equals(entry.path("mode").asText(""))
                    && sha != null
                    && !sha.isBlank()
                ? Answer.of(sha)
                : Answer.of(null); // There, but not a gitlink: a directory or a file at that path.
          }
          return Answer.of(null);
        });
  }

  /**
   * Whether a repository holds a commit, asked as the cheapest read that can only answer yes if it
   * does: the root tree listing <b>at that sha</b>. The git host resolves the rev before it lists
   * anything and answers 404 when it cannot, and a rev is "anything the repository holds, reachable
   * from a ref or not" — which is the right question about a pin, since a submodule's pinned commit
   * routinely sits behind a branch that has moved on.
   *
   * <p>A 404 is <b>false</b> rather than a failure, and it deliberately covers two cases with one
   * word: the sha is not there, or no repository of that project answers to that name. Both are the
   * same fact for a caller checking a pin — nothing on this host resolves what the fold declares —
   * and a clone would fail on either in the same place. Everything else is classified as this class
   * classifies every other answer.
   */
  @Override
  public Answer<Boolean> resolves(String projectId, String repoName, String sha) {
    return git(
        "/tree/" + encode(sha),
        projectId,
        repoName,
        Answer.<Boolean>of(false),
        body -> Answer.of(true));
  }

  // -----------------------------------------------------------------------------------------------
  // Writes
  // -----------------------------------------------------------------------------------------------

  @Override
  public Answer<String> commit(
      String repoId,
      String ref,
      String message,
      Map<String, String> files,
      Map<String, String> gitlinks) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ref", ref);
    body.put("message", message);
    body.put("files", files);
    if (gitlinks != null && !gitlinks.isEmpty()) {
      body.put("gitlinks", gitlinks);
    }
    body.put("author", AUTHOR);
    return call(
        builder -> builder.POST(json(body)),
        "/commits",
        repoId,
        answered -> {
          JsonNode answer = MAPPER.readTree(answered);
          String sha = answer.path("sha").asText(null);
          if (sha == null || sha.isBlank()) {
            return Answer.failedRetryable(
                "qits-githost answered 200 to a commit with no sha: " + clip(answered));
          }
          // `unchanged` is a 200 carrying the tip's own sha — the bump produced the tree that was
          // already there. Nothing to distinguish here: the caller wants the sha to tag either way.
          return Answer.of(sha);
        });
  }

  @Override
  public TagAnswer tag(String repoId, String name, String sha, String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("sha", sha);
    body.put("message", message);
    body.put("author", AUTHOR);
    String address = address(repoId, "/tags");
    if (address == null) {
      return TagAnswer.failedRetryable(unconfigured());
    }
    Optional<String> token = bearer.token();
    if (token.isEmpty()) {
      return TagAnswer.failedRetryable(noBearer());
    }
    try {
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(URI.create(address))
                  .timeout(CALL_TIMEOUT)
                  .header("Content-Type", "application/json")
                  .header("Authorization", "Bearer " + token.get())
                  .POST(json(body))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 201) {
        return TagAnswer.created(MAPPER.readTree(response.body()).path("sha").asText(null));
      }
      if (response.statusCode() == 409
          && "tag-exists".equals(MAPPER.readTree(response.body()).path("error").asText(""))) {
        return TagAnswer.alreadyExists(MAPPER.readTree(response.body()).path("sha").asText(null));
      }
      String detail =
          "qits-githost answered " + response.statusCode() + " to the tag: " + clip(response.body());
      return retryable(response.statusCode(), response.body())
          ? TagAnswer.failedRetryable(detail)
          : TagAnswer.failed(detail);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return TagAnswer.failedRetryable("interrupted while tagging " + name);
    } catch (Exception e) {
      LOG.warnf("qits-githost could not be reached to tag %s: %s", name, e.toString());
      return TagAnswer.failedRetryable("qits-githost could not be reached: " + e);
    }
  }

  @Override
  public void deleteBranch(String repoId, String name) {
    String address = address(repoId, "/branches/" + encodePath(name));
    if (address == null) {
      LOG.warnf("Cannot delete %s of %s: %s", name, repoId, unconfigured());
      return;
    }
    Optional<String> token = bearer.token();
    if (token.isEmpty()) {
      LOG.warnf("Cannot delete %s of %s: %s", name, repoId, noBearer());
      return;
    }
    try {
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(URI.create(address))
                  .timeout(CALL_TIMEOUT)
                  .header("Authorization", "Bearer " + token.get())
                  .DELETE()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      // 204 deleted, 404 already gone — both are the state this asked for.
      if (response.statusCode() != 204 && response.statusCode() != 404) {
        LOG.warnf(
            "qits-githost answered %d deleting %s of %s: %s",
            response.statusCode(), name, repoId, clip(response.body()));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warnf("Interrupted deleting %s of %s", name, repoId);
    } catch (Exception e) {
      LOG.warnf("Could not delete %s of %s: %s", name, repoId, e.toString());
    }
  }

  // -----------------------------------------------------------------------------------------------
  // The one round trip
  // -----------------------------------------------------------------------------------------------

  /** A request builder step, so the four classified calls share everything but their verb. */
  private interface Verb {
    HttpRequest.Builder apply(HttpRequest.Builder builder);
  }

  /** What a 2xx body means, allowed to throw — a body that will not parse is a failure like any. */
  private interface Reader<T> {
    ReleaseGitHost.Answer<T> read(String body) throws Exception;
  }

  /**
   * The name-addressed git plane's one round trip — {@code GET /git/{projectId}/{repoName}<tail>} —
   * beside {@link #call}'s api-plane one rather than folded into it, because the two differ in every
   * part that matters: a different base, a two-segment address instead of a storage id, and a 404
   * that is an <b>answer</b> here rather than a failure. {@code notFound} is what that 404 means for
   * this particular read, which is the only thing the two callers disagree about.
   *
   * <p>Same credential and same must-not-throw contract as everything else in this class: the {@code
   * githost} client's bearer, no header fallback, and every exception classified into an answer.
   */
  private <T> Answer<T> git(
      String tail, String projectId, String repoName, Answer<T> notFound, Reader<T> reader) {
    if (githostUrl.isEmpty() || githostUrl.get().isBlank()) {
      return Answer.failedRetryable(unconfigured());
    }
    Optional<String> token = bearer.token();
    if (token.isEmpty()) {
      return Answer.failedRetryable(noBearer());
    }
    String address =
        githostUrl.get() + "/git/" + encode(projectId) + "/" + encode(repoName) + tail;
    try {
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(URI.create(address))
                  .timeout(CALL_TIMEOUT)
                  .header("Authorization", "Bearer " + token.get())
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 200) {
        return reader.read(response.body());
      }
      if (response.statusCode() == 404) {
        return notFound;
      }
      String detail =
          "qits-githost answered " + response.statusCode() + ": " + clip(response.body());
      return retryable(response.statusCode(), response.body())
          ? Answer.failedRetryable(detail)
          : Answer.failed(detail);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Answer.failedRetryable("interrupted while asking qits-githost");
    } catch (Exception e) {
      LOG.warnf("qits-githost could not be reached (%s): %s", tail, e.toString());
      return Answer.failedRetryable("qits-githost could not be reached: " + e);
    }
  }

  private <T> Answer<T> call(Verb verb, String tail, String repoId, Reader<T> reader) {
    String address = address(repoId, tail);
    if (address == null) {
      return Answer.failedRetryable(unconfigured());
    }
    Optional<String> token = bearer.token();
    if (token.isEmpty()) {
      return Answer.failedRetryable(noBearer());
    }
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create(address))
              .timeout(CALL_TIMEOUT)
              .header("Content-Type", "application/json")
              .header("Authorization", "Bearer " + token.get());
      HttpResponse<String> response =
          client.send(verb.apply(builder).build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 200) {
        return reader.read(response.body());
      }
      String detail =
          "qits-githost answered " + response.statusCode() + ": " + clip(response.body());
      return retryable(response.statusCode(), response.body())
          ? Answer.failedRetryable(detail)
          : Answer.failed(detail);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Answer.failedRetryable("interrupted while asking qits-githost");
    } catch (Exception e) {
      LOG.warnf("qits-githost could not be reached (%s): %s", tail, e.toString());
      return Answer.failedRetryable("qits-githost could not be reached: " + e);
    }
  }

  /**
   * Whether asking again can change the answer. A 5xx is the host's moment. A 409 splits by the git
   * host's own error word: {@code ref-moved} is a concurrent writer and re-reading the tip fixes it;
   * {@code protected-branch} is a rule. Every other 4xx names something about the ask — a repository,
   * a rev, a ref, a path or an object that is not there, or a body the host would not take.
   */
  private static boolean retryable(int status, String body) {
    if (status >= 500) {
      return true;
    }
    if (status != 409) {
      return false;
    }
    try {
      return "ref-moved".equals(MAPPER.readTree(body).path("error").asText(""));
    } catch (Exception e) {
      return false;
    }
  }

  private String address(String repoId, String tail) {
    if (githostUrl.isEmpty() || githostUrl.get().isBlank()) {
      return null;
    }
    return githostUrl.get() + "/githost/api/repositories/" + encode(repoId) + tail;
  }

  private static String unconfigured() {
    return "qits.projects.release-requests.githost-url is not configured; nothing can release this"
        + " request";
  }

  private static String noBearer() {
    return "No machine bearer is available for qits-githost; the release primitives take qits:system"
        + " and refuse an anonymous caller";
  }

  private static HttpRequest.BodyPublisher json(Map<String, Object> body) {
    try {
      return HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body));
    } catch (Exception e) {
      // A map of strings that will not serialize is a programming error, not a runtime condition.
      throw new IllegalStateException("cannot serialize a git-host request body", e);
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  /**
   * A branch name is the TAIL of the delete route ({@code /branches/{name:.+}}), so its slashes are
   * path separators and must survive — {@code release/17} is one ref, not a repository called
   * {@code release} with a branch {@code 17}. Only the segments are encoded.
   */
  private static String encodePath(String name) {
    List<String> segments = new ArrayList<>();
    for (String segment : name.split("/")) {
      if (!segment.isEmpty()) {
        segments.add(encode(segment));
      }
    }
    return String.join("/", segments);
  }

  private static String clip(String body) {
    if (body == null) {
      return "";
    }
    return body.length() <= 300 ? body : body.substring(0, 300) + "…";
  }
}

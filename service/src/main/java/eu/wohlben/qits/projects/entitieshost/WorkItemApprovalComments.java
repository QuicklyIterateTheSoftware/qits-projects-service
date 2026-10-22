package eu.wohlben.qits.projects.entitieshost;

import eu.wohlben.qits.entities.control.EntityCommentService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.projects.api.ProjectChangeHint;
import eu.wohlben.qits.projects.api.ProjectChangePublisher;
import eu.wohlben.qits.projects.control.ApprovalRequirementComments;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.control.approval.ApprovalRequirement;
import io.quarkus.arc.DefaultBean;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * {@link ApprovalRequirementComments} onto the work item's thread (epic qits-1038, the owner's
 * decision 4): when an approval requirement opens, each work item a branch source names — {@code
 * ticket/<qualified id>} or {@code epic/<qualified id>}, and an agent's own {@code
 * ticket/<qualified id>-<anything>} beside it — is told what changed (the requirement's reason and up
 * to {@value #MAX_PATHS} of its paths), where to read it (the Changes link, from {@code qits.domain})
 * and the exact command that approves it, with the roles that may run it.
 *
 * <p>Resolved in process, through {@link EntityIdResolver} and {@link EntityCommentService}, as
 * {@link TicketUnattendedGateTickets} comments. Called after the transaction that recorded the
 * requirement OPEN has committed, once per (request, fold, requirement). <b>Never throws</b>: a
 * comment that could not be written is a WARN, and a branch naming no work item is no comment at
 * all.
 */
@ApplicationScoped
@DefaultBean
public class WorkItemApprovalComments implements ApprovalRequirementComments {

  private static final Logger LOG = Logger.getLogger(WorkItemApprovalComments.class);

  /** Who the comment is by — the same reporter the gate tickets name. */
  static final String AUTHOR = TicketUnattendedGateTickets.REPORTER;

  /** How many of a requirement's paths a comment lists before it says how many more there are. */
  static final int MAX_PATHS = 20;

  @Inject EntityIdResolver resolver;

  @Inject EntityCommentService comments;

  @Inject ProjectService projects;

  @Inject ProjectChangePublisher publisher;

  @ConfigProperty(name = "qits.domain")
  Optional<String> domain;

  @Override
  @ActivateRequestContext
  public void opened(Opened opened) {
    Map<String, CommitSubjectEntities.NamedEntity> items = workItems(opened.branches());
    if (items.isEmpty()) {
      return;
    }
    String slug = slug(opened);
    String body = body(opened, changesLink(opened, slug), slug);
    for (CommitSubjectEntities.NamedEntity item : items.values()) {
      try {
        QuarkusTransaction.suspendingExisting()
            .run(() -> comments.addComment(item.id(), body, AUTHOR));
        LOG.infof(
            "Release request %s: approval requirement %s opened; said so on %s",
            opened.releaseRequestQualifiedId(), opened.requirementId(), item.qualifiedId());
        redraw(item.projectId());
      } catch (RuntimeException e) {
        LOG.warnf(
            e,
            "Could not tell %s that approval requirement %s of release request %s opened",
            item.qualifiedId(),
            opened.requirementId(),
            opened.releaseRequestQualifiedId());
      }
    }
  }

  /** The work items the branches name, once each, in branch order. */
  private Map<String, CommitSubjectEntities.NamedEntity> workItems(List<String> branches) {
    Map<String, CommitSubjectEntities.NamedEntity> items = new LinkedHashMap<>();
    if (branches == null) {
      return items;
    }
    for (String branch : branches) {
      try {
        workItemOf(branch).ifPresent(item -> items.putIfAbsent(item.id(), item));
      } catch (RuntimeException e) {
        LOG.debugf(e, "Could not resolve the work item of branch %s", branch);
      }
    }
    return items;
  }

  /**
   * The work item {@code branch} names: {@code ticket/<qualified id>} a ticket, {@code
   * epic/<qualified id>} an epic, each also with a {@code -<suffix>} an agent adds. The whole name is
   * tried first, then each shorter prefix ending before a hyphen.
   */
  Optional<CommitSubjectEntities.NamedEntity> workItemOf(String branch) {
    Archetype archetype;
    String rest;
    if (branch == null) {
      return Optional.empty();
    } else if (branch.startsWith("ticket/")) {
      archetype = Archetype.TICKET;
      rest = branch.substring("ticket/".length());
    } else if (branch.startsWith("epic/")) {
      archetype = Archetype.EPIC;
      rest = branch.substring("epic/".length());
    } else {
      return Optional.empty();
    }
    List<String> candidates = new ArrayList<>();
    candidates.add(rest);
    for (int dash = rest.lastIndexOf('-'); dash > 0; dash = rest.lastIndexOf('-', dash - 1)) {
      candidates.add(rest.substring(0, dash));
    }
    for (String candidate : candidates) {
      Optional<CommitSubjectEntities.NamedEntity> item =
          CommitSubjectEntities.parse(candidate)
              .flatMap(resolver::lookup)
              .filter(found -> found.archetype() == archetype);
      if (item.isPresent()) {
        return item;
      }
    }
    return Optional.empty();
  }

  /** The request's project slug, or null where it could not be read. */
  private String slug(Opened opened) {
    if (opened.projectId() == null) {
      return null;
    }
    try {
      return QuarkusTransaction.requiringNew().call(() -> projects.get(opened.projectId()).slug);
    } catch (RuntimeException e) {
      LOG.debugf(e, "Could not read the slug of project %s", opened.projectId());
      return null;
    }
  }

  /** The request's Changes tab, or null where this deployment names no domain or no slug. */
  private String changesLink(Opened opened, String slug) {
    String base = domain.map(String::trim).filter(value -> !value.isEmpty()).orElse(null);
    if (base == null || slug == null) {
      return null;
    }
    return "https://projects.qits."
        + base
        + "/"
        + slug
        + "/release-requests/"
        + opened.releaseRequestQualifiedId()
        + "?tab=changes";
  }

  /**
   * The comment: what changed, where to read it, and the command, with who may run it.
   *
   * @param slug the project's slug for the {@code --project} flag, or null to fall back to the
   *     project id (the command still parses; the id is simply less readable)
   */
  static String body(Opened opened, String link, String slug) {
    StringBuilder text = new StringBuilder();
    text.append("**Release request `")
        .append(opened.releaseRequestQualifiedId())
        .append("` needs approval:** ")
        .append(opened.reason())
        .append(".\n\n");
    List<String> paths = paths(opened.detail());
    if (!paths.isEmpty()) {
      text.append("What changed in fold `")
          .append(opened.mergedSha())
          .append("`")
          .append(opened.repoName() == null ? "" : " of `" + opened.repoName() + "`")
          .append(":\n\n");
      paths.stream()
          .limit(MAX_PATHS)
          .forEach(path -> text.append("- `").append(path).append("`\n"));
      if (paths.size() > MAX_PATHS) {
        text.append("- … and ").append(paths.size() - MAX_PATHS).append(" more\n");
      }
      text.append("\n");
    } else if (opened.detail() != null && !opened.detail().isBlank()) {
      text.append(opened.detail().trim()).append("\n\n");
    }
    if (link != null) {
      text.append("Changes: ").append(link).append("\n\n");
    }
    text.append("Approve it with:\n\n    qits release-request --project ")
        .append(slug != null ? slug : opened.projectId())
        .append(" approve --request ")
        .append(opened.releaseRequestQualifiedId())
        .append(" --sha ")
        .append(opened.mergedSha())
        .append("\n\nWho may run it: ")
        .append(String.join(", ", opened.admits()))
        .append(".");
    return text.toString();
  }

  /** A detail that lists paths, split; empty for a sentence. */
  static List<String> paths(String detail) {
    if (detail == null || detail.isBlank()) {
      return List.of();
    }
    List<String> paths = List.of(detail.split(ApprovalRequirement.PATH_SEPARATOR));
    return paths.stream().allMatch(path -> !path.isBlank() && !path.contains(" "))
        ? paths
        : List.of();
  }

  private void redraw(String projectId) {
    try {
      publisher.fire(projectId, ProjectChangeHint.Topic.TICKETS);
    } catch (RuntimeException e) {
      LOG.debugf(e, "Could not fire the TICKETS hint for project %s", projectId);
    }
  }
}

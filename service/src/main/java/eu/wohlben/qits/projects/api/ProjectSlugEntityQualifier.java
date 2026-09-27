package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.campaign.EntityQualifier;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.control.ProjectService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * <b>The {@code entities} module's {@link EntityQualifier} port, answered from {@code domain}'s
 * project table</b> — {@link QualifiedEntityIds}' rendering, for a sentence {@code CampaignService}
 * writes down (a state-at-start evidence summary, a refusal naming a member).
 *
 * <p><b>Its own transaction, always.</b> The caller is inside an {@code epics} write, and the two
 * datasources are local and non-XA: Narayana enlists one such resource per transaction, so the slug
 * read is {@code requiringNew} — the rule {@code CommitSubjectEntities} states for the same
 * crossing. The read re-runs harmlessly if the write around it is retried.
 *
 * <p><b>Never throws</b>, per the port: a project that cannot be read answers null and the module
 * falls back to {@code #<number>}.
 */
@ApplicationScoped
public class ProjectSlugEntityQualifier implements EntityQualifier {

  private static final Logger LOG = Logger.getLogger(ProjectSlugEntityQualifier.class);

  @Inject ProjectService projects;

  @Override
  public String qualifiedId(WorkEntity entity) {
    if (entity == null || entity.projectId == null) {
      return null;
    }
    try {
      String slug =
          QuarkusTransaction.requiringNew()
              .call(() -> projects.slugsByIds(Set.of(entity.projectId)).get(entity.projectId));
      return slug == null ? null : QualifiedEntityIds.render(slug, entity.number);
    } catch (RuntimeException e) {
      LOG.debugf(e, "Could not read the slug of project %s", entity.projectId);
      return null;
    }
  }
}

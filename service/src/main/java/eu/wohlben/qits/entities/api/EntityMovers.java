package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.auth.MachineIdentity;
import eu.wohlben.qits.auth.QitsClaims;
import eu.wohlben.qits.entities.control.Mover;
import eu.wohlben.qits.projects.control.ProjectConfig.DeliverySettings;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.projects.security.AgentAccess;
import eu.wohlben.qits.projects.security.PersonCheck;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * <b>Who a REST door's caller is, as a {@link Mover}</b> (qits-887): a person when qits-891's {@link
 * PersonCheck} verified one — a browser session this service introspected, or a person's {@code
 * qits} CLI bearer — under the name that proof carries; a machine otherwise, under the caller's
 * principal. Nothing else here decides "is this a person", and no forwarded identity header ever
 * makes one: asserted headers with or without a machine bearer are a machine.
 *
 * <p><b>A project's delivery desk is a machine of its own kind</b> (epic qits-1440): a caller holding
 * {@link #DESK_ROLE} and neither {@code qits:admin} nor {@code qits:admin-agent} is {@link
 * Mover#desk}, bound to its token's {@code project} claim (null when it carried none, which the
 * dispatch door then refuses as another project's). A wider role wins, as {@code
 * AgentAccess.isBoundAgent} reads it.
 *
 * <p><b>So is a project's long-running agent</b> (qits-2672, epic qits-2521): a bearer whose {@code
 * context_kind} claim is {@value EntitiesPrincipal#PROJECT_AGENT_CONTEXT_KIND} is {@link
 * Mover#projectAgent}, bound to its token's {@code project} claim (the project's id) and named by
 * its roster name — the tail of its fixed subject {@code tok-project-agent-<projectSlug>-<name>},
 * read against the slug of the project its claim names, since either half may hold a hyphen.
 * qits-agents-service commissions that token with the {@code project} claim alone, so the subject
 * is the only place the name is. {@link #ofMove} stamps it with {@value #START_REFINED} when its
 * project declares {@code front_desk.delivery.start_refined}, which lets it schedule its own items
 * as the desk does ({@code PERSON_APPROVAL}).
 *
 * <p>The lifecycle doors build one per move ({@code WorkEntityDoors.move}, behind {@code POST
 * /work/{qualifiedId}/status} for every archetype); the MCP tools and the platform's in-process callers never do — they are machines
 * by construction and say so with {@link Mover#machine}.
 */
@ApplicationScoped
public class EntityMovers {

  /** The role of a project's delivery desk token ({@code desk-delivery}, epic qits-1440). */
  public static final String DESK_ROLE = AgentAccess.DESK_ROLE;

  /**
   * The declared approval a project agent's move carries under its project's {@code start_refined}
   * (qits-2672): the rule's name, as {@code front_desk.delivery.start_refined} spells it.
   */
  public static final String START_REFINED = "start-refined";

  @Inject PersonCheck persons;

  @Inject ProjectService projects;

  /** The mover behind {@code identity} on the current request. */
  public Mover of(SecurityIdentity identity) {
    return persons
        .verifiedAdmin(identity)
        .map(Mover::person)
        .orElseGet(() -> machineOf(identity));
  }

  /**
   * The mover of a lifecycle move ({@code POST /work/{qualifiedId}/status}): {@link #of}, with a
   * project agent stamped with {@value #START_REFINED} when its own project declares {@code
   * front_desk.delivery.start_refined} (qits-2672). The stamp alone schedules nothing: {@code
   * PERSON_APPROVAL} still wants the row to be of that project and assigned to the agent by name.
   */
  public Mover ofMove(SecurityIdentity identity) {
    Mover mover = of(identity);
    if (!mover.isProjectAgent() || mover.agentProject() == null) {
      return mover;
    }
    return startRefined(mover.agentProject()) ? mover.withDeclaredApproval(START_REFINED) : mover;
  }

  /**
   * The caller's roster name when it is a project agent bound to {@code projectId} (qits-2672);
   * empty for every other caller, and for a project agent of another project.
   */
  public Optional<String> projectAgentName(SecurityIdentity identity, String projectId) {
    if (!isProjectAgent(identity)) {
      return Optional.empty();
    }
    String bound = MachineIdentity.claim(identity, QitsClaims.PROJECT).orElse(null);
    if (bound == null || !bound.equals(projectId)) {
      return Optional.empty();
    }
    return Optional.ofNullable(agentNameOf(identity, bound));
  }

  /** Whether the bearer is a project's long-running agent, by its {@code context_kind}. */
  public static boolean isProjectAgent(SecurityIdentity identity) {
    return EntitiesPrincipal.PROJECT_AGENT_CONTEXT_KIND.equals(
        EntitiesPrincipal.contextKindOf(identity));
  }

  private Mover machineOf(SecurityIdentity identity) {
    String name = EntitiesPrincipal.changedBy(identity);
    // qits:desk-admin is deliberately absent (epic qits-1621): unlike qits:admin-agent it is the
    // desk's own role, and a desk-admin token is still the desk — its mover keeps the desk's
    // refusals and its project binding.
    if (identity != null
        && identity.hasRole(DESK_ROLE)
        && !identity.hasRole(AgentAccess.ADMIN_ROLE)
        && !identity.hasRole(AgentAccess.ADMIN_AGENT_ROLE)) {
      return Mover.desk(name, MachineIdentity.claim(identity, QitsClaims.PROJECT).orElse(null));
    }
    if (isProjectAgent(identity)) {
      String project = MachineIdentity.claim(identity, QitsClaims.PROJECT).orElse(null);
      return Mover.projectAgent(name, project, agentNameOf(identity, project));
    }
    return Mover.machine(name);
  }

  /**
   * A project agent's roster name: its subject past {@code tok-project-agent-<projectSlug>-}, the
   * slug being that of the project its claim names. Null when the claim names no project here or the
   * subject is not of that shape — a name that cannot be read matches no assignee.
   */
  private String agentNameOf(SecurityIdentity identity, String projectId) {
    String subject = subjectOf(identity);
    if (projectId == null || subject == null) {
      return null;
    }
    String slug =
        QuarkusTransaction.requiringNew()
            .call(() -> projects.slugsByIds(Set.of(projectId)).get(projectId));
    if (slug == null) {
      return null;
    }
    String prefix = EntitiesPrincipal.PROJECT_AGENT_SUBJECT_PREFIX + slug + "-";
    if (!subject.startsWith(prefix) || subject.length() == prefix.length()) {
      return null;
    }
    return subject.substring(prefix.length());
  }

  /** The token's {@code sub}, else the principal's name — what qits-idp fixed as the subject. */
  private static String subjectOf(SecurityIdentity identity) {
    if (identity.getPrincipal() instanceof JsonWebToken jwt
        && jwt.getSubject() != null
        && !jwt.getSubject().isBlank()) {
      return jwt.getSubject();
    }
    return EntitiesPrincipal.changedBy(identity);
  }

  /** Whether {@code projectId}'s stored delivery settings declare {@code start_refined}. */
  private boolean startRefined(String projectId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              try {
                return DeliverySettings.of(projects.get(projectId)).startRefined();
              } catch (NotFoundException noSuchProject) {
                return false;
              }
            });
  }
}

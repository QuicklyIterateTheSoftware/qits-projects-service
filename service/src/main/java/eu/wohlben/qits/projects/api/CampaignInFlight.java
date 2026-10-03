package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * <b>Whether a campaign member joins with its work already in flight</b> — the default the service
 * layer decides when the caller states none (qits-413). The {@code entities} module cannot decide
 * it: half of the answer is a workspace, which lives in another service.
 *
 * <p>In flight means <b>the member is IMPLEMENTING, IMPLEMENTED, VERIFYING, VERIFIED or DONE</b>, or <b>an ACTIVE workspace
 * stands on its branch</b> — the same {@link WorkspaceAgentDispatch#workspacesReferencing} lookup
 * {@link PhaseAdvance} makes, with the branch from {@link EntityWorkspaces#branchOf}, and the same
 * branch-and-ACTIVE filter: a workspace on some other branch is somebody else's work, and a resolved
 * one on this branch is work that is over.
 *
 * <p><b>Never throws.</b> An absent port, an absent answer and a port that threw all mean "nobody is
 * standing on it", which is the not-in-flight answer; the explicit value always wins over all of
 * this.
 */
@ApplicationScoped
public class CampaignInFlight {

  private static final Logger LOG = Logger.getLogger(CampaignInFlight.class);

  private static final Set<String> UNDER_WAY =
      Set.of(
          EntityStatus.IMPLEMENTING.name(),
          EntityStatus.IMPLEMENTED.name(),
          EntityStatus.VERIFYING.name(),
          EntityStatus.VERIFIED.name(),
          EntityStatus.DONE.name());

  @Inject Instance<WorkspaceAgentDispatch> dispatch;

  /** {@code explicit} when given, else the default in the class javadoc. */
  public boolean resolve(Boolean explicit, WorkEntity member) {
    if (explicit != null) {
      return explicit;
    }
    if (member.status != null && UNDER_WAY.contains(member.status)) {
      return true;
    }
    if (member.archetype != Archetype.TICKET && member.archetype != Archetype.EPIC) {
      // Nothing else is ever dispatched, so nothing else can be standing anywhere; the add refuses
      // such a member on its own.
      return false;
    }
    if (dispatch.isUnsatisfied()) {
      return false;
    }
    String branch = EntityWorkspaces.branchOf(member);
    boolean ticket = member.archetype == Archetype.TICKET;
    try {
      return dispatch
          .get()
          .workspacesReferencing(
              ticket ? List.of(member.id) : List.of(), ticket ? List.of() : List.of(member.id))
          .stream()
          .anyMatch(
              reference ->
                  branch.equals(reference.branch())
                      && WorkspaceAgentDispatch.Reference.ACTIVE.equals(reference.status()));
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not look up the workspaces of %s: the port threw", member.id);
      return false;
    }
  }
}

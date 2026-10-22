package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.agenthost.AgentDaemonRegistry;
import eu.wohlben.qits.projects.entity.FrontDesk;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Refinement;
import eu.wohlben.qits.projects.persistence.FrontDeskRepository;
import eu.wohlben.qits.projects.persistence.ProjectRepository;
import eu.wohlben.qits.projects.persistence.RefinementRepository;
import eu.wohlben.qits.projects.refinementhost.RefinementCredentialsChanged;
import eu.wohlben.qits.projects.refinementhost.RefinementTokens;
import eu.wohlben.qits.projectsdaemon.protocol.SessionCredentials;
import eu.wohlben.qits.projectsdaemon.protocol.SessionCredentials.Credential;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/**
 * The credentials a project's front desk runs its sessions on, handed to its daemon over the control
 * socket as one {@link SessionCredentials} frame (qits-1482). Tokens travel here and never in the
 * desk's environment, so no session can read another's off the container.
 *
 * <p><b>The list</b> ({@link #compose(Project, FrontDesk, List)}, pure) — always the full one, since
 * a frame replaces everything the daemon held:
 *
 * <ul>
 *   <li>{@value SessionCredentials#FRONT_DESK_KEY} — the one front desk session, which runs the
 *       delivery loop as turns: the desk's delivery token ({@code desk-delivery}, {@code qits:agent}
 *       + {@code qits:desk}) while the project's loop is on and the row holds one, else its session
 *       token ({@code desk-session}, {@code qits:agent}).
 *   <li>{@value SessionCredentials#DESK_AGENTS_KEY} — every session a person starts from the desk
 *       panel: always the session token, so a person-started session never holds {@code qits:desk}.
 *   <li>one per refinement room of the project holding a token, keyed by its entity id (qits-770's
 *       session key).
 * </ul>
 *
 * A key with no token is <b>left out</b>, never sent empty: the daemon then fails closed for that
 * key, which is the point — a session must not fall back to another's credential.
 *
 * <p><b>When it is sent</b> ({@link #push}): right after a daemon's {@code hello} is accepted, and
 * again whenever the list may have moved — a session or delivery token minted or revoked ({@link
 * FrontDesks}), the delivery setting toggled ({@link FrontDeskHooks}), a refinement room created or
 * discarded or its token minted or revoked ({@link RefinementCredentialsChanged}) — plus once per
 * {@link FrontDeskSweep} pass as a safety net. Only to a daemon whose {@code hello} said capability
 * {@value AgentDaemonRegistry#SESSION_CREDENTIALS_CAPABILITY} or later; an older one is never sent
 * the frame. Re-sending an unchanged list is harmless: the daemon restarts the front desk session
 * only when the {@code front-desk} credential itself changed.
 *
 * <p>Token values are never logged; keys and subjects are.
 */
@ApplicationScoped
public class DeskSessionCredentials {

  private static final Logger LOG = Logger.getLogger(DeskSessionCredentials.class);

  @Inject AgentDaemonRegistry daemons;

  @Inject FrontDeskRepository desks;

  @Inject ProjectRepository projects;

  @Inject RefinementRepository refinements;

  /**
   * The full list for a project, from its project row, its desk row and its refinement rows. Pure: a
   * null project or desk contributes no desk keys; nothing here reads a database.
   */
  public static SessionCredentials compose(
      Project project, FrontDesk desk, List<Refinement> rooms) {
    List<Credential> credentials = new ArrayList<>();
    Credential session =
        desk == null ? null : credential(null, desk.sessionTokenValue, desk.sessionTokenSubject);
    Credential delivery =
        desk == null || project == null || !project.deliveryEnabled
            ? null
            : credential(null, desk.deliveryTokenValue, desk.deliveryTokenSubject);
    Credential frontDesk = delivery != null ? delivery : session;
    if (frontDesk != null) {
      credentials.add(
          new Credential(SessionCredentials.FRONT_DESK_KEY, frontDesk.token(), frontDesk.subject()));
    }
    if (session != null) {
      credentials.add(
          new Credential(SessionCredentials.DESK_AGENTS_KEY, session.token(), session.subject()));
    }
    if (rooms != null) {
      for (Refinement room : rooms) {
        RefinementTokens.credentialOf(room)
            .ifPresent(
                held -> credentials.add(new Credential(held.key(), held.token(), held.subject())));
      }
    }
    return new SessionCredentials(credentials);
  }

  /** A credential under {@code key}, or null when there is no token to carry. */
  private static Credential credential(String key, String token, String subject) {
    if (token == null || token.isBlank()) {
      return null;
    }
    return new Credential(key == null ? "" : key, token, subject);
  }

  /** {@link #compose(Project, FrontDesk, List)} over what the database holds for {@code projectId}. */
  public SessionCredentials compose(String projectId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                compose(
                    projects.findById(projectId),
                    desks.findById(projectId),
                    refinements.listByProject(projectId)));
  }

  /**
   * Send {@code projectId}'s connected desk daemon its full list, if that daemon reads the frame;
   * answers whether a frame went out. A project with no daemon connected, or one below capability
   * {@value AgentDaemonRegistry#SESSION_CREDENTIALS_CAPABILITY}, costs no read. <b>Never throws</b>:
   * every caller has already done its own work, and a missed send is caught by the next change or
   * the next sweep.
   */
  public boolean push(String projectId) {
    if (projectId == null) {
      return false;
    }
    try {
      if (!daemons.acceptsSessionCredentials(projectId)) {
        return false;
      }
      SessionCredentials frame = compose(projectId);
      boolean sent = daemons.sendSessionCredentials(projectId, frame);
      if (sent) {
        LOG.debugf(
            "Sent project %s's desk daemon its session credentials: %s",
            projectId,
            frame.credentials().isEmpty()
                ? "none"
                : frame.credentials().stream()
                    .map(credential -> credential.key() + " (" + credential.subject() + ")")
                    .collect(Collectors.joining(", ")));
      }
      return sent;
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not send project %s's desk daemon its session credentials: %s",
          projectId, e.getClass().getSimpleName());
      return false;
    }
  }

  /** A refinement room of the project came or went, or its token did. */
  void onRefinementCredentialsChanged(@Observes RefinementCredentialsChanged changed) {
    push(changed.projectId());
  }
}

package eu.wohlben.qits.projects.desktriggers;

import eu.wohlben.qits.projects.entity.DeskTriggerWindow;
import eu.wohlben.qits.projects.entity.DeskTriggerWindowState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * <b>The window store's one write from the bus side</b> (epic qits-1604): admit a trigger into its
 * project's open window, opening one when there is none.
 *
 * <p>The rules, each of which the migration (V57) backs with a constraint rather than a hope:
 *
 * <ul>
 *   <li><b>One OPEN window per project.</b> The open row is read {@code for update}, so two admits
 *       for one project queue behind each other rather than both seeing none. When there is none the
 *       insert is {@code on conflict … do nothing} against the partial unique index, and the row is
 *       read again: whoever lost the race joins the winner's window. No unique violation is ever
 *       raised, which matters because a raised one would abort the caller's transaction.
 *   <li><b>{@code closes_at} is set once</b>, to the opening admit's {@code now} plus {@code
 *       qits.projects.desk-triggers.window}, and is <b>never extended</b>. A project that keeps
 *       producing triggers is prompted every window, never starved by its own activity. The one
 *       move is forward: an admit that is {@code prompt} — a trigger the desk asked for itself
 *       ({@link DeskTriggerSource#requestedByDesk()}, qits-1618) — sets it to its own {@code now}
 *       when that is earlier, so the next sweep closes the window and the desk is woken within a
 *       sweep interval instead of a window.
 *   <li><b>A repeat is one line.</b> The event insert is {@code on conflict do nothing} against
 *       {@code (window_id, source, item_ref, label, comment_id)}, so a redelivered frame admits
 *       nothing new. Two comments are two lines: each is its own row (V63).
 * </ul>
 *
 * <p>{@code @Transactional}: it joins the caller's transaction — the listener's own {@code projects}
 * one — and opens one only for a caller that has none.
 */
@ApplicationScoped
public class DeskTriggerWindows {

  /** How long a window admits, from the trigger that opened it. */
  @ConfigProperty(name = "qits.projects.desk-triggers.window", defaultValue = "PT15M")
  Duration window;

  /**
   * Admit {@code trigger}, from {@code source}, into {@code projectId}'s open window, opening one
   * closing at {@code now + window} when there is none.
   *
   * @return the window it is in now (whether or not the line was new)
   */
  @Transactional
  public UUID admit(String projectId, String source, DeskTrigger trigger, Instant now) {
    return admit(projectId, source, trigger, now, false);
  }

  /**
   * {@link #admit(String, String, DeskTrigger, Instant)}, and when {@code prompt} the window's
   * {@code closes_at} is brought forward to {@code now} (never back), so the next sweep closes it.
   *
   * @return the window it is in now (whether or not the line was new)
   */
  @Transactional
  public UUID admit(
      String projectId, String source, DeskTrigger trigger, Instant now, boolean prompt) {
    // Postgres keeps microseconds; a nanosecond instant would read back as a different one.
    Instant at = now.truncatedTo(ChronoUnit.MICROS);
    EntityManager em = DeskTriggerWindow.getEntityManager();
    UUID windowId = openWindowOf(em, projectId);
    if (windowId == null) {
      em.createNativeQuery(
              "insert into desk_trigger_window (id, project_id, opened_at, closes_at, state,"
                  + " attempts) values (:id, :project, :opened, :closes, :state, 0)"
                  + " on conflict (project_id) where state = 'OPEN' do nothing")
          .setParameter("id", UUID.randomUUID())
          .setParameter("project", projectId)
          .setParameter("opened", at)
          .setParameter("closes", at.plus(window))
          .setParameter("state", DeskTriggerWindowState.OPEN.name())
          .executeUpdate();
      windowId = openWindowOf(em, projectId);
      if (windowId == null) {
        throw new IllegalStateException(
            "project " + projectId + " has no open desk trigger window after opening one");
      }
    }
    em.createNativeQuery(
            "insert into desk_trigger_event (id, window_id, source, item_ref, item_id, label,"
                + " cause, comment_id, event_id, occurred_at, admitted_at) values (:id, :window,"
                + " :source, :ref, :itemId, :label, :cause, :commentId, :event, :occurred,"
                + " :admitted)"
                + " on conflict do nothing")
        .setParameter("id", UUID.randomUUID())
        .setParameter("window", windowId)
        .setParameter("source", source)
        .setParameter("ref", trigger.itemRef())
        .setParameter("itemId", trigger.itemId())
        .setParameter("label", trigger.label())
        .setParameter("cause", trigger.cause())
        .setParameter("commentId", trigger.commentId())
        .setParameter("event", trigger.eventId())
        .setParameter("occurred", trigger.occurredAt() == null ? at : trigger.occurredAt())
        .setParameter("admitted", at)
        .executeUpdate();
    if (prompt) {
      em.createNativeQuery(
              "update desk_trigger_window set closes_at = :at where id = :id and closes_at > :at")
          .setParameter("at", at)
          .setParameter("id", windowId)
          .executeUpdate();
    }
    return windowId;
  }

  /** The project's OPEN window, locked for the rest of the transaction; null when there is none. */
  private static UUID openWindowOf(EntityManager em, String projectId) {
    @SuppressWarnings("unchecked")
    List<Object> ids =
        em.createNativeQuery(
                "select id from desk_trigger_window where project_id = :project"
                    + " and state = 'OPEN' for update")
            .setParameter("project", projectId)
            .getResultList();
    return ids.isEmpty() ? null : (UUID) ids.get(0);
  }
}

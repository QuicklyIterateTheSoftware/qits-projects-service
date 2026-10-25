package eu.wohlben.qits.projects.desktriggers;

import eu.wohlben.qits.projects.entity.DeskTriggerEvent;
import eu.wohlben.qits.projects.entity.DeskTriggerWindow;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <b>The read side of the window store</b> (epic qits-1604, task qits-1612): a project's newest
 * windows with their lines, for {@code GET /projects/{projectId}/desk-triggers}. It writes nothing,
 * and reads every state — an OPEN window shows what is collecting, an OWED one why it has not
 * arrived, a FAILED one that it never will.
 *
 * <p>Two queries, not one per window: the windows, newest {@code opened_at} first (the id breaks a
 * tie, so a page is stable), then every line of those windows at once, in the order the message
 * renders them from — {@code admitted_at}, then {@code item_ref}.
 */
@ApplicationScoped
public class DeskTriggerHistory {

  /** One window and its lines, in the order the read answers them. */
  public record Entry(DeskTriggerWindow window, List<DeskTriggerEvent> events) {}

  /** The newest {@code limit} windows of {@code projectId}; an empty list when it has none. */
  @Transactional
  public List<Entry> recent(String projectId, int limit) {
    List<DeskTriggerWindow> windows =
        DeskTriggerWindow.<DeskTriggerWindow>find(
                "projectId", Sort.descending("openedAt").and("id", Sort.Direction.Descending),
                projectId)
            .page(Page.ofSize(limit))
            .list();
    if (windows.isEmpty()) {
      return List.of();
    }
    Map<UUID, List<DeskTriggerEvent>> byWindow = new LinkedHashMap<>();
    windows.forEach(window -> byWindow.put(window.id, new ArrayList<>()));
    for (DeskTriggerEvent event :
        DeskTriggerEvent.<DeskTriggerEvent>list(
            "windowId in ?1 order by admittedAt, itemRef", List.copyOf(byWindow.keySet()))) {
      byWindow.get(event.windowId).add(event);
    }
    return windows.stream()
        .map(window -> new Entry(window, List.copyOf(byWindow.get(window.id))))
        .toList();
  }
}

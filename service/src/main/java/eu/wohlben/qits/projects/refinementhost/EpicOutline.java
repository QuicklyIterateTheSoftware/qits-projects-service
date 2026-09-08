package eu.wohlben.qits.projects.refinementhost;

import eu.wohlben.qits.epics.control.FeatureService;
import eu.wohlben.qits.epics.control.TaskService;
import eu.wohlben.qits.epics.entity.Epic;
import eu.wohlben.qits.epics.entity.Feature;
import eu.wohlben.qits.epics.entity.Task;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * One epic rendered as Markdown — the heading, the description and the feature/task outline — for
 * the workspace goal an agent container opens with.
 *
 * <p>It exists because <b>two</b> flows now hand an agent the same epic: {@link RefinementService}
 * opens a refinement with {@code # Refine: <title>}, and {@code
 * eu.wohlben.qits.projects.api.EpicDispatchController} stands an implementing workspace up with
 * {@code # Implement: <title>}. The two differ in exactly one word, so a second copy of the walk
 * would be two renderings of one epic free to drift apart — and the drift would be silent, because
 * neither is read by anything that could disagree with the other. The heading verb is the parameter;
 * everything below it is the epic.
 *
 * <p>It is a bean rather than a static helper because the walk needs {@link FeatureService} and
 * {@link TaskService}, and this module injects its collaborators rather than passing them.
 *
 * <p><b>Whatever is rendered here is a snapshot and never the brief.</b> The bytes are frozen into
 * the container's goal at creation and the epic goes on moving underneath them, which is why both
 * callers' instructions send the agent to read the epic live over MCP instead of trusting this.
 */
@ApplicationScoped
public class EpicOutline {

  @Inject FeatureService features;

  @Inject TaskService tasks;

  /**
   * The epic under {@code # <heading>: <title>}.
   *
   * @param epic the epic to render
   * @param heading the verb in the heading — {@code "Refine"} or {@code "Implement"}, which is the
   *     whole of what the two callers disagree about
   */
  public String render(Epic epic, String heading) {
    StringBuilder text = new StringBuilder();
    text.append("# ").append(heading).append(": ").append(epic.title).append("\n\n");
    if (epic.description == null || epic.description.isBlank()) {
      text.append("_This draft has no description yet._\n");
    } else {
      text.append(epic.description).append("\n");
    }
    text.append("\n## Outline as it stands\n\n");
    List<Feature> outline = features.listByEpic(epic.id);
    if (outline.isEmpty()) {
      text.append("_No features drafted yet._\n");
      return text.toString();
    }
    for (Feature feature : outline) {
      text.append("- **").append(feature.title).append("**");
      if (feature.description != null && !feature.description.isBlank()) {
        text.append(" — ").append(feature.description);
      }
      text.append("\n");
      for (Task task : tasks.listByFeature(feature.id)) {
        text.append("  - ").append(task.title);
        if (task.description != null && !task.description.isBlank()) {
          text.append(" — ").append(task.description);
        }
        text.append("\n");
      }
    }
    return text.toString();
  }
}

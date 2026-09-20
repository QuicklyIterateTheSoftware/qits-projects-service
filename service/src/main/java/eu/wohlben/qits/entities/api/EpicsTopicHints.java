package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.control.EpicService;
import eu.wohlben.qits.entities.control.FeatureService;
import eu.wohlben.qits.entities.control.TaskService;
import eu.wohlben.qits.projects.api.ProjectChangeHint;
import eu.wohlben.qits.projects.api.ProjectChangePublisher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Turns an EPIC, FEATURE or TASK id into the project whose live channel has to hear about it, and
 * fires the hint. Every controller in this package announces its mutations of those three
 * archetypes through this one bean, so the routes stay free of the walk up the tree.
 *
 * <p><b>Named for the SSE topic it fires, not for an entity kind.</b> {@code
 * ProjectChangeHint.Topic.EPICS} is a wire contract — {@code ProjectEventBroadcaster} lowercases
 * the enum name onto the frame and the SPA subscribes to {@code epics} — so the topic did not move
 * when the module did, and this name tracks it. It was {@code EpicChangeHints}, which read as
 * "hints about an Epic" back when an epic was the container concept; what it actually resolves is
 * three archetypes of one {@code entity} table, and the only thing they have in common is the
 * channel they redraw.
 *
 * <p>Resolve <em>before</em> a delete: once the row is gone there is no way back to its project.
 */
@ApplicationScoped
class EpicsTopicHints {

  @Inject ProjectChangePublisher publisher;

  @Inject EpicService epicService;

  @Inject FeatureService featureService;

  @Inject TaskService taskService;

  /** Announce that the project's epic tree changed. */
  void fire(String projectId) {
    publisher.fire(projectId, ProjectChangeHint.Topic.EPICS);
  }

  String projectOfEpic(String epicId) {
    return epicService.get(epicId).projectId;
  }

  /** The parent is the membership edge's, carried beside the row as a {@code control/Nested}. */
  String projectOfFeature(String featureId) {
    return projectOfEpic(featureService.get(featureId).parentId());
  }

  String projectOfTask(String taskId) {
    return projectOfFeature(taskService.get(taskId).parentId());
  }
}

package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
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
 *
 * <p>The project is read off the row itself — every entity row carries its {@code projectId}, root
 * and node alike — so a feature or a task needs no walk up to its epic. The lookup is still the
 * archetype's, so an id naming no row of that kind is that kind's 404.
 */
@ApplicationScoped
class EpicsTopicHints {

  @Inject ProjectChangePublisher publisher;

  @Inject WorkEntityService entities;

  /** Announce that the project's epic tree changed. */
  void fire(String projectId) {
    publisher.fire(projectId, ProjectChangeHint.Topic.EPICS);
  }

  /** The project of a row of {@code archetype} — EPIC, FEATURE or TASK — or that kind's 404. */
  String projectOf(Archetype archetype, String id) {
    return entities.get(archetype, id).projectId;
  }

  String projectOfEpic(String epicId) {
    return projectOf(Archetype.EPIC, epicId);
  }
}

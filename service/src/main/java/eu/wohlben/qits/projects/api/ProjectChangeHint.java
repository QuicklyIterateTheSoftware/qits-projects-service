package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.entity.Archetype;

/**
 * A payload-free "something changed, re-read it" signal for one project's live channel — the
 * projects flavour of qits-workspaces' {@code WorkspaceChangeHint}. Fired at every epic mutation
 * choke-point (the REST controllers and the epic MCP tools) and delivered over CDI async events to
 * {@link ProjectEventBroadcaster}, which pushes the {@link Topic} name to subscribed browsers.
 *
 * <p>The hint carries no data: the frontend reacts by re-fetching through the unchanged REST
 * endpoints, so a dropped or missed hint self-heals on the next hint or on reconnect.
 *
 * <p>It lives in {@code service} rather than in {@code entities} because every producer is here — the
 * entities module stays free of anything the SSE boundary needs, and it depends on this package
 * nowhere.
 */
public record ProjectChangeHint(String projectId, Topic topic) {

  /** The kind of change; maps 1:1 to a frontend query-invalidation. */
  public enum Topic {
    /** An epic, feature or task of this project was created, changed, moved or removed. */
    EPICS,
    /**
     * A ticket of this project, or a comment on one, was created, changed, transitioned or
     * removed. A topic of its own rather than a second producer on {@link #EPICS}: tickets and
     * epics are sibling roots on separate screens, so one channel would redraw a board because
     * somebody commented on a bug.
     */
    TICKETS,
    /**
     * A per-project refinement agent's live activity changed. Nothing fires it yet — it is on the
     * wire contract from the start so the frontend can subscribe to it before the agent registry
     * exists, rather than needing a second protocol change later.
     */
    AGENT_ACTIVITY;

    /**
     * <b>The topic a write about an entity of {@code archetype} redraws</b> — {@link #TICKETS} for a
     * ticket, {@link #EPICS} for every other archetype (an epic, a feature, a task, a campaign).
     * Read by the writers that address an entity of any kind, a comment above all (qits-551), so
     * the rule the two topics split on is stated once.
     */
    public static Topic of(Archetype archetype) {
      return archetype == Archetype.TICKET ? TICKETS : EPICS;
    }
  }
}

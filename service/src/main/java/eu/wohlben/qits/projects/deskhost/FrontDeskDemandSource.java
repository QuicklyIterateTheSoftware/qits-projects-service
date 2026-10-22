package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.entity.DeskRole;
import eu.wohlben.qits.projects.entity.FrontDesk;
import eu.wohlben.qits.projects.entity.Project;
import java.time.Instant;

/**
 * One reason a project's desk should be running (qits-767). {@link FrontDeskDemand} ORs every source
 * of the desk's {@link #role} it finds: a desk is wanted while any of them wants it. A new reason — "a
 * refinement room is open" (qits-770) — is one more bean, and nothing else changes.
 */
public interface FrontDeskDemandSource {

  /** A short name for the log. */
  String name();

  /**
   * The role of desk this source speaks for (qits-1482). A FRONT source never wants a DELIVERY desk
   * and the other way round; every source is a FRONT one unless it says otherwise.
   */
  default DeskRole role() {
    return DeskRole.FRONT;
  }

  /** Whether this source wants {@code project}'s desk running at {@code now}. Pure; no writes. */
  boolean wants(Project project, FrontDesk row, Instant now);
}

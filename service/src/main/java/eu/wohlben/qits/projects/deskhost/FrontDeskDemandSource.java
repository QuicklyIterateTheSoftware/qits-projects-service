package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.entity.FrontDesk;
import eu.wohlben.qits.projects.entity.Project;
import java.time.Instant;

/**
 * One reason a project's front desk should be running (qits-767). {@link FrontDeskDemand} ORs every
 * source it finds: a desk is wanted while any source wants it. A new reason — "a refinement room is
 * open" (qits-770) — is one more bean, and nothing else changes.
 */
public interface FrontDeskDemandSource {

  /** A short name for the log. */
  String name();

  /** Whether this source wants {@code project}'s desk running at {@code now}. Pure; no writes. */
  boolean wants(Project project, FrontDesk row, Instant now);
}

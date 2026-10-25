package eu.wohlben.qits.projects.refinementhost;

/**
 * A CDI event (epic qits-770): whether a session room of {@code projectId} wants its session may have
 * moved — a room opened, ensured, stopped or discarded. Fired by {@link RefinementService} after the
 * row write has committed; {@code deskhost} observes it, creates the project's desk when a room now
 * wants it and recomputes the desk's desired state, so a refinement starts an {@code ON_DEMAND} desk
 * (the desk's {@code RefinementDemand} source). The sessions list itself is pushed by {@link
 * RefinementSessions}. Like {@link RefinementCredentialsChanged}, it keeps {@code refinementhost} from
 * naming a {@code deskhost} type.
 *
 * @param projectId the project whose desk demand may have moved
 */
public record RefinementDemandChanged(String projectId) {}

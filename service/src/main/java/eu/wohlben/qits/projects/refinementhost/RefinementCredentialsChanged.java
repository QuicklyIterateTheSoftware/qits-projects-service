package eu.wohlben.qits.projects.refinementhost;

/**
 * A CDI event (qits-1482): a refinement room of {@code projectId} was created or discarded, or its
 * token was minted or revoked — so the list of session credentials the project's front desk holds
 * may have moved. Fired by {@link RefinementService} after the row write has committed; {@code
 * deskhost/DeskSessionCredentials} observes it and re-sends the list. The event names only the
 * project, never a token.
 *
 * @param projectId the project whose desk's credential list may have changed
 */
public record RefinementCredentialsChanged(String projectId) {}

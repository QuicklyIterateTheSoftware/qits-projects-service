package eu.wohlben.qits.projects.idphost;

import java.util.List;

/**
 * The commission body's spellings this service's idp adapters share (moved off the retired {@code
 * AgentCredentials} / {@code IdpAgentCredentials}, qits-767).
 */
public final class IdpCommissionWire {

  /** The claim naming the project a credential may act on ({@code QitsClaims.PROJECT}). */
  public static final String PROJECT_CLAIM = "project";

  /** The member stating the Git refs a credential may push (contract C2). */
  public static final String GIT_REFS = "gitRefs";

  /** "May push nothing": stated, never left out — an absent list could push anything. */
  public static final List<String> NO_GIT_REFS = List.of();

  private IdpCommissionWire() {}
}

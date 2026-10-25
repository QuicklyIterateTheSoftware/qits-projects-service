package eu.wohlben.qits.entities.control;

/**
 * <b>Who is moving an entity, as a door verified it</b> (qits-887): a name, and whether that name is
 * a person's. Handed to {@link WorkEntityService#transition} so a {@link TransitionGate} can judge
 * the caller without knowing anything about HTTP, cookies or tokens.
 *
 * <p><b>This module never decides {@link #isPerson}.</b> The service module's doors do, from the one
 * definition of a person the platform has ({@code projects/security/PersonCheck}, qits-891): a
 * REST door builds {@link #person} only when that check verified the caller, and every other
 * caller — an MCP tool, a platform sweep, a dispatch, a test that says nothing — is {@link
 * #machine}. A machine is the default because a gate that needs a person must fail closed: a door
 * that forgot to say who is moving gets refused, never waved through.
 *
 * <p><b>A person Mover may also come from a stored pre-approval</b> (qits-1075): a person's
 * Dispatch press on a REPORTED entity stores their name ({@code WorkEntity.preApprovedBy}), and when
 * the refine phase lands REFINED the platform schedules the entity as {@link #person} under that
 * name. That is not this module deciding who is a person: the name was itself stamped only from a
 * Mover a door verified as a person, at the press, and nothing else writes it.
 *
 * <p><b>A desk Mover is the project's delivery desk</b> (epic qits-1440): a machine — never a person
 * — holding {@code qits:desk} and bound to the project its token's {@code project} claim names
 * ({@link #deskProject}, null when the token carried none). Its one difference from {@link
 * #machine} is that the dispatch path may stamp it with a {@link #declaredApproval}: the
 * {@code start_refined} rule a project declared in its {@code .config/qits/project.yml}, which
 * {@link PersonApprovalGate} accepts in a person's place. Only that path sets one.
 *
 * @param name what the audit records as {@code changedBy} — for a person, the name the proof
 *     carries, never a header or a body field; for a machine, the caller's principal (null for an
 *     anonymous one)
 * @param isPerson whether a door verified a person behind {@code name}
 * @param isDesk whether the caller is a project's delivery desk ({@code qits:desk})
 * @param deskProject the project a desk is bound to, from its token's claim; null otherwise
 * @param declaredApproval the declared rule standing in for a person's approval of the scheduling,
 *     or null — set only on a desk, by the dispatch path, when the project declares it
 */
public record Mover(
    String name, boolean isPerson, boolean isDesk, String deskProject, String declaredApproval) {

  /** A person or a plain machine: no desk, no declared approval. */
  public Mover(String name, boolean isPerson) {
    this(name, isPerson, false, null, null);
  }

  /** A caller nobody verified as a person: an agent, a service client, the platform itself. */
  public static Mover machine(String name) {
    return new Mover(name, false);
  }

  /** A person a door verified, under the name the proof carries. */
  public static Mover person(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("a verified person has a name");
    }
    return new Mover(name, true);
  }

  /**
   * A project's delivery desk (epic qits-1440): a machine under its principal {@code name}, bound
   * to {@code projectId} — its token's {@code project} claim, null when it carried none.
   */
  public static Mover desk(String name, String projectId) {
    return new Mover(name, false, true, projectId, null);
  }

  /**
   * This desk, carrying {@code rule} as the declared approval a person's scheduling move would
   * otherwise need. Refused on anything but a desk: no other machine has a declaration to point at.
   */
  public Mover withDeclaredApproval(String rule) {
    if (!isDesk) {
      throw new IllegalStateException("only a delivery desk carries a declared approval");
    }
    return new Mover(name, false, true, deskProject, rule);
  }

  /** How a refusal names the caller: its name, or "an anonymous caller". */
  public String described() {
    return name == null || name.isBlank() ? "an anonymous caller" : name;
  }
}

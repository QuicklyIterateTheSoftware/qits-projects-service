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
 * @param name what the audit records as {@code changedBy} — for a person, the name the proof
 *     carries, never a header or a body field; for a machine, the caller's principal (null for an
 *     anonymous one)
 * @param isPerson whether a door verified a person behind {@code name}
 */
public record Mover(String name, boolean isPerson) {

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

  /** How a refusal names the caller: its name, or "an anonymous caller". */
  public String described() {
    return name == null || name.isBlank() ? "an anonymous caller" : name;
  }
}

package eu.wohlben.qits.projects.entity;

/**
 * What a project's desk is for (qits-1482, V49): {@code front_desk.role}, spelled as the constant's
 * name. A project has at most one desk of each role.
 *
 * <p>The desk id is derived from the project id and the role, never stored as a choice: a FRONT
 * desk's id <em>is</em> its project id, so every path, socket, proxy URL and runner key that
 * addressed the one desk a project used to have still addresses it byte for byte; a DELIVERY desk's
 * is {@code <projectId>}{@link #DELIVERY_SUFFIX}. A project id is a UUID minted by this service, so
 * the suffix can never be part of one, and {@link #ofDeskId} and {@link #projectIdOf} read a desk
 * id back without a database read — which is what the event-loop checks need.
 */
public enum DeskRole {
  /** The project's front desk: its people's and its agents' sessions. */
  FRONT,
  /** The delivery loop's own desk (epic qits-1440), wanted only while the loop is enabled. */
  DELIVERY;

  /** What a DELIVERY desk's id adds to its project id. */
  public static final String DELIVERY_SUFFIX = "-delivery";

  /** The id of {@code projectId}'s desk of this role. */
  public String deskId(String projectId) {
    return this == FRONT ? projectId : projectId + DELIVERY_SUFFIX;
  }

  /** The role a desk id names: DELIVERY for one ending in {@link #DELIVERY_SUFFIX}, else FRONT. */
  public static DeskRole ofDeskId(String deskId) {
    return deskId != null
            && deskId.length() > DELIVERY_SUFFIX.length()
            && deskId.endsWith(DELIVERY_SUFFIX)
        ? DELIVERY
        : FRONT;
  }

  /** The project a desk id belongs to. */
  public static String projectIdOf(String deskId) {
    return ofDeskId(deskId) == DELIVERY
        ? deskId.substring(0, deskId.length() - DELIVERY_SUFFIX.length())
        : deskId;
  }
}

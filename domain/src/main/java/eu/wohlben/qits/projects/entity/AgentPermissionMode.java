package eu.wohlben.qits.projects.entity;

/**
 * Whether a session's tool calls are auto-approved or prompted for.
 *
 * <p>{@link #SKIP_PERMISSIONS} is what every launch on this platform renders today — {@code
 * --dangerously-skip-permissions}, unconditionally, on every launch shape in both daemons — so it is
 * what all eight surfaces are seeded with. The knob exists because that was an invariant nobody
 * chose: it is also the amplifier that makes an attached third-party MCP server dangerous, and the
 * external-server catalog is the first real reason for a surface to be anything but this.
 */
public enum AgentPermissionMode {
  /** Tools run auto-approved: the harness's skip-permissions flag. */
  SKIP_PERMISSIONS,
  /** The harness asks before a tool that is not pre-approved runs. */
  PROMPT
}

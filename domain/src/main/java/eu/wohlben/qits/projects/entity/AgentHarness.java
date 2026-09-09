package eu.wohlben.qits.projects.entity;

/**
 * Which coding-agent harness a session surface launches — the store's copy of the daemons' {@code
 * AgentType}.
 *
 * <p>A copy rather than a shared type, and that is forced rather than chosen: the daemons'
 * {@code AgentType} lives in a framework-free module inside each daemon (soon in the shared {@code
 * eu.wohlben.qits:qits-coding-agents} library), and this service depends on neither. What crosses
 * between them is the <em>name</em> — {@code CLAUDE} / {@code KIMI} — which is what the resolved
 * document carries and what the library parses back. Keep the two spellings identical; a rename on
 * either side is a wire break that no suite in either repository would notice.
 */
public enum AgentHarness {
  CLAUDE,
  KIMI
}

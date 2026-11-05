package eu.wohlben.qits.projects.entity;

/**
 * What a backup reset did to the twin's main (qits-1885) — the two outcomes the door accepts. The
 * other two shapes the twin can be in, ahead of ours or diverged from it with a common base, are
 * refused before anything is pushed, so they never reach a row.
 */
public enum BackupResetKind {
  /**
   * The twin had no main, held ours already, or held an ancestor of ours: the same push a backup
   * makes, without force.
   */
  FAST_FORWARD,
  /**
   * The twin's main and ours share no commit at all: main was force-updated under a lease on the
   * sha the door probed, and the tags pushed without force.
   */
  UNRELATED_RESET
}

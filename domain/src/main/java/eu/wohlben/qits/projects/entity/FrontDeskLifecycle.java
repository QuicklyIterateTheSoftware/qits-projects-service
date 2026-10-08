package eu.wohlben.qits.projects.entity;

/**
 * How a project's front desk — its agent container running the {@code project.work} session — is
 * kept, as the wrapper's {@code .config/qits/project.yml} declares it under {@code
 * front_desk.lifecycle}.
 *
 * <p>{@link #ON_DEMAND} is what an absent key means and what every project had before the key
 * existed, which is why it is the column's default (V35).
 */
public enum FrontDeskLifecycle {
  /** Kept up permanently, and the session starts by itself. */
  ALWAYS_ON,
  /** Up while the desk panel is open, plus the idle window. The default. */
  ON_DEMAND
}

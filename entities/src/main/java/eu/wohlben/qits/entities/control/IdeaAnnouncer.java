package eu.wohlben.qits.entities.control;

/**
 * Tells the rest of the platform that an idea changed (qits-1387): created, edited (its derived
 * status included), archived or unarchived.
 *
 * <p><b>A port, for {@link TransitionAnnouncer}'s reason</b>: this module publishes nothing and
 * knows of no bus, so the announcement leaves through a seam declared here and implemented in
 * {@code service/…/bus/}. <b>Absent is a supported configuration</b> — injected as an {@code
 * Instance<T>}, and with no implementation an idea write simply announces nothing.
 *
 * <p><b>Nothing here may throw</b>, and nothing here is called inside the transaction the write ran
 * in: the write seam is {@code WritePatience}, whose body re-runs on a retry. {@link
 * IdeaAnnouncements} is the one caller, after the hold has returned.
 */
public interface IdeaAnnouncer {

  /** {@code change} committed. One call per idea per change. */
  void onIdeaChanged(IdeaChange change);
}

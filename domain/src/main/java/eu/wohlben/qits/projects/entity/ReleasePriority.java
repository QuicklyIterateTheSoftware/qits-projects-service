package eu.wohlben.qits.projects.entity;

import java.util.Collection;
import java.util.Optional;

/**
 * How urgently a <b>participating branch</b> wants to be released — the signal a queue reads when
 * there is more to build than there is capacity to build it with.
 *
 * <p><b>It sits on the source, not on the request.</b> A release request is an octopus merge of N
 * branches and each of them was put on it by somebody with their own reason; a single value on the
 * request would make the last caller's urgency the whole fold's. What the request answers with is
 * the {@link #max(Collection) max} over its named sources, which is the only reading that cannot
 * lose an escalation: a BLOCKING branch folded in beside a LOWEST one is a blocking release.
 *
 * <p><b>The order of the constants IS the order</b> — comparison is by ordinal, the way {@link
 * Enum#compareTo} already spells it, so a value inserted in the middle of this list re-ranks every
 * stored row and a new one belongs at whichever end it really is. {@link #MEDIUM} is the default a
 * caller who states nothing gets, and it is deliberately not the lowest: "I did not say" must not
 * read as "this can wait forever".
 *
 * <p><b>Nothing reorders anything yet.</b> The value is inert data today: it rides down the chain
 * onto {@code ReleaseRequestChanged}, {@code SCMRelease} and eventually a deployment request, and no
 * consumer acts on it. The queue-ordering feature is what turns it into behaviour.
 *
 * <p>A local copy rather than a shared vocabulary jar, the ruling every cross-context type here
 * takes: a jar this platform's Maven registry does not serve is a build that resolves from a
 * developer's {@code ~/.m2}. Consumers carry the value as a plain string.
 *
 * <p><b>Nothing here throws.</b> {@link #of} answers empty for a word that names no priority and
 * the control layer turns that into the 400 naming the value — a domain enum that threw would put
 * an HTTP status in the vocabulary.
 */
public enum ReleasePriority {
  LOWEST,
  LOW,
  MEDIUM,
  HIGH,
  HIGHER,
  BLOCKING;

  /** What a source is worth when nobody said — and what an empty source set answers. */
  public static final ReleasePriority DEFAULT = MEDIUM;

  /**
   * The priority this word names, or empty where it names none.
   *
   * <p><b>Exact match, case included.</b> The values are a wire vocabulary — they travel through
   * the REST bodies, the DTOs and the events as the constant's own name — so accepting {@code high}
   * for {@link #HIGH} would make the spelling a thing each caller decides. Null and blank name
   * nothing; a caller who stated nothing is the control layer's default, not this method's.
   */
  public static Optional<ReleasePriority> of(String name) {
    if (name == null || name.isBlank()) {
      return Optional.empty();
    }
    for (ReleasePriority candidate : values()) {
      if (candidate.name().equals(name)) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }

  /**
   * The highest of them, and {@link #DEFAULT} where there are none — the request's effective
   * priority, computed over its named branch sources.
   *
   * <p>Empty answers {@code MEDIUM} rather than {@code LOWEST} for the reason the default is
   * {@code MEDIUM} at all: a request whose sources say nothing is an ordinary request, not one
   * that may be starved. Nulls are skipped, which is what lets a caller pass the rows straight in.
   */
  public static ReleasePriority max(Collection<ReleasePriority> priorities) {
    ReleasePriority highest = null;
    for (ReleasePriority candidate : priorities) {
      if (candidate != null && (highest == null || candidate.ordinal() > highest.ordinal())) {
        highest = candidate;
      }
    }
    return highest == null ? DEFAULT : highest;
  }
}

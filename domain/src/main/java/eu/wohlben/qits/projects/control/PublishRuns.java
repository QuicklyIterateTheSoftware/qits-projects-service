package eu.wohlben.qits.projects.control;

import java.util.Optional;

/**
 * Whether qits-ci runs a <b>release run</b> for a released tag — the publish gate's membership
 * question, and the one question about that gate this service cannot answer for itself.
 *
 * <p><b>Why it is not answerable here.</b> {@link ReleaseArtifacts#SLOT_CONFIG} naming an {@code
 * archetype:} says that qits-ci <em>composes</em> something for the repository; it does not say that
 * what it composes has a {@code release:} slot, and only a composition carrying that slot produces a
 * run at a tag. Two things put the answer out of reach from here. The archetype lives at the
 * <b>wrapper repository's {@code main}</b>, which is a different repository from the one being
 * released and one this service has no business reading pipeline schemas out of. And a repository's
 * own file may <b>override the slot wholesale</b> ({@code CiReleaseComposer.choose} over there), so
 * even a correct reading of the archetype could be the wrong answer for this repository. qits-ci is
 * the composer; the composer is asked.
 *
 * <p><b>The cost of guessing was measured</b>, which is why this port exists at all: the {@code
 * spa-frontend} and {@code cli} archetypes deliberately declare no {@code release:} slot — an SPA
 * publishes nothing, because the consuming service carries it as a submodule and builds the bundle
 * into its own image — so every release of such a repository stamped a PENDING publish gate that no
 * run would ever answer, and its {@code main} never moved again (qits-observability-frontend,
 * 2026-09-16).
 *
 * <p>A port in the house shape: the implementation is {@code service/…/releasehost} (one HTTP read
 * of {@code GET /ci/api/repositories/{repoId}/release-phase?rev=…}), resolved through {@code
 * Instance} with absent supported, and it must not throw.
 *
 * <p><b>{@code Optional.empty()} means "could not ask" and NEVER "no release run".</b> The port
 * unconfigured, qits-ci unreachable, a 503 saying it could not answer, an unreadable body — all one
 * answer, and {@code ReleaseFinalization} reads it as {@link
 * ReleaseFinalization.Readability#UNKNOWN_FOR_NOW}: the released tag stays ungated and the sweep
 * asks again. Collapsing it into {@code false} would merge a tag to {@code main} whose publish was
 * never checked, and a wrong {@code main} is not something a later answer can take back.
 */
public interface PublishRuns {

  /**
   * Does qits-ci run a release run for this repository at this rev?
   *
   * @param repoId the repository the released tag belongs to
   * @param rev the released tag as a full ref — {@code refs/tags/<version>}, which is what the tree
   *     read beside this call already uses
   * @return {@code true} when the composition for that rev declares a {@code release:} slot, {@code
   *     false} when it declares none, and empty when the question could not be asked at all
   */
  Optional<Boolean> declaredFor(String repoId, String rev);
}

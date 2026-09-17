package eu.wohlben.qits.projects.refinementhost;

import eu.wohlben.qits.workspacedaemon.protocol.WorkspaceImage;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Says out loud, once at boot, that a retired configuration key is set and is no longer read.
 *
 * <h2>What is retired, and why the entry is still there</h2>
 *
 * <p>Until 2026-09-17 the refinement container's image version came from {@code
 * qits.projects.refinement-image-version}, written into this deployment's environment by
 * qits-configuration's {@code ImagePins} on every {@code qits/workspace} release. Both halves of
 * that are gone: the declaration has left {@code .config/qits/configuration.yml}, and {@link
 * RefinementContainerFactory} takes the version from the dependency this reactor pins, whose own
 * version <em>is</em> the image tag.
 *
 * <p><b>But the entry already written is still in every deployment's environment.</b> Nothing on
 * this platform deletes a configuration entry — qits-configuration's own service says so, "an
 * orphan is reported and never cleaned up" — and this service holds no credential that could. What
 * makes it stop deciding is that the override moved to {@code …-version-override}: a different
 * name, so the residue is read by nobody.
 *
 * <h2>Why a warning rather than nothing</h2>
 *
 * <p>Inert and invisible is the wrong pair. A value sitting in the environment that <em>looks</em>
 * like it decides which image a refinement starts, and does not, is exactly the sort of thing that
 * costs somebody an afternoon — and this process is the only reader placed to notice it. So it
 * names the key, says what replaced it, and says it can be deleted: residue becomes a work item
 * somebody with {@code qits:admin} closes whenever, instead of a prerequisite for this change to
 * work.
 *
 * <p><b>A WARN and never a refusal.</b> The entry is harmless by construction. Refusing to start
 * over a stale key would turn a tidy-up into an outage, and this service starting is worth more
 * than this service being fastidious.
 *
 * <h2>Why it is its own bean, and why every property on it is Optional</h2>
 *
 * <p>Both are one rule, and qits-workspaces paid for it in the pilot: observing {@link
 * StartupEvent} <b>forces the observing bean to be created at boot</b>. Hanging this observer on
 * {@link RefinementContainerFactory} — the obvious place, since that is the class the key used to
 * decide — would make every {@code @QuarkusTest} in this module satisfy that factory's required
 * config (the image repo, the idp block, the own-host pair), and the suite would go red on
 * configuration rather than on behaviour. A bean whose every {@code @ConfigProperty} is {@code
 * Optional} can be created anywhere, which is what this one is. Keep it that way: a required
 * property added here is the same failure again.
 */
@ApplicationScoped
public class RetiredRefinementImageVersionKey {

  private static final Logger LOG = Logger.getLogger(RetiredRefinementImageVersionKey.class);

  /**
   * What qits-configuration's {@code ImagePins} used to write for the refinement image. {@code
   * Optional} for the reason the class javadoc gives, and not only because the key may be absent.
   */
  @ConfigProperty(name = "qits.projects.refinement-image-version")
  Optional<String> retiredRefinementKey;

  void onStart(@Observes StartupEvent startup) {
    warnIfSet(
        "qits.projects.refinement-image-version", retiredRefinementKey, WorkspaceImage.VERSION);
  }

  /**
   * Blank counts as unset, for the reason the override itself filters blanks: SmallRye maps an
   * environment variable onto the property, and a deployment that renders {@code KEY=} produces a
   * present, empty value rather than an absent one. Warning about that would be warning about a
   * template with nothing in it.
   */
  private static void warnIfSet(String key, Optional<String> value, String pinned) {
    value
        .filter(set -> !set.isBlank())
        .ifPresent(
            set ->
                LOG.warnf(
                    "%s is set to '%s' and is NO LONGER READ: this service takes the refinement"
                        + " image version from the dependency it pins (%s). Set %s-override to pin"
                        + " a different image deliberately; otherwise this entry can be deleted.",
                    key, set, pinned, key));
  }
}

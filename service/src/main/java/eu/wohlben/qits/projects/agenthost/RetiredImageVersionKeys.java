package eu.wohlben.qits.projects.agenthost;

import eu.wohlben.qits.projectsdaemon.protocol.ProjectAgentImage;
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
 * <p>Until 2026-09-17 the agent image version came from {@code qits.projects.agent-image-version},
 * written into this deployment's environment as {@code QITS_PROJECTS_AGENT_IMAGE_VERSION} by
 * qits-configuration's release listener on every {@code qits/project-agent} release. Both halves of
 * that are gone here: {@link AgentContainerFactory} takes the version from the dependency this
 * reactor pins ({@link ProjectAgentImage#VERSION}, whose own version <em>is</em> the image tag), and
 * the override that remains is at a different name, {@code
 * qits.projects.agent-image-version-override}.
 *
 * <p><b>But the entry already written is still in every deployment's environment.</b> Nothing on
 * this platform deletes a configuration entry — qits-configuration reports an orphan and never
 * cleans one up — and this service holds no credential that could. What makes it stop deciding is
 * the rename, not a deletion: a different name is read by nobody. The declaration for it is removed
 * from {@code .config/qits/configuration.yml} in the same change, which is what turns the stored
 * entry into a visible {@code orphaned} row over there.
 *
 * <p>The refinement image's key is <b>not</b> here. {@code qits.projects.refinement-image-version}
 * is still read, still followed through qits-configuration by qits-workspace-daemon's releases, and
 * is a different ticket's subject; a class named for what is retired must not quietly imply it.
 *
 * <h2>Why a warning rather than nothing, and never a refusal</h2>
 *
 * <p>Inert and invisible is the wrong pair. A value sitting in the environment that <em>looks</em>
 * like it decides which image an agent container starts, and does not, is exactly the sort of thing
 * that costs somebody an afternoon — and this process is the only reader placed to notice it. So it
 * names the key, its stale value, the version being started instead, and the key to set to pin
 * deliberately.
 *
 * <p>It is a WARN and never a refusal to start. Refusing to boot over a configuration entry nobody
 * can delete would turn a tidy-up into an outage, on every deployment at once, for a value that
 * changes nothing about what this process does.
 *
 * <h2>Why it is its own bean, and why every property is {@code Optional}</h2>
 *
 * <p>The qits-workspaces pilot put this on its container factory for one commit, which is a mistake
 * worth recording rather than repeating: observing {@link StartupEvent} forces the observing bean to
 * be <em>constructed</em> at boot, and {@link AgentContainerFactory} carries required config with no
 * defaults ({@code qits.projects.agent-image-repo}, the daemon api token, the git base). Every
 * {@code @QuarkusTest} in the module that had never needed the factory suddenly had to satisfy all
 * of it, and a whole suite went red on configuration rather than on behaviour. A bean whose every
 * property is {@code Optional} can be constructed anywhere, which is what this one is.
 */
@ApplicationScoped
public class RetiredImageVersionKeys {

  private static final Logger LOG = Logger.getLogger(RetiredImageVersionKeys.class);

  /** The key qits-configuration's {@code ImagePins} release listener used to write. */
  @ConfigProperty(name = "qits.projects.agent-image-version")
  Optional<String> retiredAgentImageVersion;

  void onStart(@Observes StartupEvent startup) {
    warnIfSet(
        "qits.projects.agent-image-version", retiredAgentImageVersion, ProjectAgentImage.VERSION);
  }

  /**
   * Blank counts as unset, for the reason {@link AgentContainerFactory#imageVersion()} gives:
   * SmallRye maps an environment variable onto the property, and a deployment that renders {@code
   * KEY=} produces a present, empty value rather than an absent one. Warning about that would be
   * warning about a template with nothing in it.
   */
  private static void warnIfSet(String key, Optional<String> value, String pinned) {
    value
        .filter(set -> !set.isBlank())
        .ifPresent(
            set ->
                LOG.warnf(
                    "%s is set to '%s' and is NO LONGER READ: this service takes the agent image"
                        + " version from the dependency it pins (%s). Set %s-override to pin a"
                        + " different image deliberately; otherwise this entry can be deleted.",
                    key, set, pinned, key));
  }
}

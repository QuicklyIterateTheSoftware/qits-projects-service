package eu.wohlben.qits.projects.agenthost;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Says out loud, once at boot, that a retired container git key is set and is no longer read.
 *
 * <h2>What is retired, and why one of the entries was actively harmful</h2>
 *
 * <p>Until 2026-09-18 each container harness read its own key for the same fact: {@code
 * qits.projects.agent-git-base} (the address a project agent container clones from, <em>including
 * </em> qits-githost's {@code /git} path) and {@code qits.projects.refinement-git-url} (the same
 * address for a refinement container, without the path). Both are now {@code
 * qits.projects.container-git-url} — one key for one concept, scheme, host and port, with each
 * factory appending {@code /git} itself.
 *
 * <p>The rename is not tidying. The deployed entry {@code env.QITS_PROJECTS_AGENT_GIT_BASE} names
 * qits-githost's <b>service</b> alias, {@code http://dev-qits-githost:8080/git}, and that address
 * <b>refuses the credential every container image authenticates git with</b>: the image's {@code
 * /usr/local/bin/qits-git-credential} helper answers {@code username=oauth2} plus a token, git sends
 * it as HTTP Basic, and only the <em>internal</em> alias's oauth2 transport turns Basic into the
 * Bearer the git host accepts. Measured on the live platform, same token and same repository:
 *
 * <pre>
 *   dev-qits-githost:8080       Basic oauth2:&lt;token&gt; -&gt; 401    Bearer -&gt; 200
 *   githost.dev.internal:8080   Basic oauth2:&lt;token&gt; -&gt; 200    Bearer -&gt; 200
 * </pre>
 *
 * <p>The shipped defaults were corrected, and that changed nothing: <b>a stored configuration entry
 * outranks a shipped default</b>, and nothing on this platform deletes an entry — qits-configuration
 * reports an orphan and never cleans one up — while only a {@code qits:admin} caller can rewrite
 * one. So the poisoned value went on being handed to every agent container. What makes it stop
 * deciding is that the code reads a <b>different name</b>: the residue is read by nobody. The
 * declaration for it leaves {@code .config/qits/configuration.yml} in the same change, which is what
 * turns the stored entry into a visible {@code orphaned} row over there.
 *
 * <p>{@code qits.projects.refinement-git-url} is named here for completeness rather than because it
 * ever hurt: no deployment carries an entry for it, it was declared nowhere, and the refinement
 * harness has always run on the default this change leaves unmoved. It is retired all the same,
 * because a second name for one fact is how the two halves drift apart again.
 *
 * <h2>Why a warning rather than nothing, and never a refusal</h2>
 *
 * <p>Inert and invisible is the wrong pair. A value sitting in the environment that <em>looks</em>
 * like it decides where a container clones from, and does not, is exactly the sort of thing that
 * costs somebody an afternoon — and this process is the only reader placed to notice it. So it names
 * the key, its stale value, the key read instead, and says the entry can be deleted by somebody
 * holding {@code qits:admin}: residue becomes a work item closed whenever, rather than a
 * prerequisite for this change to work.
 *
 * <p><b>A WARN and never a refusal.</b> The entry is harmless by construction once nothing reads it.
 * Refusing to start over a configuration entry this service cannot delete would turn a tidy-up into
 * an outage, on every deployment at once, for a value that changes nothing about what this process
 * does.
 *
 * <h2>Why it is its own bean, and why every property on it is Optional</h2>
 *
 * <p>Both are one rule, and its siblings {@link RetiredImageVersionKeys} and {@code
 * refinementhost.RetiredRefinementImageVersionKey} carry the same one: observing {@link
 * StartupEvent} <b>forces the observing bean to be created at boot</b>. Hanging this observer on
 * {@link AgentContainerFactory} — the obvious place, since that is a class the key used to decide
 * for — would make every {@code @QuarkusTest} in this module satisfy that factory's required config
 * (the image repo, the idp block, the own-host pair), and the suite would go red on configuration
 * rather than on behaviour. A bean whose every {@code @ConfigProperty} is {@code Optional} can be
 * created anywhere, which is what this one is. Keep it that way: a required property added here is
 * the same failure again.
 */
@ApplicationScoped
public class RetiredContainerGitKeys {

  private static final Logger LOG = Logger.getLogger(RetiredContainerGitKeys.class);

  /** The key that used to decide where an agent container clones from, path included. */
  @ConfigProperty(name = "qits.projects.agent-git-base")
  Optional<String> retiredAgentGitBase;

  /** The key that used to decide where a refinement container clones from. */
  @ConfigProperty(name = "qits.projects.refinement-git-url")
  Optional<String> retiredRefinementGitUrl;

  /**
   * The key that is read instead, quoted back so the warning says what this process actually
   * resolved rather than what it ships. {@code Optional} like everything else on this bean — a
   * required read here would be the boot failure the class javadoc warns about, even though the
   * shipped configuration always answers.
   */
  @ConfigProperty(name = "qits.projects.container-git-url")
  Optional<String> containerGitUrl;

  void onStart(@Observes StartupEvent startup) {
    warnIfSet("qits.projects.agent-git-base", retiredAgentGitBase);
    warnIfSet("qits.projects.refinement-git-url", retiredRefinementGitUrl);
  }

  /**
   * Blank counts as unset, for the reason every override here filters blanks: SmallRye maps an
   * environment variable onto the property, and a deployment that renders {@code KEY=} produces a
   * present, empty value rather than an absent one. Warning about that would be warning about a
   * template with nothing in it.
   */
  private void warnIfSet(String key, Optional<String> value) {
    value
        .filter(set -> !set.isBlank())
        .ifPresent(
            set ->
                LOG.warnf(
                    "%s is set to '%s' and is NO LONGER READ: both container harnesses take the"
                        + " address a container reaches git at from"
                        + " qits.projects.container-git-url, which this process resolved to '%s'."
                        + " A stale entry under the old name cannot decide anything any more;"
                        + " somebody holding qits:admin can delete it.",
                    key, set, containerGitUrl.orElse("<unset>")));
  }
}

package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.dto.AgentHarnessCapabilityDto;
import eu.wohlben.qits.projects.entity.AgentHarness;
import java.util.List;

/**
 * What each harness reads as before any container has reported — the shipped fallback behind the
 * editor's dropdowns on a fresh estate.
 *
 * <p><b>An empty dropdown is worse than a slightly stale one</b>, and that is the whole reason this
 * class exists. A platform that has just been stood up has started no container, so nothing has
 * probed a binary and the cache is empty; the editor must still let somebody pick a model. So a
 * harness with no row answers this, flagged {@code shipped: true} so the editor can say plainly that
 * it is showing what ships rather than what a binary reported.
 *
 * <p><b>These values are a copy of the library's, and the copy is deliberate.</b> The authoritative
 * fallback lives in {@code eu.wohlben.qits:qits-coding-agents} beside the probes that produce the
 * real answers (task 9b347736), because that is where a harness upgrade is noticed. This service
 * cannot depend on that library — it is not published yet, and when it is, a host-side service
 * pinning a harness library to render a dropdown would be the wrong dependency anyway. What keeps the
 * two honest is that this copy is only ever read when the cache is empty: the first container to
 * start overwrites it with the binary's own answer, so a drift here has a lifetime of one container
 * start rather than for ever.
 *
 * <p><b>What each harness actually exposes</b>, checked against the binaries in today's workspace
 * image:
 *
 * <ul>
 *   <li><b>Claude Code</b> — {@code --model} takes an alias ({@code opus}, {@code sonnet}, {@code
 *       haiku}, {@code fable}) or a full model name, and there is <em>no listing command</em>:
 *       {@code claude} has {@code agents}, {@code auth}, {@code mcp}, {@code plugin}, {@code
 *       project}, {@code doctor}, {@code install} and nothing that prints a model catalogue. So the
 *       models below are an alias set and {@code modelsEnumerated} is <b>false</b> — the editor must
 *       lead with the free-text escape, because pinning a full model id is exactly what somebody
 *       comes to this field for. {@code --effort} is a real flag whose {@code --help} enumerates its
 *       levels.
 *   <li><b>Kimi Code</b> — {@code kimi provider list --json} prints the configured providers and
 *       their model aliases, so the real report <em>is</em> enumerated; but the aliases depend on
 *       what that container's {@code config.toml} configures, so there is nothing honest to ship as
 *       a fallback and the list here is empty. Kimi has <b>no effort flag at all</b>, which is a
 *       fact about the harness rather than about a probe, so {@code effortSupported} is false here
 *       and stays false in every real report.
 * </ul>
 *
 * <p>Framework-free by construction — constants and pure functions, so the fallback is assertable
 * without a database or a container, the same discipline {@link AgentSurfaceDefaults} keeps.
 */
public final class AgentCapabilityDefaults {

  private AgentCapabilityDefaults() {}

  /**
   * Claude Code's model aliases. Not a catalogue the binary printed — it cannot print one — which is
   * why every report for this harness carries {@code modelsEnumerated: false}.
   */
  public static final List<String> CLAUDE_MODELS = List.of("opus", "sonnet", "haiku", "fable");

  /** The levels {@code claude --help} enumerates for {@code --effort}. */
  public static final List<String> CLAUDE_EFFORT_LEVELS =
      List.of("low", "medium", "high", "xhigh", "max");

  /**
   * Kimi's models, empty on purpose: they come from the container's own provider configuration and
   * there is no set this platform could ship that would be true of any particular container.
   */
  public static final List<String> KIMI_MODELS = List.of();

  /** The shipped fallback for one harness — everything unknown, nothing invented. */
  public static AgentHarnessCapabilityDto shipped(AgentHarness harness) {
    boolean claude = harness == AgentHarness.CLAUDE;
    return new AgentHarnessCapabilityDto(
        harness.name(),
        // No image version: nothing reported, so there is no build to name. Empty rather than a
        // placeholder, because "unknown" and "some build called unknown" must not look alike.
        "",
        "",
        claude ? CLAUDE_MODELS : KIMI_MODELS,
        // False for both, and for two different reasons: Claude cannot enumerate, and Kimi has not
        // been asked yet. The editor's behaviour is the same either way — offer the free text.
        false,
        claude,
        claude ? CLAUDE_EFFORT_LEVELS : List.of(),
        // NOT authenticated, because nobody has looked. Fail-closed: an editor that claimed a
        // harness was signed in on the strength of a shipped constant would be inventing the one
        // fact feature e560229a exists to stop inventing.
        false,
        "No container has reported; nobody has checked whether this harness is signed in.",
        false,
        "",
        "",
        null,
        true,
        List.of());
  }

  /** The whole fallback catalogue — every harness, in {@link AgentHarness}'s own order. */
  public static List<AgentHarnessCapabilityDto> shippedCatalogue() {
    return List.of(AgentHarness.values()).stream().map(AgentCapabilityDefaults::shipped).toList();
  }
}

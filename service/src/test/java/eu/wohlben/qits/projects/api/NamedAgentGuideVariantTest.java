package eu.wohlben.qits.projects.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.projects.control.DevelopmentGuideDefaults;
import eu.wohlben.qits.projects.security.AgentTokens;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * qits-2154: a named agent's own token — idp kind {@code named-agent}, {@code qits:agent} alone,
 * claims {@code {project}}, a {@code tok-} subject of its own — reads the {@code agent} guide,
 * never the desk's. It holds no {@code qits:desk}, so {@link DevelopmentGuideController#detect}
 * already says so; this pins it, because a named agent handed the desk's guide would be handed the
 * desk's triggers and delivery with it.
 */
class NamedAgentGuideVariantTest {

  private static final String PROJECT = "11111111-2222-3333-4444-555555555555";

  @Test
  void aNamedAgentsTokenReadsTheAgentVariantNeverTheDesks() {
    String variant =
        DevelopmentGuideController.detect(
            AgentTokens.token(
                Map.of("sub", "tok-ui-builder-0001", "project", PROJECT), "qits:agent"));

    assertEquals(DevelopmentGuideDefaults.AGENT, variant);
    assertEquals("agent", variant);
  }
}

package eu.wohlben.qits.projects.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads the development guide (qits-1679) out of the agent-surface store and composes a variant;
 * see {@link DevelopmentGuideDefaults}. Also seeds and rolls forward its rows on boot.
 */
@ApplicationScoped
public class DevelopmentGuideService {

  @Inject AgentSurfaceConfigurationService surfaces;

  /**
   * The variant's markdown: the holder's brief (its surface's live system prompt, when it has
   * one), the variant's own part, then, for an in-platform variant, the development flow.
   */
  public String render(String variant) {
    List<String> parts = new ArrayList<>();
    String part = text(DevelopmentGuideDefaults.key(variant));
    if (!part.isBlank()) {
      parts.add(part.strip());
    }
    String holder = DevelopmentGuideDefaults.HOLDER_SURFACE.get(variant);
    if (holder != null) {
      String brief = surfaces.get(holder).systemPrompt();
      if (brief != null && !brief.isBlank()) {
        parts.add("## Your brief\n\n" + brief.strip());
      }
      String flow = text(DevelopmentGuideDefaults.FLOW_KEY);
      if (!flow.isBlank()) {
        parts.add(flow.strip());
      }
    }
    return String.join("\n\n", parts) + "\n";
  }

  /** One stored part, or its shipped text where no row exists. */
  String text(String key) {
    String stored = surfaces.get(key).systemPrompt();
    return stored == null ? "" : stored;
  }

  /** Seed every part the store lacks. Answers how many rows were written. */
  public int seed() {
    int written = 0;
    for (String key : DevelopmentGuideDefaults.KEYS) {
      if (surfaces.seedIfAbsent(key)) {
        written++;
      }
    }
    return written;
  }

  /**
   * Rewrite each part that still holds a previous shipped text to the current one. An edited part
   * stays. Answers how many rows were rewritten.
   */
  public int rollForward() {
    return rollForward(DevelopmentGuideDefaults.PREVIOUS);
  }

  /** {@link #rollForward()} over the given previous texts; what the suite drives. */
  public int rollForward(Map<String, List<String>> previousTexts) {
    int rewritten = 0;
    for (Map.Entry<String, List<String>> entry : previousTexts.entrySet()) {
      String current = DevelopmentGuideDefaults.shippedText(entry.getKey()).orElse(null);
      if (current == null) {
        continue;
      }
      for (String previous : entry.getValue()) {
        if (surfaces.replaceSystemPromptIfUnedited(entry.getKey(), previous, current)) {
          rewritten++;
          break;
        }
      }
    }
    return rewritten;
  }
}

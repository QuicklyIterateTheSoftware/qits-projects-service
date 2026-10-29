package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.dto.AgentSurfaceConfigurationDto;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
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
   * The variant's markdown: the variant's own part, then, for an in-platform variant, the holder's
   * brief (see {@link #brief}) and the development flow.
   */
  public String render(String variant) {
    List<String> parts = new ArrayList<>();
    String part = text(DevelopmentGuideDefaults.key(variant));
    if (!part.isBlank()) {
      parts.add(part.strip());
    }
    String holder = DevelopmentGuideDefaults.HOLDER_SURFACE.get(variant);
    if (holder != null) {
      String brief = brief(surfaces.get(holder));
      if (!brief.isBlank()) {
        parts.add("## Your brief\n\n" + brief.strip());
      }
      String flow = text(DevelopmentGuideDefaults.FLOW_KEY);
      if (!flow.isBlank()) {
        parts.add(flow.strip());
      }
    }
    return String.join("\n\n", parts) + "\n";
  }

  /**
   * The guide-hash of a text (qits-1734): the first {@value #HASH_LENGTH} lowercase hex characters
   * of the SHA-256 of its UTF-8 bytes. A session compares it against the hash it last read to know
   * whether the guide moved under it.
   */
  public static String hash(String markdown) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(digest.digest(markdown.getBytes(StandardCharsets.UTF_8)))
          .substring(0, HASH_LENGTH);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  /** How many hex characters a guide-hash keeps. */
  public static final int HASH_LENGTH = 16;

  /**
   * The {@link #hash} of exactly the text {@link #render} answers for {@code variant} now. Read in
   * the caller's transaction or, where it stands in none, one of its own: the desk trigger sweep, the
   * front-desk door and the phase hand-off all ask, and only some of them stand in a request context.
   */
  public String renderHash(String variant) {
    return hash(QuarkusTransaction.joiningExisting().call(() -> render(variant)));
  }

  /**
   * A holder's brief: its surface's live system prompt or, where that is blank, its initial prompt.
   * {@code ticket.dispatch} keeps its brief in the initial prompt.
   */
  static String brief(AgentSurfaceConfigurationDto holder) {
    String system = holder.systemPrompt();
    if (system != null && !system.isBlank()) {
      return system;
    }
    String initial = holder.initialPrompt();
    return initial == null ? "" : initial;
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

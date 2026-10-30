package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.error.BadRequestException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The rules an external MCP catalog entry has to survive, and the credential key grammar.
 *
 * <h2>The credential key</h2>
 *
 * <p>An entry names a credential by key and never carries its value. The value lives in this
 * service's own {@code agent_mcp_credential} table ({@link AgentMcpCredentialStore}), set with
 * {@code qits agent-mcp credentials set --key <KEY>} and never answered back (qits-1394).
 *
 * <p><b>The grammar is {@code env.<VAR>}</b>, which is what the store this replaced —
 * qits-configuration, under a reserved application nothing deployed — could hold. It is kept as it is
 * so every reference an entry already stores stays valid unchanged; the {@code env.} prefix no longer
 * renders as anybody's environment variable and never did, because nothing deployed those keys.
 */
public final class AgentMcpCatalog {

  private AgentMcpCatalog() {}

  /**
   * The credential key grammar, {@code env.<VAR>}.
   *
   * <p>Checked on a catalog write and on the credential doors alike, so a key that can be set is
   * exactly a key an entry can reference.
   */
  private static final Pattern CREDENTIAL_KEY = Pattern.compile("^env\\.[A-Za-z_][A-Za-z0-9_]*$");

  /**
   * A catalog key: lower-case, dns-label-ish, because it is rendered as an object key into a shell
   * argument on both harnesses and a key with a quote or a space in it is a quoting bug waiting for
   * a bad day.
   */
  private static final Pattern CATALOG_KEY = Pattern.compile("^[a-z]([a-z0-9-]{0,62}[a-z0-9])?$");

  /**
   * The path segment the credential doors sit under, {@code /agent-mcp-catalog/credentials}, and so
   * the one dns-label-shaped name a catalog entry may not take.
   */
  public static final String CREDENTIALS_SEGMENT = "credentials";

  private static final int MAX_URL = 2000;
  private static final int MAX_DISPLAY_NAME = 200;

  /** The catalog key, or a 400 naming what is wrong with it. */
  public static String requireCatalogKey(String key) {
    if (key == null || key.isBlank()) {
      throw new BadRequestException("A catalog key is required");
    }
    String trimmed = key.trim();
    if (AgentSurfaceDefaults.RESERVED_SERVER_KEYS.contains(trimmed.toLowerCase(Locale.ROOT))) {
      throw new BadRequestException(
          "`"
              + trimmed
              + "` is a reserved MCP server key ("
              + String.join(", ", AgentSurfaceDefaults.RESERVED_SERVER_KEYS)
              + " belong to this platform, now or before). An external entry under one of those"
              + " names would silently replace the platform's own server in the rendered mcpServers"
              + " object, or be refused by a harness that still reserves it.");
    }
    if (CREDENTIALS_SEGMENT.equals(trimmed)) {
      throw new BadRequestException(
          "`"
              + CREDENTIALS_SEGMENT
              + "` is not a catalog key: /agent-mcp-catalog/"
              + CREDENTIALS_SEGMENT
              + " is where the credentials an entry references are set, so an entry under that"
              + " name could never be read back.");
    }
    if (!CATALOG_KEY.matcher(trimmed).matches()) {
      throw new BadRequestException(
          "Not a valid catalog key: "
              + trimmed
              + ". It must be lower case, start with a letter, end with a letter or a digit, and"
              + " hold only letters, digits and dashes in between — it is rendered as an object key"
              + " into a shell argument.");
    }
    return trimmed;
  }

  /** The display name, or a 400. Never rendered into a command, so only its length is policed. */
  public static String requireDisplayName(String displayName) {
    if (displayName == null || displayName.isBlank()) {
      throw new BadRequestException("A display name is required");
    }
    String trimmed = displayName.trim();
    if (trimmed.length() > MAX_DISPLAY_NAME) {
      throw new BadRequestException(
          "The display name is longer than " + MAX_DISPLAY_NAME + " characters");
    }
    return trimmed;
  }

  /**
   * The URL, or a 400.
   *
   * <p>http(s) only, and that is the render path's constraint rather than taste: Kimi carries servers
   * protocol-native over ACP as {@code (key, url, tools)} with nowhere to put a stdio command, and a
   * stdio server would need its binary inside the workspace image anyway.
   */
  public static String requireUrl(String url) {
    if (url == null || url.isBlank()) {
      throw new BadRequestException("A URL is required");
    }
    String trimmed = url.trim();
    if (trimmed.length() > MAX_URL) {
      throw new BadRequestException("The URL is longer than " + MAX_URL + " characters");
    }
    URI parsed;
    try {
      parsed = URI.create(trimmed);
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("Not a valid URL: " + trimmed + " (" + e.getMessage() + ")");
    }
    String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("http") && !scheme.equals("https")) {
      throw new BadRequestException(
          "Not an http(s) URL: "
              + trimmed
              + ". Catalog entries are URL-transport only — Kimi carries servers over ACP as (key,"
              + " url, tools) with no place for a stdio command.");
    }
    if (parsed.getHost() == null || parsed.getHost().isBlank()) {
      throw new BadRequestException("The URL names no host: " + trimmed);
    }
    return trimmed;
  }

  /**
   * The header name and its credential key, checked as the pair they are.
   *
   * <p>Both empty is a supported entry — a public read-only server needs no credential. One without
   * the other is refused: a header with no key renders no value, and a key with no header renders
   * nowhere. Either is a server that will 401 on the agent's first tool call with nothing in the
   * configuration to say why, which is the failure this whole feature is arranged to prevent.
   */
  public static void requireCredentialPair(String headerName, String credentialKey) {
    boolean hasHeader = headerName != null && !headerName.isBlank();
    boolean hasKey = credentialKey != null && !credentialKey.isBlank();
    if (hasHeader != hasKey) {
      throw new BadRequestException(
          "A header name and a credential key go together: set both, or neither. A header with"
              + " no key would render an empty credential and a key with no header would render"
              + " nowhere — either is a server that 401s on the agent's first tool call.");
    }
    if (!hasKey) {
      return;
    }
    requireCredentialKey(credentialKey);
  }

  /** A credential key, trimmed, or a 400 naming what is wrong with it. */
  public static String requireCredentialKey(String credentialKey) {
    if (credentialKey == null || credentialKey.isBlank()) {
      throw new BadRequestException("A credential key is required");
    }
    String trimmed = credentialKey.trim();
    if (!CREDENTIAL_KEY.matcher(trimmed).matches()) {
      throw new BadRequestException(
          "Not a valid credential key: "
              + trimmed
              + ". It is spelled `env.<VAR>`: after `env.` it starts with a letter or an underscore"
              + " and holds only letters, digits and underscores.");
    }
    return trimmed;
  }

  /** The header names refused outright: a caller may not steer the transport itself. */
  private static final List<String> REFUSED_HEADERS = List.of("host", "content-length");

  /** The header name, or a 400. */
  public static String requireHeaderName(String headerName) {
    if (headerName == null || headerName.isBlank()) {
      return "";
    }
    String trimmed = headerName.trim();
    if (!trimmed.matches("^[A-Za-z0-9!#$%&'*+._|~^-]+$")) {
      throw new BadRequestException(
          "Not a valid header name: " + trimmed + ". It must be a single HTTP token.");
    }
    if (REFUSED_HEADERS.contains(trimmed.toLowerCase(Locale.ROOT))) {
      throw new BadRequestException(
          "`" + trimmed + "` is not a header a catalog entry may set — it steers the transport.");
    }
    return trimmed;
  }
}

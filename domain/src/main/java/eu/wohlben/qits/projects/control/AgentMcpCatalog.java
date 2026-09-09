package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.error.BadRequestException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The rules an external MCP catalog entry has to survive, and the credential key namespace this
 * epic settled on.
 *
 * <h2>The namespace, and why it is the application segment rather than a key prefix</h2>
 *
 * <p>The epic left one thing open for whoever built the catalog: the config-declarations epic's
 * declared keys are {@code env.}-prefixed because they render as container environment variables, and
 * an MCP credential is not an env var of any app — so pick a non-colliding namespace, or an
 * operator-class entry, and write down which.
 *
 * <p><b>It cannot be a new key prefix.</b> qits-configuration addresses a value by {@code (env,
 * application, key)} and its key grammar is <em>closed</em>: {@code ConfigurationKeys.requireKey}
 * accepts {@code env.<VAR>} and the four indexed families ({@code mounts[i]}, {@code publishes[i]},
 * {@code groups[i]}, {@code aliases[i]}) and answers 400 to everything else. A {@code secrets.} or
 * {@code mcp.} prefix could not be <em>written</em> over there, and widening that grammar is another
 * repository's change and outside this epic.
 *
 * <p><b>So it is the application segment, which is the axis that is actually open</b> — {@code
 * requireApplication} validates a dns-label shape and nothing more — and, better, it is the axis the
 * concern is really about. The worry is not the three characters {@code env}; it is that the key
 * would be an environment variable <em>of an application</em>, injected into that application's
 * container by the deployer. Under an application nothing deploys, it is not: no deployment names
 * {@link #CREDENTIAL_APPLICATION}, so no deployer ever renders these keys into anybody's
 * environment, and no application's declaration can collide with them. That is precisely an
 * operator-class entry, spelled with the one key grammar the store has.
 *
 * <p><b>Forward-compatible with secrets by construction.</b> The entry is {@code plain} today,
 * because qits-configuration's {@code secret} class does not exist yet, and that is accepted on
 * purpose: the reference is the point. When secrets management lands there, the same {@code
 * (application, key)} pair is served as a secret, no field here moves and no editor changes — only
 * the class of the entry behind the name.
 *
 * <p><b>The cost, stated rather than hidden:</b> {@code qits-agent-mcp} has no declaration, so its
 * entries read as {@code orphaned} in qits-configuration's own listing. That flag means "no
 * declaration accounts for this key", which is true and harmless — nothing is written or removed on
 * the strength of it — and it is the honest price of using a store whose key grammar is shaped for
 * deployments.
 */
public final class AgentMcpCatalog {

  private AgentMcpCatalog() {}

  /**
   * The qits-configuration application every MCP credential key lives under.
   *
   * <p>Reserved: nothing deploys under this name and nothing may. It is the namespace — see this
   * class's javadoc for why the namespace is the application rather than the key.
   */
  public static final String CREDENTIAL_APPLICATION = "qits-agent-mcp";

  /**
   * The key grammar, which is qits-configuration's own {@code env.<VAR>} and not a second opinion
   * about it.
   *
   * <p>Checked here so that a mistyped key is a 400 an operator reads at the catalog form, rather
   * than a 400 from a peer service at document-build time — or, worse, a reference that validates
   * here and cannot be created over there.
   */
  private static final Pattern CREDENTIAL_KEY = Pattern.compile("^env\\.[A-Za-z_][A-Za-z0-9_]*$");

  /**
   * A catalog key: lower-case, dns-label-ish, because it is rendered as an object key into a shell
   * argument on both harnesses and a key with a quote or a space in it is a quoting bug waiting for
   * a bad day.
   */
  private static final Pattern CATALOG_KEY = Pattern.compile("^[a-z]([a-z0-9-]{0,62}[a-z0-9])?$");

  private static final int MAX_URL = 2000;
  private static final int MAX_DISPLAY_NAME = 200;

  /** The catalog key, or a 400 naming what is wrong with it. */
  public static String requireCatalogKey(String key) {
    if (key == null || key.isBlank()) {
      throw new BadRequestException("A catalog key is required");
    }
    String trimmed = key.trim();
    if (AgentSurfaceDefaults.BUILT_IN_SERVERS.contains(trimmed.toLowerCase(Locale.ROOT))) {
      throw new BadRequestException(
          "`"
              + trimmed
              + "` is a reserved MCP server key ("
              + String.join(", ", AgentSurfaceDefaults.BUILT_IN_SERVERS)
              + " belong to this platform). An external entry under one of those names would"
              + " silently replace the platform's own server in the rendered mcpServers object, and"
              + " the session would look normal while talking to somebody else's server.");
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
          "A header name and a configuration key go together: set both, or neither. A header with"
              + " no key would render an empty credential and a key with no header would render"
              + " nowhere — either is a server that 401s on the agent's first tool call.");
    }
    if (!hasKey) {
      return;
    }
    String trimmed = credentialKey.trim();
    if (!CREDENTIAL_KEY.matcher(trimmed).matches()) {
      throw new BadRequestException(
          "Not a valid configuration key: "
              + trimmed
              + ". It is a qits-configuration key under the reserved `"
              + CREDENTIAL_APPLICATION
              + "` application, so it is spelled `env.<VAR>`: after `env.` it starts with a letter"
              + " or an underscore and holds only letters, digits and underscores. That grammar is"
              + " qits-configuration's own — a key outside it could not be created there at all.");
    }
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

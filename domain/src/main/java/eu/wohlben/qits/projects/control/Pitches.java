package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.control.PitchAnnouncer.Change;
import eu.wohlben.qits.projects.control.PitchAnnouncer.PitchChange;
import eu.wohlben.qits.projects.entity.Pitch;
import eu.wohlben.qits.projects.entity.PitchColorScheme;
import eu.wohlben.qits.projects.entity.PitchKind;
import eu.wohlben.qits.projects.entity.PitchVersion;
import eu.wohlben.qits.projects.entity.PitchViewport;
import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.projects.persistence.PitchRepository;
import eu.wohlben.qits.projects.persistence.PitchVersionRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * <b>The pitches</b> (qits-2155): a named agent's mock snapshots of the UI builder's screenshots,
 * with every version of their HTML. Reads, the create (the pitch and its version 1), the append of
 * version {@code n+1} and the archive. Who may do which is the door's ({@code api/PitchController});
 * this class judges only the values.
 *
 * <ul>
 *   <li><b>Versions are append-only.</b> {@link #addVersion} locks the pitch row and writes {@code
 *       n+1}, so two appends racing each get their own number; nothing rewrites a version.
 *   <li><b>An archived pitch takes no version</b> (409). The archive itself is idempotent and
 *       deletes nothing.
 *   <li><b>The HTML is measured, never read.</b> It is not sanitised here: its consumer renders it
 *       in a sandboxed, script-free frame. Empty is a 400; larger than {@code
 *       qits.projects.pitch-max-bytes} (4 MiB, as {@code refinement-design-max-bytes}) a 413.
 *   <li><b>Every change is announced after its commit</b> ({@link PitchAnnouncer}), never inside
 *       it; a failed announcement is a WARN and never fails the write.
 * </ul>
 */
@ApplicationScoped
public class Pitches {

  private static final Logger LOG = Logger.getLogger(Pitches.class);

  /** The version address meaning the newest one. */
  public static final String LATEST = "latest";

  /** A pitch with its newest version number. */
  public record Listed(Pitch pitch, int latestVersion) {}

  /** A pitch with every version, oldest first; the versions' HTML is not read for this. */
  public record Detail(Pitch pitch, List<VersionInfo> versions) {

    /** The newest version number, 0 for none. */
    public int latestVersion() {
      return versions.isEmpty() ? 0 : versions.get(versions.size() - 1).number();
    }
  }

  /** One version without its document. */
  public record VersionInfo(int number, int htmlBytes, String note, Instant createdAt) {}

  /** A new pitch, the values as the caller sent them; the enums are parsed here (400). */
  public record NewPitch(
      String projectId,
      String agentName,
      String repositoryId,
      String route,
      String screenshot,
      String kind,
      String viewport,
      String colorScheme,
      String manifestVersion,
      String title,
      String note,
      String html,
      String versionNote,
      String createdBy) {}

  @Inject PitchRepository pitches;

  @Inject PitchVersionRepository versions;

  @Inject Instance<PitchAnnouncer> announcer;

  @ConfigProperty(name = "qits.projects.pitch-max-bytes", defaultValue = "4194304")
  long maxBytes;

  // --- reads -------------------------------------------------------------------------------------

  /** The pitches {@code filter} admits, newest {@code updated_at} first, each with its latest. */
  public List<Listed> list(PitchRepository.Filter filter) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              List<Pitch> rows = pitches.search(filter);
              Map<UUID, Integer> latest = versions.latest(rows.stream().map(p -> p.id).toList());
              return rows.stream()
                  .map(row -> new Listed(row, latest.getOrDefault(row.id, 0)))
                  .toList();
            });
  }

  /** The pitch {@code id}; 404 when there is none. */
  public Pitch get(UUID id) {
    Pitch row = QuarkusTransaction.requiringNew().call(() -> pitches.findById(id));
    if (row == null) {
      throw new NotFoundException("No such pitch: " + id);
    }
    return row;
  }

  /** The pitch {@code id} with every version, oldest first; 404 when there is none. */
  public Detail detail(UUID id) {
    Detail detail =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Pitch row = pitches.findById(id);
                  return row == null ? null : new Detail(row, versionInfos(id));
                });
    if (detail == null) {
      throw new NotFoundException("No such pitch: " + id);
    }
    return detail;
  }

  /**
   * Version {@code number} of the pitch {@code id} — a number from 1, or {@link #LATEST} — with its
   * document. 404 when the pitch or the version does not exist, 400 for an address that is neither.
   */
  public PitchVersion version(UUID id, String number) {
    Integer wanted = LATEST.equals(number) ? null : parseNumber(number);
    PitchVersion version =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  if (pitches.findById(id) == null) {
                    throw new NotFoundException("No such pitch: " + id);
                  }
                  int n = wanted == null ? versions.latest(id) : wanted;
                  return versions.find(id, n);
                });
    if (version == null) {
      throw new NotFoundException("Pitch " + id + " has no version " + number);
    }
    return version;
  }

  // --- writes ------------------------------------------------------------------------------------

  /** Create the pitch and its version 1; the change is announced after the commit. */
  public Detail create(NewPitch request) {
    String route = required(request.route(), "route");
    String screenshot = required(request.screenshot(), "screenshot");
    PitchKind kind = parse(PitchKind.class, request.kind(), "kind", true);
    PitchViewport viewport = parse(PitchViewport.class, request.viewport(), "viewport", true);
    PitchColorScheme scheme =
        parse(PitchColorScheme.class, request.colorScheme(), "colorScheme", false);
    String manifestVersion = required(request.manifestVersion(), "manifestVersion");
    String title = required(request.title(), "title");
    int bytes = measure(request.html());
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    Pitch row = new Pitch();
    row.id = UUID.randomUUID();
    row.projectId = request.projectId();
    row.agentName = request.agentName();
    row.repositoryId = request.repositoryId();
    row.route = route;
    row.screenshot = screenshot;
    row.kind = kind;
    row.viewport = viewport;
    row.colorScheme = scheme;
    row.manifestVersion = manifestVersion;
    row.title = title;
    row.note = blankToNull(request.note());
    row.createdBy = request.createdBy();
    row.createdAt = now;
    row.updatedAt = now;
    PitchVersion first = new PitchVersion();
    first.pitchId = row.id;
    first.number = 1;
    first.html = request.html();
    first.htmlBytes = bytes;
    first.note = blankToNull(request.versionNote());
    first.createdAt = now;
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              pitches.persist(row);
              pitches.flush();
              versions.persist(first);
            });
    announce(row, Change.CREATED, 1);
    return new Detail(row, List.of(info(first)));
  }

  /**
   * Append version {@code n+1} to the pitch {@code id}, under a lock on its row; 404 for no pitch,
   * 409 for an archived one. The pitch's {@code updated_at} moves with it.
   */
  public PitchVersion addVersion(UUID id, String html, String note) {
    int bytes = measure(html);
    PitchVersion written =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Pitch row = pitches.lock(id);
                  if (row == null) {
                    throw new NotFoundException("No such pitch: " + id);
                  }
                  if (row.archivedAt != null) {
                    throw new DomainException(
                        409, "Pitch " + id + " is archived and takes no new version.");
                  }
                  Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
                  PitchVersion version = new PitchVersion();
                  version.pitchId = id;
                  version.number = versions.latest(id) + 1;
                  version.html = html;
                  version.htmlBytes = bytes;
                  version.note = blankToNull(note);
                  version.createdAt = now;
                  versions.persist(version);
                  row.updatedAt = now;
                  return version;
                });
    announce(get(id), Change.VERSION_ADDED, written.number);
    return written;
  }

  /**
   * Archive the pitch {@code id}: idempotent, so an archived one answers as it stands and announces
   * nothing. 404 for no pitch. Nothing is deleted.
   */
  public Detail archive(UUID id) {
    boolean archived =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Pitch row = pitches.lock(id);
                  if (row == null) {
                    throw new NotFoundException("No such pitch: " + id);
                  }
                  if (row.archivedAt != null) {
                    return false;
                  }
                  Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
                  row.archivedAt = now;
                  row.updatedAt = now;
                  return true;
                });
    Detail detail = detail(id);
    if (archived) {
      announce(detail.pitch(), Change.ARCHIVED, detail.latestVersion());
    }
    return detail;
  }

  // --- helpers -----------------------------------------------------------------------------------

  private List<VersionInfo> versionInfos(UUID id) {
    List<Object[]> rows =
        versions
            .getEntityManager()
            .createQuery(
                "select v.number, v.htmlBytes, v.note, v.createdAt from PitchVersion v"
                    + " where v.pitchId = :pitch order by v.number",
                Object[].class)
            .setParameter("pitch", id)
            .getResultList();
    return rows.stream()
        .map(
            r ->
                new VersionInfo(
                    ((Number) r[0]).intValue(),
                    ((Number) r[1]).intValue(),
                    (String) r[2],
                    (Instant) r[3]))
        .toList();
  }

  private static VersionInfo info(PitchVersion version) {
    return new VersionInfo(version.number, version.htmlBytes, version.note, version.createdAt);
  }

  private void announce(Pitch row, Change change, int version) {
    if (announcer == null || !announcer.isResolvable()) {
      return;
    }
    try {
      announcer
          .get()
          .onPitchChanged(
              new PitchChange(
                  row.projectId,
                  row.id.toString(),
                  row.agentName,
                  row.repositoryId,
                  row.route,
                  row.screenshot,
                  change,
                  version,
                  row.updatedAt));
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not announce %s of pitch %s", change, row.id);
    }
  }

  /** UTF-8 length, which is what the limit counts and what the version records. */
  private int measure(String html) {
    if (html == null || html.isBlank()) {
      throw new DomainException(400, "A pitch needs its HTML: html is empty.");
    }
    int bytes = html.getBytes(StandardCharsets.UTF_8).length;
    if (bytes > maxBytes) {
      throw new DomainException(
          413, "The pitch's HTML is " + bytes + " bytes, larger than " + maxBytes + ".");
    }
    return bytes;
  }

  private static Integer parseNumber(String number) {
    try {
      int n = Integer.parseInt(number);
      if (n >= 1) {
        return n;
      }
    } catch (NumberFormatException | NullPointerException e) {
      // falls through to the refusal
    }
    throw new DomainException(
        400, "A version is a number from 1 or '" + LATEST + "', not '" + number + "'.");
  }

  private static String required(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new DomainException(400, field + " is required.");
    }
    return value;
  }

  /** {@code value} as one of {@code type}'s words, any case; absent is a 400 only when required. */
  static <E extends Enum<E>> E parse(Class<E> type, String value, String field, boolean required) {
    if (value == null || value.isBlank()) {
      if (required) {
        throw new DomainException(400, field + " is required: " + words(type) + ".");
      }
      return null;
    }
    try {
      return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new DomainException(
          400, field + " must be one of " + words(type) + ", not '" + value + "'.");
    }
  }

  private static String words(Class<? extends Enum<?>> type) {
    return Arrays.stream(type.getEnumConstants())
        .map(Enum::name)
        .collect(Collectors.joining(", "));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}

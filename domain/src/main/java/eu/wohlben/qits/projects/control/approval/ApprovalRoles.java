package eu.wohlben.qits.projects.control.approval;

import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequestApproval;
import eu.wohlben.qits.projects.persistence.ProjectRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * <b>Approval roles written down and read back</b>, with the project slug a person reads in place of
 * the id a role holds ({@link ApprovalRole#render}). A slug is unique and immutable (V6), so both
 * directions are remembered once looked up; a lookup is its own short transaction, callable inside
 * another one or outside any.
 *
 * <p>Only {@code agent@<slug>} and {@code desk@<slug>} ever need a lookup — {@code person}, {@code
 * agent} and {@code desk} (the request's own project) are spelled without one.
 */
@ApplicationScoped
public class ApprovalRoles {

  private static final Logger LOG = Logger.getLogger(ApprovalRoles.class);

  @Inject ProjectRepository projects;

  private final Map<String, String> slugById = new ConcurrentHashMap<>();
  private final Map<String, String> idBySlug = new ConcurrentHashMap<>();

  /** {@code role} rendered for a reader. */
  public String render(ApprovalRole role) {
    return role.render(this::slugOf);
  }

  /** Each role rendered, sorted and without repeats. */
  public List<String> renderAll(Collection<ApprovalRole> roles) {
    return ApprovalRole.renderAll(roles, this::slugOf);
  }

  /** The stored spelling: rendered, sorted and space-separated. */
  public String stored(Collection<ApprovalRole> roles) {
    return String.join(" ", renderAll(roles));
  }

  /** The roles a stored spelling names. */
  public List<ApprovalRole> parse(String stored) {
    return ApprovalRole.parseAll(stored, this::projectIdOf);
  }

  /**
   * The roles a decision recorded; {@code person} for a row with none, which only a row written
   * before the column existed could be.
   */
  public List<ApprovalRole> rolesOf(ReleaseRequestApproval row) {
    if (row.approverRoles == null || row.approverRoles.isBlank()) {
      return List.of(ApprovalRole.PERSON);
    }
    return parse(row.approverRoles);
  }

  private String slugOf(String projectId) {
    String known = slugById.get(projectId);
    if (known != null) {
      return known;
    }
    Optional<String> slug =
        lookup(() -> projects.findByIdOptional(projectId).map(project -> project.slug));
    slug.ifPresent(
        value -> {
          slugById.put(projectId, value);
          idBySlug.put(value, projectId);
        });
    return slug.orElse(projectId);
  }

  private String projectIdOf(String slug) {
    String known = idBySlug.get(slug);
    if (known != null) {
      return known;
    }
    Optional<String> id = lookup(() -> projects.findBySlug(slug).map((Project project) -> project.id));
    id.ifPresent(
        value -> {
          idBySlug.put(slug, value);
          slugById.put(value, slug);
        });
    return id.orElse(slug);
  }

  private static Optional<String> lookup(java.util.function.Supplier<Optional<String>> read) {
    try {
      return QuarkusTransaction.requiringNew().call(read::get);
    } catch (RuntimeException e) {
      LOG.debugf(e, "Could not look a project up for an approval role");
      return Optional.empty();
    }
  }
}

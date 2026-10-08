package eu.wohlben.qits.projects.control;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.projects.dto.DeskRunnerDto;
import eu.wohlben.qits.projects.dto.DeskRunnerHealthDto;
import eu.wohlben.qits.projects.entity.DeskRunner;
import eu.wohlben.qits.projects.entity.DeskRunnerCapabilities;
import eu.wohlben.qits.projects.error.BadRequestException;
import eu.wohlben.qits.projects.error.CodedRefusalException;
import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.projects.mapper.DeskRunnerMapper;
import eu.wohlben.qits.projects.persistence.DeskRunnerRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The front-desk runners (qits-767): every rule about a {@link DeskRunner}'s state, and every write
 * to one. qits-workspaces-service's {@code WorkspaceRunners} in the desk spelling, without the
 * workspace memory limits.
 *
 * <p><b>What is NOT here is qits-idp.</b> A runner's lifecycle is interleaved with calls to qits-idp
 * (a registration token at create and at every rotation, a client at registration, both given back
 * at delete), and every one is HTTP, so they belong to the service module. What this class offers
 * the caller is the two halves around each call: a check that can refuse <em>before</em> anything
 * is commissioned ({@link #requireCreatable}, {@link #requireUnregistered}, {@link
 * #requireRegistrable}), and a write that records what was commissioned <em>after</em> ({@link
 * #create}, {@link #replaceRegistrationToken}, {@link #markRegistered}). Nothing holds a
 * transaction across the network: each method is its own {@code requiringNew}.
 *
 * <p><b>The name rule is {@link #NAME}</b>. A taken name is a 409, checked before a token is
 * commissioned and again by {@code uq_desk_runner_name} for the race.
 *
 * <p><b>A runner that holds a desk cannot be deleted</b> (409 {@link #RUNNER_OWNS_DESKS}): a desk is
 * sticky to its runner. Which desks it holds is {@link DeskRunnerDesks}'.
 */
@ApplicationScoped
public class DeskRunners {

  /** A runner's name: a lower-case letter, then up to 63 lower-case letters, digits and hyphens. */
  public static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,63}");

  /** The widest description a runner keeps. */
  public static final int DESCRIPTION_MAX = 1024;

  /** The slots a runner is created with when the request names none: the column's default. */
  public static final int DEFAULT_SLOTS = 8;

  /** The delete refusal's code, while a desk is placed on the runner. */
  public static final String RUNNER_OWNS_DESKS = "RUNNER_OWNS_DESKS";

  /**
   * The reason a runner is quarantined with the moment it registers. Holding a token proves nothing
   * about running a desk, so the runner takes none until its first health check says it can.
   */
  public static final String AWAITING_FIRST_HEALTH_CHECK = "awaiting first health check";

  @Inject DeskRunnerRepository runners;

  @Inject DeskRunnerMapper mapper;

  @Inject DeskRunnerDesks desks;

  /** 400 unless {@code name} is a runner name; see {@link #NAME}. */
  public static void requireName(String name) {
    if (name == null || !NAME.matcher(name).matches()) {
      throw new BadRequestException(
          "A runner name is a lower-case letter followed by at most 63 lower-case letters, digits"
              + " and hyphens ([a-z][a-z0-9-]{0,63})");
    }
  }

  private static void requireSlots(Integer slots) {
    if (slots != null && slots < 1) {
      throw new BadRequestException("slots is at least 1");
    }
  }

  private static void requireDescription(String description) {
    if (description != null && description.length() > DESCRIPTION_MAX) {
      throw new BadRequestException(
          "A runner description is at most " + DESCRIPTION_MAX + " characters");
    }
  }

  /**
   * Everything a create can be refused for, asked <b>before</b> a registration token is
   * commissioned: a malformed name, slots or description (400), and a taken name (409).
   */
  public void requireCreatable(String name, String description, Integer slots) {
    requireName(name);
    requireSlots(slots);
    requireDescription(description);
    if (nameTaken(name, null)) {
      throw nameTaken(name);
    }
  }

  /**
   * Records a runner whose registration token has already been commissioned. {@code id} is minted by
   * the caller, because the token's context id at qits-idp is this runner's id and has to exist
   * before the row does.
   *
   * @throws DomainException 409 when the name was taken in between
   */
  public DeskRunner create(
      UUID id,
      String name,
      String description,
      Integer slots,
      String registrationTokenId,
      String registrationTokenSubject) {
    requireCreatable(name, description, slots);
    try {
      return QuarkusTransaction.requiringNew()
          .call(
              () -> {
                DeskRunner runner = new DeskRunner();
                runner.id = Objects.requireNonNull(id, "id");
                runner.name = name;
                runner.description = blankToNull(description);
                runner.slots = slots == null ? DEFAULT_SLOTS : slots;
                runner.registrationTokenId = registrationTokenId;
                runner.registrationTokenSubject = registrationTokenSubject;
                runner.createdAt = Instant.now();
                runners.persist(runner);
                runners.flush();
                return runner;
              });
    } catch (DomainException refused) {
      throw refused;
    } catch (RuntimeException collided) {
      // The one constraint a fresh uuid can collide with is the name's; anything else is rethrown.
      if (nameTaken(name, null)) {
        throw nameTaken(name);
      }
      throw collided;
    }
  }

  /** Every runner, by name. */
  public List<DeskRunner> list() {
    return QuarkusTransaction.requiringNew().call(runners::listByName);
  }

  /** One runner, or 404. */
  public DeskRunner get(UUID id) {
    DeskRunner runner = QuarkusTransaction.requiringNew().call(() -> runners.findById(id));
    if (runner == null) {
      throw notFound(id);
    }
    return runner;
  }

  /** The registered runner a commissioned client belongs to: the socket's question about a dial. */
  public Optional<DeskRunner> findByClientId(String clientId) {
    if (clientId == null || clientId.isBlank()) {
      return Optional.empty();
    }
    return QuarkusTransaction.requiringNew().call(() -> runners.findByClientId(clientId));
  }

  /**
   * Changes what an operator may change about a runner: its name, description and slots. A null
   * leaves the value as it is; a blank description clears it. A taken name is 409. Telling a
   * connected runner its new slots is the caller's.
   */
  public DeskRunner patch(UUID id, String name, String description, Integer slots) {
    if (name != null) {
      requireName(name);
    }
    requireSlots(slots);
    requireDescription(description);
    try {
      return QuarkusTransaction.requiringNew()
          .call(
              () -> {
                DeskRunner runner = found(id);
                if (name != null && !name.equals(runner.name)) {
                  if (runners.findByName(name).isPresent()) {
                    throw nameTaken(name);
                  }
                  runner.name = name;
                }
                if (description != null) {
                  runner.description = blankToNull(description);
                }
                if (slots != null) {
                  runner.slots = slots;
                }
                runners.flush();
                return runner;
              });
    } catch (DomainException refused) {
      throw refused;
    } catch (RuntimeException collided) {
      if (name != null && nameTaken(name, id)) {
        throw nameTaken(name);
      }
      throw collided;
    }
  }

  /**
   * 409 unless a registration token may be issued for this runner now, which is while it is
   * unregistered. Asked before a rotation commissions a new token.
   */
  public DeskRunner requireUnregistered(UUID id) {
    DeskRunner runner = get(id);
    if (runner.registered()) {
      throw registeredAlready(runner);
    }
    return runner;
  }

  /**
   * Swaps in a freshly commissioned registration token and answers the id of the one it replaced,
   * or null, so the caller can delete that one at qits-idp.
   *
   * @throws DomainException 409 when the runner registered in between
   */
  public String replaceRegistrationToken(UUID id, String tokenId, String tokenSubject) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              DeskRunner runner = found(id);
              if (runner.registered()) {
                throw registeredAlready(runner);
              }
              String previous = runner.registrationTokenId;
              runner.registrationTokenId = tokenId;
              runner.registrationTokenSubject = tokenSubject;
              return previous;
            });
  }

  /**
   * The register door's check, asked before a client is commissioned: 404 for no such runner, 403
   * when the caller's {@code sub} is not this runner's registration token subject (also the answer
   * to no subject at all), 409 when the runner is already registered.
   *
   * <p>The subject stays on the row after registration, which is what makes the 409 reachable: a
   * JWT the edge minted for the token outlives the token's deletion by up to its own lifetime.
   */
  public DeskRunner requireRegistrable(UUID id, String callerSubject) {
    DeskRunner runner = get(id);
    if (callerSubject == null
        || callerSubject.isBlank()
        || !callerSubject.equals(runner.registrationTokenSubject)) {
      throw new DomainException(403, "This registration token is not this runner's");
    }
    if (runner.registered()) {
      throw registeredAlready(runner);
    }
    return runner;
  }

  /**
   * Records the client the register door commissioned and what the runner said about itself, and
   * <b>quarantines the runner</b> ({@link #AWAITING_FIRST_HEALTH_CHECK}) until its first health check
   * passes.
   *
   * <p>The registration token id is cleared here: the token is spent, and the caller deletes it at
   * qits-idp with the id it read in {@link #requireRegistrable}. A token whose delete failed is then
   * referenced by no row, and the commission reconcile reaps it. The subject stays.
   *
   * @throws DomainException 409 when the runner registered in between; the caller then gives its own
   *     freshly commissioned client back
   */
  public DeskRunner markRegistered(UUID id, String clientId, JsonNode capabilities) {
    Objects.requireNonNull(clientId, "clientId");
    String said = capabilitiesOf(null, capabilities);
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              DeskRunner runner = found(id);
              if (runner.registered()) {
                throw registeredAlready(runner);
              }
              Instant now = Instant.now();
              runner.clientId = clientId;
              runner.registrationTokenId = null;
              runner.capabilities = said;
              runner.registeredAt = now;
              runner.lastSeenAt = now;
              runner.quarantinedAt = now;
              runner.quarantineReason = AWAITING_FIRST_HEALTH_CHECK;
              return runner;
            });
  }

  /**
   * Puts a runner back into service: an admin's greenlight, or a health check that passed. A runner
   * already in service is answered as it is.
   */
  public DeskRunner greenlight(UUID id) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              DeskRunner runner = runners.findById(id, LockModeType.PESSIMISTIC_WRITE);
              if (runner == null) {
                throw notFound(id);
              }
              runner.quarantinedAt = null;
              runner.quarantineReason = null;
              return runner;
            });
  }

  /**
   * Deletes the row and answers what it held, so the caller can give the runner's client and token
   * back at qits-idp and tell a connected runner it was deleted. 409 {@link #RUNNER_OWNS_DESKS}
   * while a desk is placed on it.
   *
   * <p>The row goes <b>first</b> and the credentials after: the row is what every door and the
   * socket check a runner against, so once it is gone the credentials open nothing here, and a
   * qits-idp that could not be reached leaves leftovers the commission reconcile reaps by that same
   * absence.
   */
  public DeskRunner delete(UUID id) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              DeskRunner runner = found(id);
              List<DeskRunnerDto.DeskRunnerDesk> owned = desks.desksOn(id);
              if (owned != null && !owned.isEmpty()) {
                throw new CodedRefusalException(
                    409,
                    RUNNER_OWNS_DESKS,
                    RUNNER_OWNS_DESKS
                        + ": runner "
                        + runner.name
                        + " holds "
                        + owned.size()
                        + " desk(s) "
                        + owned.stream().map(DeskRunnerDto.DeskRunnerDesk::projectId).toList()
                        + "; move or retire them before deleting it");
              }
              runners.delete(runner);
              return runner;
            });
  }

  /** The desks placed on a runner, for its listing. */
  public List<DeskRunnerDto.DeskRunnerDesk> desksOn(UUID id) {
    return QuarkusTransaction.requiringNew().call(() -> desks.desksOn(id));
  }

  /** The runner as an operator reads it, with what the service knows live about it. */
  public DeskRunnerDto view(DeskRunner runner, DeskRunnerMapper.Live live) {
    return mapper.toDto(runner, live);
  }

  /**
   * The runner's newest health check in full, every check's data included; null when none has
   * settled yet.
   *
   * @throws NotFoundException for no such runner
   */
  public DeskRunnerHealthDto health(UUID id) {
    return mapper.toHealthDto(get(id));
  }

  private boolean nameTaken(String name, UUID other) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                runners
                    .findByName(name)
                    .filter(runner -> other == null || !runner.id.equals(other))
                    .isPresent());
  }

  private DeskRunner found(UUID id) {
    DeskRunner runner = runners.findById(id);
    if (runner == null) {
      throw notFound(id);
    }
    return runner;
  }

  private static NotFoundException notFound(UUID id) {
    return new NotFoundException("No front-desk runner " + id);
  }

  private static DomainException nameTaken(String name) {
    return new DomainException(409, "A runner named " + name + " already exists");
  }

  private static DomainException registeredAlready(DeskRunner runner) {
    return new DomainException(409, "Runner " + runner.name + " is already registered");
  }

  /** {@link DeskRunnerCapabilities#merge}, its refusal turned into a 400. */
  private static String capabilitiesOf(String stored, JsonNode said) {
    try {
      return DeskRunnerCapabilities.merge(stored, said);
    } catch (IllegalArgumentException refused) {
      throw new BadRequestException(refused.getMessage());
    }
  }

  private static String blankToNull(String text) {
    return text == null || text.isBlank() ? null : text;
  }
}

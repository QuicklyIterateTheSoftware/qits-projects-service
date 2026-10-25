package eu.wohlben.qits.projects.desktriggers;

import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.entity.FrontDesk;
import eu.wohlben.qits.projects.entity.Project;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * <b>The bus side of desk triggers</b> (epic qits-1604): hand each frame to every {@link
 * DeskTriggerSource} that reads it, decide which of the triggers they find the desk is to hear
 * about, and admit those into their project's open window ({@link DeskTriggerWindows}). Nothing is
 * sent from here — the window closes on its own clock and {@code DeskTriggerSweep} delivers it.
 *
 * <h2>Knows no source by name</h2>
 *
 * <p>The sources are every {@code DeskTriggerSource} bean ({@code @Any Instance}); {@link
 * #signatures()} is the union of theirs, and a frame goes to each source whose own signatures name
 * it. A new source is a new bean and nothing here changes. The second constructor takes the list
 * directly, for a test (and the consumer pact) that wants a set of sources no container assembled.
 *
 * <h2>What is dropped</h2>
 *
 * <ul>
 *   <li><b>A project with delivery off</b> ({@code project.delivery_enabled} false): there is no
 *       delivery desk to tell. Only admission is gated — a window opened while delivery was on is
 *       still closed and delivered, because it is owed to a desk that existed.
 *   <li><b>What the desk caused itself</b>: a trigger whose {@code cause} equals any non-null one of
 *       the project's desk subjects ({@code front_desk.delivery_token_subject}, {@code
 *       session_token_subject}, {@code token_subject}). The desk moving a ticket to VERIFIED does not
 *       need telling that it did. A delivery token re-minted since the event leaves its old subject
 *       unmatched; toggling the loop is rare and the cost is one redundant line.
 *   <li><b>A project this service does not know</b>, which no window could reference anyway.
 * </ul>
 *
 * <h2>One {@code projects} transaction of its own per frame</h2>
 *
 * <p>The library runs {@link #onFrame} inside its claim transaction, which already holds a
 * connection of the {@code eventstream} datasource, and Narayana refuses a second non-XA resource in
 * one transaction ({@code CampaignCriteriaListener}'s javadoc has the measurement). So the frame's
 * whole {@code projects} work — the sources' reads, the checks, the admits — runs in {@link
 * QuarkusTransaction#requiringNew()}, which suspends the claim. A failure there throws out of
 * {@code onFrame} and the frame stays owed; the redelivery admits nothing twice, because a repeat
 * is one line. Admits whose transaction committed under a claim that then rolled back are the same
 * case.
 *
 * <p><b>A source that cannot read a payload</b> is poison for that source only: WARN, and the frame
 * contributes nothing from it. Retrying an unreadable payload forever would hold every later frame
 * behind it.
 */
@ApplicationScoped
public class DeskTriggerListener implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(DeskTriggerListener.class);

  /**
   * Storage, never a label — the {@code consumed_event}/watermark key. Never change it: a new value
   * is a new consumer starting at the head of the log.
   */
  static final String CONSUMER_ID = "projects-desk-triggers";

  @Inject @Any Instance<DeskTriggerSource> discovered;

  @Inject DeskTriggerWindows windows;

  /** In {@link DeskTriggerSource#order()}; resolved once, from {@link #discovered} under CDI. */
  private volatile List<DeskTriggerSource> sources;

  /** The container's. */
  public DeskTriggerListener() {}

  /** A listener over exactly these sources — a test's, or the consumer pact's. */
  public DeskTriggerListener(List<DeskTriggerSource> sources, DeskTriggerWindows windows) {
    this.sources = sorted(sources);
    this.windows = windows;
  }

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    Set<String> union = new LinkedHashSet<>();
    for (DeskTriggerSource source : sources()) {
      union.addAll(source.signatures());
    }
    return union;
  }

  @Override
  public boolean selects(EventFrame frame) {
    return sources().stream().anyMatch(source -> source.signatures().contains(frame.name()));
  }

  /** Only what happens from now on: a desk is not told about last month. */
  @Override
  public boolean replayFromEpoch() {
    return false;
  }

  @Override
  public void onFrame(EventFrame frame) {
    List<DeskTriggerSource> reading =
        sources().stream().filter(source -> source.signatures().contains(frame.name())).toList();
    if (reading.isEmpty()) {
      return;
    }
    Instant now = Instant.now();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              for (DeskTriggerSource source : reading) {
                List<DeskTrigger> triggers;
                try {
                  triggers = source.qualify(frame);
                } catch (RuntimeException e) {
                  LOG.warnf(
                      "%s %s could not be read by desk trigger source %s: %s",
                      frame.name(), frame.id(), source.id(), e.toString());
                  continue;
                }
                for (DeskTrigger trigger : triggers) {
                  if (admissible(source, trigger)) {
                    windows.admit(trigger.projectId(), source.id(), trigger, now);
                  }
                }
              }
            });
  }

  /** The drops of the class javadoc; inside the frame's {@code projects} transaction. */
  private static boolean admissible(DeskTriggerSource source, DeskTrigger trigger) {
    Project project =
        trigger.projectId() == null ? null : Project.findById(trigger.projectId());
    if (project == null) {
      LOG.debugf(
          "%s trigger %s names no known project (%s); dropped",
          source.id(), trigger.itemRef(), trigger.projectId());
      return false;
    }
    if (!project.deliveryEnabled) {
      LOG.debugf(
          "%s trigger %s: project %s has delivery off; dropped",
          source.id(), trigger.itemRef(), project.id);
      return false;
    }
    FrontDesk desk = FrontDesk.findById(project.id);
    if (desk != null && trigger.cause() != null && causedBy(desk, trigger.cause())) {
      LOG.debugf(
          "%s trigger %s was caused by the desk of project %s itself; dropped",
          source.id(), trigger.itemRef(), project.id);
      return false;
    }
    return true;
  }

  private static boolean causedBy(FrontDesk desk, String cause) {
    return cause.equals(desk.deliveryTokenSubject)
        || cause.equals(desk.sessionTokenSubject)
        || cause.equals(desk.tokenSubject);
  }

  private List<DeskTriggerSource> sources() {
    List<DeskTriggerSource> resolved = sources;
    if (resolved == null) {
      resolved = sorted(discovered.stream().toList());
      sources = resolved;
    }
    return resolved;
  }

  private static List<DeskTriggerSource> sorted(List<DeskTriggerSource> sources) {
    return sources.stream()
        .sorted(Comparator.comparingInt(DeskTriggerSource::order).thenComparing(DeskTriggerSource::id))
        .toList();
  }
}

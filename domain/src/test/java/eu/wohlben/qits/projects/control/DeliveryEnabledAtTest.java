package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.ProjectConfig.DeliverySettings;
import eu.wohlben.qits.projects.entity.Project;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@code project.delivery_enabled_at} (qits-1694): {@link DeliverySettings#applyTo} stamps it when
 * delivery goes {@code false → true}, and only then, so the desk's request-stalled trigger can
 * measure a stall from the desk's arrival. Instantiated directly (no CDI), like {@link
 * ProjectConfigParserTest} beside it.
 */
class DeliveryEnabledAtTest {

  private static final DeliverySettings ON = new DeliverySettings(true, 2, false, Set.of());

  @Test
  void turningDeliveryOnStampsIt() {
    Project project = new Project();
    Instant before = Instant.now();

    ON.applyTo(project);

    assertTrue(project.deliveryEnabled);
    assertNotNull(project.deliveryEnabledAt);
    assertFalse(project.deliveryEnabledAt.isBefore(before));
  }

  @Test
  void aReconcileThatLeavesItOnOrTurnsItOffKeepsTheStamp() {
    Project project = new Project();
    ON.applyTo(project);
    Instant stamped = Instant.parse("2026-10-20T08:00:00Z");
    project.deliveryEnabledAt = stamped;

    new DeliverySettings(true, 5, true, Set.of("manual-review")).applyTo(project);
    assertEquals(stamped, project.deliveryEnabledAt, "still on: another key moved");

    DeliverySettings.OFF.applyTo(project);
    assertFalse(project.deliveryEnabled);
    assertEquals(stamped, project.deliveryEnabledAt, "off keeps the last time it went on");
  }

  @Test
  void deliveryLeftOffStampsNothing() {
    Project project = new Project();

    DeliverySettings.OFF.applyTo(project);

    assertNull(project.deliveryEnabledAt);
  }
}

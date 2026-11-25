package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.trust.TrustAnchors;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A TEST-SCOPE {@code qits-trust-core} bundle holder (qits-2637, epic qits-2524) for a
 * {@code @QuarkusTest} — a plain object, deliberately unrelated to {@link TrustAnchors} by type.
 *
 * <p><b>It must never extend or implement {@link TrustAnchors}.</b> Quarkus indexes this module's
 * test classes in an unrestricted ("all") bean archive, so a subclass with no scope annotation at
 * all is still discovered as a second {@code TrustAnchors} bean — measured here twice:
 * {@code @ApplicationScoped @Alternative @Priority(1)} won the ambiguity and then failed every
 * suite's boot with {@code IllegalAccessError} (the generated notifier for {@code
 * TrustAnchors}'s package-private, inherited {@code start(StartupEvent)} observer could not
 * invoke it across two {@code QuarkusClassLoader}s), and dropping every annotation only traded
 * that for {@code AmbiguousResolutionException} at {@code FrontDeskSpecs#anchors} (two unqualified
 * {@code TrustAnchors} beans, this one and the real one, with nothing to prefer either). Staying
 * an unrelated type sidesteps both: nothing here satisfies that injection point, so this object is
 * never a candidate, and it declares no observer of its own.
 *
 * <p>Set on {@link FrontDeskSpecs} through {@link FrontDeskSpecs#anchorsOverride}, a plain method
 * call on the injected proxy — not a second {@code @Inject} — the same way {@link
 * FrontDeskSpecs#domainOverride} already reaches the contextual instance: {@code
 * specs.anchorsOverride(anchors::get)}.
 */
public class FakeTrustAnchors {

  private final AtomicReference<Optional<Map.Entry<String, String>>> held =
      new AtomicReference<>(Optional.empty());

  /** The held entry, exactly as {@link TrustAnchors#envEntry()} would answer it. */
  public Optional<Map.Entry<String, String>> get() {
    return held.get();
  }

  /** Hold {@code pem} as the bundle from now on. */
  public void hold(String pem) {
    held.set(Optional.of(Map.entry(TrustAnchors.ENV, pem)));
  }

  /** No bundle held — the shipped "not fetched yet" state. */
  public void clear() {
    held.set(Optional.empty());
  }
}

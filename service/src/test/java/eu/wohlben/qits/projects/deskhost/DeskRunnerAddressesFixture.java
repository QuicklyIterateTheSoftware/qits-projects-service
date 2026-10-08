package eu.wohlben.qits.projects.deskhost;

import java.util.Optional;

/** A {@link DeskRunnerAddresses} on a given public domain, for {@code QuarkusMock} (qits-767). */
public final class DeskRunnerAddressesFixture {

  /** A dotted domain, as a deployment has one. */
  public static final String DOMAIN = "example.test";

  private DeskRunnerAddressesFixture() {}

  public static DeskRunnerAddresses withDomain(String domain) {
    DeskRunnerAddresses addresses = new DeskRunnerAddresses();
    addresses.domain = Optional.ofNullable(domain);
    return addresses;
  }
}

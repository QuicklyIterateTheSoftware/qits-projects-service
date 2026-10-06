package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.projects.security.MockIdpTenant;

/**
 * {@link MockIdpTenant} under a name of its own, so {@link ScheduleNeedsAPersonDoorTest} boots an
 * application apart from {@code BearerJwksTest}'s — whose first method asserts that nothing has
 * fetched the keys yet, which a bearer presented here first would falsify.
 */
public class PersonGateIdpTenant extends MockIdpTenant {}

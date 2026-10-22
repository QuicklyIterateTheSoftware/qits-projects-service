package eu.wohlben.qits.projects.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import eu.wohlben.qits.projects.api.ReleaseRequestController;
import eu.wohlben.qits.projects.api.WorkDispatchController;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A refinement room's own token (qits-1482 §4) can neither dispatch nor approve. qits-idp issues it
 * {@code groups: [qits:agent]} for kind {@code refinement}, and nothing in this service adds a
 * role (no {@code SecurityIdentityAugmentor} grants one; {@code SocketBearerLifetime} copies the
 * roles it was given). So the dispatch door, which admits {@code qits:admin}, {@code
 * qits:admin-agent} and {@code qits:desk}, refuses it at the role check; and the approve door, which
 * admits {@code qits:agent}, then asks {@link ApproverCheck}, which holds no approval role for a
 * {@code refinement} context kind — {@code requireApprover} answers 403.
 */
class RefinementTokenRolesTest {

  private final ApproverCheck check = new ApproverCheck();

  RefinementTokenRolesTest() {
    PersonCheck persons = new PersonCheck();
    persons.sessions = new FakeSessionIntrospection();
    check.persons = persons;
  }

  /** The bearer qits-idp mints for a refinement row: its kind, its project and its entity. */
  private static SecurityIdentity refinementToken() {
    Map<String, Object> claims = new HashMap<>();
    claims.put("sub", "tok-refinement-1");
    claims.put("context_kind", "refinement");
    claims.put("project", "project-a");
    claims.put("entity", "11111111-2222-3333-4444-555555555555");
    claims.put("groups", List.of("qits:agent"));
    return AgentTokens.token(claims, "qits:agent");
  }

  @Test
  void aRefinementTokenHoldsNoApprovalRole() {
    assertEquals(Optional.empty(), check.decide(refinementToken(), null, LaunchMode.NORMAL));
  }

  @Test
  void theDispatchDoorDoesNotAdmitTheAgentRole() {
    List<String> roles = roles(dispatchMethod());
    assertFalse(roles.contains(AgentAccess.AGENT_ROLE), roles.toString());
  }

  @Test
  void theApproveDoorLeavesTheDecisionToTheApproverCheck() {
    // It admits qits:agent at the role check (a workspace agent may approve where a requirement
    // admits one), so the refusal of a refinement token is ApproverCheck's — pinned above.
    Method approve =
        Arrays.stream(ReleaseRequestController.class.getMethods())
            .filter(m -> m.getName().equals("approve"))
            .findFirst()
            .orElseThrow();
    assertEquals(List.of("qits:admin", "qits:admin-agent", "qits:agent"), roles(approve));
  }

  private static Method dispatchMethod() {
    return Arrays.stream(WorkDispatchController.class.getMethods())
        .filter(m -> m.isAnnotationPresent(RolesAllowed.class))
        .filter(m -> m.isAnnotationPresent(jakarta.ws.rs.POST.class))
        .findFirst()
        .orElseThrow();
  }

  private static List<String> roles(Method method) {
    return List.of(method.getAnnotation(RolesAllowed.class).value());
  }
}

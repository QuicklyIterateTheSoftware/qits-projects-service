package eu.wohlben.qits.projects.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.DynamicUpdate;

/**
 * One <b>named agent</b> of a project (qits-2154, V76): a kept Claude session in the project's front
 * desk container beside the desk's own, on its own surface and its own credential, tied to no work
 * item. The names are a closed registry in code ({@code control/NamedAgents}); the front desk is the
 * named agent {@code desk} and keeps its {@link FrontDesk} row — it never has a row here.
 *
 * <ul>
 *   <li><b>Created on first use</b> — the first prompt to the agent — with its token, so an agent
 *       nobody prompted costs no token and no session.
 *   <li><b>The session</b> — asked for in the desk's {@code sessions} frame (key {@code
 *       agent:<name>}) only while {@link #lastDemandAt} is inside {@code
 *       qits.projects.agent-idle-timeout}; {@link #sessionId} is what the daemon last reported for
 *       the key, handed back as the entry's {@code resumeSessionId}, so every prompt resumes the
 *       same session.
 *   <li><b>The token</b> — a {@code qits_tok_} of idp kind {@code named-agent} ({@code qits:agent}
 *       only, claims {@code {project}}), carried in the {@code sessionCredentials} frame under the
 *       agent's key and in no environment; its subject tells the agent's writes apart from the
 *       desk's.
 *   <li><b>A pending prompt</b> — {@link #pendingPrompt} holds one prompt taken while the desk's
 *       daemon was not connected; it is delivered once the agent's session is asked for, then
 *       cleared.
 * </ul>
 *
 * <p>{@link Uncaused}, like {@link FrontDesk}: machine state written by doors, sweeps and socket
 * frames alike. {@link DynamicUpdate} for {@link FrontDesk}'s reason: several writers, each flushing
 * only its own columns.
 */
@Entity
@Table(name = "named_agent")
@IdClass(NamedAgent.Key.class)
@Uncaused
@DynamicUpdate
public class NamedAgent extends PanacheEntityBase {

  @Id
  @Column(name = "project_id", nullable = false)
  public String projectId;

  /** The agent's name, as the registry spells it; its session key is {@code agent:<name>}. */
  @Id
  @Column(nullable = false)
  public String name;

  /** The agent surface its session launches as, e.g. {@code project.ui-builder}. */
  @Column(nullable = false)
  public String surface;

  /** The harness session id the daemon last reported for the agent's key, or null. */
  @Column(name = "session_id")
  public String sessionId;

  @Column(name = "token_id")
  public String tokenId;

  @Column(name = "token_value")
  public String tokenValue;

  @Column(name = "token_subject")
  public String tokenSubject;

  /** The last prompt, or the session's own activity; null before either. */
  @Column(name = "last_demand_at")
  public Instant lastDemandAt;

  /** A prompt waiting for the agent's session to be asked for, or null. */
  @Column(name = "pending_prompt", columnDefinition = "text")
  public String pendingPrompt;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  /** The composite key. */
  public static class Key implements Serializable {
    public String projectId;
    public String name;

    public Key() {}

    public Key(String projectId, String name) {
      this.projectId = projectId;
      this.name = name;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && Objects.equals(projectId, key.projectId)
          && Objects.equals(name, key.name);
    }

    @Override
    public int hashCode() {
      return Objects.hash(projectId, name);
    }
  }
}

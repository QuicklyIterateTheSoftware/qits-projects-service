package eu.wohlben.qits.projects.control;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import java.util.List;

/**
 * The shipped {@link BranchListing}: {@link RepositoryService#listBranches}, which reads the git host
 * live ({@code ls-remote --heads}) and needs no clone.
 *
 * <p>{@link ActivateRequestContext} because its caller fans the listings out onto a pool of its
 * own, where no request context is active, and {@code listBranches} reaches Panache for the row.
 */
@ApplicationScoped
@DefaultBean
public class RepositoryBranchListing implements BranchListing {

  @Inject RepositoryService repositories;

  @Override
  @ActivateRequestContext
  public List<String> branches(String repoId) {
    return repositories.listBranches(repoId);
  }
}

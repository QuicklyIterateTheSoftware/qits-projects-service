package eu.wohlben.qits.projects.control;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * The shipped {@link BranchListing}: {@link RepositoryService#listBranches}, which reads the git host
 * live ({@code ls-remote --heads}) and needs no clone.
 */
@ApplicationScoped
@DefaultBean
public class RepositoryBranchListing implements BranchListing {

  @Inject RepositoryService repositories;

  @Override
  public List<String> branches(String repoId) {
    return repositories.listBranches(repoId);
  }
}

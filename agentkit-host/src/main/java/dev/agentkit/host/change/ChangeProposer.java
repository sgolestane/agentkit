package dev.agentkit.host.change;

import java.util.Map;

/**
 * Where a change to an organization's agents goes to be reviewed: a pull request, or a branch — never straight to what
 * the host runs. Git stays the one source of truth: the change is served once it is merged, and not before.
 */
public interface ChangeProposer {

    /**
     * Opens {@code files} — paths relative to the organization's repository, and their new content — as one commit on a
     * new branch whose parent is {@code base}, the commit the host runs.
     *
     * @throws ProposalException with a sentence for the person when it could not be opened
     */
    Opened open(String base, String branch, String title, String body, Map<String, String> files);

    /** Where proposals go, for the person proposing: "pull requests on acme/agents", "branches in …". */
    String where();

    /** A change opened for review: its branch, and its pull request's address when there is one. */
    record Opened(String branch, String url) {
    }

    /** A proposal that could not be opened, and why. */
    final class ProposalException extends RuntimeException {
        public ProposalException(String message) {
            super(message);
        }
    }
}

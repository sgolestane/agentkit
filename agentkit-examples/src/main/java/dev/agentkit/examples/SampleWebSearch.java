package dev.agentkit.examples;

import java.util.List;

/**
 * Offline {@link WebSearch} with a few canned results about the demo topic
 * (adding a user to a group with the Microsoft Graph API), so the web-research
 * example runs end-to-end with zero setup. Set {@code TAVILY_API_KEY} to search
 * the live web instead.
 *
 * <p>The results are a fixed, clearly-labelled sample — they do not vary with the
 * query.
 */
public final class SampleWebSearch implements WebSearch {

    @Override
    public List<Result> search(String query, int maxResults) {
        List<Result> sample = List.of(
                new Result(
                        "Add member — Microsoft Graph v1.0 (sample)",
                        "https://learn.microsoft.com/graph/api/group-post-members",
                        "POST /groups/{group-id}/members/$ref with a JSON body "
                                + "{\"@odata.id\": \"https://graph.microsoft.com/v1.0/directoryObjects/{user-id}\"}. "
                                + "Returns 204 No Content on success."),
                new Result(
                        "group: addMember permissions — Microsoft Graph (sample)",
                        "https://learn.microsoft.com/graph/api/group-post-members#permissions",
                        "Delegated and application permissions: GroupMember.ReadWrite.All, or "
                                + "Directory.ReadWrite.All. Adding owners/members to role-assignable groups "
                                + "additionally requires RoleManagement.ReadWrite.Directory."),
                new Result(
                        "Batch add members — Microsoft Graph (sample)",
                        "https://learn.microsoft.com/graph/api/group-update-members",
                        "Add up to 20 members in one request via PATCH /groups/{group-id} with a "
                                + "members@odata.bind array."));
        return sample.subList(0, Math.min(sample.size(), Math.max(1, maxResults)));
    }
}

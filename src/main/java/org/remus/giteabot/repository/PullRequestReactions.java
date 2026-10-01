package org.remus.giteabot.repository;

/**
 * Reaction identifiers shared by all providers: GitHub and Gitea accept them as the reaction
 * {@code content}, GitLab as the award-emoji {@code name}.
 */
public final class PullRequestReactions {

    public static final String EYES = "eyes";

    private PullRequestReactions() {
    }
}

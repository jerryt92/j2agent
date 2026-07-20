package io.github.jerryt92.j2agent.service.rag.knowledge.repository;

import io.github.jerryt92.j2agent.model.po.KnowledgeRepositoryPo;
import io.github.jerryt92.j2agent.model.repository.KnowledgeRepositoryDtos;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;

/**
 * Git 协议知识库仓库同步器。
 */
@Component
public class GitKnowledgeRepositorySyncer implements KnowledgeRepositorySyncer {

    @Override
    public String protocol() {
        return KnowledgeRepositoryConstants.PROTOCOL_GIT;
    }

    @Override
    public KnowledgeRepositorySyncResult sync(KnowledgeRepositoryPo repository,
                                              KnowledgeRepositoryDtos.CredentialConfig credentialConfig,
                                              Path localPath) {
        Path normalizedLocalPath = localPath.toAbsolutePath().normalize();
        String branch = StringUtils.trimToNull(repository.getDefaultBranch());
        try {
            Files.createDirectories(normalizedLocalPath.getParent());
            UsernamePasswordCredentialsProvider credentials = credentialsProvider(credentialConfig);
            if (!Files.exists(normalizedLocalPath.resolve(".git"))) {
                deleteExistingNonGitDirectory(normalizedLocalPath);
                cloneRepository(repository, branch, normalizedLocalPath, credentials);
            } else {
                hardResetToRemote(branch, normalizedLocalPath, credentials);
            }
            return readHead(normalizedLocalPath);
        } catch (Exception e) {
            throw new IllegalStateException("Git 知识库同步失败: " + repository.getRepoCode(), e);
        }
    }

    private void cloneRepository(KnowledgeRepositoryPo repository,
                                 String branch,
                                 Path localPath,
                                 UsernamePasswordCredentialsProvider credentials) throws Exception {
        var command = Git.cloneRepository()
                .setURI(repository.getRemoteUrl())
                .setDirectory(localPath.toFile())
                .setCloneAllBranches(false);
        if (StringUtils.isNotBlank(branch)) {
            command.setBranch(branch);
        }
        if (credentials != null) {
            command.setCredentialsProvider(credentials);
        }
        try (Git ignored = command.call()) {
            // Clone completes with HEAD at the requested branch.
        }
    }

    private void hardResetToRemote(String branch,
                                   Path localPath,
                                   UsernamePasswordCredentialsProvider credentials) throws Exception {
        try (Git git = Git.open(localPath.toFile())) {
            var fetch = git.fetch()
                    .setRemote("origin")
                    .setRemoveDeletedRefs(true);
            if (StringUtils.isNotBlank(branch)) {
                fetch.setRefSpecs(new RefSpec("+refs/heads/" + branch + ":refs/remotes/origin/" + branch));
            }
            if (credentials != null) {
                fetch.setCredentialsProvider(credentials);
            }
            fetch.call();

            String activeBranch = StringUtils.defaultIfBlank(branch, git.getRepository().getBranch());
            if (StringUtils.isBlank(activeBranch)) {
                throw new IllegalStateException("无法确定 Git 知识库分支");
            }
            String remoteBranch = "refs/remotes/origin/" + activeBranch;
            Ref localBranch = git.getRepository().findRef(activeBranch);
            var checkout = git.checkout().setName(activeBranch).setForced(true);
            if (localBranch == null) {
                checkout.setCreateBranch(true).setStartPoint(remoteBranch);
            }
            checkout.call();
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef("origin/" + activeBranch).call();
            git.clean().setCleanDirectories(true).setIgnore(false).setForce(true).call();
        }
    }

    private KnowledgeRepositorySyncResult readHead(Path localPath) throws Exception {
        try (Git git = Git.open(localPath.toFile())) {
            ObjectId head = git.getRepository().resolve("HEAD");
            Iterable<RevCommit> commits = git.log().setMaxCount(1).call();
            var iterator = commits.iterator();
            RevCommit commit = iterator.hasNext() ? iterator.next() : null;
            return new KnowledgeRepositorySyncResult(
                    head == null ? null : head.name(),
                    commit == null ? null : commit.getShortMessage(),
                    commit == null || commit.getAuthorIdent() == null ? null : commit.getAuthorIdent().getName(),
                    commit == null ? null : Instant.ofEpochSecond(commit.getCommitTime()).toEpochMilli());
        }
    }

    private UsernamePasswordCredentialsProvider credentialsProvider(
            KnowledgeRepositoryDtos.CredentialConfig credentialConfig) {
        if (credentialConfig == null) {
            return null;
        }
        String username = StringUtils.defaultString(credentialConfig.getUsername());
        String password = StringUtils.defaultIfBlank(credentialConfig.getToken(),
                StringUtils.defaultString(credentialConfig.getPassword()));
        if (StringUtils.isBlank(username) && StringUtils.isBlank(password)) {
            return null;
        }
        return new UsernamePasswordCredentialsProvider(username, password);
    }

    private void deleteExistingNonGitDirectory(Path localPath) throws IOException {
        if (!Files.exists(localPath)) {
            return;
        }
        try (var stream = Files.walk(localPath)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}

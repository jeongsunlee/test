package kr.wisestone.owl.service.impl;

import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.mysema.query.BooleanBuilder;
import kr.wisestone.owl.config.websocket.WebSocketSessionService;
import kr.wisestone.owl.constant.Constants;
import kr.wisestone.owl.constant.MsgConstants;
import kr.wisestone.owl.domain.*;
import kr.wisestone.owl.exception.OwlRuntimeException;
import kr.wisestone.owl.repository.BaseRepository;
import kr.wisestone.owl.repository.GitIntegrationDslrepository;
import kr.wisestone.owl.repository.GitIntegrationRepository;
import kr.wisestone.owl.service.GitCommitFileService;
import kr.wisestone.owl.service.GitCommitService;
import kr.wisestone.owl.service.GitIntegrationService;
import kr.wisestone.owl.service.ProjectService;
import kr.wisestone.owl.util.ConvertUtil;
import kr.wisestone.owl.util.DateUtil;
import kr.wisestone.owl.util.MapUtil;
import kr.wisestone.owl.util.PageUtil;
import kr.wisestone.owl.vo.*;
import org.eclipse.jgit.api.*;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.WindowCacheConfig;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.util.StringUtils;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.hibernate.Hibernate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.spring4.SpringTemplateEngine;

import java.io.*;
import java.util.*;

/**
 * Created by Jeong on 2017-01-13.
 */
@Service
public class GitIntegrationServiceImpl extends AbstractServiceImpl<GitIntegration, Long, BaseRepository<GitIntegration, Long>> implements GitIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(GitIntegrationServiceImpl.class);

    @Autowired
    private kr.wisestone.owl.repository.GitIntegrationRepository gitIntegrationRepository;

    @Autowired
    private GitIntegrationDslrepository gitIntegrationDslrepository;

    @Autowired
    private GitCommitService gitCommitService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private GitCommitFileService gitCommitFileService;

    @Autowired
    private SimpMessagingTemplate template;

    @Override
    protected BaseRepository<GitIntegration, Long> getRepository() {
        // TODO Auto-generated method stub
        return this.gitIntegrationRepository;
    }

    //  사용자 인증
    @Override
    @Transactional
    public void authGit(Map<String, Object> content) throws IOException, GitAPIException {
        GitIntegration gitIntegration = ConvertUtil.convertMapToClass(content, GitIntegration.class);
        this.authGit(gitIntegration);
    }

    private void authGit(GitIntegration gitIntegration) {
        try {
            if (StringUtils.isEmptyOrNull(gitIntegration.getPassword())) {
                Git.lsRemoteRepository()
                    .setRemote(gitIntegration.getUrl())
                    .call();
            } else {
                CredentialsProvider cp = new UsernamePasswordCredentialsProvider("", gitIntegration.getPassword());
                Git.lsRemoteRepository()
                    .setCredentialsProvider(cp)
                    .setRemote(gitIntegration.getUrl())
                    .call();
            }
        } catch (Exception e) {
            throw new OwlRuntimeException(this.ma.getMessage(MsgConstants.GIT_AUTH_FAIL));
        }
    }

    private void verifyGitIntegration(GitIntegration gitIntegration) {
        if (StringUtils.isEmptyOrNull(gitIntegration.getUrl())) {
            throw new OwlRuntimeException(this.ma.getMessage(MsgConstants.GIT_URL_NOT_EXIST));
        }

        if (StringUtils.isEmptyOrNull(gitIntegration.getDisplayName())) {
            throw new OwlRuntimeException(this.ma.getMessage(MsgConstants.GIT_DISPLAY_NAME_NOT_EXIST));
        }

        if (StringUtils.isEmptyOrNull(gitIntegration.getRepositoryRoot())) {
            throw new OwlRuntimeException(this.ma.getMessage(MsgConstants.GIT_REPOSITORY_ROOT_NOT_EXIST));
        }

        if (StringUtils.isEmptyOrNull(gitIntegration.getRepositoryOrigin())) {
            throw new OwlRuntimeException(this.ma.getMessage(MsgConstants.GIT_REPOSITORY_ORIGIN_NOT_EXIST));
        }

        if (gitIntegration.getUrl().contains("ssh")) {
            if (StringUtils.isEmptyOrNull(gitIntegration.getPassword())) {
                throw new OwlRuntimeException(this.ma.getMessage(MsgConstants.GIT_PASSWORD_NOT_EXIST));
            }
        }
    }

    private void findProject(GitIntegration gitIntegration, Map<String, Object> content) {
        Long projectId = MapUtil.getLong(content, "projectId");

        Project project = this.projectService.findOne(projectId);

        if (project == null) {
            throw new OwlRuntimeException(this.ma.getMessage(MsgConstants.PROJECT_NOT_EXIST));
        }

        gitIntegration.setProject(project);
    }

    private void verifyName(GitIntegration gitIntegration) {
        GitIntegration findGitIntegration = this.gitIntegrationRepository.findByProjectIdAndDisplayName(gitIntegration.getProject().getId(), gitIntegration.getDisplayName());

        if (findGitIntegration != null) {
            throw new OwlRuntimeException(this.ma.getMessage(MsgConstants.GIT_USED_DISPLAY_NAME));
        }
    }

    @Override
    @Async
    public void cloneRepository(GitIntegrationVo gitIntegrationVo, UserVo loginUser) throws IOException, GitAPIException {

        File localRepository = new File(gitIntegrationVo.getRepositoryRoot());
        Git git = null;

        WindowCacheConfig config = new WindowCacheConfig();
        config.setPackedGitMMAP(true);

        if (!localRepository.exists()) {
            localRepository.mkdirs();
        }

        try {
            if (StringUtils.isEmptyOrNull(gitIntegrationVo.getPassword())) {
                git = Git.cloneRepository()
                    .setRemote(gitIntegrationVo.getRepositoryOrigin())
                    .setBranch(gitIntegrationVo.getMainBranch())
                    .setURI(gitIntegrationVo.getUrl())
                    .setDirectory(localRepository)
                    .call();
            } else {
                CredentialsProvider cp = new UsernamePasswordCredentialsProvider("", gitIntegrationVo.getPassword());

                git = Git.cloneRepository()
                    .setCredentialsProvider(cp)
                    .setRemote(gitIntegrationVo.getRepositoryOrigin())
                    .setBranch(gitIntegrationVo.getMainBranch())
                    .setURI(gitIntegrationVo.getUrl())
                    .setDirectory(localRepository)
                    .call();
            }
        } catch (Exception e) {
            Repository closeRepository = new FileRepository(gitIntegrationVo.getRepositoryRoot() + "/.git");
            closeRepository.close();

            if (git != null) {
                git.close();
                git.getRepository().close();
            }

            throw new OwlRuntimeException(this.ma.getMessage(MsgConstants.GIT_MAKE_LOCAL_REPOSITORY_FAIL));
        }


        try {
            //  커밋 정보 등록
            this.commitUpdate(git, gitIntegrationVo);

            this.gitCommitFileUpdate(git, gitIntegrationVo);
        } catch (Exception e) {
            System.out.println(e);
        } finally {
            Repository closeRepository = new FileRepository(gitIntegrationVo.getRepositoryRoot() + "/.git");
            closeRepository.close();

            if (git != null) {
                git.close();
                git.getRepository().close();
            }
        }

        GitIntegration gitIntegration = this.gitIntegrationRepository.findOne(gitIntegrationVo.getId());
        //  상태 - 진행중에서 활성으로 변경
        gitIntegration.setStatus(GitIntegration.STATUS_ACTIVE);
        //  마지막 업데이트 시간
        gitIntegration.setUpdateDate(DateUtil.convertDateToStr(new Date()));

        this.gitIntegrationRepository.saveAndFlush(gitIntegration);

        /*this.template.convertAndSendToUser(loginUser.getUserNo(), "/gitIntegration/cloneComplete", gitIntegrationVo);*/
    }

    private void commitUpdate(Git git, GitIntegrationVo gitIntegrationVo) throws IOException, GitAPIException {
        LogCommand log = git.log();
        Iterable<RevCommit> revCommits = log.all().call();

        Project project = this.projectService.findOne(gitIntegrationVo.getProjectVo().getId());

        if (project == null) {
            throw new OwlRuntimeException(this.ma.getMessage(MsgConstants.PROJECT_NOT_EXIST));
        }

        for (RevCommit commit : revCommits) {
            org.eclipse.jgit.lib.PersonIdent author = commit.getAuthorIdent();
            Date commitTime = new Date(commit.getCommitTime() * 1000L);
            GitCommit gitCommit = new GitCommit(commit.getName(), commit.getFullMessage(), author.getName(), DateUtil.convertDateToStr(commitTime));

            Issue issue = this.gitCommitService.findIssueByCommitMessage(commit.getFullMessage());

            if (issue != null) {
                gitCommit.setIssue(issue);
            }

            gitCommit.setProject(project);

            this.gitCommitService.saveAndFlush(gitCommit);
        }
    }

    private void gitCommitFileUpdate(Git git, GitIntegrationVo gitIntegrationVo) throws IOException, GitAPIException {

        List<GitCommit> gitCommits = this.gitCommitService.findByProjectId(gitIntegrationVo.getProjectVo().getId());

        String oldCommitName = null;

        for (GitCommit gitCommit : gitCommits) {
            this.saveCommitDiff(git, gitCommit, oldCommitName);
            oldCommitName = gitCommit.getCommitName();
        }
    }

    @Override
    @Transactional
    public void saveCommitDiff(Git git, GitCommit newGitCommit, String oldCommitName) throws IOException, GitAPIException {
        if (!StringUtils.isEmptyOrNull(oldCommitName) && !StringUtils.isEmptyOrNull(newGitCommit.getCommitName())) {
            AbstractTreeIterator oldTreeParser = this.prepareTreeParser(git.getRepository(), oldCommitName);
            AbstractTreeIterator newTreeParser = this.prepareTreeParser(git.getRepository(), newGitCommit.getCommitName());

            List<DiffEntry> diffs = new ArrayList<DiffEntry>();

            try {
                diffs = git.
                    diff().
                    setOldTree(oldTreeParser).
                    setNewTree(newTreeParser).
                    call();
            } catch (Exception e) {
                throw new OwlRuntimeException(this.ma.getMessage(MsgConstants.GIT_LOCAL_REPOSITORY_LOAD_FAIL));
            }

            DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE);
            df.setRepository(git.getRepository());
            df.setDiffComparator(RawTextComparator.DEFAULT);
            df.setDetectRenames(true);

            for (DiffEntry entry : diffs) {
                Long linesAdded = 0L;
                Long linesDeleted = 0L;

                for (Edit edit : df.toFileHeader(entry).toEditList()) {
                    linesDeleted += edit.getEndA() - edit.getBeginA();
                    linesAdded += edit.getEndB() - edit.getBeginB();
                }

                GitCommitFile gitCommitFile = new GitCommitFile();
                gitCommitFile.setGitCommit(newGitCommit);
                gitCommitFile.setCommitType(entry.getChangeType());
                gitCommitFile.setFileName(entry.getNewPath());
                gitCommitFile.setLinesAdded(linesAdded);
                gitCommitFile.setLinesDeleted(linesDeleted);

                this.gitCommitFileService.saveAndFlush(gitCommitFile);
            }
        }
    }

    //  local git repository 생성
    @Override
    @Transactional
    public GitIntegrationVo addGit(Map<String, Object> content) throws IOException, GitAPIException {
        GitIntegration gitIntegration = ConvertUtil.convertMapToClass(content, GitIntegration.class);
        //  프로젝트 셋팅
        this.findProject(gitIntegration, content);
        //  필수 값 체크
        this.verifyGitIntegration(gitIntegration);
        //  이름 중복 체크
        this.verifyName(gitIntegration);

        //  main branch가 존재하지 않을 경우 master를 메인으로 한다.
        if (StringUtils.isEmptyOrNull(gitIntegration.getMainBranch())) {
            gitIntegration.setMainBranch("master");
        }

        this.gitIntegrationRepository.saveAndFlush(gitIntegration);

        GitIntegrationVo gitIntegrationVo = ConvertUtil.copyProperties(gitIntegration, GitIntegrationVo.class);
        gitIntegrationVo.setProjectVo(ConvertUtil.copyProperties(gitIntegration.getProject(), ProjectVo.class));

        return gitIntegrationVo;
    }

    @Override
    @Transactional
    public GitIntegrationVo modifyGit(Map<String, Object> content) throws IOException, GitAPIException {

        GitIntegration gitIntegration = this.gitIntegrationRepository.findOne(MapUtil.getLong(content, "id"));

        if (gitIntegration == null) {
            throw new OwlRuntimeException(this.ma.getMessage(MsgConstants.GIT_INTEGRATION_NOT_EXIST));
        }

        //  해당 git정보를 먼저 삭제한다.
        List<GitCommit> commits = this.gitCommitService.findByProjectId(gitIntegration.getProject().getId());
        this.gitCommitService.removeGitCommit(commits);

        //  동일할 경우 해당 로컬 git 파일을 삭제해준다.
        if (gitIntegration.getRepositoryRoot().equals(MapUtil.getString(content, "repositoryRoot"))) {

            File gitFolder = new File(gitIntegration.getRepositoryRoot());
            this.deleteDirectory(gitFolder);
        }

        GitIntegrationVo gitIntegrationVo = ConvertUtil.convertMapToClass(content, GitIntegrationVo.class);

        ConvertUtil.copyProperties(gitIntegrationVo, gitIntegration, "id");

        this.gitIntegrationRepository.saveAndFlush(gitIntegration);

        GitIntegrationVo modifyGitIntegrationVo = ConvertUtil.copyProperties(gitIntegration, GitIntegrationVo.class);
        modifyGitIntegrationVo.setProjectVo(ConvertUtil.copyProperties(gitIntegration.getProject(), ProjectVo.class));

        return modifyGitIntegrationVo;
    }

    private boolean deleteDirectory(File path) {
        if (!path.exists()) {
            return false;
        }
        File[] files = path.listFiles();
        for (File file : files) {
            if (file.isDirectory()) {
                this.deleteDirectory(file);
            } else {
                file.delete();
            }
        }
        return path.delete();
    }

    //  local git에 remote git 정보 pull하기
    @Override
    @Transactional
    public void pullGit(Map<String, Object> content) throws IOException, GitAPIException {
        GitIntegration gitIntegration = this.gitIntegrationDslrepository.findOne(MapUtil.getLong(content, "id"));

        if (gitIntegration == null) {
            throw new OwlRuntimeException(this.ma.getMessage(MsgConstants.GIT_INTEGRATION_NOT_EXIST));
        }

        Repository localRepository = new FileRepository(gitIntegration.getRepositoryRoot() + "/.git");
        Git git = new Git(localRepository);

        this.pullUpdate(git, gitIntegration);
    }

    //  github hook에서 호출해서 사용
    @Override
    @Transactional
    public void pullUpdate(Git git, GitIntegration gitIntegration) throws IOException, GitAPIException {
        try {
            if (StringUtils.isEmptyOrNull(gitIntegration.getPassword())) {

                git.pull()
                    .setRemote(gitIntegration.getRepositoryOrigin())
                    .setRemoteBranchName(gitIntegration.getMainBranch())
                    .call();
            } else {
                CredentialsProvider cp = new UsernamePasswordCredentialsProvider("", gitIntegration.getPassword());

                git.pull()
                    .setCredentialsProvider(cp)
                    .setRemote(gitIntegration.getRepositoryOrigin())
                    .setRemoteBranchName(gitIntegration.getMainBranch())
                    .call();
            }
        } catch (Exception e) {
            throw new OwlRuntimeException(this.ma.getMessage(MsgConstants.GIT_PULL_FAIL));
        }

        gitIntegration.setUpdateDate(DateUtil.convertDateToStr(new Date()));

        this.gitIntegrationRepository.saveAndFlush(gitIntegration);
    }

    @Override
    @Transactional(readOnly = true)
    public String findFileDiff(GitIntegration gitIntegration, String oldCommitName, String newCommitName) throws IOException, GitAPIException {
        Repository localRepository = new FileRepository(gitIntegration.getRepositoryRoot() + "/.git");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DiffFormatter formatter = new DiffFormatter(out);

        AbstractTreeIterator oldTreeParser = this.prepareTreeParser(localRepository, oldCommitName);
        AbstractTreeIterator newTreeParser = this.prepareTreeParser(localRepository, newCommitName);

        String diffResult = "";

        try (Git git = new Git(localRepository)) {
            List<DiffEntry> diffs = git.
                diff().
                setOldTree(oldTreeParser).
                setNewTree(newTreeParser).
                call();

            diffResult = this.getFileDiffContent(formatter, out, diffs, localRepository);
        } catch (Exception e) {
            throw new OwlRuntimeException(this.ma.getMessage(MsgConstants.GIT_LOCAL_REPOSITORY_LOAD_FAIL));
        }

        return diffResult;
    }

    private String getFileDiffContent(DiffFormatter formatter, ByteArrayOutputStream out, List<DiffEntry> diffs, Repository localRepo) throws IOException, GitAPIException {
        String diffText = "";

        for (DiffEntry entry : diffs) {
            formatter.setRepository(localRepo);
            formatter.format(entry);

            diffText = out.toString("UTF-8");
        }

        out.reset();

        return diffText;
    }

    private AbstractTreeIterator prepareTreeParser(Repository repository, String objectId) throws IOException {
        // from the commit we can build the tree which allows us to construct the TreeParser
        //noinspection Duplicates
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(ObjectId.fromString(objectId));
            RevTree tree = walk.parseTree(commit.getTree().getId());

            CanonicalTreeParser oldTreeParser = new CanonicalTreeParser();
            try (ObjectReader oldReader = repository.newObjectReader()) {
                oldTreeParser.reset(oldReader, tree.getId());
            }

            walk.dispose();

            return oldTreeParser;
        }
    }

    //  git 정보 목록
    @Override
    @Transactional(readOnly = true)
    public Page<GitIntegrationVo> findGit(Map<String, Object> conditions, Pageable pageable) {

        BooleanBuilder predicate = this.makePredicate(conditions);

        Page<GitIntegration> page = this.gitIntegrationDslrepository.findAll(predicate, PageUtil.applySort(pageable, "displayName", Sort.Direction.ASC));

        List<GitIntegrationVo> gitIntegrationVos = this.convertGitIntegrationToVo(page.getContent());

        return new PageImpl<>(gitIntegrationVos, pageable, page.getTotalElements());
    }

    private ArrayList<GitIntegrationVo> convertGitIntegrationToVo(List<GitIntegration> gitIntegrationVos) {
        return Lists.newArrayList(Iterables.transform(gitIntegrationVos, new Function<GitIntegration, GitIntegrationVo>() {
            @Override
            public GitIntegrationVo apply(GitIntegration gitIntegration) {

                GitIntegrationVo vo = ConvertUtil.copyProperties(gitIntegration, GitIntegrationVo.class);


                return vo;
            }
        }));
    }

    private BooleanBuilder makePredicate(Map<String, Object> conditions) {
        Long projectId = MapUtil.getLong(conditions, "projectId");

        QGitIntegration $gitIntegration = QGitIntegration.gitIntegration;
        BooleanBuilder predicate = new BooleanBuilder();

        predicate.and($gitIntegration.project.id.eq(projectId));

        return predicate;
    }

    @Override
    @Transactional
    public GitIntegrationVo findModifyGitIntegrationData(GitIntegrationVo paramVo) {
        GitIntegrationVo gitIntegrationVo = null;

        if (paramVo.getId() != null) {
            gitIntegrationVo = this.findGitIntegrationDetail(paramVo.getId());
        } else {
            gitIntegrationVo = new GitIntegrationVo();
        }

        return gitIntegrationVo;
    }

    private GitIntegrationVo findGitIntegrationDetail(Long gitIntegrationId) {
        GitIntegration gitIntegration = this.gitIntegrationDslrepository.findOne(gitIntegrationId);

        if (gitIntegration == null) {
            throw new OwlRuntimeException(
                this.ma.getMessage(MsgConstants.GIT_INTEGRATION_NOT_EXIST));
        }

        GitIntegrationVo gitIntegrationVo = ConvertUtil.copyProperties(gitIntegration, GitIntegrationVo.class);
        gitIntegrationVo.setProjectVo(ConvertUtil.copyProperties(gitIntegration.getProject(), ProjectVo.class));

        return gitIntegrationVo;
    }

    @Override
    @Transactional
    public void removeGit(List<GitIntegrationVo> gitIntegrationVos) {
        gitIntegrationVos.parallelStream().forEach(gitIntegrationVo -> {
            GitIntegration gitIntegration = this.gitIntegrationRepository.findOne(gitIntegrationVo.getId());
            this.gitIntegrationRepository.delete(gitIntegration);
            Project project = gitIntegration.getProject();
            //  커밋 정보 삭제
            List<GitCommit> gitCommits = this.gitCommitService.findByProjectId(project.getId());
            this.gitCommitService.removeGitCommit(gitCommits);
            //  로컬 저장소 폴더 삭제
            File gitFolder = new File(gitIntegration.getRepositoryRoot());
            this.deleteDirectory(gitFolder);

            project.setGitIntegration(null);
            this.projectService.saveAndFlush(project);
        });

        this.gitIntegrationRepository.flush();
    }

    //  다른 브런치로 체크아웃 하기
    @Override
    @Transactional
    public void checkOutGit(Map<String, Object> content) throws IOException, GitAPIException {
        String localPath = "C:\\testGit";
        String branchName = "3.4.0-git";
        Repository localRepo = new FileRepository(localPath + "/.git");
        Git git = new Git(localRepo);

        try {
            //  remote서버에서 선택한 branch정보로 local git에 branch를 생성한다.
            git.branchCreate()
                .setName(branchName)
                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM)
                .setStartPoint("origin/" + branchName)
                .setForce(true)
                .call();
        } catch (Exception e) {
            throw new OwlRuntimeException(this.ma.getMessage(MsgConstants.GIT_REMOVE_BRANCH_CHECK_OUT_FAIL));
        }
    }

    //  해당 브런치 구조(파일 정보) 가져오기
    @Override
    @Transactional(readOnly = true)
    public void findTreeGit(Map<String, Object> content, Map<String, Object> resJsonData) throws IOException, GitAPIException {
        String localPath = "C:\\testGit";
        Repository localRepo = new FileRepository(localPath + "/.git");
        Git git = new Git(localRepo);

        //commit.name을 db에 저장하면 된다.
        ObjectId head = ObjectId.fromString("9012b36ff3b09b8c8db1b0ab211aeda11fc0398e");
        ObjectId oldHead = ObjectId.fromString("646d05374b39a41d2c5febaf8e8d31f01fd9065a");

        RevWalk revWalk = new RevWalk(localRepo);
        RevCommit HeadCommit = revWalk.parseCommit(head);
        RevCommit oldCommit = revWalk.parseCommit(oldHead);

        /*try (ObjectReader reader = localRepo.newObjectReader()) {
            CanonicalTreeParser oldTreeIter = new CanonicalTreeParser();
            oldTreeIter.reset(reader, oldCommit.getTree());
            CanonicalTreeParser newTreeIter = new CanonicalTreeParser();
            newTreeIter.reset(reader, HeadCommit.getTree());

            // finally get the list of changed files
            try {
                List<DiffEntry> diffs = git.diff()
                    .setNewTree(newTreeIter)
                    .setOldTree(oldTreeIter)
                    .call();
                resJsonData.put(Constants.RES_KEY_CONTENTS, this.getCommitDiffContent(diffs, localRepo));
            } catch (Exception e) {

            }
        }*/
    }

    @Override
    @Transactional(readOnly = true)
    public void findFileDiff(Map<String, Object> content, Map<String, Object> resJsonData) throws IOException, GitAPIException {
        String localPath = "C:\\testGit";
        Repository localRepo = new FileRepository(localPath + "/.git");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DiffFormatter formatter = new DiffFormatter(out);

        AbstractTreeIterator oldTreeParser = this.prepareTreeParser(localRepo, "646d05374b39a41d2c5febaf8e8d31f01fd9065a");
        AbstractTreeIterator newTreeParser = this.prepareTreeParser(localRepo, "9012b36ff3b09b8c8db1b0ab211aeda11fc0398e");

        // then the procelain diff-command returns a list of diff entries
        try (Git git = new Git(localRepo)) {
            List<DiffEntry> diffs = git.diff().
                setOldTree(oldTreeParser).
                setNewTree(newTreeParser).
                /*setPathFilter(PathFilter.create("src/main/webapp/index.html")).*/
                    call();

            resJsonData.put(Constants.RES_KEY_CONTENTS, this.getFileDiffContent(formatter, out, diffs, localRepo));

            /*for (DiffEntry entry : diff) {
                formatter.setRepository(localRepo);
                formatter.format(entry);
                System.out.println("Entry: " + entry + ", from: " + entry.getOldId() + ", to: " + entry.getNewId());

                String diffText = out.toString("UTF-8");

                resJsonData.put(Constants.RES_KEY_CONTENTS, diffText);

                out.reset();

                *//*try (DiffFormatter formatter = new DiffFormatter(System.out)) {
                    formatter.setRepository(localRepo);
                    formatter.format(entry);
                }*//*
            }*/
        }
    }
}

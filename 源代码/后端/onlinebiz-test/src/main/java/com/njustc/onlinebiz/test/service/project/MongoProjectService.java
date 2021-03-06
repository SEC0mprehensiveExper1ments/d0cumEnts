package com.njustc.onlinebiz.test.service.project;

import com.njustc.onlinebiz.common.model.EntrustDto;
import com.njustc.onlinebiz.common.model.PageResult;
import com.njustc.onlinebiz.common.model.Role;
import com.njustc.onlinebiz.common.model.test.project.*;
import com.njustc.onlinebiz.common.model.test.report.Report;
import com.njustc.onlinebiz.test.dao.project.ProjectDAO;
import com.njustc.onlinebiz.test.exception.project.*;
import com.njustc.onlinebiz.test.service.report.ReportService;
import com.njustc.onlinebiz.test.service.review.EntrustTestReviewService;
import com.njustc.onlinebiz.test.service.review.ReportReviewService;
import com.njustc.onlinebiz.test.service.review.SchemeReviewService;
import com.njustc.onlinebiz.test.service.scheme.SchemeService;
import com.njustc.onlinebiz.test.service.testcase.TestcaseService;
import com.njustc.onlinebiz.test.service.testissue.TestIssueService;
import com.njustc.onlinebiz.test.service.testrecord.TestRecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Objects;


@Slf4j
@Service
public class MongoProjectService implements ProjectService {

    private final ProjectDAO projectDAO;
    private final SchemeService schemeService;
    private final SchemeReviewService schemeReviewService;
    private final TestcaseService testcaseService;
    private final TestRecordService testRecordService;
    private final ReportService reportService;
    private final EntrustTestReviewService entrustTestReviewService;
    private final ReportReviewService reportReviewService;
    private final TestIssueService testIssueService;

    private final RestTemplate restTemplate;
    private static final String ENTRUST_SERVICE = "http://onlinebiz-entrust";

    public MongoProjectService(ProjectDAO projectDAO,
                               SchemeService schemeService,
                               SchemeReviewService schemeReviewService,
                               TestcaseService testcaseService,
                               TestRecordService testRecordService,
                               ReportService reportService,
                               EntrustTestReviewService entrustTestReviewService,
                               ReportReviewService reportReviewService,
                               TestIssueService testIssueService,
                               RestTemplate restTemplate) {
        this.projectDAO = projectDAO;
        this.schemeService = schemeService;
        this.schemeReviewService = schemeReviewService;
        this.testcaseService = testcaseService;
        this.testRecordService = testRecordService;
        this.reportService = reportService;
        this.entrustTestReviewService = entrustTestReviewService;
        this.reportReviewService = reportReviewService;
        this.testIssueService = testIssueService;
        this.restTemplate = restTemplate;
    }

    @Override
    public String createTestProject(Long userId, Role userRole, String entrustId) {
        //??????????????????????????????
        if (userRole != Role.ADMIN && userRole != Role.MARKETER && userRole != Role.MARKETING_SUPERVISOR) {
            throw new ProjectPermissionDeniedException("??????????????????????????????");
        }
        EntrustDto entrustDto;
        String params = "?userId=" + userId + "&userRole=" + userRole;
        String url = ENTRUST_SERVICE + "/api/entrust/" + entrustId + "/get_dto";
        try {
            entrustDto = restTemplate.getForObject(url + params, EntrustDto.class);
        } catch (HttpClientErrorException e) {
            throw new ProjectDAOFailureException("??????DTO?????????" + e.getResponseBodyAsString());
        }
        // ??????entrustDto??????
        if (entrustDto == null) {
            throw new ProjectDAOFailureException("entrustDto??????");
        }
        // ??????????????????????????????
        if (entrustDto.getProjectId() != null) {
            throw new ProjectDAOFailureException("???????????????????????????");
        }
        // ?????????????????????
        if (userRole == Role.MARKETER && !Objects.equals(userId, entrustDto.getMarketerId())) {
            throw new ProjectPermissionDeniedException("??????????????????????????????, ????????????");
        }
        // ????????????????????????
        ProjectBaseInfo projectBaseInfo = new ProjectBaseInfo(entrustId, entrustDto);

        // ???????????????????????? [???updateQa????????????]
        // ???????????????????????????????????????????????????????????????????????? WAIT_FOR_QA
        Project project = new Project().setProjectBaseInfo(projectBaseInfo);
        project.setStatus(new ProjectStatus(ProjectStage.WAIT_FOR_QA, "??????????????????????????????????????????"));
        // ????????????ID
        String projectId = projectDAO.insertProject(project).getId();
        // ?????????ID????????????????????????
        params = "?projectId=" + projectId;
        url = ENTRUST_SERVICE + "/api/entrust/" + entrustId + "/register_project";
        try {
            restTemplate.postForEntity(url + params, null, Void.class);
        } catch (HttpClientErrorException e) {
            throw new InternalError("????????????ID?????????" + e.getResponseBodyAsString());
        }
        return projectId;
    }

    @Override
    public Project findProject(String projectId, Long userId, Role userRole) {
        Project project = projectDAO.findProjectById(projectId);
        if (project == null) {
            throw new ProjectNotFoundException("????????????????????????");
        }
        ProjectStage curStage = project.getStatus().getStage();
        // ????????????
        if (curStage == ProjectStage.WAIT_FOR_QA) {
            throw new ProjectInvalidStageException("????????????????????????");
        }
        return project;
    }

    @Override
    public PageResult<ProjectOutline> findProjectOutlines(Integer page, Integer pageSize, Long userId, Role userRole) {
        if (page <= 0 || pageSize <= 0) {
            throw new ProjectInvalidArgumentException("???????????????????????????????????????");
        }
        long total;
        List<ProjectOutline> list;
        // ?????????????????????????????????????????????
        if (userRole == Role.ADMIN || userRole == Role.MARKETING_SUPERVISOR
                || userRole == Role.QA_SUPERVISOR || userRole == Role.TESTING_SUPERVISOR) {
            total = projectDAO.countAll();
            list = projectDAO.findAllProjects(page, pageSize);
        }
        else if (userRole == Role.MARKETER) {
            total = projectDAO.countByMarketerId(userId);
            list = projectDAO.findProjectByMarketerId(userId, page, pageSize);
        }
        else if (userRole == Role.TESTER) {
            total = projectDAO.countByTesterId(userId);
            list = projectDAO.findProjectByTesterId(userId, page, pageSize);
        }
        else if (userRole == Role.QA) {
            total = projectDAO.countByQaId(userId);
            list = projectDAO.findProjectByQaId(userId, page, pageSize);
        }
        else {
            throw new ProjectPermissionDeniedException("??????????????????????????????");
        }
        return new PageResult<>(page, pageSize, total, list);
    }

    // ??????????????????????????????????????????????????????????????????????????????????????????????????????/???????????????????????????
    private Boolean createProjectForms(String projectId) {
        Project project = projectDAO.findProjectById(projectId);
        ProjectBaseInfo projectBaseInfo = project.getProjectBaseInfo();
        String entrustId = projectBaseInfo.getEntrustId();
        Long marketerId = projectBaseInfo.getMarketerId();
        Long testerId = projectBaseInfo.getTesterId();
        Long qaId = projectBaseInfo.getQaId();
        String serialNumber = projectBaseInfo.getSerialNumber();

        ProjectFormIds projectFormIds = new ProjectFormIds();
        /*TODO: ?????????????????????????????????????????????????????????????????????testProject??????????????????null*/
        /* ????????????????????????projectId???????????????projectId??????????????????????????????Id */
        /* ???????????????????????????????????????????????????project service?????????????????????????????? getFormIds ??????????????????????????? */
        // ????????????????????????????????????????????????????????????????????????????????????????????????create??????????????????????????????ID??????????????????????????????

        // ????????????????????? id (JS006)
        String testSchemeId = schemeService.createScheme(entrustId, null, marketerId, Role.MARKETER, projectId);
        projectFormIds.setTestSchemeId(testSchemeId);
        Report.ReportContent reportContent = new Report.ReportContent();
        reportContent.setProjectSerialNumber(serialNumber);
        String testReportId = reportService.createReport(projectId, entrustId, reportContent, marketerId, Role.MARKETER);
        projectFormIds.setTestReportId(testReportId);
        String testcaseListId = testcaseService.createTestcaseList(projectId, entrustId, null, marketerId, Role.MARKETER);
        projectFormIds.setTestcaseListId(testcaseListId);
        String testRecordListId = testRecordService.createTestRecordList(projectId, entrustId, null, marketerId, Role.MARKETER);
        projectFormIds.setTestRecordListId(testRecordListId);
        // ?????????????????????????????? id (JS010)
        String testReportChecklistId = reportReviewService.createReportReview(projectId, marketerId, Role.MARKETER);
        projectFormIds.setTestReportCecklistId(testReportChecklistId);
        // ??????????????????????????? id (JS011)
        String testIssueListId = testIssueService.createTestIssueList(projectId, entrustId, null, marketerId, Role.MARKETER);
        projectFormIds.setTestIssueListId(testIssueListId);
        // ???????????????????????? id (JS012)
        String workChecklistId = entrustTestReviewService.createEntrustTestReview(projectId, marketerId, Role.MARKETER);
        projectFormIds.setWorkChecklistId(workChecklistId);
        // ?????????????????????????????? (JS013)
        String testSchemeChecklistId = schemeReviewService.createSchemeReview(projectId, serialNumber, qaId, testerId);
        projectFormIds.setTestSchemeChecklistId(testSchemeChecklistId);

        // ?????????????????????formIds
        return projectDAO.updateFormIds(projectId, projectFormIds);
    }

    @Override
    public void updateQa(String projectId, Long qaId, Long userId, Role userRole) {
        Project project = projectDAO.findProjectById(projectId);
        // ????????????????????????
        ProjectStage curStage = project.getStatus().getStage();
        if (curStage != ProjectStage.WAIT_FOR_QA) {
            throw new ProjectInvalidStageException("????????????????????????????????????");
        }
        // ??????????????????
        if (userRole != Role.ADMIN && userRole != Role.QA_SUPERVISOR) {
            throw new ProjectPermissionDeniedException("???????????????????????????");
        }
        // ?????????????????????????????????
        if (!projectDAO.updateQaId(projectId, qaId)) {
            throw new ProjectDAOFailureException("???????????????????????????");
        }
        // ????????????
        if (!createProjectForms(projectId)) {
            throw new ProjectDAOFailureException("???????????????????????????");
        }
    }

    @Override
    public void removeProject(String projectId, Long userId, Role userRole) {
        if (userRole != Role.ADMIN) {
            throw new ProjectPermissionDeniedException("??????????????????");
        }
        Project project = findProject(projectId, userId, userRole);

        // TODO: ???????????????????????????????????????
        ProjectFormIds projectFormIds = project.getProjectFormIds();
        schemeReviewService.removeSchemeReview(projectFormIds.getTestSchemeChecklistId(), userId, userRole);

        if (!projectDAO.deleteProject(project.getId())) {
            throw new ProjectDAOFailureException("??????????????????");
        }
    }

    @Override
    public void updateStatus(String projectId, ProjectStatus nextStatus, Long userId, Role userRole) {
        Project project = projectDAO.findProjectById(projectId);
        // ??????????????????
        if (userRole != Role.ADMIN && userRole != Role.TESTING_SUPERVISOR && userRole != Role.QA_SUPERVISOR
            && !userId.equals(project.getProjectBaseInfo().getCustomerId())     // ?????????????????????
            && !userId.equals(project.getProjectBaseInfo().getTesterId())       // ??????????????????????????????
            && !userId.equals(project.getProjectBaseInfo().getQaId())           // ??????????????????????????????
            && !userId.equals(project.getProjectBaseInfo().getMarketerId()))    // ??????????????????????????????
        {
            throw new ProjectPermissionDeniedException("????????????????????????");
        }

        if (userId.equals(project.getProjectBaseInfo().getCustomerId())) {
            // ???????????????????????????????????????, next: REPORT_CUSTOMER_CONFIRM ??? REPORT_CUSTOMER_REJECT
            if (project.getStatus().getStage() != ProjectStage.REPORT_WAIT_CUSTOMER
                    || (nextStatus.getStage() != ProjectStage.REPORT_CUSTOMER_CONFIRM
                    && nextStatus.getStage() != ProjectStage.REPORT_CUSTOMER_REJECT)) {
                throw new ProjectInvalidStageException("?????????????????????????????????");
            }
            if (!projectDAO.updateStatus(project.getId(), nextStatus)) {
                throw new ProjectDAOFailureException("??????????????????????????????");
            }
            return;
        }

        switch (project.getStatus().getStage()) {
            case WAIT_FOR_QA:               // ????????????????????????????????????????????????, next: SCHEME_UNFILLED
                if (nextStatus.getStage() != ProjectStage.SCHEME_UNFILLED || project.getProjectBaseInfo().getQaId() == null) {
                    throw new ProjectInvalidStageException("?????????????????????????????????");
                }
                break;
            case SCHEME_UNFILLED:           // ?????????????????????, next: SCHEME_AUDITING
            case SCHEME_AUDITING_DENIED:    // ???????????????????????????????????????, next: SCHEME_AUDITING
                if (nextStatus.getStage() != ProjectStage.SCHEME_AUDITING) {
                    throw new ProjectInvalidStageException("?????????????????????????????????");
                }
                break;
            case SCHEME_AUDITING:           // ??????????????????????????????????????????, next: SCHEME_AUDITING_DENIED ??? SCHEME_AUDITING_PASSED
                if (nextStatus.getStage() != ProjectStage.SCHEME_AUDITING_DENIED && nextStatus.getStage() != ProjectStage.SCHEME_AUDITING_PASSED) {
                    throw new ProjectInvalidStageException("?????????????????????????????????");
                }
                break;
            case SCHEME_AUDITING_PASSED:    // ????????????????????????????????????, next: SCHEME_REVIEW_UPLOADED
                if (nextStatus.getStage() != ProjectStage.SCHEME_REVIEW_UPLOADED) {
                    throw new ProjectInvalidStageException("?????????????????????????????????");
                }
                break;
            case SCHEME_REVIEW_UPLOADED:    // ???????????????????????????????????????????????????????????????????????????, next: REPORT_AUDITING, ????????????????????????
            case REPORT_QA_DENIED:          // ???????????????????????????????????????, next: REPORT_AUDITING,???????????????????????????
            case REPORT_CUSTOMER_REJECT:    // ??????????????????????????????, next: REPORT_AUDITING
                if (nextStatus.getStage() != ProjectStage.REPORT_AUDITING) {
                    throw new ProjectInvalidStageException("?????????????????????????????????");
                }
                break;
            case REPORT_AUDITING:           // ??????????????????????????????????????????, next: REPORT_QA_DENIED ??? REPORT_QA_PASSED
                if (nextStatus.getStage() != ProjectStage.REPORT_QA_DENIED && nextStatus.getStage() != ProjectStage.REPORT_QA_PASSED) {
                    throw new ProjectInvalidStageException("?????????????????????????????????");
                }
                break;
            case REPORT_QA_PASSED:          // ?????????????????????????????????????????????????????????????????????, next: REPORT_WAIT_SENT_TO_CUSTOMER
                if (nextStatus.getStage() != ProjectStage.REPORT_WAIT_SENT_TO_CUSTOMER) {
                    throw new ProjectInvalidStageException("?????????????????????????????????");
                }
                break;
            case REPORT_WAIT_SENT_TO_CUSTOMER: // ????????????????????????????????????????????????????????????????????????, next: REPORT_WAIT_CUSTOMER
                if (nextStatus.getStage() != ProjectStage.REPORT_WAIT_CUSTOMER) {
                    throw new ProjectInvalidStageException("?????????????????????????????????");
                }
                break;
//            case REPORT_WAIT_CUSTOMER:      // ???????????????????????????????????????, next: REPORT_CUSTOMER_CONFIRM ??? REPORT_CUSTOMER_REJECT
//                if (nextStatus.getStage() != ProjectStage.REPORT_CUSTOMER_CONFIRM && nextStatus.getStage() != ProjectStage.REPORT_CUSTOMER_REJECT) {
//                    throw new ProjectInvalidStageException("?????????????????????????????????");
//                }
//                break;
            case REPORT_CUSTOMER_CONFIRM:   // ????????????????????????????????????????????????????????????, next: QA_ALL_REJECTED ??? QA_ALL_PASSED
                if (nextStatus.getStage() != ProjectStage.QA_ALL_REJECTED && nextStatus.getStage() != ProjectStage.QA_ALL_PASSED) {
                    throw new ProjectInvalidStageException("?????????????????????????????????");
                }
                break;
            case QA_ALL_REJECTED:           // ????????????????????????????????????, next: REPORT_CUSTOMER_CONFIRM
                if (nextStatus.getStage() != ProjectStage.REPORT_CUSTOMER_CONFIRM) {
                    throw new ProjectInvalidStageException("?????????????????????????????????");
                }
                break;
            case QA_ALL_PASSED:             // ?????????????????????????????????????????????????????????????????????
                throw new ProjectInvalidStageException("???????????????");
            default:
                throw new ProjectInvalidStageException("?????????????????????????????????????????????????????????");
        }
        if (!projectDAO.updateStatus(project.getId(), nextStatus)) {
            throw new ProjectDAOFailureException("??????????????????????????????");
        }
    }

    @Override
    public ProjectFormIds getProjectFormIds(String projectId) {
        Project project = projectDAO.findProjectById(projectId);
        if (project == null) {
            throw new ProjectNotFoundException("????????????????????????");
        }
        return project.getProjectFormIds();
    }

    @Override
    public ProjectBaseInfo getProjectBaseInfo(String projectId) {
        Project project = projectDAO.findProjectById(projectId);
        if (project == null) {
            throw new ProjectNotFoundException("????????????????????????");
        }
        return project.getProjectBaseInfo();
    }
}

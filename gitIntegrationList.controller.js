/**
 * Created by Jeong on 2017-01-06.
 */
(function () {
    'use strict';

    angular.module('wisestoneApp')
        .controller("gitIntegrationListCtrl", gitIntegrationListCtrl);

    gitIntegrationListCtrl.$inject = ['$filter', '$scope', 'resourceService', '$log', 'dataService', 'uiConstant', '$state', '$stateParams',
        'alterMessageService', 'progressbarService', 'GitIntegration', 'cookieService', 'excelService', 'projectInfo', 'tableService',
        'modalService', 'permissionService', '$rootScope'];

    function gitIntegrationListCtrl($filter, $scope, resourceService, $log, dataService, uiConstant, $state, $stateParams,
                                    alterMessageService, progressbarService, GitIntegration, cookieService, excelService, projectInfo, tableService,
                                    modalService, permissionService, $rootScope) {

        $scope.addGitIntegration = addGitIntegration;
        $scope.modifyGitIntegration = modifyGitIntegration;
        $scope.updateRepository = updateRepository;


        $scope.findGit = findGit;
        $scope.addGit = addGit;
        $scope.checkOutGit = checkOutGit;
        $scope.findTreeGit = findTreeGit;
        $scope.pullGit = pullGit;
        $scope.authGit = authGit;
        $scope.findFileDiff = findFileDiff;
        $scope.webHook = webHook;


        $scope.getList = getList;
        $scope.deleteData = deleteData;
        $scope.makeTableConfig = makeTableConfig;
        $scope.checkExcelDownLoad = checkExcelDownLoad;

        angular.extend(this, new commonListCtrl($scope, excelService, cookieService, $state, uiConstant, dataService));
        angular.extend(this, new customHelpCtrl($scope, resourceService, $state, dataService, $log));

        dataService.setProjectId(projectInfo.id);
        dataService.setProjectName(projectInfo.name);

        $scope.dataService = dataService;
        $scope.cookieService = cookieService;
        $scope.tableService = tableService;
        $scope.resourceService = resourceService;
        $scope.modalService = modalService;
        $scope.permissionService = permissionService;
        $scope.filter = $filter("relativeUrl");

        //  변수 모음
        $scope.vm = {
            excelSearchFormName: "gitIntegrationSearchForm",
            statusOptions: [{
                fieldKey: "01",
                fieldValue: $filter("translate")("common.active")
            }, {
                fieldKey: "03",
                fieldValue: $filter("translate")("common.progressing")
            }]
        };

        $scope.actionList = {
            modify: uiConstant.urlType.MILESTONE_MODIFY,
            add: uiConstant.urlType.MILESTONE_ADD,
            remove: uiConstant.urlType.MILESTONE_REMOVES,
            list: uiConstant.urlType.MILESTONE_FIND,
            excel: uiConstant.urlType.MILESTONE_EXPORT,
            detail: uiConstant.urlType.MILESTONE_DETAIL,
        };

        //	목록 데이터 저장
        $scope.responseData = {
            page: {
                totalPage: 1
            }
        };

        // 검색 영역
        $scope.searchData = {
            name: "",
            projectId: projectInfo.id,
            projectName: projectInfo.name,
            displayName: "",
            statuses: []
        };

        //  목록 화면을 새로고친다.
        $scope.$on("initList", function (event, args) {
            $scope.getList(cookieService.getCookie("/selectedPage"));
        });

        $scope.$on("repositoryCloneComplete", function (event, args) {
            var updateLists = [];

            angular.forEach($scope.responseData.data, function (data) {
                if (data.id == args.id) {
                    data.status = "01";
                    data.updateDate = args.updateDate
                }
            });

            updateLists = angular.copy($scope.responseData.data);

            $scope.responseData.data = [];

            $scope.responseData.data = angular.copy(updateLists);
        });

        //  git 접속 정보 생성
        function addGitIntegration() {
            modalService.openModal({
                url: "/app/projectConfig/gitIntegration/gitIntegrationAdd.html",
                size: "lg",
                ctrl: "gitIntegrationAddCtrl",
                projectId: projectInfo.id
            });
        }

        //  git 접속 정보 변경
        function modifyGitIntegration (data) {
            modalService.openModal({
                url: "/app/projectConfig/gitIntegration/gitIntegrationModify.html",
                size: "lg",
                ctrl: "gitIntegrationModifyCtrl",
                projectId: projectInfo.id,
                id : data.id
            });
        }

        function updateRepository (data) {
            progressbarService.show();

            GitIntegration.pullGit(resourceService.getTransactionParam(
                { id : data.id },
                resourceService.getPageParam(0, 10))).then(function (response) {

                alterMessageService.show(response.data.message, null);

                $rootScope.$broadcast("initList", {});

                progressbarService.hide();
            });
        }


        //	엑셀 다운로드 가능 여부 검사
        function checkExcelDownLoad() {
            $scope.excelAvailable = true;
        }

        // 검색
        function getList(selectedPage) {
            progressbarService.show();

            //  만약 0보다 적으면 잘못된 값이므로 0으로 셋팅
            if (selectedPage < 0) {
                selectedPage = 0;
            }
            //  현재 페이지 정보
            var currentPage = 0;

            //쿠키에 선택한 페이지 정보가 없으면 기본 페이지 정보 0 을 저장
            if (selectedPage === undefined || selectedPage === "") {
                currentPage = $scope.selectedPage;
            }
            else {
                //	요청 페이지 정보를 쿠키를구운다.
                currentPage = selectedPage;
            }

            cookieService.setCookie("/selectedPage", currentPage);

            //페이지 갯수가 쿠키에 있을 경우
            if (cookieService.getCookie("/selectedPageRowCount") !== undefined) {
                $scope.selectedPageRowCount = cookieService.getCookie("/selectedPageRowCount");
            }

            var conditions = {
                projectId: $scope.searchData.projectId,
                displayName: $scope.searchData.displayName,
                statuses: (function () {
                    var statuses = [];

                    angular.forEach($scope.searchData.statuses, function (status) {
                        statuses.push(status.fieldKey);
                    });

                    return statuses;
                })()
            };

            GitIntegration.find(resourceService.getSearchParam(
                conditions,
                resourceService.getPageParam(currentPage, $scope.selectedPageRowCount))).then(function (response) {
                if (response.data.message.status == "success") {
                    $scope.responseData = response.data;
                    //통신에 성공하면 요청 페이지를 기억
                    $scope.selectedPage = currentPage + 1;
                }
                else {
                    alterMessageService.show(response.data.message, null);
                }

                progressbarService.hide();

                tableService.checkedClear();
            });
        }

        // 삭제
        function deleteData() {
            var delData = [];
            delData = $scope.getCheckedData();
            if (delData.length < 1) {
                modalService.userConfirmModal("common.removeSelect", "common.removeTargetSelect", uiConstant.urlType.COMMON_ALERT_MODAL_HTML);
                return;
            }
            $rootScope.$broadcast(uiConstant.common.SET_LIST_TARGET, delData);
            $state.go("projectConfig.milestoneRemove", {projectId: dataService.projectId});
        }

        function makeTableConfig() {
            $scope.tableConfigs.push(tableService.getConfig("", "checked")
                .setHWidth(uiConstant.css.WIDTH_30_P)
                .setDAlign(uiConstant.css.TEXT_CENTER)
                .setHAlign(uiConstant.css.TEXT_CENTER)
                .setDType(uiConstant.common.CHECK));
            $scope.tableConfigs.push(tableService.getConfig("gitIntegration.displayName", "displayName")
                .setHWidth(uiConstant.css.WIDTH_160_P));
            $scope.tableConfigs.push(tableService.getConfig("gitIntegration.repositoryPath", "url")
                .setHWidth(uiConstant.css.WIDTH_200_P)
                .setDAlign(uiConstant.css.TEXT_CENTER));
            $scope.tableConfigs.push(tableService.getConfig("gitIntegration.updateDate", "updateDate")
             .setHWidth(uiConstant.css.WIDTH_100_P)
             .setDAlign(uiConstant.css.TEXT_CENTER));
            $scope.tableConfigs.push(tableService.getConfig("common.status", "status")
                .setHWidth(uiConstant.css.WIDTH_100_P)
                .setDType(uiConstant.common.RENDERER)
                .setDRenderer(uiConstant.renderType.GIT_INTEGRATION_STATUS)
                .setDAlign(uiConstant.css.TEXT_CENTER));
            $scope.tableConfigs.push(tableService.getConfig("common.config", "")
                .setHWidth(uiConstant.css.WIDTH_100_P)
                .setDType(uiConstant.common.RENDERER)
                .setDRenderer(uiConstant.renderType.GIT_INTEGRATION_CONFIG)
                .setDAlign(uiConstant.css.TEXT_CENTER));
        }


        $scope.makeTableConfig();

        $scope.getList(cookieService.getCookie("/selectedPage"));


        function authGit() {
            GitIntegration.authGit(resourceService.getSearchParam(
                {},
                resourceService.getPageParam(0, 10))).then(function (response) {
                alterMessageService.show(response.data.message, null);

            });
        }

        function pullGit() {
            GitIntegration.pullGit(resourceService.getSearchParam(
                {},
                resourceService.getPageParam(0, 10))).then(function (response) {
                alterMessageService.show(response.data.message, null);

            });
        }

        function checkOutGit() {
            GitIntegration.checkOutGit(resourceService.getSearchParam(
                {},
                resourceService.getPageParam(0, 10))).then(function (response) {
                alterMessageService.show(response.data.message, null);

            });
        }

        function findTreeGit() {
            GitIntegration.findTreeGit(resourceService.getSearchParam(
                {},
                resourceService.getPageParam(0, 10))).then(function (response) {
                alterMessageService.show(response.data.message, null);

                var diff2htmlUi = new Diff2HtmlUI({diff: response.data.data});
                diff2htmlUi.draw('#gitshow', {inputFormat: 'json', showFiles: true, matching: 'lines'});

            });
        }

        function addGit() {
            GitIntegration.addGit(resourceService.getSearchParam(
                {},
                resourceService.getPageParam(0, 10))).then(function (response) {
                alterMessageService.show(response.data.message, null);

            });
        }

        function findGit() {
            GitIntegration.findGit(resourceService.getSearchParam(
                {},
                resourceService.getPageParam(0, 10))).then(function (response) {
                alterMessageService.show(response.data.message, null);

            });
        }

        function findFileDiff() {
            GitIntegration.findFileDiff(resourceService.getSearchParam(
                {},
                resourceService.getPageParam(0, 10))).then(function (response) {
                alterMessageService.show(response.data.message, null);

                var diff2htmlUi = new Diff2HtmlUI({diff: response.data.data});
                diff2htmlUi.draw('#gitshow', {inputFormat: 'json', showFiles: true, matching: 'lines'});

            });
        }

        function webHook() {
            GitIntegration.webHook(resourceService.getSearchParam(
                {},
                resourceService.getPageParam(0, 10))).then(function (response) {
                alterMessageService.show(response.data.message, null);

            });
        }
    }
})();

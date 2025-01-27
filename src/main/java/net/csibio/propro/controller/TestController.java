package net.csibio.propro.controller;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import net.csibio.aird.bean.WindowRange;
import net.csibio.propro.algorithm.formula.FragmentFactory;
import net.csibio.propro.algorithm.learner.SemiSupervise;
import net.csibio.propro.algorithm.learner.Statistics;
import net.csibio.propro.algorithm.learner.classifier.Lda;
import net.csibio.propro.algorithm.learner.classifier.Xgboost;
import net.csibio.propro.algorithm.score.ScoreType;
import net.csibio.propro.algorithm.score.scorer.Scorer;
import net.csibio.propro.constants.enums.IdentifyStatus;
import net.csibio.propro.domain.Result;
import net.csibio.propro.domain.bean.common.IdName;
import net.csibio.propro.domain.bean.data.DataScore;
import net.csibio.propro.domain.bean.data.PeptideRef;
import net.csibio.propro.domain.bean.learner.ErrorStat;
import net.csibio.propro.domain.bean.learner.FinalResult;
import net.csibio.propro.domain.bean.learner.LearningParams;
import net.csibio.propro.domain.bean.score.PeakGroup;
import net.csibio.propro.domain.bean.score.SelectedPeakGroup;
import net.csibio.propro.domain.db.*;
import net.csibio.propro.domain.query.*;
import net.csibio.propro.service.*;
import net.csibio.propro.utils.PeptideUtil;
import net.csibio.propro.utils.ProProUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/test")
@Slf4j
public class TestController {

    @Autowired
    ProjectService projectService;
    @Autowired
    ProteinService proteinService;
    @Autowired
    LibraryService libraryService;
    @Autowired
    PeptideService peptideService;
    @Autowired
    OverviewService overviewService;
    @Autowired
    DataSumService dataSumService;
    @Autowired
    RunService runService;
    @Autowired
    MethodService methodService;
    @Autowired
    SemiSupervise semiSupervise;
    @Autowired
    DataService dataService;
    @Autowired
    Lda lda;
    @Autowired
    Xgboost xgboost;
    @Autowired
    Statistics statistics;
    @Autowired
    Scorer scorer;
    @Autowired
    FragmentFactory fragmentFactory;
    @Autowired
    BlockIndexService blockIndexService;

    @GetMapping(value = "/lms")
    Result lms() {
        List<IdName> projects = projectService.getAll(new ProjectQuery(), IdName.class);
        for (IdName project : projects) {
            List<OverviewDO> overviewList =
                    overviewService.getAll(new OverviewQuery().setProjectId(project.id()));
            for (OverviewDO overviewDO : overviewList) {
                //                overviewDO.setReselect(overviewDO.getReselect());
                //                overviewService.update(overviewDO);
            }
        }

        return Result.OK();
    }

    @GetMapping(value = "/lms2_1")
    Result lms2_1() {
        String projectId = "613f5d8262cbcf5bb4345270";
        List<IdName> idNameList =
                overviewService.getAll(new OverviewQuery(projectId).setDefaultOne(true), IdName.class);
        List<String> overviewIds = idNameList.stream().map(IdName::id).collect(Collectors.toList());
        overviewIds.clear();
        overviewIds.add("619b3907130b5f12ee620956");

        log.info("一共overview" + overviewIds.size() + "个");
        boolean success = true;
        for (String overviewId : overviewIds) {
            OverviewDO overview = overviewService.getById(overviewId);
            LearningParams params = new LearningParams();
            params.setScoreTypes(overview.fetchScoreTypes());
            params.setFdr(overview.getParams().getMethod().getClassifier().getFdr());
            FinalResult finalResult = new FinalResult();

            // Step1. 数据预处理
            log.info("数据预处理");
            params.setType(overview.getType());
            // Step2. 从数据库读取全部含打分结果的数据
            log.info("开始获取打分数据");
            List<DataScore> peptideList =
                    dataService.getAll(
                            new DataQuery().setOverviewId(overviewId).setStatus(IdentifyStatus.WAIT.getCode()),
                            DataScore.class,
                            overview.getProjectId());
            if (peptideList == null || peptideList.size() == 0) {
                log.info("没有合适的数据");
                return null;
            }
            log.info("总计有待鉴定态肽段" + peptideList.size() + "个");

            log.info("重新计算初始分完毕");
            // Step3. 开始训练数据集
            xgboost.classifier(peptideList, params);

            // 进行第一轮严格意义的初筛
            log.info("开始第一轮严格意义上的初筛");
            List<SelectedPeakGroup> selectedPeakGroupListV1 = null;
            try {
                selectedPeakGroupListV1 = scorer.findBestPeakGroup(peptideList);
                statistics.errorStatistics(selectedPeakGroupListV1, params);
                semiSupervise.giveDecoyFdr(selectedPeakGroupListV1);
                // 获取第一轮严格意义上的最小总分阈值
                double minTotalScore =
                        selectedPeakGroupListV1.stream()
                                .filter(s -> s.getFdr() != null && s.getFdr() < params.getFdr())
                                .max(Comparator.comparingDouble(SelectedPeakGroup::getFdr))
                                .get()
                                .getTotalScore();
                log.info("初筛下的最小总分值为:" + minTotalScore + ";开始第二轮筛选");
                List<SelectedPeakGroup> selectedPeakGroupListV2 = scorer.findBestPeakGroup(peptideList);
                // 重新统计
                ErrorStat errorStat = statistics.errorStatistics(selectedPeakGroupListV2, params);
                semiSupervise.giveDecoyFdr(selectedPeakGroupListV2);

                long start = System.currentTimeMillis();
                // Step4. 对于最终的打分结果和选峰结果保存到数据库中, 插入最终的DataSum表的数据为所有的鉴定结果以及 fdr小于0.01的伪肽段
                log.info(
                        "将合并打分及定量结果反馈更新到数据库中,总计:"
                                + selectedPeakGroupListV2.size()
                                + "条数据,开始统计相关数据,FDR:"
                                + params.getFdr());
                minTotalScore =
                        selectedPeakGroupListV2.stream()
                                .filter(s -> s.getFdr() != null && s.getFdr() < params.getFdr())
                                .max(Comparator.comparingDouble(SelectedPeakGroup::getFdr))
                                .get()
                                .getTotalScore();

                log.info("最小阈值总分为:" + minTotalScore);
                dataSumService.buildDataSumList(
                        selectedPeakGroupListV2, params.getFdr(), overview, overview.getProjectId());
                log.info(
                        "插入Sum数据"
                                + selectedPeakGroupListV2.size()
                                + "条一共用时："
                                + (System.currentTimeMillis() - start)
                                + "毫秒");

                semiSupervise.targetDecoyDistribution(
                        selectedPeakGroupListV2, overview); // 统计Target Decoy分布的函数
                overviewService.update(overview);
                overviewService.statistic(overview);

                finalResult.setAllInfo(errorStat);
                int count = ProProUtil.checkFdr(finalResult, params.getFdr());
                if (count < 20000) {
                    success = false;
                    break;
                }
                log.info("合并打分完成,共找到新肽段" + count + "个");
            } catch (Exception e) {
                e.printStackTrace();
                success = false;
                break;
            }
        }

        return Result.OK();
    }

    @GetMapping(value = "/lms3")
    Result lms3() {
        List<LibraryDO> libraryList = libraryService.getAll(new LibraryQuery());
        log.info("总计有库:" + libraryList.size() + "个");
        AtomicInteger count = new AtomicInteger(1);
        libraryList.stream()
                .parallel()
                .forEach(
                        library -> {
                            log.info("开始处理库:" + library.getName() + ":" + count.get() + "/" + libraryList.size());
                            count.getAndIncrement();
                            List<PeptideDO> peptideList =
                                    peptideService.getAll(new PeptideQuery(library.getId()));
                            peptideList.forEach(
                                    peptide -> {
                                        fragmentFactory.calcFingerPrints(peptide);
                                        peptideService.update(peptide);
                                    });
                            log.info("库" + library.getName() + "处理完毕");
                        });
        return Result.OK();
    }

    @GetMapping(value = "/lms4")
    Result lms4() {
        String projectId = "613f5d8262cbcf5bb4345270";
        List<IdName> idNameList =
                overviewService.getAll(new OverviewQuery(projectId).setDefaultOne(true), IdName.class);
        idNameList = idNameList.subList(0, 1);
        for (IdName idName : idNameList) {
            String overviewId = idName.id();
            OverviewDO overview = overviewService.getById(overviewId);

            log.info("读取数据库信息中");
            Map<String, DataScore> peptideMap =
                    dataService
                            .getAll(
                                    new DataQuery()
                                            .setOverviewId(overviewId)
                                            .setStatus(IdentifyStatus.WAIT.getCode())
                                            .setDecoy(false),
                                    DataScore.class,
                                    overview.getProjectId())
                            .stream()
                            .collect(Collectors.toMap(DataScore::getId, Function.identity()));
            Map<String, DataSumDO> sumMap =
                    dataSumService
                            .getAll(
                                    new DataSumQuery().setOverviewId(overviewId).setDecoy(false),
                                    DataSumDO.class,
                                    overview.getProjectId())
                            .stream()
                            .collect(Collectors.toMap(DataSumDO::getPeptideRef, Function.identity()));
            AtomicLong stat = new AtomicLong(0);
            List<String> findItList = new ArrayList<>();
            peptideMap
                    .values()
                    .forEach(
                            data -> {
                                DataSumDO sum = sumMap.get(data.getPeptideRef());
                                if (sum != null && sum.getStatus().equals(IdentifyStatus.SUCCESS.getCode())) {
                                    PeakGroup peakGroup =
                                            data.getPeakGroupList().stream()
                                                    .filter(peak -> peak.getSelectedRt().equals(sum.getSelectedRt()))
                                                    .findFirst()
                                                    .get();
                                    double pearson = peakGroup.get(ScoreType.Pearson, ScoreType.usedScoreTypes());
//                                    double apexPearson = peakGroup.get(ScoreType.ApexPearson, ScoreType.usedScoreTypes());
                                    double libDotprod = peakGroup.get(ScoreType.Dotprod, ScoreType.usedScoreTypes());
                                    //                    double isoOverlap = peakGroup.get(ScoreType.IsoOverlap,
                                    // ScoreType.usedScoreTypes());
                                    if (pearson < 0.2) {
                                        stat.getAndIncrement();
                                        findItList.add(sum.getPeptideRef());
                                    }
                                }
                            });
            log.info(overview.getRunName() + "-符合要求的Peptide有:" + stat.get() + "个");
            log.info(JSON.toJSONString(findItList));
        }

        return Result.OK();
    }

    /**
     * 测试相似肽段覆盖率的代码
     *
     * @return
     */
    @GetMapping(value = "/lms5")
    Result lms5() {
        String projectId = "613f5d8262cbcf5bb4345270";
        List<IdName> idNameList =
                overviewService.getAll(new OverviewQuery(projectId).setDefaultOne(true), IdName.class);
        idNameList = idNameList.subList(0, 1);
        for (IdName idName : idNameList) {
            String overviewId = idName.id();

            OverviewDO overview = overviewService.getById(overviewId);
            log.info("读取数据库信息中");
            //            Map<String, PeptideScore> dataMap = dataService.getAll(new
            // DataQuery().setOverviewId(overviewId).setStatus(IdentifyStatus.WAIT.getCode()).setDecoy(false), PeptideScore.class, overview.getProjectId()).stream().collect(Collectors.toMap(PeptideScore::getId, Function.identity()));
            Map<String, DataSumDO> sumMap =
                    dataSumService
                            .getAll(
                                    new DataSumQuery().setOverviewId(overviewId).setDecoy(false),
                                    DataSumDO.class,
                                    overview.getProjectId())
                            .stream()
                            .collect(Collectors.toMap(DataSumDO::getPeptideRef, Function.identity()));
            RunDO run = runService.getById(overview.getRunId());
            log.info("当前分析实验:" + run.getAlias());
            List<WindowRange> ranges = run.getWindowRanges();
            Set<String> similarPeptides = new HashSet<>();
            log.info("开始计算相似度");
            for (WindowRange range : ranges) {
                TreeMap<Double, List<DataSumDO>> rtMap = new TreeMap<>();
                Map<String, PeptideDO> peptideMap =
                        peptideService
                                .getAll(
                                        new PeptideQuery(overview.getAnaLibId())
                                                .setMzStart(range.getStart())
                                                .setMzEnd(range.getEnd()))
                                .stream()
                                .collect(Collectors.toMap(PeptideDO::getPeptideRef, Function.identity()));
                BlockIndexDO index = blockIndexService.getMS2(run.getId(), range.getMz());
                for (Float rt : index.getRts()) {
                    rtMap.put((double) rt, new ArrayList<>());
                }
                for (PeptideDO peptide : peptideMap.values()) {
                    DataSumDO sum = sumMap.get(peptide.getPeptideRef());
                    if (sum != null && sum.getStatus().equals(IdentifyStatus.SUCCESS.getCode())) {
                        rtMap.get(sum.getSelectedRt()).add(sum);
                    }
                }

                List<List<DataSumDO>> sumListList = new ArrayList<>(rtMap.values());
                for (int i = 0; i < sumListList.size() - 4; i++) {
                    List<DataSumDO> sumList = new ArrayList<>();
                    sumList.addAll(sumListList.get(i));
                    sumList.addAll(sumListList.get(i + 1));
                    sumList.addAll(sumListList.get(i + 2));
                    sumList.addAll(sumListList.get(i + 3));
                    sumList.addAll(sumListList.get(i + 4));
                    if (sumList.size() <= 1) {
                        continue;
                    }
                    for (int k = 0; k < sumList.size(); k++) {
                        for (int j = k + 1; j < sumList.size(); j++) {
                            int sequenceLengthA =
                                    peptideMap.get(sumList.get(k).getPeptideRef()).getSequence().length();
                            int sequenceLengthB =
                                    peptideMap.get(sumList.get(j).getPeptideRef()).getSequence().length();
                            int finalLength = Math.min(sequenceLengthA, sequenceLengthB);
                            if (PeptideUtil.similar(
                                    peptideMap.get(sumList.get(k).getPeptideRef()),
                                    peptideMap.get(sumList.get(j).getPeptideRef()),
                                    finalLength <= 8 ? 5 : 6)) {
                                similarPeptides.add(
                                        sumList.get(k).getPeptideRef() + ":" + sumList.get(j).getPeptideRef());
                            }
                        }
                    }
                }
            }

            log.info("相似组总计有:" + similarPeptides.size() + "组");
            similarPeptides.forEach(System.out::println);
        }

        return Result.OK();
    }

    /**
     * 测试相似肽段覆盖率的代码
     *
     * @return
     */
    @GetMapping(value = "/lms6")
    Result lms6() {
        String projectId = "613f5d8262cbcf5bb4345270";
        List<IdName> idNameList =
                overviewService.getAll(new OverviewQuery(projectId).setDefaultOne(true), IdName.class);
        idNameList = idNameList.subList(0, 1);
        for (IdName idName : idNameList) {
            String overviewId = idName.id();
            List<String> targetPeptideList = new ArrayList<>();
            OverviewDO overview = overviewService.getById(overviewId);
            log.info("读取数据库信息中--" + overview.getRunName());
            Map<String, DataSumDO> sumMap =
                    dataSumService
                            .getAll(
                                    new DataSumQuery().setOverviewId(overviewId).setDecoy(false),
                                    DataSumDO.class,
                                    overview.getProjectId())
                            .stream()
                            .collect(Collectors.toMap(DataSumDO::getPeptideRef, Function.identity()));
            Map<String, DataScore> dataMap = dataService.getAll(
                            new DataQuery().setOverviewId(overviewId).setDecoy(false),
                            DataScore.class,
                            overview.getProjectId())
                    .stream()
                    .collect(Collectors.toMap(DataScore::getPeptideRef, Function.identity()));
            List<DataSumDO> sumList = sumMap.values().stream()
                    .filter(sum -> sum.getStatus().equals(IdentifyStatus.SUCCESS.getCode()))
                    .toList();
            for (DataSumDO sum : sumList) {
                DataScore data = dataMap.get(sum.getPeptideRef());
                lda.scoreForPeakGroups(data.getPeakGroupList(),
                        overview.getWeights(),
                        overview.getParams().getMethod().getScore().getScoreTypes());
                List<PeakGroup> peakGroupList = data.getPeakGroupList().stream()
                        .filter(peak -> peak.getTotalScore() > overview.getMinTotalScore())
                        .toList();
                if (peakGroupList.size() >= 2) {
                    peakGroupList = peakGroupList.stream()
                            .sorted(Comparator.comparing(PeakGroup::getTotalScore).reversed())
                            .toList();
                    if (peakGroupList.get(0).getTotalScore() - peakGroupList.get(1).getTotalScore() < 0.1) {
                        targetPeptideList.add(data.getPeptideRef());
                    }
                }
            }
            log.info("相似组内肽段有:" + targetPeptideList.size());
            log.info(JSON.toJSONString(targetPeptideList));
        }


        return Result.OK();
    }

    /**
     * 测试相似肽段覆盖率的代码
     *
     * @return
     */
    @GetMapping(value = "/lms7")
    Result lms7() {
        String overviewIdOld = "61a492078363936500b07026";
        String overviewIdNew = "61a63585000a1b6efde040e1";
        OverviewDO overviewOld = overviewService.getById(overviewIdOld);
        OverviewDO overviewNew = overviewService.getById(overviewIdNew);
        List<PeptideRef> peptideRefsOld = dataSumService.getAll(new DataSumQuery(overviewOld.getId()).setDecoy(false).setStatus(IdentifyStatus.SUCCESS.getCode()), PeptideRef.class, overviewOld.getProjectId());
        List<PeptideRef> peptideRefsNew = dataSumService.getAll(new DataSumQuery(overviewNew.getId()).setDecoy(false).setStatus(IdentifyStatus.SUCCESS.getCode()), PeptideRef.class, overviewOld.getProjectId());
        Set<String> oldPeptides = peptideRefsOld.stream().map(PeptideRef::getPeptideRef).collect(Collectors.toSet());
        Set<String> newPeptides = peptideRefsNew.stream().map(PeptideRef::getPeptideRef).collect(Collectors.toSet());
        List<String> 老的有新的没有 = new ArrayList<>();
        List<String> 新的有老的没有 = new ArrayList<>();
        for (String oldPeptide : oldPeptides) {
            if (!newPeptides.contains(oldPeptide)) {
                老的有新的没有.add(oldPeptide);
            }
        }
        for (String newPeptide : newPeptides) {
            if (!oldPeptides.contains(newPeptide)) {
                新的有老的没有.add(newPeptide);
            }
        }
        log.info("老的有新的没有" + 老的有新的没有.size() + "");
        log.info(JSON.toJSONString(老的有新的没有.subList(0, 100)));
        log.info("新的有老的没有" + 新的有老的没有.size() + "");
        log.info(JSON.toJSONString(新的有老的没有.subList(0, 100)));
        return Result.OK();
    }

    @GetMapping(value = "/checkFragmentMz")
    Result checkFragmentMz() {
        long count = peptideService.count(new PeptideQuery());
        log.info("Total Peptides:" + count);
        long batch = count / 2000 + 1;
        PeptideQuery query = new PeptideQuery();
        query.setPageSize(2000);
        for (int i = 0; i < batch; i++) {
            query.setPageNo(i + 1);
            Result<List<PeptideDO>> pepListRes = peptideService.getList(query);
            if (pepListRes.isFailed()) {
                log.error(pepListRes.getErrorMessage());
            }
            List<PeptideDO> pepList = pepListRes.getData();
            if (pepList.size() == 0) {
                break;
            }
            pepList.forEach(
                    pep -> {
                        pep.getDecoyFragments()
                                .forEach(
                                        fragmentInfo -> {
                                            if (fragmentInfo.getMz() == null) {
                                                log.info(pep.getLibraryId() + "-" + pep.getPeptideRef() + "有问题");
                                            }
                                        });
                    });
            log.info("已扫描" + i + "/" + batch);
        }

        return Result.OK();
    }

    public static File[] getFiles(String path) throws IOException {
        ClassPathResource classPathResource = new ClassPathResource(path);
        File file = classPathResource.getFile();
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            for (int i = 0; i < files.length; i++) {
                if (files[i].isDirectory()) {
                    getFiles(files[i].getPath());
                } else {
                }
            }
            return files;
        } else {
            File[] files = new File[0];
            return files;
        }
    }
}

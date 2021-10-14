package net.csibio.propro.controller;

import io.swagger.annotations.Api;
import lombok.extern.slf4j.Slf4j;
import net.csibio.propro.algorithm.learner.classifier.Lda;
import net.csibio.propro.algorithm.peak.GaussFilter;
import net.csibio.propro.algorithm.peak.SignalToNoiseEstimator;
import net.csibio.propro.algorithm.score.Scorer;
import net.csibio.propro.algorithm.stat.StatConst;
import net.csibio.propro.constants.enums.IdentifyStatus;
import net.csibio.propro.constants.enums.ResultCode;
import net.csibio.propro.domain.Result;
import net.csibio.propro.domain.bean.common.FloatPairs;
import net.csibio.propro.domain.bean.common.IdName;
import net.csibio.propro.domain.bean.common.IdNameAlias;
import net.csibio.propro.domain.bean.common.PeptideRtPairs;
import net.csibio.propro.domain.bean.data.PeptideRt;
import net.csibio.propro.domain.bean.overview.Overview4Clinic;
import net.csibio.propro.domain.db.*;
import net.csibio.propro.domain.options.SigmaSpacing;
import net.csibio.propro.domain.query.DataQuery;
import net.csibio.propro.domain.query.DataSumQuery;
import net.csibio.propro.domain.query.ExperimentQuery;
import net.csibio.propro.domain.query.OverviewQuery;
import net.csibio.propro.domain.vo.ClinicPrepareDataVO;
import net.csibio.propro.domain.vo.ExpDataVO;
import net.csibio.propro.service.*;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Api(tags = {"Clinic Module"})
@RestController
@RequestMapping("clinic/")
public class ClinicController {

    @Autowired
    TaskService taskService;
    @Autowired
    LibraryService libraryService;
    @Autowired
    ProjectService projectService;
    @Autowired
    MethodService methodService;
    @Autowired
    ExperimentService experimentService;
    @Autowired
    OverviewService overviewService;
    @Autowired
    DataService dataService;
    @Autowired
    DataSumService dataSumService;
    @Autowired
    SignalToNoiseEstimator signalToNoiseEstimator;
    @Autowired
    PeptideService peptideService;
    @Autowired
    BlockIndexService blockIndexService;
    @Autowired
    Lda lda;
    @Autowired
    Scorer scorer;

    @GetMapping(value = "prepare")
    Result<ClinicPrepareDataVO> prepare(@RequestParam("projectId") String projectId) {
        ProjectDO project = projectService.getById(projectId);
        if (project == null) {
            return Result.Error(ResultCode.PROJECT_NOT_EXISTED);
        }
        if (StringUtils.isEmpty(project.getInsLibId()) || StringUtils.isEmpty(project.getAnaLibId()) || StringUtils.isEmpty(project.getMethodId())) {
            return Result.Error(ResultCode.INS_ANA_METHOD_ID_CANNOT_BE_EMPTY_WHEN_USING_CLINIC);
        }
        LibraryDO anaLib = libraryService.getById(project.getAnaLibId());
        if (anaLib == null) {
            return Result.Error(ResultCode.ANA_LIBRARY_NOT_EXISTED);
        }
        LibraryDO insLib = libraryService.getById(project.getInsLibId());
        if (insLib == null) {
            return Result.Error(ResultCode.INS_LIBRARY_NOT_EXISTED);
        }
        MethodDO method = methodService.getById(project.getMethodId());
        if (method == null) {
            return Result.Error(ResultCode.METHOD_NOT_EXISTED);
        }
        List<IdNameAlias> expList = experimentService.getAll(new ExperimentQuery().setProjectId(projectId), IdNameAlias.class);
        List<Overview4Clinic> totalOverviewList = overviewService.getAll(new OverviewQuery(projectId), Overview4Clinic.class);
        Map<String, List<Overview4Clinic>> overviewMap = totalOverviewList.stream().collect(Collectors.groupingBy(Overview4Clinic::getExpId));
        overviewMap.values().forEach(overviews -> {
            overviews = overviews.stream().sorted(Comparator.nullsLast(Comparator.comparing(Overview4Clinic::getDefaultOne))).toList();
        });

        ClinicPrepareDataVO data = new ClinicPrepareDataVO();
        data.setProject(project);
        if (expList.stream().filter(idNameAlias -> idNameAlias.alias() != null).count() == expList.size()) {
            data.setExpList(expList.stream().sorted(Comparator.comparing(IdNameAlias::alias)).collect(Collectors.toList()));
        } else {
            data.setExpList(expList.stream().sorted(Comparator.comparing(IdNameAlias::name)).collect(Collectors.toList()));
        }
        data.setInsLib(new IdName(insLib.getId(), insLib.getName()));
        data.setAnaLib(new IdName(anaLib.getId(), anaLib.getName()));
        data.setMethod(method);
        data.setProteins(anaLib.getProteins());
        data.setOverviewMap(overviewMap);
        if (anaLib.getStatistic().get(StatConst.Peptide_Count) != null) {
            data.setPeptideCount((Long) anaLib.getStatistic().get(StatConst.Peptide_Count));
        }
        if (anaLib.getStatistic().get(StatConst.Protein_Count) != null) {
            data.setProteinCount((Long) anaLib.getStatistic().get(StatConst.Protein_Count));
        }

        return Result.OK(data);
    }

    /**
     * Core API
     * 1. If the EIC data exist. Get the data directly from the database
     * 2. Else predict the Y-Ion for the target peptide and analyze the EIC data from the Aird file
     *
     * @param projectId
     * @param libraryId
     * @param peptideRef
     * @param predict
     * @param onlyDefault
     * @param smooth
     * @param denoise
     * @param expIds
     * @return
     */
    @PostMapping(value = "/getExpData")
    Result<List<ExpDataVO>> getExpData(@RequestParam("projectId") String projectId,
                                       @RequestParam(value = "libraryId", required = false) String libraryId,
                                       @RequestParam("peptideRef") String peptideRef,
                                       @RequestParam("predict") Boolean predict,
                                       @RequestParam(value = "changeCharge", required = false) Boolean changeCharge,
                                       @RequestParam("onlyDefault") Boolean onlyDefault,
                                       @RequestParam(value = "smooth", required = false) Boolean smooth,
                                       @RequestParam(value = "denoise", required = false) Boolean denoise,
                                       @RequestParam("expIds") List<String> expIds) {
        List<ExpDataVO> dataList = new ArrayList<>();
        for (int i = 0; i < expIds.size(); i++) {
            String expId = expIds.get(i);
            OverviewQuery query = new OverviewQuery(projectId).setExpId(expId);
            if (onlyDefault) {
                query.setDefaultOne(true);
            }
            OverviewDO overview = overviewService.getOne(query, OverviewDO.class);
            if (overview == null) {
                continue;
            }
            ExpDataVO data = null;
            //如果使用预测方法,则进行实时EIC获取
            if (predict) {
                ExperimentDO exp = experimentService.getById(expId);
                DataSumDO existed = dataSumService.getOne(new DataSumQuery().setOverviewId(overview.getId()).setPeptideRef(peptideRef).setDecoy(false), DataSumDO.class, projectId);
                if (existed.getStatus() == IdentifyStatus.SUCCESS.getCode()) {
                    DataDO existedData = dataService.getById(existed.getId(), projectId);
                    DataSumDO dataSum = scorer.calcBestTotalScore(existedData, overview, null);
                    data = new ExpDataVO().merge(existedData, dataSum);
                    data.setGroup(exp.getGroup());
                    data.setAlias(exp.getAlias());
                    data.setExpId(exp.getId());
                } else {
                    Result<ExpDataVO> res = dataService.predictDataFromFile(exp, libraryId, peptideRef, changeCharge, overview.getId());
                    if (res.isSuccess()) {
                        data = res.getData();
                        data.setGroup(exp.getGroup());
                        data.setAlias(exp.getAlias());
                        data.setExpId(exp.getId());
                    }
                }
            } else {
                data = dataService.getDataFromDB(projectId, expId, overview.getId(), peptideRef);
            }
            if (data != null) {
                data.setMinTotalScore(overview.getMinTotalScore());
                lda.scoreForPeakGroups(data.getScoreList(), overview.getWeights(), overview.getParams().getMethod().getScore().getScoreTypes());
                dataList.add(data);
            }
        }

        if (smooth) {
            SigmaSpacing ss = SigmaSpacing.create();
            dataList.forEach(data -> {
                HashMap<String, float[]> smoothInt = GaussFilter.filter(data.getRtArray(), (HashMap<String, float[]>) data.getIntMap(), ss);
                data.setIntMap(smoothInt);
            });
        }

        if (denoise) {
            dataList.forEach(data -> {
                HashMap<String, float[]> denoiseIntMap = new HashMap<>();
                float[] rt = data.getRtArray();
                for (String cutInfo : data.getIntMap().keySet()) {
                    double[] noises200 = signalToNoiseEstimator.computeSTN(rt, data.getIntMap().get(cutInfo), 200, 30);
                    float[] denoiseInt = new float[noises200.length];
                    for (int i = 0; i < noises200.length; i++) {
                        denoiseInt[i] = (float) (data.getIntMap().get(cutInfo)[i] * noises200[i] / (noises200[i] + 1));
                    }
                    denoiseIntMap.put(cutInfo, denoiseInt);
                }
                data.setIntMap(denoiseIntMap);
            });
        }
        if (dataList.size() == 0) {
            return Result.Error(ResultCode.DATA_IS_EMPTY);
        }
        return Result.OK(dataList);
    }

    @PostMapping(value = "/getSpectra")
    Result<FloatPairs> getSpectra(@RequestParam(value = "expId", required = false) String expId,
                                  @RequestParam(value = "mz", required = false) Double mz,
                                  @RequestParam(value = "rt", required = false) Float rt) {
        ExperimentDO exp = experimentService.getById(expId);
        FloatPairs pairs = experimentService.getSpectrum(exp, mz, rt);
        return Result.OK(pairs);
    }

    @PostMapping(value = "/getRtPairs")
    Result<HashMap<String, PeptideRtPairs>> getRtPairs(@RequestParam("projectId") String projectId,
                                                       @RequestParam("onlyDefault") Boolean onlyDefault,
                                                       @RequestParam("expIds") List<String> expIds) {
        HashMap<String, PeptideRtPairs> map = new HashMap<>();
        long start = System.currentTimeMillis();
        for (int i = 0; i < expIds.size(); i++) {
            String expId = expIds.get(i);
            ExperimentDO exp = experimentService.getById(expId);
            OverviewQuery query = new OverviewQuery(projectId).setExpId(expId);
            if (onlyDefault) {
                query.setDefaultOne(true);
            }
            Overview4Clinic overview = overviewService.getOne(query, Overview4Clinic.class);
            if (overview == null) {
                continue;
            }
            List<PeptideRt> realRtList = dataSumService.getAll(new DataSumQuery(overview.getId()).setDecoy(false).setStatus(IdentifyStatus.SUCCESS.getCode()).setIsUnique(true), PeptideRt.class, projectId);
            List<String> ids = realRtList.stream().map(PeptideRt::id).collect(Collectors.toList());
            if (ids.size() == 0) {
                log.error("没有找到任何鉴定到的数据");
                continue;
            }
            List<PeptideRt> libRtList = dataService.getAll(new DataQuery(overview.getId()).setIds(ids), PeptideRt.class, projectId);
            if (realRtList.size() != libRtList.size()) {
                log.error("数据异常,LibRt Size:" + libRtList.size() + ",RealRt Size:" + realRtList.size());
                continue;
            }
            Map<String, Double> libRtMap = libRtList.stream().collect(Collectors.toMap(PeptideRt::peptideRef, PeptideRt::libRt));
            //横坐标是libRt,纵坐标是realRt
            String[] peptideRefs = new String[realRtList.size()];
            double[] x = new double[realRtList.size()];
            double[] y = new double[realRtList.size()];
            realRtList = realRtList.stream().sorted(Comparator.comparingDouble(PeptideRt::realRt)).collect(Collectors.toList());
            for (int j = 0; j < realRtList.size(); j++) {
                peptideRefs[j] = realRtList.get(j).peptideRef();
                x[j] = libRtMap.get(peptideRefs[j]);
                y[j] = realRtList.get(j).realRt() - exp.getIrt().getSi().realRt(x[j]);
            }
            map.put(expId, new PeptideRtPairs(peptideRefs, x, y));
        }
        log.info("rt坐标已渲染,耗时:" + (System.currentTimeMillis() - start));
        return Result.OK(map);
    }
}

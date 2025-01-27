package net.csibio.propro.algorithm.core;

import com.google.common.util.concurrent.AtomicDouble;
import lombok.extern.slf4j.Slf4j;
import net.csibio.aird.bean.MzIntensityPairs;
import net.csibio.propro.algorithm.extract.Extractor;
import net.csibio.propro.algorithm.extract.IonStat;
import net.csibio.propro.algorithm.formula.FragmentFactory;
import net.csibio.propro.algorithm.learner.classifier.Lda;
import net.csibio.propro.algorithm.peak.GaussFilter;
import net.csibio.propro.algorithm.peak.PeakFitter;
import net.csibio.propro.algorithm.score.features.DIAScorer;
import net.csibio.propro.algorithm.score.scorer.Scorer;
import net.csibio.propro.constants.enums.IdentifyStatus;
import net.csibio.propro.domain.bean.common.AnyPair;
import net.csibio.propro.domain.bean.peptide.FragmentInfo;
import net.csibio.propro.domain.bean.peptide.PeptideCoord;
import net.csibio.propro.domain.bean.score.PeakGroup;
import net.csibio.propro.domain.db.DataDO;
import net.csibio.propro.domain.db.DataSumDO;
import net.csibio.propro.domain.db.OverviewDO;
import net.csibio.propro.domain.db.RunDO;
import net.csibio.propro.domain.options.AnalyzeParams;
import net.csibio.propro.service.DataService;
import net.csibio.propro.service.DataSumService;
import net.csibio.propro.service.OverviewService;
import net.csibio.propro.service.SimulateService;
import net.csibio.propro.utils.DataUtil;
import net.csibio.propro.utils.LogUtil;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.paukov.combinatorics3.Generator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component("coreFunc")
public class CoreFunc {

    @Autowired
    Scorer scorer;
    @Autowired
    SimulateService simulateService;
    @Autowired
    FragmentFactory fragmentFactory;
    @Autowired
    OverviewService overviewService;
    @Autowired
    Lda lda;
    @Autowired
    DataSumService dataSumService;
    @Autowired
    DataService dataService;
    @Autowired
    DIAScorer diaScorer;
    @Autowired
    GaussFilter gaussFilter;
    @Autowired
    Extractor extractor;
    @Autowired
    PeakFitter peakFitter;

    /**
     * EIC Predict Peptide
     * 核心EIC预测函数
     * 通过动态替换碎片用来预测
     * <p>
     *
     * @param coord
     * @param ms2Map
     * @param params
     * @return
     */
    public AnyPair<DataDO, DataSumDO> predictOneDelete(PeptideCoord coord, TreeMap<Float, MzIntensityPairs> ms1Map, TreeMap<Float, MzIntensityPairs> ms2Map, RunDO run, OverviewDO overview, AnalyzeParams params) {

        List<FragmentInfo> libFrags = new ArrayList<>(coord.getFragments());

        //初始化原始数据对象集
        HashMap<Double, List<AnyPair<DataDO, DataSumDO>>> hitPairMap = new HashMap<>();
        double bestScore = -99999d;
        AnyPair<DataDO, DataSumDO> bestPair = null;
        DataDO data = null;
        //当i=0的时候,不切换,也就是说跳过了强度比最高的碎片,强度比最高的碎片不进行离子替换
        for (int i = 0; i < libFrags.size(); i++) {
            //i==-1的时候检测的就是自己本身
            List<FragmentInfo> newLibFrags = new ArrayList<>(libFrags);
            newLibFrags.remove(i);
            coord.setFragments(newLibFrags);
            data = extractor.extract(coord, ms1Map, ms2Map, params, true, null);
            if (data == null) {
                continue;
            }

            try {
                data = scorer.score(run, data, coord, ms1Map, ms2Map, params);
            } catch (Exception e) {
                e.printStackTrace();
                log.error("Peptide打分异常:" + coord.getPeptideRef());
            }

            if (data.getPeakGroupList() != null) {
                DataSumDO dataSum = scorer.calcBestTotalScore(data, overview);
                if (dataSum != null) {
                    if (!hitPairMap.containsKey(dataSum.getSelectedRt())) {
                        List<AnyPair<DataDO, DataSumDO>> pairs = new ArrayList<>();
                        pairs.add(new AnyPair<DataDO, DataSumDO>(data, dataSum));
                        hitPairMap.put(dataSum.getSelectedRt(), pairs);
                    } else {
                        List<AnyPair<DataDO, DataSumDO>> currentPairs = hitPairMap.get(dataSum.getSelectedRt());
                        currentPairs.add(new AnyPair<DataDO, DataSumDO>(data, dataSum));
                    }
                }
            }
        }
        AtomicDouble maxHitRt = new AtomicDouble();
        AtomicInteger maxHits = new AtomicInteger();
        hitPairMap.forEach((key, value) -> {
            if (value.size() > maxHits.get()) {
                maxHitRt.set(key);
                maxHits.set(value.size());
            }
        });

        //获取Max Hit的pair组
        List<AnyPair<DataDO, DataSumDO>> maxHitGroup = hitPairMap.get(maxHitRt.get());
        if (maxHitGroup == null) {
            return null;
        }
        for (AnyPair<DataDO, DataSumDO> anyPair : maxHitGroup) {
            if (anyPair.getRight().getTotalScore() > bestScore) {
                bestPair = anyPair;
                bestScore = anyPair.getRight().getTotalScore();
            }
        }

        if (bestPair == null) {
            log.info("没有产生数据:" + run.getAlias() + ":Max Hit RT:" + maxHitRt.get() + ";Hits:" + maxHits.get());
            return null;
        }

        return bestPair;
    }

    public AnyPair<DataDO, DataSumDO> predictOneNiubi(PeptideCoord coord, TreeMap<Float, MzIntensityPairs> ms1Map, TreeMap<Float, MzIntensityPairs> ms2Map, RunDO run, OverviewDO overview, AnalyzeParams params) {
        DataDO data = extractor.extract(coord, ms1Map, ms2Map, params, true, null);
        //EIC结果如果为空则没有继续的必要了
        if (data == null) {
            log.info(coord.getPeptideRef() + ":EIC结果为空");
            return null;
        }
        if (data.getIntMap() == null || data.getIntMap().size() <= coord.getFragments().size() / 2) {
            data.setStatus(IdentifyStatus.NO_ENOUGH_FRAGMENTS.getCode());
            return null;
        }

        data = scorer.score(run, data, coord, ms1Map, ms2Map, params);

        if (data.getPeakGroupList() != null && data.getPeakGroupList().size() > 0) {
            lda.scoreForPeakGroups(data.getPeakGroupList(), overview.getWeights(), overview.fetchScoreTypes());
            DataSumDO sum = judge(data, overview.getMinTotalScore());
            return new AnyPair<>(data, sum);
        } else {
            return new AnyPair<>(data, null);
        }
    }

    /**
     * EIC Predict Peptide
     * 核心EIC预测函数
     * 通过动态替换碎片用来预测
     * <p>
     *
     * @param coord
     * @param ms2Map
     * @param params
     * @return
     */
    public AnyPair<DataDO, DataSumDO> predictOneReplace(PeptideCoord coord, TreeMap<Float, MzIntensityPairs> ms1Map, TreeMap<Float, MzIntensityPairs> ms2Map, RunDO run, OverviewDO overview, AnalyzeParams params) {
        //Step1.对库中的碎片进行排序,按照强度从大到小排列
        Map<String, FragmentInfo> libFragMap = coord.getFragments().stream().collect(Collectors.toMap(FragmentInfo::getCutInfo, Function.identity()));
        List<String> libIons = coord.getFragments().stream().map(FragmentInfo::getCutInfo).toList();

        //Step2.生成碎片离子,碎片最大带电量为母离子的带电量,最小碎片长度为3
        Set<FragmentInfo> proproFiList = fragmentFactory.buildFragmentMap(coord, 3);
        proproFiList.forEach(fi -> fi.setIntensity(500d)); //给到一个任意的初始化强度
        Map<String, FragmentInfo> predictFragmentMap = proproFiList.stream().collect(Collectors.toMap(FragmentInfo::getCutInfo, Function.identity()));
        //将预测碎片中的库碎片信息替换为库碎片完整信息(主要是intensity值)
        libFragMap.keySet().forEach(cutInfo -> {
            predictFragmentMap.put(cutInfo, libFragMap.get(cutInfo));
        });
        //Step3.对所有碎片进行EIC计算
        coord.setFragments(new ArrayList<>(proproFiList));
        DataDO data = extractor.extract(coord, ms1Map, ms2Map, params, true, null);
        Map<String, float[]> intMap = data.getIntMap();

        //Step4.获取所有碎片的统计分,并按照CV值进行排序,记录前15的碎片
        List<IonStat> statList = buildIonStat(intMap);
        int maxCandidateIons = params.getMethod().getScore().getMaxCandidateIons();
        maxCandidateIons = 20;
        if (statList.size() > maxCandidateIons) {
            statList = statList.subList(0, maxCandidateIons);
        }
        List<String> totalIonList = statList.stream().map(IonStat::cutInfo).toList();

        //Step5.开始全枚举所有的组合分
        double bestScore = -99999d;
        DataSumDO bestDataSum = null;
        DataDO bestData = null;
        List<FragmentInfo> bestIonGroup = null;

        int replace = params.getChangeCharge() ? 6 : 1; //如果是新带点碎片预测,那么直接全部替换
        List<List<String>> allPossibleIonsGroup = Generator.combination(totalIonList).simple(replace).stream().collect(Collectors.toList()); //替换策略
        double maxBYCount = -1;
        for (int i = 0; i < allPossibleIonsGroup.size(); i++) {
            List<String> selectedIons = allPossibleIonsGroup.get(i);

            //抹去强度最低的两个碎片
            List<String> ions = new ArrayList<>(libIons.subList(0, libIons.size() - selectedIons.size()));
            ions.addAll(selectedIons);
            DataDO buildData = buildData(data, ions);
            if (buildData == null) {
                continue;
            }
            List<FragmentInfo> selectFragments = selectFragments(predictFragmentMap, ions);
            if (selectFragments.size() < libIons.size()) {
                continue;
            }
            coord.setFragments(selectFragments);
            try {
                buildData = scorer.score(run, buildData, coord, ms1Map, ms2Map, params);
            } catch (Exception e) {
                log.error("Peptide打分异常:" + coord.getPeptideRef());
            }

            if (buildData.getPeakGroupList() != null) {
                DataSumDO dataSum = scorer.calcBestTotalScore(buildData, overview);
                double currentBYCount = scorer.calcBestIonsCount(buildData);
                if (currentBYCount > maxBYCount) {
                    maxBYCount = currentBYCount;
                }
                if (dataSum != null && dataSum.getTotalScore() > bestScore) {
                    bestScore = dataSum.getTotalScore();
                    bestDataSum = dataSum;
                    bestData = buildData;
                    bestIonGroup = selectFragments;
                }
            }
        }

        if (bestData == null) {
            //  log.info("居然一个可能的组都没有:" + coord.getPeptideRef());
            return null;
        }
        double finalBYCount = scorer.calcBestIonsCount(bestData);
        //Max BYCount Limit
        if (maxBYCount != finalBYCount && finalBYCount <= 5) {
            log.info("未预测到严格意义下的新碎片组合:" + coord.getPeptideRef() + ",IonsCount:" + finalBYCount);
            return null;
        }

        log.info("预测到严格意义下的新碎片组合:" + coord.getPeptideRef() + ",IonsCount:" + finalBYCount);
        coord.setFragments(bestIonGroup); //这里必须要将coord置为最佳峰组
//        log.info(run.getAlias() + "碎片组:" + bestIonGroup.stream().map(FragmentInfo::getCutInfo).toList() + "; Score:" + bestScore + " RT:" + bestRt);
        return new AnyPair<DataDO, DataSumDO>(bestData, bestDataSum);
    }

    /**
     * EIC+PEAK_PICKER+PEAK_SCORE 核心流程
     * 最终的提取XIC结果需要落盘数据库,一般用于正式XIC提取的计算
     *
     * @param coordinates
     * @param ms2Map
     * @param params
     * @return
     */
    public List<DataDO> epps(RunDO run, List<PeptideCoord> coordinates, TreeMap<Float, MzIntensityPairs> ms1Map, TreeMap<Float, MzIntensityPairs> ms2Map, AnalyzeParams params) {
        List<DataDO> dataList = Collections.synchronizedList(new ArrayList<>());
        long start = System.currentTimeMillis();
        if (coordinates == null || coordinates.size() == 0) {
            log.error("肽段坐标为空");
            return null;
        }

        //传入的coordinates是没有经过排序的,需要排序先处理真实肽段,再处理伪肽段.如果先处理的真肽段没有被提取到任何信息,或者提取后的峰太差被忽略掉,都会同时删掉对应的伪肽段的XIC
        coordinates.parallelStream().forEach(coord -> {
            //Step1. 常规提取XIC,XIC结果不进行压缩处理,如果没有提取到任何结果,那么加入忽略列表
            DataDO dataDO = extractor.extract(coord, ms1Map, ms2Map, params, true, null);
            //如果EIC结果中所有的碎片均为空,那么也不需要再做Reselect操作,直接跳过
            if (dataDO == null) {
//                log.info(coord.getPeptideRef() + ":EIC结果为空");
                return;
            }

            //Step2. 常规选峰及打分,未满足条件的直接忽略
            dataDO = scorer.score(run, dataDO, coord, ms1Map, ms2Map, params);
            dataList.add(dataDO);

            //Step3. 忽略过程数据,将数据提取结果加入最终的列表
            DataUtil.compress(dataDO);

            //如果没有打分数据,那么对应的decoy也不再计算,以保持target与decoy 1:1的混合比例,这里需要注意的是,即便是scoreList是空,也需要将DataDO存储到数据库中,以便后续的重新统计和分析
            if (dataDO.getPeakGroupList() == null) {
                return;
            }

            //Step4. 如果第一,二步均符合条件,那么开始对对应的伪肽段进行数据提取和打分
            coord.setDecoy(true);
            DataDO decoyData = extractor.extract(coord, ms1Map, ms2Map, params, true, null);
            if (decoyData == null) {
                return;
            }

            //Step5. 对Decoy进行打分
            decoyData = scorer.score(run, decoyData, coord, ms1Map, ms2Map, params);
            dataList.add(decoyData);

            //Step6. 忽略过程数据,将数据提取结果加入最终的列表
            DataUtil.compress(decoyData);
        });

        LogUtil.log("XIC+选峰+打分耗时", start);
        log.info("总计构建Data数目" + dataList.size() + "/" + (coordinates.size() * 2) + "个");
        if (dataList.stream().filter(data -> data.getStatus() == null).toList().size() > 0) {
            log.info("居然有问题");
        }
        return dataList;
    }

    public List<DataDO> reselect(RunDO run, List<PeptideCoord> coordinates, TreeMap<Float, MzIntensityPairs> ms1Map, TreeMap<Float, MzIntensityPairs> ms2Map, AnalyzeParams params) {
        List<DataDO> dataList = Collections.synchronizedList(new ArrayList<>());
        long start = System.currentTimeMillis();
        if (coordinates == null || coordinates.size() == 0) {
            log.error("肽段坐标为空");
            return null;
        }
        AtomicLong newIonsGroup = new AtomicLong(0);
        //传入的coordinates是没有经过排序的,需要排序先处理真实肽段,再处理伪肽段.如果先处理的真肽段没有被提取到任何信息,或者提取后的峰太差被忽略掉,都会同时删掉对应的伪肽段的XIC
        coordinates.parallelStream().forEach(coord -> {
            DataDO dataDO = extractor.extract(coord, ms1Map, ms2Map, params, true, null);
            //如果EIC结果中所有的碎片均为空,那么也不需要再做Reselect操作,直接跳过
            if (dataDO == null) {
                return;
            }
            //Step2. 常规选峰及打分,未满足条件的直接忽略
            dataDO = scorer.score(run, dataDO, coord, ms1Map, ms2Map, params);
            lda.scoreForPeakGroups(dataDO.getPeakGroupList(), params.getBaseOverview().getWeights(), params.getBaseOverview().getParams().getMethod().getScore().getScoreTypes());
            DataSumDO tempSum = scorer.calcBestTotalScore(dataDO, params.getBaseOverview());
            if (tempSum == null || tempSum.getStatus() != IdentifyStatus.SUCCESS.getCode()) {
                DataSumDO dataSum = scorer.calcBestTotalScore(dataDO, params.getBaseOverview());
                if (dataSum == null || dataSum.getStatus() != IdentifyStatus.SUCCESS.getCode()) {
                    AnyPair<DataDO, DataSumDO> pair = predictOneDelete(coord, ms1Map, ms2Map, run, params.getBaseOverview(), params);
                    if (pair != null && pair.getLeft() != null) {
                        newIonsGroup.getAndIncrement();
                        dataDO = pair.getLeft();
                    }
                }
            }

            dataList.add(dataDO);
            //Step3. 忽略过程数据,将数据提取结果加入最终的列表
            DataUtil.compress(dataDO);

            //如果没有打分数据,那么对应的decoy也不再计算,以保持target与decoy 1:1的混合比例,这里需要注意的是,即便是scoreList是空,也需要将DataDO存储到数据库中,以便后续的重新统计和分析
            if (dataDO.getPeakGroupList() == null) {
                return;
            }

            //Step4. 如果第一,二步均符合条件,那么开始对对应的伪肽段进行数据提取和打分
            coord.setDecoy(true);
            DataDO decoyData = extractor.extract(coord, ms1Map, ms2Map, params, true, null);
            if (decoyData == null) {
                return;
            }

            //Step5. 对Decoy进行打分
            decoyData = scorer.score(run, decoyData, coord, ms1Map, ms2Map, params);
            dataList.add(decoyData);

            //Step6. 忽略过程数据,将数据提取结果加入最终的列表
            DataUtil.compress(decoyData);
        });

        LogUtil.log("XIC+选峰+打分耗时", start);
        log.info("新增组合碎片数目为:" + newIonsGroup.get());
        log.info("总计构建Data数目" + dataList.size() + "/" + (coordinates.size() * 2) + "个");
        if (dataList.stream().filter(data -> data.getStatus() == null).toList().size() > 0) {
            log.info("居然有问题");
        }
        return dataList;
    }

    public List<DataDO> csi(RunDO run, List<PeptideCoord> coordinates, TreeMap<Float, MzIntensityPairs> ms1Map, TreeMap<Float, MzIntensityPairs> ms2Map, AnalyzeParams params) {
        List<DataDO> dataList = Collections.synchronizedList(new ArrayList<>());
        if (coordinates == null || coordinates.size() == 0) {
            log.error("肽段坐标为空");
            return null;
        }
        int maxIons = params.getMethod().getEic().getMaxIons();
        //传入的coordinates是没有经过排序的,需要排序先处理真实肽段,再处理伪肽段.如果先处理的真肽段没有被提取到任何信息,或者提取后的峰太差被忽略掉,都会同时删掉对应的伪肽段的XIC
        coordinates.parallelStream().forEach(coord -> {
            if (coord.getFragments().size() > maxIons) {
                coord.setFragments(coord.getFragments().subList(0, maxIons));
            }
            if (coord.getDecoyFragments().size() > maxIons) {
                coord.setDecoyFragments(coord.getDecoyFragments().subList(0, maxIons));
            }
            DataDO dataDO = extractor.extract(coord, ms1Map, ms2Map, params, true, null);
            if (dataDO == null) {
                return;
            }

            dataDO = scorer.score(run, dataDO, coord, ms1Map, ms2Map, params);
//            sumList.add(judge(dataDO));
            dataList.add(dataDO);
            //Step3. 忽略过程数据,将数据提取结果加入最终的列表
            DataUtil.compress(dataDO);

            //如果没有打分数据,那么对应的decoy也不再计算,以保持target与decoy 1:1的混合比例,这里需要注意的是,即便是scoreList是空,也需要将DataDO存储到数据库中,以便后续的重新统计和分析
//            if (dataDO.getPeakGroupList() == null) {
//                return;
//            }

            coord.setDecoy(true);
            DataDO decoyData = extractor.extract(coord, ms1Map, ms2Map, params, true, null);
            if (decoyData == null) {
                return;
            }

            //Step5. 对Decoy进行打分
            decoyData = scorer.score(run, decoyData, coord, ms1Map, ms2Map, params);
            dataList.add(decoyData);

            //Step6. 忽略过程数据,将数据提取结果加入最终的列表
            DataUtil.compress(decoyData);
        });

//        LogUtil.log("XIC+选峰+打分耗时", start);
//        log.info("新增组合碎片数目为:" + newIonsGroup.get());
//        log.info("总计构建Data数目" + dataList.size() + "/" + (coordinates.size() * 2) + "个");
//        if (dataList.stream().filter(data -> data.getStatus() == null).toList().size() > 0) {
//            log.info("居然有问题");
//        }
//        Result<List<DataSumDO>> result = dataSumService.insert(sumList, run.getProjectId());
//        if (result.isFailed()) {
//            log.error(result.getErrorMessage());
//        }
//        log.info("检测到肽段数目:" + sumList.stream().filter(sum -> sum.getStatus().equals(IdentifyStatus.SUCCESS.getCode())).count());
        return dataList;
    }

    private List<FragmentInfo> selectFragments(Map<String, FragmentInfo> fragMap, List<String> selectedIons) {
        List<FragmentInfo> fragmentInfos = new ArrayList<>();
        for (String selectedIon : selectedIons) {
            fragmentInfos.add(fragMap.get(selectedIon));
        }
        return fragmentInfos;
    }

    private DataDO buildData(DataDO data, List<String> selectedIons) {
        HashMap<String, float[]> selectedIntMap = new HashMap<>();
        HashMap<String, Float> selectedCutInfoMap = new HashMap<>();
        for (String cutInfo : selectedIons) {
            if (data.getIntMap().get(cutInfo) == null) {
                return null;
            }
            selectedIntMap.put(cutInfo, data.getIntMap().get(cutInfo));
            selectedCutInfoMap.put(cutInfo, data.getCutInfoMap().get(cutInfo));
        }
        DataDO newData = data.clone();
        newData.setIntMap(selectedIntMap);
        newData.setCutInfoMap(selectedCutInfoMap);
        return newData;
    }

    private List<IonStat> buildIonStat(Map<String, float[]> intMap) {
        List<IonStat> statList = new ArrayList<>();
        List<IonStat> finalStatList = statList;
        intMap.forEach((key, fArray) -> {
            double[][] sumArray = new double[fArray.length][2];
            List<Double> dList = new ArrayList<>();
            for (int i = 0; i < fArray.length; i++) {
                sumArray[i] = (i == 0) ? new double[]{1d, (double) fArray[i]} : new double[]{(double) i + 1, sumArray[i - 1][1] + fArray[i]};
                if (fArray[i] != 0f) {
                    dList.add((double) fArray[i]);
                }
            }
            double[] dArray = new double[dList.size()];
            for (int i = 0; i < dList.size(); i++) {
                dArray[i] = dList.get(i);
            }
            DescriptiveStatistics stat = new DescriptiveStatistics(dArray);
            //计算强度的偏差值,在RT范围内的偏差值越大说明峰的显著程度越高
            double cv = stat.getStandardDeviation() / stat.getMean();
            finalStatList.add(new IonStat(key, cv));
        });
        //按照cv从大到小排序
        statList = statList.stream().sorted(Comparator.comparing(IonStat::stat).reversed()).toList();
        return statList;
    }

    private DataSumDO judge(DataDO dataDO, double minTotalScore) {

        DataSumDO sum = new DataSumDO(dataDO);
        PeakGroup finalPgs = null;
        List<PeakGroup> peakGroupList = dataDO.getPeakGroupList();
        if (peakGroupList != null && peakGroupList.size() > 0) {
            List<PeakGroup> candidateList = peakGroupList.stream().filter(peakGroup -> peakGroup.getTotalScore() >= minTotalScore).toList();
            if (candidateList.size() > 0) {
                finalPgs = candidateList.stream().sorted(Comparator.comparing(PeakGroup::getTotalScore).reversed()).toList().get(0);
            }
        }

        if (finalPgs != null) {
            sum.setApexRt(finalPgs.getApexRt());
            sum.setSelectedRt(finalPgs.getSelectedRt());
            sum.setIntensitySum(finalPgs.getIntensitySum());
            sum.setFitIntSum(finalPgs.getFitIntSum());
            sum.setIonsLow(finalPgs.getIonsLow());
            sum.setBestIon(finalPgs.getBestIon());
            sum.setMs1Sum(finalPgs.getMs1Sum());
            dataDO.setStatus(IdentifyStatus.SUCCESS.getCode());
            sum.setStatus(IdentifyStatus.SUCCESS.getCode());
        } else {
            if (dataDO.getStatus() == null || dataDO.getStatus().equals(IdentifyStatus.WAIT.getCode())) {
                sum.setStatus(IdentifyStatus.FAILED.getCode());
                dataDO.setStatus(IdentifyStatus.FAILED.getCode());
            } else {
                sum.setStatus(dataDO.getStatus());
            }
        }

        return sum;
    }

}

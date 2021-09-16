package net.csibio.propro.algorithm.lfqbench;

import lombok.extern.slf4j.Slf4j;
import net.csibio.propro.algorithm.batch.BatchFitter;
import net.csibio.propro.algorithm.batch.bean.DataSum;
import net.csibio.propro.algorithm.batch.bean.GroupStat;
import net.csibio.propro.algorithm.lfqbench.bean.BenchStat;
import net.csibio.propro.algorithm.lfqbench.bean.PeptideRatio;
import net.csibio.propro.constants.enums.ResultCode;
import net.csibio.propro.domain.Result;
import net.csibio.propro.domain.bean.experiment.BaseExp;
import net.csibio.propro.domain.db.OverviewDO;
import net.csibio.propro.domain.db.ProjectDO;
import net.csibio.propro.domain.query.ExperimentQuery;
import net.csibio.propro.service.*;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component("lfqBench")
public class LfqBench {

    public static final String LABEL_A = "A";
    public static final String LABEL_B = "B";
    public static final String HUMAN = "HUMAN";
    public static final String YEAS8 = "YEAS8";
    public static final String ECOLI = "ECOLI";

    @Autowired
    ProjectService projectService;
    @Autowired
    ExperimentService experimentService;
    @Autowired
    DataSumService dataSumService;
    @Autowired
    DataService dataService;
    @Autowired
    OverviewService overviewService;
    @Autowired
    BatchFitter batchFitter;

    public Result<BenchStat<PeptideRatio>> buildPeptideRatio(ProjectDO project) {
        List<BaseExp> expList = experimentService.getAll(new ExperimentQuery().setProjectId(project.getId()), BaseExp.class);
        List<BaseExp> expAList = expList.stream().filter(exp -> exp.getLabel().equals(LABEL_A)).collect(Collectors.toList());
        List<BaseExp> expBList = expList.stream().filter(exp -> exp.getLabel().equals(LABEL_B)).collect(Collectors.toList());
        Map<String, OverviewDO> overviewMap = overviewService.getDefaultOverviews(expList.stream().map(BaseExp::getId).collect(Collectors.toList()));
        if (overviewMap.size() != expList.size()) {
            return Result.Error(ResultCode.SOME_EXPERIMENT_HAVE_NO_DEFAULT_OVERVIEW);
        }
        GroupStat statForA = batchFitter.merge(project, expAList, overviewMap);
        GroupStat statForB = batchFitter.merge(project, expBList, overviewMap);
        List<PeptideRatio> humanPoints = new ArrayList<>();
        List<PeptideRatio> yeastPoints = new ArrayList<>();
        List<PeptideRatio> ecoliPoints = new ArrayList<>();
        statForA.getDataMap().forEach((key, a) -> {
            if (statForB.getDataMap().containsKey(key)) {
                DataSum b = statForB.getDataMap().get(key);
                PeptideRatio peptideRatio = new PeptideRatio(key, Math.log(b.getSum()) / Math.log(2), Math.log(a.getSum() / b.getSum()) / Math.log(2));
                if (a.getProteins().get(0).endsWith(HUMAN)) {
                    humanPoints.add(peptideRatio);
                } else if (a.getProteins().get(0).endsWith(YEAS8)) {
                    yeastPoints.add(peptideRatio);
                } else {
                    ecoliPoints.add(peptideRatio);
                }
            }
        });

        BenchStat<PeptideRatio> points = new BenchStat<>(humanPoints, yeastPoints, ecoliPoints);
        DescriptiveStatistics human = new DescriptiveStatistics();
        humanPoints.forEach(p -> human.addValue(p.y()));

        DescriptiveStatistics yeast = new DescriptiveStatistics();
        yeastPoints.forEach(p -> yeast.addValue(p.y()));

        DescriptiveStatistics ecoli = new DescriptiveStatistics();
        ecoliPoints.forEach(p -> ecoli.addValue(p.y()));

        points.setIdentifyNumA(statForA.getDataMap().size());
        points.setMissingRatioA(statForA.getMissingRatio());
        points.setHit1A(statForA.getHit1());
        points.setHit2A(statForA.getHit2());
        points.setHit3A(statForA.getHit3());
        points.setIdentifyProteinNumA(statForA.getProteins());

        points.setIdentifyNumB(statForB.getDataMap().size());
        points.setMissingRatioB(statForB.getMissingRatio());
        points.setHit1B(statForB.getHit1());
        points.setHit2B(statForB.getHit2());
        points.setHit3B(statForB.getHit3());
        points.setIdentifyProteinNumB(statForB.getProteins());

        points.setHumanStat(human);
        points.setYeastStat(yeast);
        points.setEcoliStat(ecoli);
        return Result.OK(points);
    }
}
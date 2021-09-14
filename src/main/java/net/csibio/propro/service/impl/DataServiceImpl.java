package net.csibio.propro.service.impl;

import lombok.extern.slf4j.Slf4j;
import net.csibio.propro.algorithm.extract.Extractor;
import net.csibio.propro.algorithm.score.Scorer;
import net.csibio.propro.constants.constant.SpModelConstant;
import net.csibio.propro.dao.BaseMultiDAO;
import net.csibio.propro.dao.DataDAO;
import net.csibio.propro.domain.Result;
import net.csibio.propro.domain.bean.peptide.FragmentInfo;
import net.csibio.propro.domain.db.*;
import net.csibio.propro.domain.options.AnalyzeParams;
import net.csibio.propro.domain.query.DataQuery;
import net.csibio.propro.domain.query.PeptideQuery;
import net.csibio.propro.domain.vo.ExpDataVO;
import net.csibio.propro.exceptions.XException;
import net.csibio.propro.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

@Slf4j
@Service("dataService")
public class DataServiceImpl implements DataService {

    @Autowired
    DataDAO dataDAO;
    @Autowired
    DataSumService dataSumService;
    @Autowired
    Extractor extractor;
    @Autowired
    Scorer scorer;
    @Autowired
    PeptideService peptideService;
    @Autowired
    SimulateService simulateService;
    @Autowired
    ExperimentService experimentService;

    @Override
    public BaseMultiDAO<DataDO, DataQuery> getBaseDAO() {
        return dataDAO;
    }

    @Override
    public void beforeInsert(DataDO dataDO, String projectId) throws XException {
        //Do Nothing
    }

    @Override
    public void beforeUpdate(DataDO dataDO, String projectId) throws XException {
        //Do Nothing
    }

    @Override
    public void beforeRemove(String id, String projectId) throws XException {
        //Do Nothing
    }

    @Override
    public ExpDataVO getData(String projectId, String expId, String overviewId, String peptideRef) {
        ExpDataVO dataVO = new ExpDataVO(expId, overviewId, peptideRef);
        DataDO data = getOne(new DataQuery(overviewId).setPeptideRef(peptideRef), DataDO.class, projectId);
        if (data == null) {
            return null;
        }
        DataSumDO dataSum = dataSumService.getById(data.getId(), projectId);
        dataVO.merge(data, dataSum);
        return dataVO;
    }

    @Override
    public List<ExpDataVO> buildData(List<String> expIds, String libraryId, String brotherPeptideRef, int newCharge) {
        PeptideDO brother = peptideService.getOne(new PeptideQuery().setLibraryId(libraryId).setPeptideRef(brotherPeptideRef), PeptideDO.class);
        if (brother == null) {
            return null;
        }
        List<ExpDataVO> eicDataList = new ArrayList<>();
        //如果是带两个点的就改为3个点,如果不是2个电的就改为2个电
        PeptideDO newGuy = brother.buildBrother(newCharge);
        List<FragmentInfo> fragmentInfos = simulateService.predictFragment(brother, SpModelConstant.HCD, true, 5);
        newGuy.setFragments(new HashSet<>(fragmentInfos));
        AnalyzeParams params = new AnalyzeParams(new MethodDO().init());
        expIds.forEach(expId -> {
            ExperimentDO exp = experimentService.getById(expId);
            Result<DataDO> result = extractor.extractOne(exp, newGuy.toTargetPeptide(), params);
            if (result.isSuccess()) {
                ExpDataVO data = new ExpDataVO();
                data.merge(result.getData(), null);
                eicDataList.add(data);
            }
        });
        return eicDataList;
    }

    @Override
    public ExpDataVO buildData(ExperimentDO exp, String libraryId, String originalPeptide) {
        PeptideDO brother = peptideService.getOne(new PeptideQuery().setLibraryId(libraryId).setPeptideRef(originalPeptide), PeptideDO.class);
        if (brother == null) {
            return null;
        }
        //如果是带两个点的就改为3个点,如果不是2个电的就改为2个电
        PeptideDO newGuy = brother.buildBrother(2);
        List<FragmentInfo> fragmentInfos = simulateService.predictFragment(brother, SpModelConstant.CID, false, 6);
        newGuy.setFragments(new HashSet<>(fragmentInfos));
        AnalyzeParams params = new AnalyzeParams(new MethodDO().init());

        Result<DataDO> result = extractor.extractOne(exp, newGuy.toTargetPeptide(), params);
        if (result.isSuccess()) {
            ExpDataVO data = new ExpDataVO();
            data.merge(result.getData(), null);
            return data;
        }

        return null;
    }
}

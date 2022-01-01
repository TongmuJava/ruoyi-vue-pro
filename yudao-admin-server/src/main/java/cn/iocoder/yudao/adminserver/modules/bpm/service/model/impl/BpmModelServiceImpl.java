package cn.iocoder.yudao.adminserver.modules.bpm.service.model.impl;

import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.adminserver.modules.bpm.controller.model.vo.BpmModelCreateReqVO;
import cn.iocoder.yudao.adminserver.modules.bpm.controller.model.vo.BpmModelPageItemRespVO;
import cn.iocoder.yudao.adminserver.modules.bpm.controller.model.vo.BpmModelRespVO;
import cn.iocoder.yudao.adminserver.modules.bpm.controller.model.vo.ModelPageReqVO;
import cn.iocoder.yudao.adminserver.modules.bpm.convert.model.ModelConvert;
import cn.iocoder.yudao.adminserver.modules.bpm.dal.dataobject.form.BpmFormDO;
import cn.iocoder.yudao.adminserver.modules.bpm.enums.BpmErrorCodeConstants;
import cn.iocoder.yudao.adminserver.modules.bpm.service.definition.BpmDefinitionService;
import cn.iocoder.yudao.adminserver.modules.bpm.service.form.BpmFormService;
import cn.iocoder.yudao.adminserver.modules.bpm.service.model.BpmModelService;
import cn.iocoder.yudao.adminserver.modules.bpm.service.model.dto.BpmModelMetaInfoRespDTO;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.collection.CollectionUtils;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.PageUtils;
import lombok.extern.slf4j.Slf4j;
import org.activiti.bpmn.converter.BpmnXMLConverter;
import org.activiti.bpmn.model.BpmnModel;
import org.activiti.engine.RepositoryService;
import org.activiti.engine.repository.Deployment;
import org.activiti.engine.repository.Model;
import org.activiti.engine.repository.ModelQuery;
import org.activiti.engine.repository.ProcessDefinition;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;
import org.springframework.validation.annotation.Validated;

import javax.annotation.Resource;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.iocoder.yudao.adminserver.modules.bpm.enums.BpmErrorCodeConstants.BPM_MODEL_KEY_EXISTS;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertMap;

/**
 * 流程定义实现
 * 主要进行 Activiti {@link Model} 的维护
 *
 * @author yunlongn
 */
@Service
@Validated
@Slf4j
public class BpmModelServiceImpl implements BpmModelService {

    private static final String BPMN_FILE_SUFFIX = ".bpmn";

    @Resource
    private RepositoryService repositoryService;
    @Resource
    private BpmFormService bpmFormService;
    @Resource
    private BpmDefinitionService bpmDefinitionService;

    @Override
    public PageResult<BpmModelPageItemRespVO> getModelPage(ModelPageReqVO pageVO) {
        ModelQuery modelQuery = repositoryService.createModelQuery();
        if (StrUtil.isNotBlank(pageVO.getName())) {
            modelQuery.modelNameLike("%" + pageVO.getName() + "%"); // 模糊匹配
        }
        // 执行查询
        List<Model> models = modelQuery.orderByCreateTime().desc()
                .listPage(PageUtils.getStart(pageVO), pageVO.getPageSize());

        // 获得 Form Map
        Set<Long> formIds = CollectionUtils.convertSet(models, model -> {
            BpmModelMetaInfoRespDTO metaInfo = JsonUtils.parseObject(model.getMetaInfo(), BpmModelMetaInfoRespDTO.class);
            return metaInfo != null ? metaInfo.getFormId() : null;
        });
        Map<Long, BpmFormDO> formMap = bpmFormService.getFormMap(formIds);

        // 获得 ProcessDefinition Map
        Set<String> deploymentIds = new HashSet<>();
        models.forEach(model -> CollectionUtils.addIfNotNull(deploymentIds, model.getId()));
        List<ProcessDefinition> processDefinitions = bpmDefinitionService.getProcessDefinitionListByDeploymentIds(deploymentIds);
        Map<String, ProcessDefinition> processDefinitionMap = convertMap(processDefinitions, ProcessDefinition::getDeploymentId);

        // 拼接结果
        long modelCount = modelQuery.count();
        return new PageResult<>(ModelConvert.INSTANCE.convertList(models, formMap, processDefinitionMap), modelCount);
    }

    @Override
    public BpmModelRespVO getModel(String id) {
        Model model = repositoryService.getModel(id);
        BpmModelRespVO modelRespVO = ModelConvert.INSTANCE.convert(model);
        // 拼接 bpmn XML
        byte[] bpmnBytes = repositoryService.getModelEditorSource(id);
        if (ArrayUtil.isNotEmpty(bpmnBytes)) {
            modelRespVO.setBpmnXml(StrUtil.utf8Str(bpmnBytes));
        }
        return modelRespVO;
    }

    @Override
    @Transactional(rollbackFor = Exception.class) // 因为进行多个 activiti 操作，所以开启事务
    public String createModel(BpmModelCreateReqVO createReqVO) {
        // 校验流程标识已经存在
        Model keyModel = this.getModelByKey(createReqVO.getKey());
        if (keyModel != null) {
            throw exception(BPM_MODEL_KEY_EXISTS);
        }

        // 创建流程定义
        Model model = repositoryService.newModel();
        ModelConvert.INSTANCE.copy(model, createReqVO);
        // 保存流程定义
        repositoryService.saveModel(model);
        // 添加 BPMN XML
        repositoryService.addModelEditorSource(model.getId(), StrUtil.utf8Bytes(createReqVO.getBpmnXml()));
        return model.getId();
    }

//    @Override
//    @Transactional(rollbackFor = Exception.class) // 因为进行多个 activiti 操作，所以开启事务
//    public String createModel(BpmModelCreateReqVO createReqVO) {
//        Deployment deploy = repositoryService.createDeployment()
//                .key(createReqVO.getKey()).name(createReqVO.getName()).category(createReqVO.getCategory())
//                .addString(createReqVO.getName() + BPMN_FILE_SUFFIX, createReqVO.getBpmnXml())
//                .deploy();
//        // 设置 ProcessDefinition 的 category 分类
//        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery().deploymentId(deploy.getId()).singleResult();
//        repositoryService.setProcessDefinitionCategory(definition.getId(), createReqVO.getCategory());
//        return definition.getId();
//    }

//    @Override
//    @Transactional(rollbackFor = Exception.class) // 因为进行多个 activiti 操作，所以开启事务
//    public String createModel(BpmModelCreateReqVO createReqVO) {
//        // 校验流程标识已经存在
//        Model keyModel = this.getModelByKey(createReqVO.getKey());
//        if (keyModel != null) {
//            throw exception(BPM_MODEL_KEY_EXISTS);
//        }
//
//        // 创建流程定义
//        Model model = repositoryService.newModel();
//        ModelConvert.INSTANCE.copy(model, createReqVO);
//        // 保存流程定义
//        repositoryService.saveModel(model);
//        // 添加 BPMN XML
//        repositoryService.addModelEditorSource(model.getId(), StrUtil.utf8Bytes(createReqVO.getBpmnXml()));
//        return model.getId();
//    }

    @Override
    public CommonResult<String> updateModel(BpmModelCreateReqVO modelVO) {
//        try {
//            Model model = repositoryService.getModel(modelVO.getId());
//            if (ObjectUtils.isEmpty(model)) {
//                throw ServiceExceptionUtil.exception(BpmErrorCodeConstants.BPMN_MODEL_EDITOR_SOURCE_NOT_EXISTS);
//            }
//            // 只能修改名字跟描述
//            BpmModelCreateReqVO modelCreateVO = JsonUtils.parseObject(model.getMetaInfo(), BpmModelCreateReqVO.class);
//            if (ObjectUtils.isEmpty(modelCreateVO)) {
//                modelCreateVO = new BpmModelCreateReqVO();
//            }
//            modelCreateVO.setName(modelVO.getName());
//            modelCreateVO.setDescription(modelVO.getDescription());
//            model.setMetaInfo(JsonUtils.toJsonString(modelCreateVO));
//            model.setName(modelVO.getName());
//            model.setKey(modelVO.getKey());
//            // 更新模型
//            repositoryService.saveModel(model);
//
//            repositoryService.addModelEditorSource(model.getId(), modelVO.getBpmnXml().getBytes(StandardCharsets.UTF_8));
//
//            return CommonResult.success("保存成功");
//        }catch (Exception e){
//            log.info("模型更新失败！modelVO = {}", modelVO, e);
//            throw ServiceExceptionUtil.exception(BpmErrorCodeConstants.BPMN_MODEL_ERROR);
//        }
        return null;
    }

    @Override
    public CommonResult<String> deploy(String modelId) {
        try {
            Model modelData = repositoryService.getModel(modelId);
            if (ObjectUtils.isEmpty(modelData)) {
                throw exception(BpmErrorCodeConstants.BPMN_MODEL_EDITOR_SOURCE_NOT_EXISTS);
            }
            byte[] bytes = repositoryService.getModelEditorSource(modelData.getId());
            if (bytes == null) {
                throw exception(BpmErrorCodeConstants.BPMN_MODEL_EDITOR_SOURCE_NOT_EXISTS);
            }
            // 将xml转换为流
            // TODO @Li：这里是标准逻辑，看看 hutool 有没工具类提供。如果没有，咱自己封装一个
            ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
            XMLInputFactory xif = XMLInputFactory.newInstance();
            InputStreamReader in = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
            XMLStreamReader xtr = xif.createXMLStreamReader(in);
            // 流数据转化为 model
            BpmnModel model = new BpmnXMLConverter().convertToBpmnModel(xtr);
            if(ObjectUtils.isEmpty(model.getProcesses())){
                throw exception(BpmErrorCodeConstants.BPMN_MODEL_PROCESS_NOT_EXISTS);
            }
            byte[] bpmnBytes = new BpmnXMLConverter().convertToXML(model);
            // 部署发布模型流程
            String processName = modelData.getName() + ".bpmn20.xml";
            Deployment deployment = repositoryService.createDeployment()
                    .name(modelData.getName())
                    .addString(processName, new String(bpmnBytes, StandardCharsets.UTF_8))
                    .deploy();
            // 部署成功
            return CommonResult.success(deployment.getId());
        } catch (Exception e) {
            log.info("模型部署失败！modelId = {} e = {} ", modelId, ExceptionUtils.getStackTrace(e));
            throw exception(BpmErrorCodeConstants.BPMN_MODEL_ERROR);
        }
    }

    @Override
    public CommonResult<String> deleteModel(String modelId) {
        // TODO @Li：activitie 是逻辑删除么？
        repositoryService.deleteModel(modelId);
        return CommonResult.success("删除成功");
    }

    private Model getModelByKey(String key) {
        return repositoryService.createModelQuery().modelKey(key).singleResult();
    }

}
package com.zifang.z.llm.gateway.config.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zifang.z.llm.gateway.config.dto.LlmModelDto;
import com.zifang.z.llm.gateway.config.dto.LlmProviderDto;
import com.zifang.z.llm.gateway.config.dto.ToolTemplateDto;
import com.zifang.z.llm.gateway.config.entity.AgentToolTemplate;
import com.zifang.z.llm.gateway.config.entity.LlmModel;
import com.zifang.z.llm.gateway.config.entity.LlmProvider;
import com.zifang.z.llm.gateway.api.dto.LlmCredential;
import com.zifang.z.llm.gateway.config.mapper.AgentToolTemplateMapper;
import com.zifang.z.llm.gateway.config.mapper.LlmModelMapper;
import com.zifang.z.llm.gateway.config.mapper.LlmProviderMapper;
import com.zifang.z.llm.gateway.core.registry.LlmManagedRuntime;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class LlmConfigService {

    @Autowired(required = false)
    private LlmProviderMapper providerMapper;
    @Autowired(required = false)
    private LlmModelMapper modelMapper;
    @Autowired(required = false)
    private AgentToolTemplateMapper toolTemplateMapper;

    /**
     * 网关运行时 (可选). 存在 = 与 z-llm-core 运行时同装, CRUD 后把 DB 配置推给运行时,
     * 使前端新增/修改的供应商+模型立即可被路由. 纯配置面板 (无运行时) 场景下为 null, no-op.
     */
    @Autowired(required = false)
    private LlmManagedRuntime managedRuntime;

    // ==================== Provider ====================

    public List<LlmProviderDto> listProviders() {
        LambdaQueryWrapper<LlmProvider> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(LlmProvider::getPriority);
        return providerMapper.selectList(wrapper).stream()
                .map(this::toProviderDto)
                .collect(Collectors.toList());
    }

    public LlmProviderDto getProvider(String providerCode) {
        LambdaQueryWrapper<LlmProvider> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LlmProvider::getProviderCode, providerCode);
        LlmProvider p = providerMapper.selectOne(wrapper);
        return p != null ? toProviderDto(p) : null;
    }

    public void saveProvider(LlmProviderDto dto) {
        LlmProvider p = new LlmProvider();
        BeanUtils.copyProperties(dto, p);
        if (dto.getId() != null) {
            providerMapper.updateById(p);
        } else {
            providerMapper.insert(p);
        }
        pushManagedToRuntime();
    }

    public void deleteProvider(Long id) {
        providerMapper.deleteById(id);
        pushManagedToRuntime();
    }

    // ==================== Model ====================

    public List<LlmModelDto> listModels(String providerCode) {
        LambdaQueryWrapper<LlmModel> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LlmModel::getEnabled, 1);
        if (StringUtils.hasText(providerCode)) {
            wrapper.eq(LlmModel::getProviderCode, providerCode);
        }
        wrapper.orderByDesc(LlmModel::getGmtCreate);
        return modelMapper.selectList(wrapper).stream()
                .map(this::toModelDto)
                .collect(Collectors.toList());
    }

    public List<LlmModelDto> listModelsByProvider(String providerCode) {
        LambdaQueryWrapper<LlmModel> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LlmModel::getEnabled, 1);
        wrapper.eq(StringUtils.hasText(providerCode), LlmModel::getProviderCode, providerCode);
        return modelMapper.selectList(wrapper).stream()
                .map(this::toModelDto)
                .collect(Collectors.toList());
    }

    public Page<LlmModelDto> pageModels(String providerCode, String keyword, int pageNum, int pageSize) {
        Page<LlmModel> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<LlmModel> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(providerCode)) {
            wrapper.eq(LlmModel::getProviderCode, providerCode);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.like(LlmModel::getModelName, keyword).or()
                    .like(LlmModel::getModelCode, keyword);
        }
        wrapper.orderByDesc(LlmModel::getGmtCreate);
        Page<LlmModel> result = modelMapper.selectPage(page, wrapper);
        Page<LlmModelDto> dtoPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        dtoPage.setRecords(result.getRecords().stream().map(this::toModelDto).collect(Collectors.toList()));
        return dtoPage;
    }

    public LlmModelDto getModel(String modelCode) {
        LambdaQueryWrapper<LlmModel> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LlmModel::getModelCode, modelCode);
        LlmModel m = modelMapper.selectOne(wrapper);
        return m != null ? toModelDto(m) : null;
    }

    public void saveModel(LlmModelDto dto) {
        LlmModel m = new LlmModel();
        BeanUtils.copyProperties(dto, m);
        if (dto.getId() != null) {
            modelMapper.updateById(m);
        } else {
            modelMapper.insert(m);
        }
        pushManagedToRuntime();
    }

    public void deleteModel(Long id) {
        modelMapper.deleteById(id);
        pushManagedToRuntime();
    }

    // ==================== Tool Template ====================

    public List<ToolTemplateDto> listToolTemplates() {
        LambdaQueryWrapper<AgentToolTemplate> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentToolTemplate::getEnabled, 1);
        return toolTemplateMapper.selectList(wrapper).stream()
                .map(this::toToolTemplateDto)
                .collect(Collectors.toList());
    }

    public ToolTemplateDto getToolTemplate(String toolCode) {
        LambdaQueryWrapper<AgentToolTemplate> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentToolTemplate::getToolCode, toolCode);
        AgentToolTemplate t = toolTemplateMapper.selectOne(wrapper);
        return t != null ? toToolTemplateDto(t) : null;
    }

    public void saveToolTemplate(ToolTemplateDto dto) {
        AgentToolTemplate t = new AgentToolTemplate();
        BeanUtils.copyProperties(dto, t);
        if (dto.getId() != null) {
            toolTemplateMapper.updateById(t);
        } else {
            toolTemplateMapper.insert(t);
        }
    }

    public void deleteToolTemplate(Long id) {
        toolTemplateMapper.deleteById(id);
    }

    // ==================== 运行时下发 (DB → 网关运行时) ====================

    /**
     * 把 DB 里启用的供应商/模型全量映射成受管凭据, 推给网关运行时.
     *
     * <p>无运行时 (纯配置面板) 时为 no-op. 有运行时则覆盖式下发: 受管凭据按 alias
     * 合并进凭据池并重建 provider 注册表, 使前端新增/修改的模型立即可被 ModelRouter 路由.
     */
    public void pushManagedToRuntime() {
        if (managedRuntime == null) {
            return;
        }
        List<LlmProvider> providers = providerMapper.selectList(null);
        List<LlmModel> models = modelMapper.selectList(null);
        List<LlmCredential> managed = ManagedCredentialMapper.toCredentials(providers, models);
        managedRuntime.applyManaged(managed);
    }

    /** 供控制器/运维手动触发一次 DB→运行时同步. */
    public void syncRuntime() {
        pushManagedToRuntime();
    }

    // ==================== DTO Converters ====================

    private LlmProviderDto toProviderDto(LlmProvider p) {
        LlmProviderDto dto = new LlmProviderDto();
        BeanUtils.copyProperties(p, dto);
        return dto;
    }

    private LlmModelDto toModelDto(LlmModel m) {
        LlmModelDto dto = new LlmModelDto();
        BeanUtils.copyProperties(m, dto);
        return dto;
    }

    private ToolTemplateDto toToolTemplateDto(AgentToolTemplate t) {
        ToolTemplateDto dto = new ToolTemplateDto();
        BeanUtils.copyProperties(t, dto);
        return dto;
    }
}
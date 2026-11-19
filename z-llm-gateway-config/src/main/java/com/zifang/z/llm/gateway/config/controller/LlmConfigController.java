package com.zifang.z.llm.gateway.config.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zifang.util.core.meta.Result;
import com.zifang.z.llm.gateway.config.auth.LlmConfigAccess;
import com.zifang.z.llm.gateway.config.dto.LlmModelDto;
import com.zifang.z.llm.gateway.config.dto.LlmProviderDto;
import com.zifang.z.llm.gateway.config.dto.ToolTemplateDto;
import com.zifang.z.llm.gateway.config.service.LlmConfigService;
import com.zifang.z.meta.api.annotation.BizApi;
import com.zifang.z.meta.api.annotation.BizField;
import com.zifang.z.meta.api.annotation.BizParam;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * LLM 配置 API: 提供 Provider / Model / 工具模板的元数据管理.
 * <p>
 * 替代 z-opc 内 z-agent-llm-center（原 /api/llm-center/*）。
 * 新路径: /api/z-llm/config
 *
 * <p>主要端点:
 * <ul>
 *   <li>GET    /provider/list — 查询所有 LLM 提供方</li>
 *   <li>GET    /provider?providerCode= — 按编码查询提供方</li>
 *   <li>POST   /provider/create — 创建 LLM 提供方</li>
 *   <li>POST   /provider/update — 更新 LLM 提供方</li>
 *   <li>POST   /provider/delete — 删除 LLM 提供方</li>
 *   <li>GET    /model/list — 查询模型列表 (可按 providerCode 过滤)</li>
 *   <li>GET    /model/page — 模型分页查询</li>
 *   <li>GET    /model?modelCode= — 按编码查询模型</li>
 *   <li>POST   /model/create — 创建模型</li>
 *   <li>POST   /model/update — 更新模型</li>
 *   <li>POST   /model/delete — 删除模型</li>
 *   <li>GET    /tool-template/list — 查询工具模板列表</li>
 *   <li>GET    /tool-template?toolCode= — 按编码查询工具模板</li>
 *   <li>POST   /tool-template/create — 创建工具模板</li>
 *   <li>POST   /tool-template/delete — 删除工具模板</li>
 * </ul>
 */
@Tag(name = "z-llm-配置")
@RestController
@RequestMapping("/api/z-llm/config")
public class LlmConfigController {

    @Resource
    private LlmConfigService llmConfigService;

    @Resource
    private LlmConfigAccess llmConfigAccess;

    // ==================== Provider ====================

    @Operation(summary = "查询所有 LLM 提供方")
    @GetMapping("/provider/list")
    @BizApi(summary = "listProviders", description = "", tags = { "z-llm-gateway" })
    public Result<List<LlmProviderDto>> listProviders() {
        return Result.success(llmConfigService.listProviders());
    }

    @Operation(summary = "按编码查询 LLM 提供方")
    @GetMapping("/provider")
    @BizApi(summary = "getProvider", description = "", tags = { "z-llm-gateway" })
    public Result<LlmProviderDto> getProvider(@RequestParam @BizParam(name = "providerCode", type = BizField.FieldType.STRING, description = "", position = BizParam.Position.QUERY) String providerCode) {
        return Result.success(llmConfigService.getProvider(providerCode));
    }

    @Operation(summary = "创建 LLM 提供方")
    @PostMapping("/provider/create")
    @BizApi(summary = "createProvider", description = "", tags = { "z-llm-gateway" })
    public Result<Object> createProvider(@RequestBody @BizParam(name = "dto", type = BizField.FieldType.STRING, description = "", position = BizParam.Position.BODY) LlmProviderDto dto) {
        llmConfigAccess.assertWriteOperator();
        llmConfigService.saveProvider(dto);
        return Result.success();
    }

    @Operation(summary = "更新 LLM 提供方")
    @PostMapping("/provider/update")
    @BizApi(summary = "updateProvider", description = "", tags = { "z-llm-gateway" })
    public Result<Object> updateProvider(@RequestBody @BizParam(name = "dto", type = BizField.FieldType.STRING, description = "", position = BizParam.Position.BODY) LlmProviderDto dto) {
        llmConfigAccess.assertWriteOperator();
        llmConfigService.saveProvider(dto);
        return Result.success();
    }

    @Operation(summary = "删除 LLM 提供方")
    @PostMapping("/provider/delete")
    @BizApi(summary = "deleteProvider", description = "", tags = { "z-llm-gateway" })
    public Result<Object> deleteProvider(@RequestBody @BizParam(name = "id", type = BizField.FieldType.INTEGER, description = "", position = BizParam.Position.BODY) Long id) {
        llmConfigAccess.assertWriteOperator();
        llmConfigService.deleteProvider(id);
        return Result.success();
    }

    // ==================== Model ====================

    @Operation(summary = "查询模型列表 (可按 providerCode 过滤)")
    @GetMapping("/model/list")
    @BizApi(summary = "listModels", description = "", tags = { "z-llm-gateway" })
    public Result<List<LlmModelDto>> listModels(@RequestParam(required = false) @BizParam(name = "providerCode", type = BizField.FieldType.STRING, description = "", position = BizParam.Position.QUERY) String providerCode) {
        return Result.success(llmConfigService.listModels(providerCode));
    }

    @Operation(summary = "模型分页查询 (支持 providerCode 和关键字过滤)")
    @GetMapping("/model/page")
    @BizApi(summary = "pageModels", description = "", tags = { "z-llm-gateway" })
    public Result<Page<LlmModelDto>> pageModels(
            @RequestParam(required = false)
            @BizParam(name = "providerCode", type = BizField.FieldType.STRING, description = "", position = BizParam.Position.QUERY) String providerCode,
            @RequestParam(required = false)
            @BizParam(name = "keyword", type = BizField.FieldType.STRING, description = "", position = BizParam.Position.QUERY) String keyword,
            @RequestParam(defaultValue = "1")
            @BizParam(name = "pageNum", type = BizField.FieldType.INTEGER, description = "", position = BizParam.Position.QUERY) int pageNum,
            @RequestParam(defaultValue = "10")
            @BizParam(name = "pageSize", type = BizField.FieldType.INTEGER, description = "", position = BizParam.Position.QUERY) int pageSize) {
        return Result.success(llmConfigService.pageModels(providerCode, keyword, pageNum, pageSize));
    }

    @Operation(summary = "按编码查询模型")
    @GetMapping("/model")
    @BizApi(summary = "getModel", description = "", tags = { "z-llm-gateway" })
    public Result<LlmModelDto> getModel(@RequestParam @BizParam(name = "modelCode", type = BizField.FieldType.STRING, description = "", position = BizParam.Position.QUERY) String modelCode) {
        return Result.success(llmConfigService.getModel(modelCode));
    }

    @Operation(summary = "创建模型")
    @PostMapping("/model/create")
    @BizApi(summary = "createModel", description = "", tags = { "z-llm-gateway" })
    public Result<Object> createModel(@RequestBody @BizParam(name = "dto", type = BizField.FieldType.STRING, description = "", position = BizParam.Position.BODY) LlmModelDto dto) {
        llmConfigAccess.assertWriteOperator();
        llmConfigService.saveModel(dto);
        return Result.success();
    }

    @Operation(summary = "更新模型")
    @PostMapping("/model/update")
    @BizApi(summary = "updateModel", description = "", tags = { "z-llm-gateway" })
    public Result<Object> updateModel(@RequestBody @BizParam(name = "dto", type = BizField.FieldType.STRING, description = "", position = BizParam.Position.BODY) LlmModelDto dto) {
        llmConfigAccess.assertWriteOperator();
        llmConfigService.saveModel(dto);
        return Result.success();
    }

    @Operation(summary = "删除模型")
    @PostMapping("/model/delete")
    @BizApi(summary = "deleteModel", description = "", tags = { "z-llm-gateway" })
    public Result<Object> deleteModel(@RequestBody @BizParam(name = "id", type = BizField.FieldType.INTEGER, description = "", position = BizParam.Position.BODY) Long id) {
        llmConfigAccess.assertWriteOperator();
        llmConfigService.deleteModel(id);
        return Result.success();
    }

    // ==================== Tool Template ====================

    @Operation(summary = "查询工具模板列表")
    @GetMapping("/tool-template/list")
    @BizApi(summary = "listToolTemplates", description = "", tags = { "z-llm-gateway" })
    public Result<List<ToolTemplateDto>> listToolTemplates() {
        return Result.success(llmConfigService.listToolTemplates());
    }

    @Operation(summary = "按编码查询工具模板")
    @GetMapping("/tool-template")
    @BizApi(summary = "getToolTemplate", description = "", tags = { "z-llm-gateway" })
    public Result<ToolTemplateDto> getToolTemplate(@RequestParam @BizParam(name = "toolCode", type = BizField.FieldType.STRING, description = "", position = BizParam.Position.QUERY) String toolCode) {
        return Result.success(llmConfigService.getToolTemplate(toolCode));
    }

    @Operation(summary = "创建工具模板")
    @PostMapping("/tool-template/create")
    @BizApi(summary = "createToolTemplate", description = "", tags = { "z-llm-gateway" })
    public Result<Object> createToolTemplate(@RequestBody @BizParam(name = "dto", type = BizField.FieldType.STRING, description = "", position = BizParam.Position.BODY) ToolTemplateDto dto) {
        llmConfigAccess.assertWriteOperator();
        llmConfigService.saveToolTemplate(dto);
        return Result.success();
    }

    @Operation(summary = "更新工具模板")
    @PostMapping("/tool-template/update")
    @BizApi(summary = "updateToolTemplate", description = "", tags = { "z-llm-gateway" })
    public Result<Object> updateToolTemplate(@RequestBody @BizParam(name = "dto", type = BizField.FieldType.STRING, description = "", position = BizParam.Position.BODY) ToolTemplateDto dto) {
        llmConfigAccess.assertWriteOperator();
        llmConfigService.saveToolTemplate(dto);
        return Result.success();
    }

    @Operation(summary = "删除工具模板")
    @PostMapping("/tool-template/delete")
    @BizApi(summary = "deleteToolTemplate", description = "", tags = { "z-llm-gateway" })
    public Result<Object> deleteToolTemplate(@RequestBody @BizParam(name = "id", type = BizField.FieldType.INTEGER, description = "", position = BizParam.Position.BODY) Long id) {
        llmConfigAccess.assertWriteOperator();
        llmConfigService.deleteToolTemplate(id);
        return Result.success();
    }
}


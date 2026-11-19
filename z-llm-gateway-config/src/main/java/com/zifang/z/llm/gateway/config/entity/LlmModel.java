package com.zifang.z.llm.gateway.config.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zifang.z.meta.api.annotation.BizField;
import com.zifang.z.meta.api.annotation.BizModel;

import java.time.LocalDateTime;

@BizModel(name = "z_llm_model", module = "z-llm-gateway", description = "z_llm_model")
@TableName("z_llm_model")
public class LlmModel {

    @TableId(type = IdType.AUTO)
    @BizField(name = "id", type = BizField.FieldType.INTEGER)
    private Long id;
    @BizField(name = "modelCode", type = BizField.FieldType.STRING)
    private String modelCode;
    @BizField(name = "modelName", type = BizField.FieldType.STRING)
    private String modelName;
    @BizField(name = "providerCode", type = BizField.FieldType.STRING)
    private String providerCode;
    @BizField(name = "modelType", type = BizField.FieldType.STRING)
    private String modelType;
    @BizField(name = "contextWindow", type = BizField.FieldType.INTEGER)
    private Integer contextWindow;
    @BizField(name = "supportsFunctionCall", type = BizField.FieldType.INTEGER)
    private Integer supportsFunctionCall;
    @BizField(name = "supportsVision", type = BizField.FieldType.INTEGER)
    private Integer supportsVision;
    @BizField(name = "maxOutputTokens", type = BizField.FieldType.INTEGER)
    private Integer maxOutputTokens;
    @BizField(name = "inputPrice", type = BizField.FieldType.DECIMAL)
    private Double inputPrice;
    @BizField(name = "outputPrice", type = BizField.FieldType.DECIMAL)
    private Double outputPrice;
    @BizField(name = "enabled", type = BizField.FieldType.INTEGER)
    private Integer enabled;
    @BizField(name = "description", type = BizField.FieldType.STRING)
    private String description;
    @BizField(name = "defaultParams", type = BizField.FieldType.STRING)
    private String defaultParams;
    @BizField(name = "extConfig", type = BizField.FieldType.STRING)
    private String extConfig;
    @BizField(name = "gmtCreate", type = BizField.FieldType.DATETIME)
    private LocalDateTime gmtCreate;
    @BizField(name = "gmtModified", type = BizField.FieldType.DATETIME)
    private LocalDateTime gmtModified;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getModelCode() {
        return modelCode;
    }

    public void setModelCode(String modelCode) {
        this.modelCode = modelCode;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getProviderCode() {
        return providerCode;
    }

    public void setProviderCode(String providerCode) {
        this.providerCode = providerCode;
    }

    public String getModelType() {
        return modelType;
    }

    public void setModelType(String modelType) {
        this.modelType = modelType;
    }

    public Integer getContextWindow() {
        return contextWindow;
    }

    public void setContextWindow(Integer contextWindow) {
        this.contextWindow = contextWindow;
    }

    public Integer getSupportsFunctionCall() {
        return supportsFunctionCall;
    }

    public void setSupportsFunctionCall(Integer supportsFunctionCall) {
        this.supportsFunctionCall = supportsFunctionCall;
    }

    public Integer getSupportsVision() {
        return supportsVision;
    }

    public void setSupportsVision(Integer supportsVision) {
        this.supportsVision = supportsVision;
    }

    public Integer getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(Integer maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public Double getInputPrice() {
        return inputPrice;
    }

    public void setInputPrice(Double inputPrice) {
        this.inputPrice = inputPrice;
    }

    public Double getOutputPrice() {
        return outputPrice;
    }

    public void setOutputPrice(Double outputPrice) {
        this.outputPrice = outputPrice;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDefaultParams() {
        return defaultParams;
    }

    public void setDefaultParams(String defaultParams) {
        this.defaultParams = defaultParams;
    }

    public String getExtConfig() {
        return extConfig;
    }

    public void setExtConfig(String extConfig) {
        this.extConfig = extConfig;
    }

    public LocalDateTime getGmtCreate() {
        return gmtCreate;
    }

    public void setGmtCreate(LocalDateTime gmtCreate) {
        this.gmtCreate = gmtCreate;
    }

    public LocalDateTime getGmtModified() {
        return gmtModified;
    }

    public void setGmtModified(LocalDateTime gmtModified) {
        this.gmtModified = gmtModified;
    }
}


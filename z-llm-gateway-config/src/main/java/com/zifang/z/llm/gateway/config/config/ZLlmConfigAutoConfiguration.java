package com.zifang.z.llm.gateway.config.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;

/**
 * z-llm-config 自动配置.
 * <p>
 * 让 LlmConfigController 和 LlmConfigService 被 Spring 扫描注册.
 */
@Configuration
@ComponentScan(
        basePackages = {
                "com.zifang.z.llm.gateway.config.controller",
                "com.zifang.z.llm.gateway.config.service"
        },
        nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class
)
public class ZLlmConfigAutoConfiguration {
}
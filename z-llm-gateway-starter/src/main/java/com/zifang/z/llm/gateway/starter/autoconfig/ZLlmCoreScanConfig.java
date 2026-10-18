package com.zifang.z.llm.gateway.starter.autoconfig;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 扫描 z-llm-core 的 controller + service + registry + credential 包, 让 RestController 自动注册.
 */
@Configuration
@ComponentScan(basePackages = {
        "com.zifang.z.llm.gateway.core.controller",
        "com.zifang.z.llm.gateway.core.service",
        "com.zifang.z.llm.gateway.core.registry",
        "com.zifang.z.llm.gateway.core.credential"
})
public class ZLlmCoreScanConfig {
}
package com.zifang.z.llm.gateway.config.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zifang.z.llm.gateway.config.entity.LlmProvider;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LlmProviderMapper extends BaseMapper<LlmProvider> {
}
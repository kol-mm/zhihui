package com.aiknowledge.knowledge.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("mysql")
@MapperScan("com.aiknowledge.knowledge.mapper")
public class MyBatisConfig {
}

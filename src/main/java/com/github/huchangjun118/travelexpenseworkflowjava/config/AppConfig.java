package com.github.huchangjun118.travelexpenseworkflowjava.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ApprovalPolicyProperties.class, ExpenseProperties.class})
public class AppConfig {
}

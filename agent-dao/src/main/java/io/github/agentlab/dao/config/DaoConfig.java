package io.github.agentlab.dao.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * DAO 层配置。把实体扫描和 Repository 扫描显式声明在本模块，
 * 这样 agent-app 启动时能自动发现 agent-dao 里的 @Entity 与 Repository。
 */
@Configuration
@EntityScan(basePackages = "io.github.agentlab.dao.entity")
@EnableJpaRepositories(basePackages = "io.github.agentlab.dao.repository")
public class DaoConfig {
}

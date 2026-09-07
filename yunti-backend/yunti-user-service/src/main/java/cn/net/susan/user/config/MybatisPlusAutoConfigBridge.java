package cn.net.susan.user.config;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;

/**
 * MyBatis-Plus 装配兜底（Spring Boot 4 兼容）。
 *
 * <p>mybatis-plus-spring-boot3-starter 3.5.x 面向 Spring Boot 3，在 Spring Boot 4 下
 * 其自动配置可能不被触发，导致 SqlSessionFactory 缺失、Mapper 注入失败。
 * 参考 smart-recruit 接入方案：仅在容器中不存在 SqlSessionFactory 时手动装配，
 * 未来升级兼容版本后本类因 @ConditionalOnMissingBean 自动失效。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnMissingBean(SqlSessionFactory.class)
public class MybatisPlusAutoConfigBridge {

    @Bean
    public SqlSessionFactory sqlSessionFactory(
            DataSource dataSource,
            @Qualifier("mybatisPlusInterceptor") MybatisPlusInterceptor mybatisPlusInterceptor
    ) throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/mapper/**/*.xml"));

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);

        // 与应用配置保持一致：主键统一应用层雪花 ID，数据库不自增
        GlobalConfig globalConfig = new GlobalConfig();
        GlobalConfig.DbConfig dbConfig = new GlobalConfig.DbConfig();
        dbConfig.setIdType(IdType.INPUT);
        globalConfig.setDbConfig(dbConfig);
        factory.setGlobalConfig(globalConfig);

        if (mybatisPlusInterceptor != null) {
            factory.setPlugins(mybatisPlusInterceptor);
        }
        return factory.getObject();
    }

    @Bean
    public SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }
}

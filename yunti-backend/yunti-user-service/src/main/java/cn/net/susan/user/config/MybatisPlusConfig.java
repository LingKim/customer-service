package cn.net.susan.user.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置：分页、防全表更新/删除。
 *
 * <p>接入方案参考 smart-recruit（Spring Boot 4 + MyBatis-Plus 3.5.x）：
 * 自动配置存在兼容缺口时，由 MybatisPlusAutoConfigBridge 手动装配 SqlSessionFactory，
 * 本类负责提供插件链，二者配合保证 Mapper 可用。</p>
 */
@Configuration
public class MybatisPlusConfig {

    private static final Logger log = LoggerFactory.getLogger(MybatisPlusConfig.class);

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 分页插件（PostgreSQL 方言，单页硬限制 500）
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.POSTGRE_SQL);
        pagination.setMaxLimit(500L);
        pagination.setOverflow(true);
        interceptor.addInnerInterceptor(pagination);

        // 阻止全表更新/删除攻击
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());

        log.info("MyBatis-Plus 拦截器已配置：分页(max=500)、全表操作拦截");
        return interceptor;
    }
}

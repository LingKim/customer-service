package cn.net.susan.customer.mapper;

import cn.net.susan.customer.entity.QaAlert;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * qa_alert 实时质检告警 Mapper。
 */
@Mapper
public interface QaAlertMapper extends BaseMapper<QaAlert> {

    /**
     * 告警列表：处理状态、告警级别、规则类型、会话号、关键词、时间范围都可以组合过滤，按时间倒序。
     *
     * @param keyword 模糊匹配会话号 / 规则名 / 命中词 / 命中内容
     */
    List<QaAlert> selectAlerts(@Param("tenantCode") String tenantCode,
                               @Param("status") Integer status,
                               @Param("severity") Integer severity,
                               @Param("ruleType") Integer ruleType,
                               @Param("sessionNo") String sessionNo,
                               @Param("keyword") String keyword,
                               @Param("startTime") LocalDateTime startTime,
                               @Param("endTime") LocalDateTime endTime,
                               @Param("limit") int limit);

    /**
     * 告警总览：总数、待处理、严重、今日新增。
     */
    Map<String, Object> selectAlertStat(@Param("tenantCode") String tenantCode);

    /**
     * 冲突忽略插入：同一条消息 + 同一条规则只留一条告警（消息幂等重发不会重复弹窗）。
     */
    int insertIgnore(QaAlert alert);
}

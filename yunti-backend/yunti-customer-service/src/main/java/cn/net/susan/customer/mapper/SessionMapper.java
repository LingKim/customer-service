package cn.net.susan.customer.mapper;

import cn.net.susan.customer.entity.Session;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * session 会话 Mapper。
 */
@Mapper
public interface SessionMapper extends BaseMapper<Session> {

    /**
     * 坐席工作台的会话列表：带客户名与最后一条消息（SQL 见 SessionMapper.xml）。
     */
    List<Map<String, Object>> selectAgentSessions(
            @Param("tenantCode") String tenantCode,
            @Param("keyword") String keyword,
            @Param("agentId") Long agentId,
            @Param("unassigned") boolean unassigned,
            @Param("limit") int limit
    );

    /**
     * 访客复用会话：同一渠道下未结束的最近一条会话。
     */
    Session selectOpenSession(
            @Param("tenantCode") String tenantCode,
            @Param("channelId") Long channelId,
            @Param("customerId") Long customerId
    );

    int claimIfUnassigned(
            @Param("tenantCode") String tenantCode,
            @Param("sessionId") Long sessionId,
            @Param("agentId") Long agentId
    );

    int changeAssignment(
            @Param("tenantCode") String tenantCode,
            @Param("sessionId") Long sessionId,
            @Param("expectedAgentId") Long expectedAgentId,
            @Param("newAgentId") Long newAgentId,
            @Param("newStatus") int newStatus
    );
}

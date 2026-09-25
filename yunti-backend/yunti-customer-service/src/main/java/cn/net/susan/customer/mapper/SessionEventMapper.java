package cn.net.susan.customer.mapper;

import cn.net.susan.customer.entity.SessionEvent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * session_event 会话事件 Mapper。
 */
@Mapper
public interface SessionEventMapper extends BaseMapper<SessionEvent> {

    /**
     * 会话流转记录（转接 / 分配 / 关闭），SQL 见 SessionEventMapper.xml。
     */
    List<Map<String, Object>> selectBySession(
            @Param("tenantCode") String tenantCode,
            @Param("sessionId") Long sessionId,
            @Param("limit") int limit
    );

    /**
     * 坐席当前接待量（在线会话数），SQL 见 SessionEventMapper.xml。
     */
    List<Map<String, Object>> selectAgentWorkload(@Param("tenantCode") String tenantCode);
}

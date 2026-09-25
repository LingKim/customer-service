package cn.net.susan.customer.mapper;

import cn.net.susan.customer.entity.SessionMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * session_message 会话消息 Mapper。
 */
@Mapper
public interface SessionMessageMapper extends BaseMapper<SessionMessage> {

    /**
     * 按会话读取对话记录（时间正序，SQL 见 SessionMessageMapper.xml）。
     */
    List<SessionMessage> selectDialog(
            @Param("tenantCode") String tenantCode,
            @Param("sessionId") Long sessionId,
            @Param("limit") int limit
    );

    /**
     * 会话消息分页（倒序取一页，供聊天记录回溯）。
     */
    List<SessionMessage> selectBySession(
            @Param("tenantCode") String tenantCode,
            @Param("sessionId") Long sessionId,
            @Param("beforeId") Long beforeId,
            @Param("visibleTo") Integer visibleTo,
            @Param("limit") int limit
    );

    /**
     * 按客户端消息号取一条（幂等判重）。
     */
    SessionMessage selectByClientMsgNo(
            @Param("tenantCode") String tenantCode,
            @Param("sessionId") Long sessionId,
            @Param("clientMsgNo") String clientMsgNo
    );

    /**
     * 增量补拉：取序号大于 afterSeq 的消息（重连后补齐断线期间漏掉的消息）。
     */
    List<SessionMessage> selectAfterSeq(
            @Param("tenantCode") String tenantCode,
            @Param("sessionId") Long sessionId,
            @Param("afterSeq") long afterSeq,
            @Param("visibleTo") Integer visibleTo,
            @Param("limit") int limit
    );
}

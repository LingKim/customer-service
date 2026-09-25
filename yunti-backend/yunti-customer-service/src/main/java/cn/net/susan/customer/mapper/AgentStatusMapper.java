package cn.net.susan.customer.mapper;

import cn.net.susan.customer.entity.AgentStatus;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * agent_status 坐席状态 Mapper。
 */
@Mapper
public interface AgentStatusMapper extends BaseMapper<AgentStatus> {

    /**
     * 路由候选：在线坐席 + 当前接待量（按负载升序、状态停留时间升序）。
     *
     * @param skillGroupId 指定技能组时只取组内成员；传 null 表示不限技能组（排队升级用）
     */
    List<Map<String, Object>> selectCandidates(
            @Param("tenantCode") String tenantCode,
            @Param("skillGroupId") Long skillGroupId
    );
}

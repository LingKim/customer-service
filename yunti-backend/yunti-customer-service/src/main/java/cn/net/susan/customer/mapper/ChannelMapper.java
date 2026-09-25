package cn.net.susan.customer.mapper;

import cn.net.susan.customer.entity.Channel;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ChannelMapper extends BaseMapper<Channel> {
    @Select("SELECT * FROM channel WHERE id = #{id} AND tenant_code = #{tenantCode} AND is_deleted = FALSE FOR UPDATE")
    Channel findOwnedForUpdate(@Param("id") long id, @Param("tenantCode") String tenantCode);
}

package cn.net.susan.tenant.mapper;

import cn.net.susan.tenant.entity.Tenant;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TenantMapper extends BaseMapper<Tenant> {
    @Select("SELECT nextval('tenant_code_seq')")
    long nextCodeSequence();
}

package cn.net.susan.user.mapper;

import cn.net.susan.user.entity.TenantMember;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * tenant_member Mapper。
 */
@Mapper
public interface TenantMemberMapper extends BaseMapper<TenantMember> {

    /**
     * 冲突忽略插入（并发建立成员关系时不会中断事务），SQL 见 TenantMemberMapper.xml。
     */
    int insertIgnore(TenantMember member);
}

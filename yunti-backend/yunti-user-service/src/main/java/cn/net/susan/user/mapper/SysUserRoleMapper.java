package cn.net.susan.user.mapper;

import cn.net.susan.user.entity.SysUserRole;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * sys_user_role Mapper。
 */
@Mapper
public interface SysUserRoleMapper extends BaseMapper<SysUserRole> {

    /**
     * 冲突忽略插入（并发绑定角色时不会中断事务），SQL 见 SysUserRoleMapper.xml。
     */
    int insertIgnore(SysUserRole userRole);
}

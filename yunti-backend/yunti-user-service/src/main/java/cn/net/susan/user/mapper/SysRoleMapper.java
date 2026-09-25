package cn.net.susan.user.mapper;

import cn.net.susan.user.entity.SysRole;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * sys_role Mapper。
 */
@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {

    /**
     * 冲突忽略插入（并发初始化角色时不会中断事务），SQL 见 SysRoleMapper.xml。
     */
    int insertIgnore(SysRole role);
}

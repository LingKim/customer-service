package cn.net.susan.user.mapper;

import cn.net.susan.user.entity.SysUser;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * sys_user 用户表 Mapper。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 按登录账号查询（手机号 / 邮箱 / 用户编号任一匹配），SQL 见 SysUserMapper.xml。
     */
    SysUser findByAccount(@Param("account") String account);

    /**
     * 判断手机号或邮箱是否已被注册。
     */
    int countByPhoneOrEmail(@Param("phone") String phone, @Param("email") String email);

    List<Map<String, Object>> selectEnterpriseMembers(@Param("tenantCode") String tenantCode);
}

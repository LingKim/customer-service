package cn.net.susan.user.mapper;

import cn.net.susan.user.entity.LoginLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * login_log 登录记录 Mapper（只追加）。
 */
@Mapper
public interface LoginLogMapper extends BaseMapper<LoginLog> {
}

package cn.net.susan.tenant.mapper;

import cn.net.susan.tenant.entity.Enterprise;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * enterprise 企业表 Mapper。
 */
@Mapper
public interface EnterpriseMapper extends BaseMapper<Enterprise> {

    /**
     * 按申请人用户 ID 查询企业（注册草稿 creator 存申请人用户 ID），SQL 见 EnterpriseMapper.xml。
     */
    Enterprise findByApplicantId(@Param("applicantId") String applicantId);

    /**
     * 查询指定日期前缀下最新企业编码（含逻辑删除，避免编码复用冲突）。
     */
    String findLatestCodeByDatePrefix(@Param("datePrefix") String datePrefix);
}

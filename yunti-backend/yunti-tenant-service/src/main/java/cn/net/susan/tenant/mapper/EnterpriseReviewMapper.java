package cn.net.susan.tenant.mapper;

import cn.net.susan.tenant.entity.EnterpriseReview;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 企业审核记录 Mapper。
 */
@Mapper
public interface EnterpriseReviewMapper extends BaseMapper<EnterpriseReview> {

    EnterpriseReview findLatestByEnterpriseId(@Param("enterpriseId") long enterpriseId);

    int nextVersionNo(@Param("enterpriseId") long enterpriseId);
}

package cn.net.susan.tenant.mapper;

import cn.net.susan.tenant.entity.EnterpriseReview;
import cn.net.susan.tenant.admin.dto.ReviewPageVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 企业审核记录 Mapper。
 */
@Mapper
public interface EnterpriseReviewMapper extends BaseMapper<EnterpriseReview> {

    EnterpriseReview findLatestByEnterpriseId(@Param("enterpriseId") long enterpriseId);

    int nextVersionNo(@Param("enterpriseId") long enterpriseId);

    EnterpriseReview findByIdForUpdate(@Param("reviewId") long reviewId);

    IPage<ReviewPageVO> selectReviewAdminPage(Page<ReviewPageVO> page,
                                               @Param("status") Integer status,
                                               @Param("keyword") String keyword);
}

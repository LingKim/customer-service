package cn.net.susan.customer.mapper;

import cn.net.susan.customer.entity.CsatRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;

/**
 * csat_record 满意度评价 Mapper。
 */
@Mapper
public interface CsatRecordMapper extends BaseMapper<CsatRecord> {

    /**
     * 某个客户的满意度均值（客户 360 画像里的"满意度"）。
     *
     * <p>没有评价时返回 null——前端显示"—"而不是"0 分"，这两件事不能混。</p>
     */
    BigDecimal selectCustomerAverage(
            @Param("tenantCode") String tenantCode,
            @Param("customerId") Long customerId
    );
}

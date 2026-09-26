package cn.net.susan.customer.mapper;

import cn.net.susan.customer.entity.CustomerTagDef;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * customer_tag_def 标签定义 Mapper。
 */
@Mapper
public interface CustomerTagDefMapper extends BaseMapper<CustomerTagDef> {

    /**
     * 标签体系列表：带"这个标签现在打在多少个客户身上"。
     *
     * <p>为什么要在 SQL 里数：标签体系页要按"用得多的排前面"给运营看，
     * 如果一个个标签去数，12 个标签就是 12 次查询——列表页最忌讳这个。</p>
     */
    List<Map<String, Object>> selectTagUsage(@Param("tenantCode") String tenantCode);

    /**
     * 有哪些租户配了"能自动跑"的规则标签（定时任务按租户逐个扫）。
     *
     * <p>"能自动跑"= 启用 + 未删除 + 类型是规则自动 + 配了指标与阈值。
     * 只配了文字说明（rule_metric 为空）的标签不参与，避免"看起来是规则标签其实没人跑"。</p>
     */
    List<String> selectTenantsWithRunnableRules();
}

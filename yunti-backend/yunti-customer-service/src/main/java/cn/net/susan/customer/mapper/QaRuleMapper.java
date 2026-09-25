package cn.net.susan.customer.mapper;

import cn.net.susan.customer.entity.QaRule;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface QaRuleMapper extends BaseMapper<QaRule> {

    /**
     * 冲突忽略插入（避免并发初始化产生同名规则），SQL 见 QaRuleMapper.xml。
     */
    int insertIgnore(QaRule rule);
}

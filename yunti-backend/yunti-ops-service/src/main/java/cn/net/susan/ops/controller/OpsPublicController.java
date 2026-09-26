package cn.net.susan.ops.controller;

import cn.net.susan.common.api.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

// 官网公开接口：把「官网配置」下发给官网项目。
//
// 官网（yunti-official）是独立部署的静态站点，打开时拉这份配置来覆盖默认文案
// （hero 标题、统计数字、定价说明、FAQ、页脚版权…）。当前配置来自
// resources/site-config.json（版本化、可 diff、可评审）；等平台侧「官网配置」页做好，
// 把数据源换成 DB 即可，官网侧一行都不用改。
//
// 为什么放 ops：官网配置属于平台运营范畴（与租户数据无关），不该混在业务服务里。
@RestController
@RequestMapping("/api/ops/public")
public class OpsPublicController {

    private static final Logger log = LoggerFactory.getLogger(OpsPublicController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 官网配置（原样返回 JSON 对象，官网按字段覆盖默认文案） */
    @GetMapping("/site-config")
    public ApiResponse<Map<String, Object>> siteConfig() {
        try (InputStream in = new ClassPathResource("site-config.json").getInputStream()) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return ApiResponse.ok(MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
            }));
        } catch (Exception e) {
            // 配置读不到不能让官网白屏：返回空对象，官网会用静态默认文案
            log.warn("读取 site-config.json 失败：{}", e.getMessage());
            return ApiResponse.ok(Map.of());
        }
    }
}

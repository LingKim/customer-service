package cn.net.susan.common.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 服务启动成功后的专业日志摘要：服务名、端口、环境、耗时、健康检查地址等。
 * 由各服务通过 @SpringBootApplication(scanBasePackages = "cn.net.susan") 自动装配。
 */
@Component
public class StartupLogger {

    private static final Logger log = LoggerFactory.getLogger(StartupLogger.class);

    @Value("${spring.application.name:yunti-service}")
    private String appName;

    @Value("${server.port:0}")
    private int port;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        Environment env = event.getApplicationContext().getEnvironment();
        String[] profiles = env.getActiveProfiles();
        String profile = profiles.length == 0 ? "default" : String.join(",", profiles);
        long startupMs = event.getApplicationContext().getStartupDate();
        Duration elapsed = Duration.ofMillis(System.currentTimeMillis() - startupMs);

        String line = "  ┌──────────────────────────────────────────────────────────────┐";
        String sep = "  ├──────────────────────────────────────────────────────────────┤";
        String end = "  └──────────────────────────────────────────────────────────────┘";
        String bar = "    Yunti 智能客服平台 · 服务启动成功";
        String name = pad("服务名称", appName);
        String portS = pad("服务端口", String.valueOf(port));
        String envS = pad("运行环境", profile);
        String timeS = pad("启动耗时", elapsed.toMillis() / 1000.0 + " 秒");
        String jdkS = pad("JDK 版本", System.getProperty("java.version", "unknown"));
        String health = pad("健康检查", "http://localhost:" + port + "/actuator/health");
        String api = pad("业务入口", "http://localhost:" + port + "/api/" + appName.replace("yunti-", "").replace("-service", ""));

        log.info("\n" + bar + "\n" + line + "\n" + sep + "\n"
                + row(name) + row(portS) + row(envS) + row(timeS) + row(jdkS)
                + sep + "\n" + row(health) + row(api) + end);
    }

    private static String pad(String label, String value) {
        return label + ":" + " ".repeat(Math.max(1, 10 - displayWidth(label))) + value;
    }

    private static String row(String content) {
        int width = 60;
        int len = displayWidth(content);
        String pad = len >= width ? "" : " ".repeat(width - len);
        return "  │ " + content + pad + " │\n";
    }

    private static int displayWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += s.charAt(i) > 127 ? 2 : 1;
        }
        return w;
    }
}

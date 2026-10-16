package com.kaede.uspace.common.dev;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 后端启动完成时弹一条 Windows 桌面通知（2026-10-16 加）。
 *
 * <p><b>它解决开发期的哪件事</b>：{@code mvn spring-boot:run} 起完之后，
 * 终端里那一大片 Spring 日志不会告诉你「现在可以去点页面了」——
 * devtools 热重启之后更是如此（改一行代码它就自己在后台重启一遍），人只能凭感觉等。
 * 通知弹出来，就代表 8080 真的在听了。
 *
 * <p><b>只在「本机开发运行」时弹</b>，三条判断叠起来：
 * <ol>
 *   <li>配置开关 {@code uspace.dev.startup-toast.enabled}（默认开）</li>
 *   <li>操作系统是 Windows —— 通知是 PowerShell 弹的，别的系统直接跳过</li>
 *   <li><b>classpath 上有 devtools</b>：它只随 {@code spring-boot:run} 出现，
 *       打包好的 jar 里被 exclude、测试的 classpath 上也没有 ——
 *       于是「跑测试弹一屏通知」「线上服务器弹通知」都不会发生</li>
 * </ol>
 *
 * <p>⚠️ <b>弹不出来绝不能影响启动</b>：通知是给开发者的便利，任何失败都只记一条
 * debug 日志。这里也刻意<b>不等</b>那个 PowerShell 进程（{@code waitFor}）——
 * 它要活到气泡消失（十来秒），等它就等于把启动拖慢十几秒。
 */
@Component
public class StartupToastNotifier {

    private static final Logger log = LoggerFactory.getLogger(StartupToastNotifier.class);

    /**
     * devtools 的类名。
     *
     * <p>它<b>只</b>在 {@code spring-boot:run} 的 classpath 上：devtools 是
     * {@code developmentOnly} 依赖，打包时被 exclude，测试的 classpath 里也没有。
     * 所以拿它当「这次是不是本机开发运行」的判据，比读 profile 更准 ——
     * 开发时没人会记得加 {@code -Dspring.profiles.active=dev}。
     */
    private static final String DEVTOOLS_CLASS = "org.springframework.boot.devtools.restart.RestartLauncher";

    /** 气泡停留的秒数。PowerShell 进程要活到它消失，所以两处用同一个数。 */
    private static final int TOAST_SECONDS = 10;

    private final boolean enabled;
    private final String port;

    public StartupToastNotifier(@Value("${uspace.dev.startup-toast.enabled:true}") boolean enabled,
                                @Value("${server.port:8080}") String port) {
        this.enabled = enabled;
        this.port = port;
    }

    /**
     * 上下文就绪后弹通知。
     *
     * <p>用 {@link ApplicationReadyEvent} 而不是 {@code CommandLineRunner}：
     * 前者是「应用真的可以对外服务了」的那一刻（Tomcat 已在监听），
     * 后者更早，那时弹出来的提示可能抢在端口就绪之前。
     *
     * @param event 就绪事件（只用来取启动耗时）
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady(ApplicationReadyEvent event) {
        if (!enabled || !isWindows() || !ClassUtils.isPresent(DEVTOOLS_CLASS, null)) {
            return;
        }
        long millis = System.currentTimeMillis() - event.getApplicationContext().getStartupDate();
        try {
            // -EncodedCommand 收的是 UTF-16LE 的 Base64：中文提示语直接写在 Java 源里，
            // 不必和 Windows 命令行的代码页打交道（用 -Command 传中文会在部分机器上变问号）
            String encoded = Base64.getEncoder()
                    .encodeToString(script(millis).getBytes(StandardCharsets.UTF_16LE));
            new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
                    // 不接管道：接了就有人得一直读它，而这里没人读（子进程自己活到气泡消失）
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            log.info("[开发] 已弹出「启动完成」桌面通知（端口 {}，启动耗时 {} ms）——"
                    + "不想看到就把 uspace.dev.startup-toast.enabled 设为 false", port, millis);
        } catch (Exception e) {
            log.debug("[开发] 桌面通知没弹出来（不影响启动）：{}", e.getMessage());
        }
    }

    /**
     * 拼那段 PowerShell。
     *
     * <p>用 {@code NotifyIcon} 的气泡而不是 WinRT 的 Toast：后者要有注册过的
     * AppID 才会显示，脚本里现造一个（AUMID）在部分机器上会被静默丢掉 ——
     * 而「弹不出来」这件事本身没有任何反馈，最难查。气泡方案不需要任何注册。
     *
     * @param millis 启动耗时（毫秒）
     * @return PowerShell 脚本
     */
    private String script(long millis) {
        String text = String.format("uspace · 端口 %s · 启动耗时 %.1f 秒", port, millis / 1000.0);
        return "Add-Type -AssemblyName System.Windows.Forms;"
                + "Add-Type -AssemblyName System.Drawing;"
                + "$n = New-Object System.Windows.Forms.NotifyIcon;"
                + "$n.Icon = [System.Drawing.SystemIcons]::Information;"
                + "$n.Visible = $true;"
                + "$n.ShowBalloonTip(" + TOAST_SECONDS * 1000 + ", '后端启动完成喵', '" + text + "',"
                + " [System.Windows.Forms.ToolTipIcon]::Info);"
                + "Start-Sleep -Seconds " + (TOAST_SECONDS + 1) + ";"
                + "$n.Dispose()";
    }

    /** 是不是 Windows。判据与别处一致：读 {@code os.name}，不引第三方库。 */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}

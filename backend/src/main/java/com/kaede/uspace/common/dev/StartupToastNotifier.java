package com.kaede.uspace.common.dev;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

/**
 * 后端启动完成时弹一条桌面通知（2026-10-16 加，同日改成 AWT 托盘）。
 *
 * <p><b>它解决开发期的哪件事</b>：{@code mvn spring-boot:run} 起完之后，
 * 终端里那一大片 Spring 日志不会告诉你「现在可以去点页面了」——
 * devtools 热重启之后更是如此（改一行代码它就自己在后台重启一遍），人只能凭感觉等。
 * 通知弹出来，就代表 8080 真的在听了。
 *
 * <p><b>只在「本机开发运行」时弹</b>，三条判断叠起来：
 * <ol>
 *   <li>配置开关 {@code uspace.dev.startup-toast.enabled}（默认开）</li>
 *   <li>操作系统是 Windows —— 托盘是桌面能力，服务器上直接跳过</li>
 *   <li><b>classpath 上有 devtools</b>：它只随 {@code spring-boot:run} 出现，
 *       打包好的 jar 里被 exclude、测试的 classpath 上也没有 ——
 *       于是「跑测试弹一屏通知」「线上服务器弹通知」都不会发生</li>
 * </ol>
 *
 * <p>⚠️ <b>弹不出来绝不能影响启动</b>：通知是给开发者的便利，任何失败都只记一条
 * debug 日志。
 *
 * <h3>⚠️ 为什么从 PowerShell 改成了 AWT 托盘（2026-10-16）</h3>
 *
 * <p>第一版是让 Java 起一个 {@code powershell.exe}、用 {@code NotifyIcon} 弹气泡。
 * 那段命令同时具备三个特征：<b>隐藏窗口</b>（{@code -WindowStyle Hidden}）、
 * <b>Base64 编码的命令</b>（{@code -EncodedCommand}）、以及<b>由一个非交互进程突然拉起</b>。
 * 这三条叠在一起，正是杀软眼里「隐蔽的 PowerShell 执行」的标准画像 ——
 * 于是每次热重启都弹一次告警：<b>那不是误报，是那种写法本身就长这样</b>。
 *（用 {@code -EncodedCommand} 本来是为了绕开命令行代码页、让中文提示语不变问号，
 * 结果换来一个更麻烦的问题。）
 *
 * <p>现在改成 AWT 的 {@link SystemTray}：<b>纯 Java、进程内、不起任何子进程</b>，
 * 既没有脚本也没有编码命令，杀软无从告警。代价是托盘图标要自己收
 *（气泡由系统显示固定时长，图标却会一直挂着），所以另起一条守护线程等一会儿再移除。
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

    /** 气泡停留的秒数，也是托盘图标留多久 */
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
            show(millis);
            log.info("[开发] 已弹出「启动完成」桌面通知（端口 {}，启动耗时 {} ms）——"
                    + "不想看到就把 uspace.dev.startup-toast.enabled 设为 false", port, millis);
        } catch (Exception e) {
            // 包括 HeadlessException：没桌面会话的机器上托盘用不了，跳过即可
            log.debug("[开发] 桌面通知没弹出来（不影响启动）：{}", e.getMessage());
        }
    }

    /**
     * 用系统托盘弹气泡。
     *
     * <p>⚠️ <b>Spring Boot 默认把 {@code java.awt.headless} 设成 true</b>
     * （服务端就该如此），而托盘是 AWT 的桌面能力 —— 这里临时改成 false，用完还原。
     * 若在之前已有别的代码初始化过 AWT，这次改动不会生效，
     * {@link SystemTray#isSupported()} 会给出 false，我们安静跳过：这只是开发期的便利。
     *
     * @param millis 启动耗时（毫秒）
     * @throws Exception 托盘不可用或添加图标失败
     */
    private void show(long millis) throws Exception {
        String previous = System.getProperty("java.awt.headless");
        System.setProperty("java.awt.headless", "false");
        try {
            if (!SystemTray.isSupported()) {
                log.debug("[开发] 这个环境没有系统托盘，跳过启动通知");
                return;
            }
            TrayIcon icon = new TrayIcon(dot(), "uspace 后端");
            icon.setImageAutoSize(true);
            SystemTray.getSystemTray().add(icon);
            icon.displayMessage("后端启动完成喵",
                    String.format("uspace · 端口 %s · 启动耗时 %.1f 秒", port, millis / 1000.0),
                    TrayIcon.MessageType.INFO);
            scheduleRemoval(icon);
        } finally {
            if (previous == null) {
                System.clearProperty("java.awt.headless");
            } else {
                System.setProperty("java.awt.headless", previous);
            }
        }
    }

    /**
     * 到点把托盘图标摘掉。
     *
     * <p>气泡是系统画、到点自己消失，但<b>图标得我们自己收</b> ——
     * 不收的话任务栏里会一直挂着一个小圆点，重启几次就挂一排。
     *
     * <p>用守护线程而不是 {@code Timer}/{@code ScheduledExecutor}：它不参与 JVM 的关闭流程，
     * 绝不会因为「还有个定时任务没跑完」让 {@code Ctrl+C} 停不下来。
     *
     * @param icon 刚加进去的托盘图标
     */
    private void scheduleRemoval(TrayIcon icon) {
        Thread remover = new Thread(() -> {
            try {
                Thread.sleep((TOAST_SECONDS + 1) * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                SystemTray.getSystemTray().remove(icon);
            } catch (Exception e) {
                log.debug("[开发] 移除托盘图标失败（无关紧要）：{}", e.getMessage());
            }
        }, "uspace-startup-toast");
        remover.setDaemon(true);
        remover.start();
    }

    /**
     * 现画一个小圆点当托盘图标。
     *
     * <p>不引图片资源：一个点用代码画比多一个文件更好维护，也免得将来打包时漏了它 ——
     * 那种错的表现是「托盘里一片空白」，同样不报错。
     *
     * @return 16×16 的图标
     */
    private static Image dot() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x6C, 0x5C, 0xE7));
        g.fillOval(1, 1, 14, 14);
        g.dispose();
        return image;
    }

    /** 是不是 Windows。判据与别处一致：读 {@code os.name}，不引第三方库。 */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}

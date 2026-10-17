package com.kaede.uspace.qqbot;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NapCat 掉线邮件提醒。
 *
 * <p><b>为什么需要它</b>：NapCat 跑在云端 Linux 上，掉线（QQ 被挤下线、
 * 容器挂了、隧道断了）时后端只知道「WebSocket 断开了」，而运营者往往几小时后
 * 才发现群里没人应。邮件是唯一一条<b>不依赖 QQ 本身</b>的告警通路 ——
 * 掉线时 NapCat 自己发不了群消息，用群消息告警是死循环。
 *
 * <h3>两道防抖</h3>
 * <ol>
 *   <li><b>延迟发送</b>：断开后先等 {@code offline-delay-seconds}（默认 60 秒），
 *       期间若重连成功就作废。NapCat 容器重启、网络抖动都会断几秒又连回来，
 *       不防抖的话一天能收到几十封。</li>
 *   <li><b>冷却窗口</b>：{@code cooldown-minutes}（默认 10 分钟）内最多发一封，
 *       避免「连不上 → 重连 → 又断」的反复报警刷屏。</li>
 * </ol>
 *
 * <p>本类<b>始终注册</b>（不加 @ConditionalOnProperty）：开关放在字段里判断，
 * 这样 {@link OneBotWebSocketHandler} 不必为「邮件开着没开着」写两套构造。
 * 真正的邮件发送靠 {@link ObjectProvider} 拿 {@link JavaMailSender} ——
 * 没配 {@code spring.mail.host} 时容器里根本没有这个 bean，此时只记日志不发送。
 */
@Slf4j
@Component
public class QqbotOfflineNotifier {

    /** 时间格式：邮件正文里给人看的 */
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 拿 JavaMailSender 用 provider 而不是直接注入：没配 SMTP 时它不存在 */
    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    /** 总开关 */
    private final boolean enabled;

    /** 发件人（一般是 SMTP 登录账号本身） */
    private final String from;

    /** 收件人，多个用逗号分隔 */
    private final String to;

    /** 断开后等多久才发（秒），期间重连则作废 */
    private final long delaySeconds;

    /** 两封邮件之间的最小间隔（毫秒） */
    private final long cooldownMillis;

    /** 本次是否有待发送的提醒（true 表示计时器在跑） */
    private final AtomicBoolean pending = new AtomicBoolean(false);

    /** 上次真正发出邮件的时刻（毫秒时间戳），0 表示本次进程内还没发过 */
    private final AtomicLong lastSentAt = new AtomicLong(0L);

    /** 单线程调度器：延迟复查 + 心跳超时巡检 */
    private final ScheduledExecutorService scheduler;

    /** OneBot 客户端：心跳巡检时用它确认 WS 是否还在 */
    private final OneBotClient client;

    /** 上一次收到心跳的时刻（毫秒）；进程启动时初始化，避免一开机就报「很久没心跳」 */
    private final AtomicLong lastHeartbeatAt = new AtomicLong(System.currentTimeMillis());

    /** 心跳间隔 30 秒，超过这么久没心跳就认为 NapCat 失联 */
    private static final long HEARTBEAT_TIMEOUT_MS = 90_000L;

    public QqbotOfflineNotifier(ObjectProvider<JavaMailSender> mailSenderProvider,
                                OneBotClient client,
                                @Value("${uspace.qqbot.mail.enabled:false}") boolean enabled,
                                @Value("${uspace.qqbot.mail.from:}") String from,
                                @Value("${uspace.qqbot.mail.to:}") String to,
                                @Value("${uspace.qqbot.mail.offline-delay-seconds:60}") long delaySeconds,
                                @Value("${uspace.qqbot.mail.cooldown-minutes:10}") long cooldownMinutes) {
        this.mailSenderProvider = mailSenderProvider;
        this.client = client;
        this.enabled = enabled;
        this.from = from == null ? "" : from.trim();
        this.to = to == null ? "" : to.trim();
        this.delaySeconds = Math.max(5L, delaySeconds);
        this.cooldownMillis = Math.max(0L, cooldownMinutes) * 60_000L;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "qqbot-offline-notifier");
            t.setDaemon(true);
            return t;
        });
        if (enabled) {
            log.info("[QQ机器人] 掉线邮件提醒已启用 -> {}（延迟 {} 秒，冷却 {} 分钟）",
                    this.to.isEmpty() ? "(未配置收件人)" : this.to, this.delaySeconds, cooldownMinutes);
        }
        // 心跳巡检：每 30 秒看一眼「WS 还连着、但超过 90 秒没心跳」——
        // 这正是 QQ 被挤下线的情形（NapCat 进程还在，WS 未必会断，但 QQ 已经没了）
        this.scheduler.scheduleAtFixedRate(this::checkHeartbeat, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * 收到 NapCat 心跳时调用。
     *
     * <p>心跳里带 {@code status.online}：QQ 被挤下线时它是 false，
     * 而 WS 连接可能依然保持着 —— 只看 WebSocket 关闭事件会漏掉这种情况。
     *
     * @param online QQ 是否在线
     */
    public void onHeartbeat(boolean online) {
        lastHeartbeatAt.set(System.currentTimeMillis());
        if (online) {
            onReconnected();
            return;
        }
        // QQ 明确掉线：直接走「延迟 + 冷却」那条路，不必等心跳超时
        onDisconnected("QQ 已掉线（heartbeat status.online=false）");
    }

    /**
     * 心跳巡检：WS 还连着、但很久没心跳 → 视为 NapCat 失联。
     *
     * <p>与 {@link #onHeartbeat(boolean)} 互补：那个靠 NapCat 主动说「我掉线了」，
     * 这个兜住「NapCat 干脆不说话了」（进程假死、QQ 客户端挂了但 WS 还在）。
     */
    private void checkHeartbeat() {
        try {
            if (!enabled || !client.isConnected()) {
                return;
            }
            long silent = System.currentTimeMillis() - lastHeartbeatAt.get();
            if (silent > HEARTBEAT_TIMEOUT_MS) {
                onDisconnected("超过 " + (silent / 1000) + " 秒没有收到心跳（NapCat 可能失联）");
            }
        } catch (Exception e) {
            // 巡检自己不能把线程搞死
            log.warn("[QQ机器人] 心跳巡检异常", e);
        }
    }

    /**
     * 连接断开时调用：安排一次「延迟复查」。
     *
     * <p>已经有一次在排队就不再排 —— 同一场掉线只该对应一封邮件。
     *
     * @param reason 断开原因（关闭码 + 说明），写进邮件正文
     */
    public void onDisconnected(String reason) {
        if (!enabled || to.isEmpty()) {
            return;
        }
        // 冷却窗口内不重复报警
        long now = System.currentTimeMillis();
        if (now - lastSentAt.get() < cooldownMillis) {
            log.debug("[QQ机器人] 掉线（冷却中，不发邮件）：{}", reason);
            return;
        }
        // 已有一次待发
        if (!pending.compareAndSet(false, true)) {
            return;
        }
        log.warn("[QQ机器人] 掉线，{} 秒后若无重连将发邮件提醒：{}", delaySeconds, reason);
        scheduler.schedule(() -> fireIfStillDown(reason), delaySeconds, TimeUnit.SECONDS);
    }

    /**
     * 重连成功时调用：取消还在排队的提醒。
     *
     * <p>对应的是「断开 30 秒又连上」这种最常见的抖动。
     */
    public void onReconnected() {
        // 连上就重置心跳计时：否则「进程启动已久 + 刚连上」会被误判成心跳超时，
        // 表现为每次重连都先报一次假警、几秒后又自己取消（2026-10-17 实测踩到）
        lastHeartbeatAt.set(System.currentTimeMillis());
        if (pending.compareAndSet(true, false)) {
            log.info("[QQ机器人] 已重连，取消待发的掉线邮件");
        }
    }

    /**
     * 延迟到点后的复查：期间若已重连，pending 会被 {@link #onReconnected()} 清掉。
     *
     * @param reason 断开原因
     */
    private void fireIfStillDown(String reason) {
        if (!pending.compareAndSet(true, false)) {
            return;
        }
        lastSentAt.set(System.currentTimeMillis());
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            log.warn("[QQ机器人] 掉线提醒已触发，但容器里没有 JavaMailSender（检查 spring.mail.host 配置）");
            return;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            if (!from.isEmpty()) {
                msg.setFrom(from);
            }
            msg.setTo(to.split("\\s*,\\s*"));
            msg.setSubject("[uspace] NapCat 掉线提醒");
            msg.setText(buildBody(reason));
            sender.send(msg);
            log.info("[QQ机器人] 掉线邮件已发送 -> {}", to);
        } catch (Exception e) {
            // 发信失败不能影响主流程：这只是告警，不是业务
            log.error("[QQ机器人] 掉线邮件发送失败", e);
        }
    }

    /**
     * 拼邮件正文。
     *
     * @param reason 断开原因
     * @return 纯文本正文
     */
    private String buildBody(String reason) {
        return "NapCat 掉线提醒"
                + System.lineSeparator()
                + System.lineSeparator()
                + "时间：" + LocalDateTime.now().format(TIME_FMT)
                + System.lineSeparator()
                + "断开原因：" + (reason == null || reason.isBlank() ? "（未提供）" : reason)
                + System.lineSeparator()
                + "已等待 " + delaySeconds + " 秒仍未重连。"
                + System.lineSeparator()
                + System.lineSeparator()
                + "排查建议："
                + System.lineSeparator()
                + "  1. 云端服务器是否可达（ssh 能不能连上）"
                + System.lineSeparator()
                + "  2. docker ps 看 napcat 容器是否还在跑"
                + System.lineSeparator()
                + "  3. docker logs --tail 100 napcat 看 QQ 是否被挤下线"
                + System.lineSeparator()
                + "  4. 本机的 SSH 隧道窗口是否还开着"
                + System.lineSeparator()
                + System.lineSeparator()
                + "—— uspace 自动提醒";
    }

    /** 容器关闭时收掉线程池，避免 devtools 热重启留下一堆僵尸线程 */
    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}

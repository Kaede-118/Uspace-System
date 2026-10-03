package com.kaede.uspace.qqbot;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * QQ 机器人的配置（模块 11）。
 *
 * <p><b>两个开关各管一件事</b>，与支付那两个开关是同一套思路：
 * <ul>
 *   <li>{@link #enabled} —— <b>要不要接入</b>。默认关：不配这个模块时，
 *       应用照常启动，与加它之前没有任何区别</li>
 *   <li>{@link #accessToken} —— <b>凭什么信你</b>。它是与 NapCat 共享的口令，
 *       两边必须一致</li>
 * </ul>
 *
 * <p>⚠️ <b>{@code enabled=true} 但令牌为空会让应用拒绝启动</b>，
 * 校验不在这里（那里只管 WARN），在 {@link QqbotConfig} 里。
 * 理由：令牌为空而端点照常注册 = <b>任何能访问本服务端口的人都能连上来冒充 NapCat</b>，
 * 把在店人数、消费金额查个遍。这是安全事故，不是功能故障，该在启动时就挡住 ——
 * 与「{@code JWT_SECRET} 为空拒绝启动」同源。
 */
@Data
@Slf4j
@Component
@ConfigurationProperties(prefix = "uspace.qqbot")
public class QqbotProperties {

    /**
     * 总开关。默认关。
     *
     * <p>取默认关而不是默认开，是为了<b>不改变现有部署的行为</b>：
     * 这个模块要一条常驻的 WebSocket 连接与一个对外可达的端点，
     * 没打算用它的环境不该凭空多出这两样。
     */
    private boolean enabled = false;

    /**
     * 反向 WebSocket 的端点路径。
     *
     * <p>⚠️ <b>改它要同步改两个地方</b>：{@code QqbotConfig} 里的注册用的是这个值（自动跟随），
     * 但 {@code SecurityConfig.PUBLIC_PATHS} 里那条是<b>写死的字面路径</b> ——
     * 不改的话握手会被过滤器链拦成 401，而 NapCat 那边只会表现为「连不上」。
     * 这与 {@code uspace.upload.url-prefix} 和 {@code /uploads/**} 是同一类耦合，
     * 项目里已有一处先例。
     */
    private String wsPath = "/onebot/v11/ws";

    /**
     * 访问令牌。与 NapCat 侧 {@code websocketClients} 配置里的 {@code token} 必须一致。
     *
     * <p>走环境变量 {@code QQBOT_ACCESS_TOKEN} 注入（与 {@code MYSQL_PASSWORD}、
     * {@code JWT_SECRET} 同一纪律）。本仓库是公开的，任何写在配置文件里的口令
     * 都等于公开的。
     *
     * <p>⚠️ <b>比对时两边的空白字符不做裁剪</b> —— 这不是实现疏忽，
     * OneBot 规范原文写着「这里 {@code <access_token>} 不需要对两边的空白字符进行裁剪」。
     * 顺手加个 {@code trim()} 会让「NapCat 里填了首尾带空格的口令」这种情形
     * 变成永远连不上，而排查方向会一路歪到网络和防火墙上去。
     */
    private String accessToken = "";

    /**
     * 允许响应查询指令的群号。默认空。
     *
     * <p><b>空 = 谁都不理</b>，这是刻意的默认值：填错的后果是「机器人不说话」，
     * 而不填就全放开的后果是「随便拉个群都能查到本店今天赚了多少」。
     * 两种失败方式的代价不对称，所以默认值取安全的那一侧。
     *
     * <p>这个列表同时也是<b>播报的收信人</b>（不含金额的那一版），
     * 见 {@link #adminGroups}。
     */
    private List<Long> allowedGroups = List.of();

    /**
     * 额外接收<b>含金额</b>播报的群号（店主群）。默认空。
     *
     * <p><b>默认空 = 所有播报都不含金额</b>，同样是安全的那一侧。
     * 群里每个人都看得到群消息，而「张三这单花了 40」属于个人消费信息 ——
     * 设计文档那条「播报内容按群分级」要防的就是它。
     *
     * <p><b>与 {@link #allowedGroups} 是「升级」而非「并列」关系</b>：
     * 播报时给 {@code allowedGroups} 里的群发不含金额版，
     * 给 {@code adminGroups} 里的群发含金额版；<b>两个列表的交集只收一条</b>
     * （含金额的那条），不会重复发两次。
     * 因此 {@code adminGroups} 里的群可以不在 {@code allowedGroups} 里 ——
     * 那样它收得到播报、但用不了查询指令，是个合法组合。
     */
    private List<Long> adminGroups = List.of();

    /** 播报配置 */
    private Broadcast broadcast = new Broadcast();

    /**
     * 生效的交互群 = {@link #allowedGroups} ∪ {@link #adminGroups}。
     *
     * <p>⚠️ <b>取并集而不是只认 allowedGroups</b>：只写进店主群、忘了写进白名单，
     * 是一个太容易犯的错，而它的后果是「加了群却一条消息都收不到」——
     * 犯这个错的人不会觉得是配置问题，只会觉得机器人坏了。
     * 并集让心智模型变简单：<b>admin 严格更强</b>，写进哪儿都生效，
     * 写进 admin 的额外多看到金额。
     *
     * @return 生效群号的快照。返回不可变副本，遍历时不会被别人改到
     */
    public Set<Long> effectiveGroups() {
        Set<Long> groups = new LinkedHashSet<>(allowedGroups);
        groups.addAll(adminGroups);
        return Collections.unmodifiableSet(groups);
    }

    /**
     * 判断一个群能不能与机器人交互（发指令、收播报）。
     *
     * @param groupId 群号，可为 null
     * @return 在生效群里返回 true；null 或不在其中都返回 false
     */
    public boolean isGroupAllowed(Long groupId) {
        return groupId != null && effectiveGroups().contains(groupId);
    }

    /**
     * 判断一个群能不能看到消费金额。
     *
     * <p>⚠️ <b>金额的可见性只由这一个方法决定，绝不允许从别处推断</b> ——
     * 尤其是「这条事件带了金额所以显示它」那种写法：事件永远带金额，
     * 那样写等于全员可见，<b>而它照样能跑通全部演示</b>。
     *
     * @param groupId 群号，可为 null
     * @return 配置在店主群里返回 true
     */
    public boolean isAmountVisible(Long groupId) {
        return groupId != null && adminGroups.contains(groupId);
    }

    /**
     * 配置是否完整到可以接入。
     *
     * <p>供 {@link QqbotConfig} 在装配时校验，见类注释。判据只有一条：
     * 启用了就必须有令牌。
     *
     * @return 令牌非空白返回 true
     */
    public boolean hasUsableToken() {
        return accessToken != null && !accessToken.isBlank();
    }

    /**
     * 启动时把配置状况说一遍。
     *
     * <p>⚠️ <b>「已禁用」也要打，且打 INFO 不打 DEBUG</b>：{@code @ConditionalOnProperty}
     * 是<b>字符串精确比较</b>，配成 {@code True} / {@code 1} / {@code yes} 都不匹配，
     * 于是端点是 404、NapCat 一直重连、而后端日志一片安静。
     * 这一行无条件打出来，能让「为什么没反应」在第一时间被回答。
     *
     * <p>不止一条的两种情况只 WARN 不抛 —— 它们都是<b>合法降级</b>：
     * 群白名单空着，机器人就是连上但不理任何人；店主群空着，播报就不含金额。
     * 会抛的那种（启用了却没有令牌）在 {@link QqbotConfig} 里，两类问题分开处理。
     */
    @PostConstruct
    public void logStartupSummary() {
        if (!enabled) {
            log.info("[QQ机器人] 未启用（uspace.qqbot.enabled=false）");
            return;
        }
        log.info("[QQ机器人] 已启用 端点={} 生效群={} 金额可见群={}",
                wsPath, allowedGroups, adminGroups);
        if (allowedGroups.isEmpty()) {
            log.warn("[QQ机器人] 没有配置任何群（uspace.qqbot.allowed-groups），"
                    + "机器人不会响应任何群消息 —— 这是为了不把经营数据漏给任意群，不是故障");
        }
        if (adminGroups.isEmpty()) {
            log.info("[QQ机器人] 未配置店主群（uspace.qqbot.admin-groups），播报将不含消费金额");
        }
    }

    /** 播报配置 */
    @Data
    public static class Broadcast {

        /**
         * 播报开关。默认开。
         *
         * <p>与 {@link #enabled} 分开，是因为它们关掉的层次不同：
         * 总开关关掉是<b>整条链路都不接</b>（连不上、也查不了），
         * 这个关掉是<b>照常连、照常能查，但别主动往群里推</b>。
         * 演示或调试时想安静一点，关这个就够。
         */
        private boolean enabled = true;
    }
}

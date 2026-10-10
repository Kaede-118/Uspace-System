package com.kaede.uspace.qqbot;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
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

    /**
     * 是否允许两条<b>写指令</b>（{@code fw开门}、{@code fw结账}）。
     *
     * <p><b>默认关</b>，与 {@link #enabled} 默认关是同一思路：
     * 只读是安全的那一侧，而「机器人开着、却没人知道它还能建单计费」是危险的另一侧。
     *
     * <p>注意它与 {@link Broadcast#isEnabled()} 是<b>两个独立的开关，各管一件事</b>：
     * 这个管「能不能执行」，播报那个管「执行完有没有人看得见」。
     * 而写指令要求<b>两者都开</b> —— 理由见 {@code QqWriteCommandService} 类注释。
     */
    private boolean writeEnabled = false;

    /**
     * 在店名册是否渲染成图片（{@code uspace.qqbot.instore-image-enabled}）。
     *
     * <p>默认 true：{@code fw在店} 回一张卡片图，比一屏文字醒目、
     * 也不容易被群聊刷走。渲染与发送任何一步失败（本机没有中文字体、
     * 连接问题）都会<b>自动回落文字版</b> —— 所以关掉它只是为了
     * 「不想发图」这一种情形，不是缺字体时的兜底（那个渲染器自己会发现）。
     */
    private boolean instoreImageEnabled = true;

    /**
     * 本人自查询的金额是否显示（{@code fw看看自己} 与 {@code fw结账} 的回复里）。
     *
     * <p>⚠️ <b>它与 {@link #isAmountVisible} 各管一件事，不要混，更不要互相推断</b>：
     * <ul>
     *   <li>{@link #isAmountVisible} 管的是<b>播报</b> —— 系统主动推给全群的消息里
     *       能不能出现消费金额，必须按群分级（只有店主群看得到）</li>
     *   <li>本开关管的是<b>本人自查询</b> —— 用户自己发指令问自己的消费。
     *       内容是他自己的、触发也是他主动的，所以默认开</li>
     * </ul>
     * 但两者最终都出现在同一个群里（回复也是群消息）：一条 {@code fw结账} 的回复，
     * 群里所有人都看得见金额。<b>「默认开」本身是一种取舍，用户已知情并选择</b>
     * （2026-10-04）。
     */
    private boolean selfAmountVisible = true;

    /**
     * {@code fw开门} 时要不要把固定限时密码<b>私聊</b>给本人。默认关（2026-10-09 起）。
     *
     * <p>关掉之后群里只给一次性密码，固定密码的去处只剩网页端的「查看密码」——
     * 群里那句话也相应改成指路网页端。
     *
     * <p><b>为什么默认关</b>：私聊这条路的送达<b>没有任何保证</b>
     * （机器人不是对方好友时是异步失败的，而 {@code sendPrivateMessage} 返回 true
     * 只代表帧发出去了）。配了却收不到时，用户看到的是群里一句「已私聊发你」
     * 和一个空空的私聊窗口 —— 那比一开始就不承诺更糟。
     */
    private boolean privatePasscodeEnabled = false;

    /**
     * 同一 QQ 的写指令冷却时长（如 {@code 5s}）。{@code 0} 表示不限制。
     *
     * <p>防的是连点：每次 {@code fw开门} 都会消耗 1 次门锁云额度，
     * 而群里的连发是可能的。
     *
     * <p>⚠️ <b>它不是安全防线</b> —— 真正的幂等来自订单模块的
     * 「上一单没结清不给开新单」（{@code selectUnsettledByUser}）。
     * 这个冷却只是省额度，挡住的那几秒里用户本来也做不成什么。
     *
     * <p><b>值取 5 秒</b>（2026-10-04 从 20 秒改下来）：商品下单
     * （{@code fw买个可乐}）同样吃这道冷却，而「连着买两样东西」是常态 ——
     * 20 秒会把第二条挡成一句「操作太频繁」，用户只会以为机器人出毛病了。
     * 5 秒够挡住手抖，又不至于让人等。
     */
    private Duration writeCommandCooldown = Duration.ofSeconds(5);

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
        // ⚠️ 写指令与播报的状态无条件打出来：它们是「为什么 fw开门 没反应」的
        // 第一手答案，而这类「配置写错了但没人知道」的静默失败最费排查时间
        log.info("[QQ机器人] 写指令（开门/结账/买商品/改库存）={} 播报={} 自查询金额={} 固定密码私聊={}",
                writeEnabled ? "已启用" : "未启用",
                broadcast.isEnabled() ? "已启用" : "未启用",
                selfAmountVisible ? "显示" : "不显示",
                privatePasscodeEnabled ? "已启用" : "未启用");
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

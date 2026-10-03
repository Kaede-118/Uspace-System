package com.kaede.uspace.qqbot;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.device.DeviceService;
import com.kaede.uspace.device.dto.EquipmentTypeVo;
import com.kaede.uspace.order.InstoreService;
import com.kaede.uspace.order.dto.InstoreUserVo;
import com.kaede.uspace.qqbot.protocol.OneBotEvent;
import com.kaede.uspace.user.QqVerifyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 处理群消息指令（模块 11）。
 *
 * <p>它是「群消息进来」这条链路的终点：校验来源、去重、解析、查数据、回复。
 *
 * <h3>处理顺序不能换</h3>
 *
 * <ol>
 *   <li><b>只处理群消息</b> —— 私聊、通知、请求一律不管</li>
 *   <li><b>过滤机器人自己发的</b> —— 防的是 NapCat 打开 {@code reportSelfMessage}
 *       之后「机器人回复自己、再触发一次回复」的死循环</li>
 *   <li><b>群白名单</b> —— 不在名单里的群<b>静默忽略</b>，连一个字都不回。
 *       回一句「本群未授权」等于告诉对方「这里有个机器人，去别的群试试」</li>
 *   <li><b>幂等</b> —— 见 {@link MessageDedup}</li>
 *   <li><b>解析并执行</b></li>
 * </ol>
 *
 * <p>把白名单放在解析之前是有意的：未授权的群不该能从响应差异里
 * 反推出「哪些指令是存在的」。
 *
 * <h3>依赖方向</h3>
 *
 * <p>本类调 {@link InstoreService}（模块 8）与 {@link DeviceService}（模块 4），
 * 都是<b>只读查询</b>。反方向（那两个包 import 本包）是禁止的，见包注释。
 *
 * <p>{@code DeviceService} 那条依赖是为「偏好」而引的：{@code InstoreUserVo.preference}
 * 里存的是 {@code PAIPAI} 这样的字典 code，中文名要另外映射 ——
 * 那个 VO 的注释写着「中文名由前端映射，后端为此引入依赖边不划算」，
 * 但那条理由针对的是 {@code order → device}（会与既有的 {@code device → space} 交织）。
 * 本包在最下游，加这条边不产生任何环，所以这里直接映射，把中文名送给群。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "uspace.qqbot.enabled", havingValue = "true")
public class QqCommandService {

    /**
     * 一次最多列多少人。
     *
     * <p>群消息有长度上限，而名单是随人数线性增长的 —— 不设上限的话，
     * 某天店里人多起来，这条消息会被协议端拒绝或者截断，
     * 表现是「机器人发了一串没头没尾的东西」。当前门店规模远到不了这个数，
     * 加它是为了那个上限永远不必被想起。
     */
    private static final int MAX_LISTED = 30;

    private final QqbotProperties properties;
    private final MessageDedup dedup;
    private final OneBotClient client;
    private final InstoreService instoreService;
    private final QqVerifyService qqVerifyService;
    private final DeviceService deviceService;

    public QqCommandService(QqbotProperties properties,
                            MessageDedup dedup,
                            OneBotClient client,
                            InstoreService instoreService,
                            QqVerifyService qqVerifyService,
                            DeviceService deviceService) {
        this.properties = properties;
        this.dedup = dedup;
        this.client = client;
        this.instoreService = instoreService;
        this.qqVerifyService = qqVerifyService;
        this.deviceService = deviceService;
    }

    /**
     * 处理一条入站事件。
     *
     * <p>非群消息、机器人自己发的、未授权群的、重复的 —— 四种情况都直接返回，
     * 不产生任何回复。这是「静默优先」的安排：机器人在群里说话本身是有成本的
     * （刷屏、打扰），只有确定该说话时才说。
     *
     * @param event 入站事件。调用方保证非 null
     */
    public void handle(OneBotEvent event) {
        if (!event.isGroupMessage()) {
            return;
        }
        Long groupId = event.getGroupId();
        if (!properties.isGroupAllowed(groupId)) {
            log.warn("[QQ机器人] 收到未授权群的消息，已忽略 groupId={} sender={}",
                    groupId, event.getUserId());
            return;
        }
        if (dedup.isDuplicate(event.getMessageId())) {
            return;
        }
        QqCommand command = QqCommandParser.parse(event.getRawMessage());

        // 自己这一侧发的消息（含「运营者用同一个 QQ 在手机上发指令」，
        // 两者在协议字段上无法区分）：只有【解析器认不出来】才忽略。
        //
        // ⚠️ 判据是「解析结果是不是闲聊」，**不是**「有没有 / 前缀」—— 后者是本类
        // 踩过的一个真实故障：6 位验证码不带斜杠，于是同号登录 bot 时，
        // 运营者在手机上发的验证码被当成「机器人自己发的话」挡掉，
        // 表现是取码正常、发到群里也正常，但**验证永远通不过，且两端都没有任何报错**。
        //
        // 换成这个判据之后，回环仍然被切断：机器人发出去的那几种文本
        // （`pong`、在店名册、帮助、验证结果）没有一种能解析成指令，
        // 全都落到 IGNORE 上。而 `/ping`、6 位数字这类真指令一律放行。
        if (event.isSelfSent() && command.kind() == QqCommand.Kind.IGNORE) {
            log.debug("[QQ机器人] 自己发的消息且解析为闲聊，忽略（防回环）");
            return;
        }
        switch (command.kind()) {
            case PING -> client.sendGroupMessage(groupId, "pong");
            case INSTORE -> replyInstore(groupId);
            case HELP -> replyHelp(groupId);
            case VERIFY_CODE -> replyVerifyCode(groupId, event, command.argument());
            case UNKNOWN_COMMAND -> client.sendGroupMessage(groupId,
                    "没认出这条指令。发 /帮助 看看能问什么");
            case IGNORE -> {
                // 闲聊，按设计静默
            }
        }
    }

    // ==================================================================
    // 各指令的回复
    // ==================================================================

    /**
     * 回复当前在店名册。
     *
     * <p>数据源是模块 8 的 {@link InstoreService#listInstoreUsers()} ——
     * 与 Web 端「在店用户」页<b>同一个方法</b>，所以两处的口径必然一致，
     * 不会出现「群里说 3 人、网页说 2 人」。
     *
     * <p><b>不含任何金额</b>：那个方法的返回体本来就没有金额字段，
     * 这不是本类克制的结果，而是那道边界本来就画在 {@code InstoreUserVo} 上。
     *
     * @param groupId 目标群号
     */
    private void replyInstore(Long groupId) {
        BizResult<List<InstoreUserVo>> result = instoreService.listInstoreUsers();
        if (!result.isSuccess() || result.getData() == null) {
            log.error("[QQ机器人] 查在店名册失败：{}",
                    result.resolveMessage() == null ? "未知原因" : result.resolveMessage());
            client.sendGroupMessage(groupId, "查不到在店信息，稍后再试");
            return;
        }

        List<InstoreUserVo> users = result.getData();
        if (users.isEmpty()) {
            client.sendGroupMessage(groupId, "现在店里没人。");
            return;
        }

        // 一次取回字典，逐条拼装时用 —— 放进循环里查就是 N 次数据库往返
        Map<String, String> preferenceLabels = loadPreferenceLabels();

        StringBuilder text = new StringBuilder("当前 ").append(users.size()).append(" 人在店：\n");
        int listed = Math.min(users.size(), MAX_LISTED);
        for (int i = 0; i < listed; i++) {
            text.append(i + 1).append(". ")
                    .append(formatUser(users.get(i), preferenceLabels))
                    .append('\n');
        }
        if (users.size() > listed) {
            text.append("……还有 ").append(users.size() - listed).append(" 人\n");
        }
        client.sendGroupMessage(groupId, text.toString().stripTrailing());
    }

    /**
     * 回复指令列表。
     *
     * <p>顺带把「验证码怎么用」写进去 —— 那件事没有别的入口能告诉用户，
     * 而注册页只会说「把验证码发到群里」，用户仍可能不知道该发到哪个群。
     *
     * @param groupId 目标群号
     */
    private void replyHelp(Long groupId) {
        String text = """
                可用指令
                /ping —— 看看机器人在不在
                /在店 或 /看看里面 —— 看看店里现在有谁
                /帮助 —— 显示这条消息

                注册时网页会给你一个 6 位验证码，发到群里就能把 QQ 号绑到账号上。""";
        client.sendGroupMessage(groupId, text);
    }

    /**
     * 处理疑似验证码的消息。
     *
     * <p>⚠️ <b>这两个参数的分工是整个 QQ 验证的地基</b>：
     * 发送者 QQ 取自<b>事件</b>（NapCat 推来的，用户伪造不了），
     * 验证码取自<b>消息文本</b>（群里所有人都看得见，本身不是凭证）。
     * {@link QqVerifyService#confirm} 按发送者 QQ 查表 ——
     * 改成「拿验证码遍历所有记录找匹配」的话，谁看到那串数字谁就能验证，
     * <b>而那种写法一样能跑通全部演示</b>。
     *
     * <p>绝大多数 6 位数消息都不是验证码（群友随口发的数字），
     * 此时 {@code confirm} 返回的 reply 是空串，本方法据此<b>保持沉默</b>。
     *
     * @param groupId 目标群号
     * @param event   入站事件，发送者 QQ 从它取
     * @param code    解析出的 6 位数字
     */
    private void replyVerifyCode(Long groupId, OneBotEvent event, String code) {
        QqVerifyService.ConfirmResult result =
                qqVerifyService.confirm(String.valueOf(event.getUserId()), code);
        if (!result.reply().isEmpty()) {
            client.sendGroupMessage(groupId, result.reply());
        }
    }

    // ==================================================================
    // 文案组装
    // ==================================================================

    /**
     * 把一位在店顾客拼成一行。
     *
     * @param user             名册上的一行
     * @param preferenceLabels 类型 code → 中文名
     * @return 形如 {@code 张三 · 1 小时 20 分 · 全天月卡 · 偏好 拍拍机}
     */
    private static String formatUser(InstoreUserVo user, Map<String, String> preferenceLabels) {
        StringBuilder line = new StringBuilder(displayName(user));
        line.append(" · ").append(formatDuration(user.getStayMinutes()));
        if (user.getCardTypeLabel() != null && !user.getCardTypeLabel().isBlank()) {
            line.append(" · ").append(user.getCardTypeLabel());
        }
        String preference = formatPreference(user.getPreference(), preferenceLabels);
        if (preference != null) {
            line.append(" · 偏好 ").append(preference);
        }
        return line.toString();
    }

    /**
     * 取展示用的名字。
     *
     * <p>昵称为空时用「用户{ID}」兜底而不是跳过这一行 ——
     * 名册上有几个人就得说几个人，把查不到资料的静默抹掉，
     * 会让「3 人在店」下面只列出 2 个名字。
     *
     * @param user 名册行
     * @return 展示名
     */
    private static String displayName(InstoreUserVo user) {
        String nickname = user.getNickname();
        if (nickname == null || nickname.isBlank()) {
            return "用户" + user.getUserId();
        }
        return nickname;
    }

    /**
     * 把在店时长拼成人话。
     *
     * @param minutes 分钟数，可为 null
     * @return 形如 {@code 35 分钟} / {@code 1 小时 20 分} / {@code 刚进店}
     */
    private static String formatDuration(Integer minutes) {
        if (minutes == null || minutes <= 0) {
            return "刚进店";
        }
        if (minutes < 60) {
            return minutes + " 分钟";
        }
        int hours = minutes / 60;
        int rest = minutes % 60;
        return rest == 0 ? hours + " 小时" : hours + " 小时 " + rest + " 分";
    }

    /**
     * 把偏好 code 串翻成中文名串。
     *
     * <p>⚠️ <b>映射不出来的 code 直接丢弃，不原样显示</b>：字典里查不到说明
     * 那个类型已被停用（{@code listSelectableTypes} 只给启用中的），
     * 把 {@code PAIPAI} 这种内部代号甩到群里，用户只会以为系统出错了。
     * 代价是「设了 3 个偏好只显示 2 个」，而那种情况本来就少见。
     *
     * @param preference 逗号分隔的 code 串，可为 null
     * @param labels     code → 中文名
     * @return 顿号分隔的中文名；一个都映射不出时返回 null
     */
    private static String formatPreference(String preference, Map<String, String> labels) {
        if (preference == null || preference.isBlank()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (String code : preference.split(",")) {
            String name = labels.get(code.trim());
            if (name != null) {
                names.add(name);
            }
        }
        return names.isEmpty() ? null : String.join("、", names);
    }

    /**
     * 取类型字典的 code → 中文名映射。
     *
     * <p>每次查询都取一次，<b>不缓存</b>：这张表只有几行、改动的频率以月计，
     * 而缓存会引入「管理员改了类型名、群里还是旧名字」这种要等到有人注意到
     * 才能发现的不一致。
     *
     * @return 映射；取字典失败时返回空表（此时偏好一栏整体不显示，不影响名册本身）
     */
    private Map<String, String> loadPreferenceLabels() {
        BizResult<List<EquipmentTypeVo>> result = deviceService.listSelectableTypes();
        if (!result.isSuccess() || result.getData() == null) {
            log.warn("[QQ机器人] 取设备类型字典失败，本次名册不显示偏好");
            return Map.of();
        }
        Map<String, String> labels = new HashMap<>();
        for (EquipmentTypeVo type : result.getData()) {
            labels.put(type.getCode(), type.getName());
        }
        return labels;
    }
}

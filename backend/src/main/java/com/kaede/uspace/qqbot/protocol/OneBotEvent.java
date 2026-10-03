package com.kaede.uspace.qqbot.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * OneBot v11 的入站事件（协议映射层）。
 *
 * <p><b>这一层只做一件事：把 NapCat 推过来的 JSON 映射成 Java 对象。</b>
 * 业务判断（是不是群消息、要不要处理）以方法形式挂在这里，装配与查询在别处。
 *
 * <p><b>只声明用得到的字段</b>：OneBot 的事件字段远不止这些（匿名信息、字体、
 * 消息段数组……），全声明一遍的代价是每加一个字段都要重新核对一份 SPA 文档，
 * 而收益为零 —— 用不到的字段映射进来也只是躺着。
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} 保证多出来的字段不会让反序列化失败。
 * Spring Boot 默认就关掉了那个失败开关，这里是显式写出来防「有人改了全局 Jackson 配置」。
 *
 * <p><b>刻意不解析 {@code message} 数组，只读 {@code raw_message}</b>：
 * 前者受 NapCat 的 {@code messagePostFormat} 配置影响（{@code string} / {@code array} 两种形态），
 * 解析它要另引一套 CQ 码或消息段的处理；而机器人只认「纯文本指令」，
 * {@code raw_message} 恰好就是那个东西，且不受配置影响。
 *
 * <p><b>同一个连接回来的不止事件</b> —— Action 的响应也从这里来，
 * 它没有 {@code post_type}，见 {@link OneBotActionResponse}。
 * 两者靠「有没有 {@code post_type}」分流，见 {@code OneBotWebSocketHandler}。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class OneBotEvent {

    /** {@code post_type} 的取值：消息事件（别人发的）*/
    public static final String POST_TYPE_MESSAGE = "message";

    /**
     * {@code post_type} 的取值：<b>自己那一侧</b>发出去的消息。
     *
     * <p>NapCat 的适配器配置里有个 {@code reportSelfMessage}，默认 {@code false} ——
     * 不开的话自己发的消息根本不会推上来。打开之后它们以这个类型出现，
     * <b>与普通的 {@code message} 分开</b>，所以「谁发的」这件事在协议层就有答案。
     *
     * <p>它要解决的具体场景：<b>运营者用同一个 QQ 在手机上发指令</b>，
     * 让电脑上跑着的这个机器人替他回复。从协议角度看，那条消息与
     * 「机器人自己刚发出去的话」是同一类 —— 对事件的区分解决不了问题，
     * 要靠 {@code QqCommandService} 里那条「只认带 {@code /} 前缀的」来防回环。
     */
    public static final String POST_TYPE_MESSAGE_SENT = "message_sent";

    /** {@code post_type} 的取值：元事件（心跳、生命周期）*/
    public static final String POST_TYPE_META_EVENT = "meta_event";

    /** {@code message_type} 的取值：群消息 */
    public static final String MESSAGE_TYPE_GROUP = "group";

    /**
     * 上报类型：{@code message} / {@code meta_event} / {@code notice} / {@code request}。
     *
     * <p><b>它也是「这是事件还是 Action 响应」的判据</b> —— 响应里没有这个字段。
     */
    private String postType;

    /** 消息类型：{@code group} / {@code private}。仅 {@code postType=message} 时有值 */
    private String messageType;

    /** 消息子类型：{@code normal} / {@code anonymous} / {@code notice}。骨架不区分，留作排查线索 */
    private String subType;

    /**
     * 消息 ID。
     *
     * <p>幂等去重的键。OneBot 规范里是 int32，但<b>这里用 Long 接</b> ——
     * 拿 int 接一个超出范围的数会静默截断，而截断后的 ID 会与别的消息撞车，
     * 表现为「某条指令莫名其妙被忽略」，且没有任何报错。
     */
    private Long messageId;

    /** 群号。仅群消息有值。群白名单校验读的就是它 */
    private Long groupId;

    /** 发送者 QQ 号。QQ 验证按它查表，绝不按消息里的验证码遍历查找 */
    private Long userId;

    /** 原始消息文本。指令从这里解析，不走 {@code message} 数组（见类注释）*/
    private String rawMessage;

    /** 发送者信息。字段可能缺失，不可作为权限依据 —— 见 {@link OneBotSender} 类注释 */
    private OneBotSender sender;

    /**
     * 收到该事件的机器人自己的 QQ 号。
     *
     * <p>用于过滤「机器人自己发的消息」—— 正常情况下 NapCat 不会把机器人自己发的
     * 消息推回来（它有个 {@code reportSelfMessage} 开关，默认关），但那个开关一旦被打开，
     * 机器人就会回复自己、再触发一次回复，<b>形成死循环把群刷爆</b>。
     * 一道显式判断的成本是零，所以加。
     */
    private Long selfId;

    /** 事件时间戳（秒）。仅用于日志，业务时间一律取服务端的「现在」*/
    private Long time;

    /** 元事件类型：{@code heartbeat} 心跳 / {@code lifecycle} 生命周期。仅 {@code postType=meta_event} 时有值 */
    private String metaEventType;

    /**
     * 是不是一条群消息。
     *
     * <p>三个条件缺一不可：是消息事件、是群消息、而且有群号。
     * 最后一条是防御性的 —— 规范说 {@code group_id} 在群消息里必有，
     * 但少了它后面所有按群号做的判断都会退化成「拿 null 去比」。
     *
     * <p><b>{@code message_sent} 也算</b>：自己那一侧发的消息同样要能触发指令
     * （运营者在手机上发 {@code /ping}，电脑上的机器人回复），
     * 是否处理由 {@link #isSelfSent()} 与调用方的判据共同决定。
     *
     * @return 是群消息返回 true
     */
    public boolean isGroupMessage() {
        return (POST_TYPE_MESSAGE.equals(postType) || POST_TYPE_MESSAGE_SENT.equals(postType))
                && MESSAGE_TYPE_GROUP.equals(messageType)
                && groupId != null;
    }

    /**
     * 是不是元事件（心跳、生命周期）。
     *
     * <p><b>心跳必须被显式识别并安静地丢弃</b>：NapCat 默认每 30 秒发一条，
     * 不识别的话每 30 秒就会在日志里刷一条「不认识的事件类型」，
     * 真正的异常反而被淹没在里面。
     *
     * @return 是元事件返回 true
     */
    public boolean isMetaEvent() {
        return POST_TYPE_META_EVENT.equals(postType);
    }

    /**
     * 这条消息是不是「我们自己这一侧」发出去的。
     *
     * <p>⚠️ <b>它不等于「机器人自动回复的那句话」</b>。运营者用同一个 QQ 在手机上
     * 发指令时，{@code user_id} 与 {@code self_id} 也是同一个号 ——
     * 协议的字段区分不了这两者。<b>能区分的是消息内容</b>：
     * 机器人自动回复的都是「pong」「当前 3 人在店」这类不含指令前缀的话，
     * 所以调用方拿 {@link #isSelfSent()} 配上「有没有 {@code /} 前缀」一起判断，
     * 就能既响应运营者的指令、又不让机器人回复自己。
     *
     * <p>两个判据是「或」的关系：标准做法是看事件类型（NapCat 开了
     * {@code reportSelfMessage} 会给 {@code message_sent}），
     * 但某些实现不开那个开关也会把发送者标成自己 —— 两种都认，代价是零。
     *
     * @return 是自己这一侧发出去的消息时返回 true
     */
    public boolean isSelfSent() {
        return POST_TYPE_MESSAGE_SENT.equals(postType)
                || (userId != null && userId.equals(selfId));
    }
}

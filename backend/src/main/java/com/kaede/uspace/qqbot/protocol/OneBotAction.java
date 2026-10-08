package com.kaede.uspace.qqbot.protocol;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 出站的动作请求（OneBot v11 协议映射层）。
 *
 * <p>OneBot 的调用约定是：应用端把 <b>Action</b> 当普通 WebSocket 文本帧发出去，
 * 协议端执行完再把 <b>响应</b>从<b>同一条连接</b>发回来。
 * 也就是说收发的两个方向共用一条连接，接收端必须能把两者分开 ——
 * 判据是「有没有 {@code post_type}」，见 {@link OneBotActionResponse}。
 *
 * <p><b>{@code echo} 是应用端自己填、协议端原样带回来的字段</b>，
 * 唯一用途是把响应与请求对上。骨架阶段不等待响应（发出去就返回），
 * 但依然带上它 —— 日志里能看到「哪一次发送失败了」，
 * 而不带的话只剩一句「发送失败」，连着失败三次也分不清是哪三条消息。
 */
@Data
public class OneBotAction {

    /** 发送群消息的 Action 名 */
    public static final String ACTION_SEND_GROUP_MSG = "send_group_msg";

    /** 发送私聊消息的 Action 名 */
    public static final String ACTION_SEND_PRIVATE_MSG = "send_private_msg";

    /** 动作名，如 {@code send_group_msg} */
    private String action;

    /** 动作参数。不同 action 的参数不同，用 Map 装不必为每个 action 建一个类 */
    private Map<String, Object> params;

    /** 回显字段，协议端原样返回。用途见类注释 */
    private String echo;

    /**
     * 构造一个「发送群消息」的动作。
     *
     * <p>⚠️ <b>{@code auto_escape} 取 true，这是安全考量而非洁癖</b>：
     * 它为 false（默认值）时，{@code message} 里的 <b>CQ 码会被解析</b>。
     * 而本项目的播报文案里含<b>用户昵称</b>，昵称是用户自己填的任意文本 ——
     * 起名叫 {@code [CQ:at,qq=12345]} 就能让机器人替他在群里 @ 人。
     * 转义之后整段按纯文本发送，这个注入面就没有了。
     *
     * <p>代价是<b>暂时不能主动 @ 人</b>（那需要 CQ 码）。目前没有这个需求，
     * 将来要加时应当改用「消息段数组」而非关掉转义 —— 后者会把上面那个洞一起打开。
     *
     * @param groupId 群号
     * @param text    消息文本，按纯文本发送
     * @param echo    回显串，供日志追踪，见类注释
     * @return 可直接序列化发送的动作对象
     */
    public static OneBotAction sendGroupMessage(Long groupId, String text, String echo) {
        Map<String, Object> params = new HashMap<>(4);
        params.put("group_id", groupId);
        params.put("message", text);
        params.put("auto_escape", true);

        OneBotAction action = new OneBotAction();
        action.setAction(ACTION_SEND_GROUP_MSG);
        action.setParams(params);
        action.setEcho(echo);
        return action;
    }

    /**
     * 构造一个「发送群消息并 @ 某人」的动作。
     *
     * <p><b>与纯文本重载的唯一区别是 {@code message} 传段数组而不是字符串</b> ——
     * 这是主动 @ 人的唯一途径：{@code auto_escape=true} 会把 CQ 码转义成字面文本，
     * 所以 {@code [CQ:at,qq=…]} 那种写法发出去只会显示成一行字符。
     *
     * <p>⚠️ <b>不要为了 @ 人而把 {@code auto_escape} 关掉</b>：那是上面那个重载
     * 取 true 的全部理由 —— 播报文案里含<b>用户昵称</b>，而昵称是用户自己填的
     * 任意文本，起名叫 {@code [CQ:at,qq=12345]} 就能让机器人替他在群里 @ 人。
     * <b>段数组没有这个洞</b>：数组里的 text 段按定义就是纯文本，不解析 CQ 码。
     *
     * <p>段数组形态下<b>刻意不传 {@code auto_escape}</b>：它只对字符串形式的
     * {@code message} 有效，传了会让人误以为这里也在转义。这里的安全性由
     * 「用段而不是用字符串」保证，不由那个参数保证。
     *
     * @param groupId 群号
     * @param atQq    要 @ 的 QQ 号；为 null 或空串时退化成只发文本
     *                （用于「这个人没绑 QQ」的情形）
     * @param text    @ 之后的文本，按纯文本段发送
     * @param echo    回显串，供日志追踪
     * @return 可直接序列化发送的动作对象
     */
    public static OneBotAction sendGroupMessageWithAt(Long groupId, String atQq, String text, String echo) {
        List<OneBotMessageSegment> segments = new ArrayList<>(2);
        if (atQq != null && !atQq.isBlank()) {
            segments.add(OneBotMessageSegment.at(atQq));
        }
        segments.add(OneBotMessageSegment.text(text));

        Map<String, Object> params = new HashMap<>(4);
        params.put("group_id", groupId);
        params.put("message", segments);

        OneBotAction action = new OneBotAction();
        action.setAction(ACTION_SEND_GROUP_MSG);
        action.setParams(params);
        action.setEcho(echo);
        return action;
    }

    /**
     * 构造一个「发送私聊消息」的动作。
     *
     * <p>目前唯一的用途：群指令 {@code /开门} 把<b>固定的限时密码</b>单独发给本人 ——
     * 群消息所有人可见，密码不能出现在那里。
     *
     * <p>{@code auto_escape} 同样取 true，理由与群消息那份完全相同（见上）：
     * 私聊的文本里会带订单号与门店名，同样是拼出来的字符串。
     *
     * @param userId 目标 QQ 号
     * @param text   消息文本，按纯文本发送
     * @param echo   回显串，供日志追踪
     * @return 可直接序列化发送的动作对象
     */
    public static OneBotAction sendPrivateMessage(Long userId, String text, String echo) {
        Map<String, Object> params = new HashMap<>(4);
        params.put("user_id", userId);
        params.put("message", text);
        params.put("auto_escape", true);

        OneBotAction action = new OneBotAction();
        action.setAction(ACTION_SEND_PRIVATE_MSG);
        action.setParams(params);
        action.setEcho(echo);
        return action;
    }
}

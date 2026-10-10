package com.kaede.uspace.qqbot.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OneBot v11 协议映射的单元测试。
 *
 * <p><b>纯单测，只用一个 {@code ObjectMapper}</b> —— 被测的就是「JSON 进、对象出」这一件事。
 *
 * <p>⚠️ <b>这个类是被一个真实的线上故障逼出来的，它当时缺席是那个故障能溜过去的直接原因。</b>
 * 经过是这样的：机器人接上 NapCat 之后对群里发的 {@code fwping} <b>毫无反应</b>，
 * 日志里只有一行「收到事件 postType=null messageType=null …… 全是 null」。
 * 根因是 <b>OneBot 协议用 snake_case</b>（{@code post_type} / {@code group_id} /
 * {@code raw_message}），而 Java 字段是 camelCase，<b>Jackson 默认对不上</b> ——
 * 反序列化不报错，只是每个字段都成了 null，于是事件被当成「不是群消息」静默丢弃。
 *
 * <p>教训是：<b>协议映射层「字段名对不上」的失败方式是静默的</b>，
 * 它不会抛异常、不会有告警，只会让功能看起来「没接上」。
 * 所以这一层必须有测试把协议字段的<b>真实拼写</b>钉住 —— 用规范里的字段名，
 * 而不是跟着 Java 字段想当然。
 *
 * <p>下面每条 JSON 都按 OneBot v11 规范的字段名书写，<b>不要「顺手改成驼峰」</b>，
 * 那会让整个类失去意义（而且它照样会通过）。
 */
class OneBotProtocolTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================================================================
    // 入站事件
    // ==================================================================

    @Test
    @DisplayName("协议：群消息的蛇形字段能映射到驼峰属性")
    void event_mapsSnakeCaseFields() throws Exception {
        String json = """
                {"post_type":"message","message_type":"group","sub_type":"normal",
                 "message_id":123456,"group_id":1062204057,"user_id":1509322477,
                 "raw_message":"fwping","self_id":2198047522,"time":1759244000,
                 "sender":{"user_id":1509322477,"nickname":"小枫","card":"","role":"member"}}""";

        OneBotEvent event = objectMapper.readValue(json, OneBotEvent.class);

        assertEquals("message", event.getPostType(), "post_type 对不上 → 整条事件会被当成「不是群消息」丢掉");
        assertEquals("group", event.getMessageType());
        assertEquals(123456L, event.getMessageId());
        assertEquals(1062204057L, event.getGroupId());
        assertEquals(1509322477L, event.getUserId());
        assertEquals("fwping", event.getRawMessage(), "指令是从 raw_message 解析的");
        assertEquals(2198047522L, event.getSelfId());
        assertNotNull(event.getSender(), "sender 子对象也要映射上");
        assertEquals("小枫", event.getSender().getNickname());
        assertEquals("member", event.getSender().getRole());
    }

    @Test
    @DisplayName("协议：群消息判定成立")
    void event_recognizesGroupMessage() throws Exception {
        OneBotEvent event = objectMapper.readValue("""
                {"post_type":"message","message_type":"group","group_id":1062204057,
                 "user_id":1,"raw_message":"fw在店"}""", OneBotEvent.class);

        assertTrue(event.isGroupMessage());
        assertFalse(event.isSelfSent(), "别人发的不是「自己那一侧」发的");
    }

    @Test
    @DisplayName("协议：私聊与缺群号都不算群消息")
    void event_rejectsNonGroupMessages() throws Exception {
        OneBotEvent privateMsg = objectMapper.readValue("""
                {"post_type":"message","message_type":"private","user_id":1,"raw_message":"fwping"}""",
                OneBotEvent.class);
        assertFalse(privateMsg.isGroupMessage(), "私聊不在服务范围内");

        OneBotEvent noGroupId = objectMapper.readValue("""
                {"post_type":"message","message_type":"group","user_id":1,"raw_message":"fwping"}""",
                OneBotEvent.class);
        assertFalse(noGroupId.isGroupMessage(),
                "缺少 group_id 时后面所有按群号做的判断都会退化成「拿 null 去比」");
    }

    @Test
    @DisplayName("协议：自己那一侧发的消息（message_sent）能识别")
    void event_recognizesSelfSent() throws Exception {
        // NapCat 开了 reportSelfMessage 之后，运营者在手机上用同一个 QQ 发的消息
        // 就是这个类型 —— 它是「手机上发指令、电脑上机器人回复」这条用法的前提
        OneBotEvent event = objectMapper.readValue("""
                {"post_type":"message_sent","message_type":"group","group_id":1062204057,
                 "user_id":2198047522,"self_id":2198047522,"raw_message":"fwping"}""",
                OneBotEvent.class);

        assertTrue(event.isSelfSent());
        assertTrue(event.isGroupMessage(), "message_sent 的群消息同样要能触发指令");
    }

    @Test
    @DisplayName("协议：sender 里 user_id 与 self_id 相同也判为自己发的")
    void event_recognizesSelfByUserIdFallback() throws Exception {
        // 兼容：某些实现不开 reportSelfMessage 也会把发送者标成自己
        OneBotEvent event = objectMapper.readValue("""
                {"post_type":"message","message_type":"group","group_id":1062204057,
                 "user_id":2198047522,"self_id":2198047522,"raw_message":"fwping"}""",
                OneBotEvent.class);

        assertTrue(event.isSelfSent());
    }

    @Test
    @DisplayName("协议：心跳帧能识别，且它的 status 是对象不会炸反序列化")
    void event_recognizesHeartbeat() throws Exception {
        // ⚠️ 心跳的 status 是个【对象】{"online":true,"good":true}，不是字符串。
        // 拿 status 当「这是不是 Action 响应」的判据会在这里抛反序列化异常 ——
        // 每 30 秒一次，而功能一切正常，最容易被当成无害噪音忽略过去
        OneBotEvent event = objectMapper.readValue("""
                {"post_type":"meta_event","meta_event_type":"heartbeat","interval":30000,
                 "self_id":2198047522,"time":1759244000,
                 "status":{"online":true,"good":true}}""", OneBotEvent.class);

        assertTrue(event.isMetaEvent());
        assertFalse(event.isGroupMessage());
        assertEquals("heartbeat", event.getMetaEventType());
    }

    @Test
    @DisplayName("协议：多出来的字段不会让反序列化失败")
    void event_toleratesUnknownFields() throws Exception {
        OneBotEvent event = objectMapper.readValue("""
                {"post_type":"message","message_type":"group","group_id":1,"user_id":1,
                 "raw_message":"fwping","font":0,"anonymous":null,
                 "未来才有的字段":{"a":1},"message":[{"type":"text","data":{"text":"fwping"}}]}""",
                OneBotEvent.class);

        assertEquals("fwping", event.getRawMessage(), "不认识的字段应当被忽略，而不是让整条事件丢掉");
    }

    @Test
    @DisplayName("协议：字段全对不上时得到的是一堆 null（这正是那次故障的样子）")
    void event_allNullWhenNamesMismatch() throws Exception {
        // 反过来钉住失败的样子：如果哪天有人把 @JsonNaming 去掉，
        // 上面那些测试会红，而这一条会绿 —— 它记录的是「错了但不报错」这个事实本身
        OneBotEvent event = objectMapper.readValue("""
                {"postType":"message","messageType":"group","groupId":1}""",
                OneBotEvent.class);

        assertNull(event.getPostType(),
                "驼峰写法的 JSON 对不上 —— 协议就是蛇形的，别为了「好看」把测试数据改成驼峰");
    }

    // ==================================================================
    // 出站
    // ==================================================================

    @Test
    @DisplayName("协议：发群消息的动作带有 auto_escape")
    void action_sendGroupMessageEscapesCqCode() throws Exception {
        OneBotAction action = OneBotAction.sendGroupMessage(1062204057L, "pong", "uspace-1");

        String json = objectMapper.writeValueAsString(action);
        assertTrue(json.contains("\"action\":\"send_group_msg\""), "动作名要符合规范");
        assertTrue(json.contains("\"group_id\":1062204057"), "群号按数字发，不是字符串");
        assertTrue(json.contains("\"auto_escape\":true"),
                "必须转义 —— 播报文案里含用户昵称，不转义的话起名叫 [CQ:at,qq=all] 就能让机器人替他 @ 全体");
        assertTrue(json.contains("\"echo\":\"uspace-1\""), "echo 用于日志关联");
    }

    @Test
    @DisplayName("协议：带 @ 的群消息以段数组发送，且不传 auto_escape")
    void action_sendGroupMessageWithAtUsesSegments() throws Exception {
        OneBotAction action = OneBotAction.sendGroupMessageWithAt(
                1062204057L, "2198047522", " 你的付款凭证没通过复核", "uspace-7");

        String json = objectMapper.writeValueAsString(action);
        assertTrue(json.contains("\"action\":\"send_group_msg\""), "动作名与纯文本那条相同");
        assertTrue(json.contains("\"type\":\"at\""),
                "@ 必须走段数组 —— CQ 码那种写法会被 auto_escape 转义成一行字面文本，@ 不到任何人");
        assertTrue(json.contains("\"qq\":\"2198047522\""),
                "qq 按规范的 string 类型发（写成数字多数实现也认，但不必去赌）");
        assertTrue(json.contains("\"type\":\"text\""), "正文是一段 text");
        assertFalse(json.contains("auto_escape"),
                "段数组形态下刻意不带它：它只对字符串形式的 message 有效，"
                        + "带上会让人误以为这里也在转义");
        assertFalse(json.contains("CQ:at"),
                "绝不能退回 CQ 码：那条路要关掉转义，而转义挡的正是"
                        + "「用户把昵称起成 [CQ:at,qq=all] 让机器人替他 @ 全体」这个注入面");
    }

    @Test
    @DisplayName("协议：没绑 QQ 时退化成纯文本段，但消息照发")
    void action_sendGroupMessageWithAtDegradesWithoutQq() throws Exception {
        OneBotAction action = OneBotAction.sendGroupMessageWithAt(
                1062204057L, null, " 你的付款凭证没通过复核", "uspace-8");

        String json = objectMapper.writeValueAsString(action);
        assertFalse(json.contains("\"type\":\"at\""), "没有 QQ 号就不该有 @ 段");
        assertTrue(json.contains("\"type\":\"text\""),
                "但正文照发 —— 提醒本身比 @ 到人重要，群里的人多半能认出说的是谁");
    }

    @Test
    @DisplayName("协议：带图消息以段数组发送，图片走 base64 内联")
    void action_sendGroupMessageWithImageUsesSegments() throws Exception {
        OneBotAction action = OneBotAction.sendGroupMessageWithImage(
                1062204057L, "base64://iVBORw0KGgo", null, "uspace-11");

        String json = objectMapper.writeValueAsString(action);
        assertTrue(json.contains("\"action\":\"send_group_msg\""), "动作名与其它群消息相同");
        assertTrue(json.contains("\"type\":\"image\""), "图片必须走 image 段");
        assertTrue(json.contains("\"file\":\"base64://iVBORw0KGgo\""),
                "file 用 base64:// 内联 —— 名册图是即时快照，不落盘、不传 URL");
        assertFalse(json.contains("\"type\":\"text\""),
                "没传文本时不该凭空多出一个文本段");
        assertFalse(json.contains("auto_escape"), "段数组形态下刻意不带它（同带 @ 那条）");
    }

    @Test
    @DisplayName("协议：带图消息可以附一段文本（文本在前、图在后）")
    void action_sendGroupMessageWithImageAndText() throws Exception {
        OneBotAction action = OneBotAction.sendGroupMessageWithImage(
                1062204057L, "base64://AAAA", "店内目前有 3 人。", "uspace-12");

        String json = objectMapper.writeValueAsString(action);
        assertTrue(json.contains("\"type\":\"image\"") && json.contains("\"type\":\"text\""),
                "图与文都要在");
        assertTrue(json.indexOf("\"type\":\"text\"") < json.indexOf("\"type\":\"image\""),
                "文本在前、图片在后（2026-10-10 由用户定）—— 先看到「店内目前有 X 人」，"
                        + "再往下是卡片");
    }

    @Test
    @DisplayName("协议：多张图塞进同一条消息（文本在前、图依次在后，不逐条刷屏）")
    void action_sendGroupMessageWithImagesInOneMessage() throws Exception {
        OneBotAction action = OneBotAction.sendGroupMessageWithImages(
                1062204057L, java.util.List.of("base64://AAA", "base64://BBB", "base64://CCC"),
                "店内目前有 13 人。", "uspace-13");

        String json = objectMapper.writeValueAsString(action);
        assertEquals(3, json.split("\"type\":\"image\"", -1).length - 1,
                "三张图必须都在【同一条】消息里 —— 逐条发会把群刷屏（2026-10-10 由用户定）");
        assertTrue(json.indexOf("\"type\":\"text\"") < json.indexOf("\"type\":\"image\""),
                "文本在前、图在后");
        assertEquals(1, json.split("\"action\"", -1).length - 1, "仍然只是一个动作");
    }

    @Test
    @DisplayName("协议：发私聊的动作带有 auto_escape 与 user_id")
    void action_sendPrivateMessageEscapesCqCode() throws Exception {
        OneBotAction action = OneBotAction.sendPrivateMessage(12345L, "你的固定密码：111111", "uspace-9");

        String json = objectMapper.writeValueAsString(action);
        assertTrue(json.contains("\"action\":\"send_private_msg\""), "动作名要符合规范");
        assertTrue(json.contains("\"user_id\":12345"), "私聊按 QQ 号发，不是群号");
        assertTrue(json.contains("\"auto_escape\":true"),
                "私聊文本里同样会带订单号与门店名，转义的理由与群消息那份完全相同");
        // ⚠️ 这个类存在的理由就是「协议字段拼错了会静默失败」——
        // 写错成 user_id 以外的名字，NapCat 那边只会当作参数缺失，
        // 而本系统看到的只是「私聊没发出去」
        assertFalse(json.contains("group_id"), "私聊动作里不该出现群号字段");
    }

    @Test
    @DisplayName("协议：动作响应按 status 判定成功")
    void actionResponse_readsStatus() throws Exception {
        OneBotActionResponse ok = objectMapper.readValue("""
                {"status":"ok","retcode":0,"data":{"message_id":456},"echo":"uspace-1"}""",
                OneBotActionResponse.class);
        assertTrue(ok.isOk());
        assertEquals("uspace-1", ok.getEcho());
        assertEquals(456, ok.getData().get("message_id").asInt());

        OneBotActionResponse failed = objectMapper.readValue("""
                {"status":"failed","retcode":100,"echo":"uspace-2"}""", OneBotActionResponse.class);
        assertFalse(failed.isOk());
    }

    @Test
    @DisplayName("协议：响应里没有 status 时判为失败，而不是抛 NPE")
    void actionResponse_missingStatusIsFailure() throws Exception {
        OneBotActionResponse response = objectMapper.readValue("""
                {"retcode":0}""", OneBotActionResponse.class);

        assertFalse(response.isOk(), "缺字段时按失败处理 —— 用 retcode == 0 判的话，null 拆箱会抛 NPE");
    }
}

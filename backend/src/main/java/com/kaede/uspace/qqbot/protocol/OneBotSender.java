package com.kaede.uspace.qqbot.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 群消息的发送者信息（OneBot v11 协议映射层）。
 *
 * <p>对应群消息事件里的 {@code sender} 对象。这是<b>协议里的那个人</b>，
 * 与 {@code sys_user} 里那条记录是两回事 —— 把它对上系统用户靠的是
 * {@code sys_user.qq} 这一列（见 {@link com.kaede.uspace.qqbot.QqCommandService}）。
 *
 * <p>⚠️ <b>规范原话：这些值属于「尽最大努力提供」，字段可能缺失或过期</b>，
 * 对匿名消息更是「不具有参考价值」。所以本类的任何字段都<b>不能</b>当作
 * 权限判断的依据 —— 判断权限要去读库里的 {@code sys_user.role}。
 * 项目里 {@code InstoreUserVo.role} 顶部写着同一条纪律，理由是同一个。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class OneBotSender {

    /** 发送者 QQ 号。与事件顶层的 {@code user_id} 是同一个值，这里冗余一份便于取值 */
    private Long userId;

    /** QQ 昵称 */
    private String nickname;

    /** 群名片（群备注）。群成员自己设的，可能与昵称不同，也可能为空 */
    private String card;

    /**
     * 群内角色：{@code owner} 群主 / {@code admin} 管理员 / {@code member} 普通成员。
     *
     * <p>设计文档说它「可直接作为模块 2 权限管理的第一层过滤」——
     * 骨架阶段还没有需要群内角色才能用的指令（现有的都是只读查询，
     * 与 Web 端在店名册的开放程度一致），所以<b>目前没有读它</b>。
     * 字段留着，将来加「查某人消费了多少」这类指令时才有第一道过滤。
     */
    private String role;

    /**
     * 取一个用于日志的显示名：优先群名片，其次昵称，都没有就退回 QQ 号。
     *
     * <p><b>只用于日志，不用于播报</b> —— 播报里的名字取系统内的
     * {@code sys_user.nickname}，因为群里那个名字用户随时可改，
     * 而订单是系统内的东西，两个名字对不上会让人以为播报串了人。
     *
     * @return 显示名；三者皆无时返回 {@code "未知"}（QQ 号理论上必有，但规范说了「尽最大努力」）
     */
    public String displayName() {
        if (card != null && !card.isBlank()) {
            return card;
        }
        if (nickname != null && !nickname.isBlank()) {
            return nickname;
        }
        return userId == null ? "未知" : String.valueOf(userId);
    }
}

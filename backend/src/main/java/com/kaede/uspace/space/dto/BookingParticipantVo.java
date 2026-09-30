package com.kaede.uspace.space.dto;

import com.kaede.uspace.space.BookingParticipantRole;
import com.kaede.uspace.space.entity.BookingParticipant;
import com.kaede.uspace.user.entity.SysUser;
import lombok.Data;

/**
 * 包场参与者视图（邀请落地页的参与者名单）。
 *
 * <p><b>只有五个字段，这是刻意划的披露边界。</b>拿到邀请链接的人能看到
 * 全部参与者的昵称与头像 —— 这是分享链接的固有语义（群邀请页都这样），
 * 也正因如此它比 {@code StoreStatusVo} 当初挡掉的东西轻。但**看不到任何人的
 * 手机号、QQ 号与消费金额**：那些是本店的经营信息，不该随一条转发出去的链接外泄。
 *
 * <p>若将来觉得连昵称都该收敛，最小的改法是 {@link #of} 里只取首字（「小*」），
 * 接口契约不变。
 */
@Data
public class BookingParticipantVo {

    /** 参与人用户 ID */
    private Long userId;

    /** 昵称，展示用 */
    private String nickname;

    /** 头像的站内相对路径，可为空（没传过头像时为 null） */
    private String avatar;

    /** 角色：HOST / PARTICIPANT，见 {@link BookingParticipantRole} */
    private String role;

    /** 角色的中文文案，如「发起人」。由后端翻译，前端不必再维护一份映射 */
    private String roleText;

    /**
     * 由参与者行与用户记录组装视图。
     *
     * <p><b>用户为 null 时不报错，昵称留空</b>：用户可能已被逻辑删除，
     * 而他在历史包场里的参与者行还在。让整份名单因为一个人查不到而失败，
     * 代价远大于少显示一个昵称。
     *
     * @param participant 参与者行，不可为 null
     * @param user        对应用户记录，可为 null（用户已不存在时）
     * @return 参与者视图
     */
    public static BookingParticipantVo of(BookingParticipant participant, SysUser user) {
        BookingParticipantVo vo = new BookingParticipantVo();
        vo.setUserId(participant.getUserId());
        vo.setRole(participant.getRole());
        vo.setRoleText(BookingParticipantRole.textOf(participant.getRole()));
        if (user != null) {
            vo.setNickname(user.getNickname());
            vo.setAvatar(user.getAvatar());
        }
        return vo;
    }
}

package com.kaede.uspace.space.dto;

import lombok.Data;

/**
 * 加入包场的结果。
 *
 * <p><b>为什么两个布尔值都返回，而不是一个「成功 / 失败」</b>：
 * 重复点开同一条邀请链接是<b>正常操作</b>（刷新页面、从聊天记录里再点一次、
 * 落地页加载后自动重发），不该报错，但前端需要知道要不要提示「你已经进入了」。
 * 只给一个 boolean 的话，前端分不清「刚加入」与「早就加入了」——
 * 而这两者对用户的回应完全不同（一个是欢迎，一个是「你已经在名单里了」）。
 *
 * <p>{@code joined} 与 {@code alreadyJoined} 恒为一真一假，不会同时为真：
 * 唯一键冲突时 {@code joined=false}，插入成功时 {@code alreadyJoined=false}。
 */
@Data
public class JoinResultVo {

    /** 本次是否新加入。首次点开链接为 true */
    private boolean joined;

    /** 之前是否已经在名单里。重复点开链接为 true */
    private boolean alreadyJoined;

    /**
     * 加入之后的参与者人数（含包场人）。
     *
     * <p>放在同一个响应里返回，前端不必再发一次请求去取名单长度 ——
     * 而那一刻的数字也未必与这里一致（中间可能又有别人加入）。
     */
    private int participantCount;

    /**
     * 构造「本次新加入」的结果。
     *
     * @param participantCount 加入之后的参与者人数
     * @return 结果视图
     */
    public static JoinResultVo joined(int participantCount) {
        JoinResultVo vo = new JoinResultVo();
        vo.setJoined(true);
        vo.setAlreadyJoined(false);
        vo.setParticipantCount(participantCount);
        return vo;
    }

    /**
     * 构造「已经加入过」的结果。
     *
     * @param participantCount 当前参与者人数
     * @return 结果视图
     */
    public static JoinResultVo alreadyJoined(int participantCount) {
        JoinResultVo vo = new JoinResultVo();
        vo.setJoined(false);
        vo.setAlreadyJoined(true);
        vo.setParticipantCount(participantCount);
        return vo;
    }
}

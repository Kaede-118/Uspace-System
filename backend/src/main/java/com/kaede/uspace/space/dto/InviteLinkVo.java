package com.kaede.uspace.space.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 包场的邀请链接（只有包场人本人能取）。
 *
 * <p><b>为什么 {@code path} 与 {@code url} 都给</b>：后端拼出来的 {@code url}
 * 用的是配置里的域名（{@code uspace.web.base-url}），开发期那多半是
 * {@code localhost} —— <b>发到微信里打不开</b>。所以前端应当优先用
 * {@code location.origin + path} 自己拼一条能在当前这台手机上打开的链接，
 * 把 {@code url} 当成兜底与「复制到剪贴板」的备选。
 *
 * <p><b>刻意不从 {@code Origin} 请求头拼域名</b>：那个头是客户端说了算的，
 * 伪造它就能让接口吐出一条指向任意域名的分享链接。
 */
@Data
public class InviteLinkVo {

    /** 包场 ID */
    private Long bookingId;

    /** 包场单号，便于口头核对「我发的是哪一场」 */
    private String bookingNo;

    /**
     * 邀请令牌，43 位 URL-safe 字符串。
     *
     * <p>单独给出它，是因为前端可能需要自己拼一条带额外查询参数的链接
     * （如带上来源渠道），那时只给整条 {@code url} 就不够用了。
     */
    private String inviteToken;

    /** 前端路由路径，如 {@code /invite/xxxxx}。前端应在它前面拼上自己的 origin */
    private String path;

    /** 完整链接，域名取自服务端配置。开发期多半指向 localhost，前端优先用 origin + path */
    private String url;

    /** 包场开始时刻 */
    private LocalDateTime startAt;

    /** 包场结束时刻 */
    private LocalDateTime endAt;
}

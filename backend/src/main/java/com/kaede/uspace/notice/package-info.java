/**
 * 店铺公告（不对应论文某一章，与 {@code common} 公共层同性质）。
 *
 * <p>职责：给用户端首页提供一块「店门口告示牌」，承载两类内容 ——
 * <ul>
 *   <li><b>系统自动</b>：机台转入「维护中」、包场即将开始/正在进行。由各业务模块
 *       在状态变化时调用 {@code NoticeService#publishAuto} 写入</li>
 *   <li><b>管理员手写</b>：临时通知、活动、说明。由运营后台发布</li>
 * </ul>
 *
 * <p><b>为什么必须独立成包</b>：公告要展示「机台是否维护中」，而
 * {@code device} 包已经单向依赖 {@code space}（{@code DeviceService} 用
 * {@code StoreMapper} 取当前门店）。公告若落进 {@code space}，就成了
 * {@code space → device → space} 的环。独立成包后，调用方向只剩单向：
 * <b>{@code device} / {@code space} / {@code order} 可以调本包，
 * 本包只依赖 {@code common}、不 import 任何业务包</b>。
 * 目前真正接入的只有 {@code device}（机台新增 / 状况变化 / 退役三处）；
 * 另外两条是留给将来「包场开始前提醒」「订单结算播报」这类需求的口子，
 * 真要接时按同样的方向调 {@code publishAuto} 即可，不会成环。
 *
 * <p><b>内容由调用方拼好后以字符串传入</b>，本包不认识机台、也不认识包场 ——
 * 这正是上面那条「不反向依赖」得以成立的前提。文案的统一出口是
 * {@link com.kaede.uspace.notice.NoticeContents}。
 *
 * <p><b>答辩时怎么讲</b>：{@code common} 已经开了「不对应论文某一章」的先例
 * （讲作「12 个业务包 + 1 个公共层」）。本包同理，讲作
 * 「<b>12 个业务包 + 公共层 + 公告包</b>」—— 公告是横跨设备、包场、门店三条线的
 * <b>统一信息出口</b>，它不属于任何单一业务模块，独立成包正是为了不被任何一条线绑住。
 *
 * <p><b>与 {@code StoreStatusVo} 的披露边界的关系</b>：那边定死了
 * 「停业的原因不对外披露」「包场时也不披露包场人是谁」。本包的自动公告
 * <b>遵守同一套边界</b>：只说事实与时段，不说原因与当事人。详见 {@code NoticeContents}。
 */
package com.kaede.uspace.notice;

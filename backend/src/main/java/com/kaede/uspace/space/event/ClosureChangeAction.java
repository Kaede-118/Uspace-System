package com.kaede.uspace.space.event;

/**
 * 停业时段发生了什么 —— {@link ClosureChangedEvent} 的动作标识。
 *
 * <p><b>新增与撤销共用一个事件类、靠它区分</b>，与 {@code NoticePublishedEvent}
 * 用 {@code NoticePublishMode} 区分手写 / 自动是同一个模式：两者携带的数据
 * 完全一样（都是「哪一段时段」），差别只在措辞上。
 *
 * <p>将来若要支持「改期也播」，这里加一个 {@code UPDATED} 即可 ——
 * 若当初拆成两个事件类，那时就得再加一个类、以及在监听器里多一个分支。
 */
public enum ClosureChangeAction {

    /** 新增停业安排（{@code ClosureService#createClosure}） */
    CREATED,

    /** 撤销停业安排（{@code ClosureService#deleteClosure}，逻辑删除） */
    DELETED
}

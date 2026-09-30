/**
 * 轻量提示（toast）。
 *
 * <p>不引 UI 库的 Message 组件 —— 本项目的 UI 是手写的淡紫体系，
 * 引进来还要写一堆 `!important` 覆盖样式。
 *
 * <p>状态放在模块级（而不是 {@code useToast()} 里 new 一个），
 * 是因为 toast 是<b>全局唯一</b>的一叠消息：任何页面调 {@code showToast}
 * 都应该出现在同一个地方。做成每调用一次就新开一叠的话，
 * 页面上会同时出现好几处互相盖住的提示。
 */
import { reactive } from 'vue'

/** 当前显示中的提示列表。 */
export const toasts = reactive([])

/** 自增 ID，用于列表渲染的 key 与关闭时定位。 */
let seed = 0

/**
 * 弹一条提示。
 *
 * @param {string} message  文案
 * @param {string} [type]   info / success / error / warning
 * @param {number} [duration] 显示时长（毫秒）
 */
export function showToast(message, type = 'info', duration = 2200) {
  if (!message) return
  const id = ++seed
  toasts.push({ id, message, type })
  setTimeout(() => dismiss(id), duration)
}

/**
 * 关闭一条提示。
 *
 * @param {number} id 提示 ID
 */
export function dismiss(id) {
  const index = toasts.findIndex((t) => t.id === id)
  if (index > -1) toasts.splice(index, 1)
}

/** 成功提示。 */
export function toastSuccess(message) {
  showToast(message, 'success')
}

/**
 * 失败提示。
 *
 * <p>时长给得比成功长：出错的文案通常要读完才知道该做什么，
 * 2 秒闪过去用户只会觉得「刚才弹了个什么」。
 */
export function toastError(message) {
  showToast(message, 'error', 3000)
}

/** 普通提示。 */
export function toastInfo(message) {
  showToast(message, 'info')
}

/**
 * 组合式用法。
 *
 * @returns {object} 一组提示函数，供 {@code <script setup>} 里解构使用
 */
export function useToast() {
  return { showToast, toastSuccess, toastError, toastInfo, toasts, dismiss }
}

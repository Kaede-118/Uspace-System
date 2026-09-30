<script setup>
/**
 * 机台列表。
 *
 * <p><b>这是纯展示的资产台账</b>：机台不绑定订单、不参与准入、也没有设备级使用记录。
 * 能不能进店只由停业与包场决定 —— 哪怕全店机器都标成「维护中」，
 * 系统照样放人进门。真要拦住顾客，得排一条停业区间。
 *
 * <p>「维护中」的机台<b>照样陈列</b>，不会藏起来 —— 藏起来会让顾客以为机器搬走了。
 * 三态里「待维护」与「维护中」刻意分开：前者是「还能玩，但有小毛病」，
 * 后者是「别碰」。合成一个「异常」态，顾客不知道能不能上手。
 */
import { ref, onMounted } from 'vue'
import { listDevices } from '@/api/device'
import { toastError } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'
// 状况的标签配色与运营后台共用同一份映射（utils/labels.js），
// 免得两处的颜色慢慢分岔成「顾客看到的红」与「管理员看到的橙」
import { deviceStatusCls } from '@/utils/labels'
import NavBar from '@/components/NavBar.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadingMask from '@/components/LoadingMask.vue'

const groups = ref([])
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    const resp = await listDevices()
    groups.value = resp.data || []
  } catch (err) {
    toastError(errorMessage(err, '加载失败'))
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <NavBar title="店内机台" />

    <LoadingMask :loading="loading" />

    <div v-if="groups.length" class="groups">
      <!-- ⚠️ 分组标识是 typeCode（不是 typeId），机台状况的中文是 statusLabel（不是 statusText）—— 实测核对过 -->
      <div v-for="g in groups" :key="g.typeCode" class="card">
        <div class="card-title">
          {{ g.typeName }}
          <span class="card-sub">{{ g.devices?.length || 0 }} 台</span>
        </div>

        <div class="devices">
          <div
            v-for="d in g.devices"
            :key="d.id"
            class="device"
            :class="{ 'device--unusable': !d.usable }"
          >
            <div class="device__main">
              <span class="device__name">{{ d.name }}</span>
              <span v-if="d.location" class="device__location">{{ d.location }}</span>
            </div>
            <div class="device__side">
              <span class="device__no">{{ d.deviceNo }}</span>
              <!-- 中文用后端给的 statusLabel，只有配色取自共用映射 -->
              <span :class="deviceStatusCls(d.status)">{{ d.statusLabel }}</span>
            </div>
          </div>

          <p v-if="!g.devices?.length" class="text-sm text-muted">该类型下暂无机台</p>
        </div>
      </div>
    </div>

    <EmptyState
      v-else-if="!loading"
      icon="📋"
      text="暂无机台"
      hint="管理员登记后就能看到"
    />
  </div>
</template>

<style scoped>
.groups {
  padding-top: var(--sp-4);
}

.devices {
  display: flex;
  flex-direction: column;
}

.device {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  padding: var(--sp-3) 0;
}

.device + .device {
  border-top: 1px solid var(--c-border);
}

.device__main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.device__name {
  font-size: 14px;
  color: var(--c-text);
}

.device__location {
  font-size: 11px;
  color: var(--c-text-muted);
}

.device__side {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-shrink: 0;
}

.device__no {
  font-size: 11px;
  color: var(--c-text-muted);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

/*
 * 「维护中」的机台照样陈列，只是压暗一档 ——
 * 藏起来会让顾客以为机器搬走了。是否可玩用后端给的 usable，
 * 前端不自己按状态名判断（判宽判窄都是静默的错）。
 */
.device--unusable {
  opacity: 0.55;
}
</style>

<template>
  <div class="ticket-page">
    <div class="page-title-row">
      <div>
        <h2 class="page-title">工单中心</h2>
        <p class="page-sub">
          会话转工单 · 认领分派 · 两段 SLA（首次响应 / 解决）· 超时自动提醒，解决不了的事有人跟到底
        </p>
        <p v-if="access" class="role-line">
          当前角色：<b>{{ access.roleName }}</b>
          <span v-if="access.hint">· {{ access.hint }}</span>
        </p>
      </div>
      <div class="head-actions">
        <el-button :loading="loadingList" @click="refreshAll">
          <el-icon class="btn-icon"><Refresh /></el-icon>
          刷新
        </el-button>
        <el-button v-if="access?.canManageSla" @click="openSlaRules">
          <el-icon class="btn-icon"><Stamp /></el-icon>
          SLA 规则
        </el-button>
        <el-button v-if="access?.canManageSla" :loading="scanning" @click="runScan">
          <el-icon class="btn-icon"><Timer /></el-icon>
          检查超时
        </el-button>
        <el-button @click="exportCsv">
          <el-icon class="btn-icon"><Download /></el-icon>
          导出
        </el-button>
        <el-button v-if="access?.canOperate" type="primary" @click="openCreate()">
          <el-icon class="btn-icon"><Plus /></el-icon>
          新建工单
        </el-button>
        <el-button v-if="access?.canOperate" @click="openCreate({ platform: true })">
          <el-icon class="btn-icon"><Promotion /></el-icon>
          提交给平台支持
        </el-button>
      </div>
    </div>
    <!-- 企业工单 / 提交给平台：两类工单的处理方不同，分页签看更清楚 -->
    <el-tabs v-model="ticketTypeTab" class="type-tabs" @tab-change="reloadFromFirstPage">
      <el-tab-pane :label="`企业内部工单（${overview ? overview.pending + overview.processing + overview.confirming : 0} 在办）`" :name="1" />
      <el-tab-pane label="提交给平台支持" :name="2" />
    </el-tabs>
    <div class="stat-row">
      <div class="stat-card">
        <div class="stat-icon blue"><el-icon :size="18"><Tickets /></el-icon></div>
        <div><div class="stat-value">{{ overview?.pending ?? '—' }}</div><div class="stat-label">待处理</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon purple"><el-icon :size="18"><Service /></el-icon></div>
        <div><div class="stat-value">{{ overview?.processing ?? '—' }}</div><div class="stat-label">处理中</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon blue"><el-icon :size="18"><Clock /></el-icon></div>
        <div><div class="stat-value">{{ overview?.confirming ?? '—' }}</div><div class="stat-label">待客户确认</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon amber"><el-icon :size="18"><WarningFilled /></el-icon></div>
        <div><div class="stat-value">{{ overview?.warning ?? '—' }}</div><div class="stat-label">即将超时</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon red"><el-icon :size="18"><BellFilled /></el-icon></div>
        <div><div class="stat-value">{{ overview?.overdue ?? '—' }}</div><div class="stat-label">已超时</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon green"><el-icon :size="18"><CircleCheckFilled /></el-icon></div>
        <div><div class="stat-value">{{ overview?.resolvedToday ?? '—' }}</div><div class="stat-label">今日解决</div></div>
      </div>
    </div>
    <el-card shadow="never" class="main-card">
      <div class="filter-row">
        <div class="filter-left">
          <el-select v-model="query.status" placeholder="全部状态" clearable class="f-select" @change="reloadFromFirstPage">
            <el-option v-for="item in STATUS_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
          <el-select v-model="query.priority" placeholder="全部优先级" clearable class="f-select" @change="reloadFromFirstPage">
            <el-option v-for="item in PRIORITY_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
          <el-select v-model="query.slaState" placeholder="全部 SLA" clearable class="f-select" @change="reloadFromFirstPage">
            <el-option label="正常" :value="1" />
            <el-option label="即将超时" :value="2" />
            <el-option label="已超时" :value="3" />
          </el-select>
          <el-select v-model="query.category" placeholder="全部分类" clearable class="f-select" @change="reloadFromFirstPage">
            <el-option v-for="item in CATEGORY_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
          <el-select v-model="assigneeFilter" placeholder="全部处理人" clearable class="f-select" @change="onAssigneeFilter">
            <el-option label="我处理的" value="mine" />
            <el-option label="还没人认领" value="unassigned" />
          </el-select>
          <el-input
            v-model="query.keyword"
            class="f-search"
            placeholder="搜工单号 / 主题 / 客户"
            clearable
            @keyup.enter="reloadFromFirstPage"
            @clear="reloadFromFirstPage"
          />
          <el-button type="primary" plain @click="reloadFromFirstPage">查询</el-button>
        </div>
        <div class="filter-right">
          <el-radio-group v-model="viewMode" size="small">
            <el-radio-button value="table">表格</el-radio-button>
            <el-radio-button value="kanban">看板</el-radio-button>
          </el-radio-group>
          <span class="mine-hint">我处理的 {{ overview?.mine ?? 0 }} 条</span>
        </div>
      </div>
      <el-table
        v-if="viewMode === 'table'"
        v-loading="loadingList"
        :data="rows"
        :row-class-name="rowClass"
        empty-text="没有符合条件的工单"
        class="ticket-table"
      >
        <!-- 序号：每页都从 1 开始（工单号才是唯一标识，序号只方便"这一页第几条"） -->
        <el-table-column type="index" label="序号" width="70" fixed="left" />
        <el-table-column label="工单号" width="180">
          <template #default="{ row }">
            <el-button link type="primary" class="mono" @click="openDetail(row.ticketNo)">
              {{ row.ticketNo }}
            </el-button>
            <div v-if="row.sessionNo" class="cell-sub">来自会话 {{ row.sessionNo }}</div>
          </template>
        </el-table-column>
        <el-table-column label="主题" min-width="240">
          <template #default="{ row }">
            <div class="ticket-title">{{ row.title }}</div>
            <div class="cell-sub">
              <el-tag v-if="row.ticketType === 2" type="warning" size="small" effect="plain">平台支持</el-tag>
              {{ row.sourceChannelText || row.sourceText
              }}<span v-if="row.customerName"> · 客户 {{ row.customerName }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="分类" width="110">
          <template #default="{ row }"><span class="muted">{{ row.categoryText }}</span></template>
        </el-table-column>
        <el-table-column label="优先级" width="96">
          <template #default="{ row }">
            <el-tag :type="priorityTag(row.priority)" effect="light" size="small">{{ row.priorityText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" effect="plain" size="small">{{ row.statusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="SLA" width="170">
          <template #default="{ row }">
            <div class="sla-cell" :class="`sla-${row.slaState}`">
              <el-icon :size="13"><Timer /></el-icon>
              <span>{{ row.slaStateText }}</span>
            </div>
            <div class="cell-sub">{{ slaDetail(row) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="处理人" width="130">
          <template #default="{ row }">
            <span v-if="row.assigneeName" class="member-cell">
              <span class="member-avatar">{{ (row.assigneeName || '?').slice(0, 1) }}</span>
              {{ row.assigneeName }}
            </span>
            <span v-else class="muted">未认领</span>
          </template>
        </el-table-column>
        <el-table-column label="创建" width="150">
          <template #default="{ row }">
            <div>{{ row.createTime }}</div>
            <div class="cell-sub">{{ row.creatorName || '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row.ticketNo)">详情</el-button>
            <el-button
              v-if="access?.canOperate && row.ticketType !== 2 && !row.assigneeId
                && row.status !== 5 && row.status !== 4"
              link
              type="primary"
              size="small"
              @click="claim(row)"
            >
              认领
            </el-button>
            <el-button
              v-if="access?.canOperate && row.ticketType !== 2 && row.status !== 5 && row.status !== 4"
              link
              type="primary"
              size="small"
              @click="openReply(row.ticketNo)"
            >
              回复
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <!-- 看板视图（原型里的四列看板）：卡片拖到别的列就是改状态 -->
      <div v-else v-loading="loadingList" class="kanban">
        <div
          v-for="column in KANBAN_COLUMNS"
          :key="column.status"
          class="kcol"
          @dragover.prevent
          @drop="onDropTo(column.status)"
        >
          <div class="kcol-head">
            <span class="dot" :class="column.color"></span>
            <span class="kt">{{ column.label }}</span>
            <span class="kn">{{ kanbanRowsOf(column.status).length }}</span>
          </div>
          <div class="kcol-body">
            <div
              v-for="row in kanbanRowsOf(column.status)"
              :key="row.ticketNo"
              class="kcard"
              :class="{ 'is-draggable': access?.canOperate && row.ticketType !== 2 }"
              :draggable="Boolean(access?.canOperate && row.ticketType !== 2)"
              @dragstart="onDragStart(row)"
              @click="openDetail(row.ticketNo)"
            >
              <div class="kc-top">
                <div class="kc-title">{{ row.title }}</div>
                <el-tag :type="priorityTag(row.priority)" size="small" effect="light">
                  {{ row.priorityText }}
                </el-tag>
              </div>
              <div class="kc-sub">
                {{ row.customerName || '未填客户' }} · {{ row.sourceChannelText || row.sourceText }}
              </div>
              <div class="kc-foot">
                <span class="mono">{{ row.ticketNo }}</span>
                <span v-if="row.assigneeName" class="member-avatar">{{ row.assigneeName.slice(0, 1) }}</span>
                <span v-else class="badge-wait">待分配</span>
                <span class="kc-sla" :class="`sla-${row.slaState}`">
                  <el-icon :size="12"><Timer /></el-icon>{{ slaDetail(row) }}
                </span>
              </div>
            </div>
            <div v-if="!kanbanRowsOf(column.status).length" class="kempty">暂无</div>
          </div>
        </div>
      </div>
      <div class="table-foot">
        <span class="muted">
          共 {{ total }} 条 · 按创建时间倒序 ·
          SLA 每 30 秒自动扫描一次，超时会推送提醒给处理人
        </span>
        <el-pagination
          class="page-bar"
          background
          layout="total, prev, pager, next, sizes"
          :total="total"
          :current-page="page"
          :page-size="pageSize"
          :page-sizes="[10, 20, 50]"
          @current-change="onPageChange"
          @size-change="onPageSizeChange"
        />
      </div>
    </el-card>
    <!-- 工单详情 -->
    <el-drawer v-model="detailOpen" :title="detail ? `工单 ${detail.ticket.ticketNo}` : '工单详情'" size="720px">
      <div v-if="detail" class="detail-panel">
        <div class="detail-head">
          <div class="detail-title">{{ detail.ticket.title }}</div>
          <div class="detail-badges">
            <el-tag :type="priorityTag(detail.ticket.priority)" effect="light" size="small">
              {{ detail.ticket.priorityText }}
            </el-tag>
            <el-tag :type="statusTag(detail.ticket.status)" effect="plain" size="small">
              {{ detail.ticket.statusText }}
            </el-tag>
            <span class="sla-pill" :class="`sla-${detail.ticket.slaState}`">
              <el-icon :size="13"><Timer /></el-icon>
              {{ detail.ticket.slaStateText }} · {{ slaDetail(detail.ticket) }}
            </span>
          </div>
        </div>
        <div class="sla-track">
          <div class="sla-track-item">
            <div class="track-label">首次响应（{{ detail.sla.firstResponseMinutes }} 分钟内）</div>
            <div class="track-value" :class="{ done: detail.ticket.firstResponded }">
              {{ detail.ticket.firstResponded ? '已响应' : `截止 ${detail.ticket.firstResponseDue || '—'}` }}
            </div>
          </div>
          <div class="sla-track-item">
            <div class="track-label">解决（{{ formatMinutes(detail.sla.resolveMinutes) }}内）</div>
            <div class="track-value" :class="{ done: detail.ticket.status === 4 || detail.ticket.status === 5 }">
              {{ detail.ticket.finishText || `截止 ${detail.ticket.resolveDue || '—'}` }}
            </div>
          </div>
        </div>
        <div class="info-card">
          <div class="info-row">
            <span>租户号</span>
            <span class="v">
              <span class="mono">{{ detail.tenantCode || '—' }}</span>
              <el-button
                v-if="detail.tenantCode"
                link
                type="primary"
                size="small"
                @click="copyTenant(detail.tenantCode)"
              >
                复制
              </el-button>
            </span>
          </div>
          <div class="info-row"><span>分类</span><span class="v">{{ detail.ticket.categoryText }}</span></div>
          <div class="info-row">
            <span>来源渠道</span>
            <span class="v">{{ detail.ticket.sourceChannelText || detail.ticket.sourceText }}</span>
          </div>
          <div class="info-row"><span>客户</span><span class="v">{{ detail.ticket.customerName || '—' }}</span></div>
          <div class="info-row">
            <span>来源会话</span>
            <span class="v">
              <el-button v-if="detail.ticket.sessionNo" link type="primary" @click="goSession(detail.ticket.sessionNo)">
                {{ detail.ticket.sessionNo }}（去工作台看上下文）
              </el-button>
              <span v-else>{{ detail.ticket.sourceText }}</span>
            </span>
          </div>
          <div class="info-row"><span>处理人</span><span class="v">{{ detail.ticket.assigneeName || '未认领' }}</span></div>
          <div class="info-row"><span>创建</span><span class="v">{{ detail.ticket.createTime }} · {{ detail.ticket.creatorName || '—' }}</span></div>
        </div>
        <div class="detail-section-title">问题描述</div>
        <pre class="detail-content">{{ detail.content || '（没有填写描述）' }}</pre>
        <div class="detail-section-title">
          流转记录
          <span class="section-sub">谁在什么时候做了什么，客户来催的时候翻这一段就够了</span>
        </div>
        <el-timeline class="event-line">
          <el-timeline-item
            v-for="event in detail.events"
            :key="event.id"
            :timestamp="event.eventTime"
            placement="top"
            :type="eventDot(event)"
            :hollow="event.eventType === 3"
          >
            <div class="event-head">
              <span class="event-type">{{ event.eventTypeText }}</span>
              <span class="event-who">{{ event.operatorName || '系统' }}</span>
              <span v-if="event.eventType === 7 && event.toStatusText" class="event-flow">
                {{ event.fromStatusText }} → {{ event.toStatusText }}
              </span>
              <span v-if="event.eventType === 3 && !event.visibleToCustomer" class="event-note">内部备注</span>
            </div>
            <div v-if="event.content" class="event-content">{{ event.content }}</div>
          </el-timeline-item>
        </el-timeline>
        <div class="detail-section-title">{{ isPlatformTicket ? '补充说明' : '回复 / 备注' }}</div>
        <div v-if="isPlatformTicket" class="closed-tip">
          这是提交给平台的**支持工单**，由平台运营处理：状态与解决时间由平台维护，
          你可以在这里补充说明（平台会看到），进展看下面的流转记录。
        </div>
        <div v-if="access && !access.canOperate" class="closed-tip">
          当前角色（{{ access.roleName }}）只能查看工单，不能回复或流转；需要跟进请找客服同事。
        </div>
        <div v-else-if="detail.ticket.status === 4 || detail.ticket.status === 5" class="closed-tip">
          工单{{ detail.ticket.statusText }}了，不能再回复；还需要跟进就点下面的「重新打开」。
        </div>
        <el-input
          v-model="replyForm.content"
          type="textarea"
          :rows="3"
          maxlength="512"
          show-word-limit
          :disabled="!access?.canOperate || (!isPlatformTicket
            && (detail.ticket.status === 4 || detail.ticket.status === 5))"
          :placeholder="isPlatformTicket
            ? '补充说明（例如：更详细的现象、复现步骤、影响范围）'
            : '写清楚处理方案；取消勾选「对客户可见」，这条就只作为内部备注'"
        />
        <div class="reply-actions">
          <el-checkbox
            v-if="!isPlatformTicket"
            v-model="replyForm.visibleToCustomer"
            :disabled="!access?.canOperate || detail.ticket.status === 4 || detail.ticket.status === 5"
          >
            对客户可见（会算作首次响应）
          </el-checkbox>
          <span v-else class="muted">补充说明对企业与平台双方可见</span>
          <div class="spacer" />
          <el-button
            :loading="acting"
            type="primary"
            :disabled="!access?.canOperate || (!isPlatformTicket
              && (detail.ticket.status === 4 || detail.ticket.status === 5))"
            @click="submitReply"
          >
            {{ isPlatformTicket ? '提交补充' : '提交回复' }}
          </el-button>
        </div>
      </div>
      <template #footer>
        <div v-if="detail && !isPlatformTicket" class="drawer-foot">
          <el-button
            v-if="access?.canOperate && !detail.ticket.assigneeId"
            :loading="acting"
            @click="claim(detail.ticket)"
          >
            认领
          </el-button>
          <el-button v-if="access?.canOperate" :loading="acting" @click="openAssign">转派</el-button>
          <el-button
            v-if="access?.canOperate && detail.ticket.status !== 4 && detail.ticket.status !== 5"
            :loading="acting"
            @click="escalate"
          >
            升级
          </el-button>
          <el-button
            v-if="access?.canOperate && detail.ticket.status !== 3
              && detail.ticket.status !== 4 && detail.ticket.status !== 5"
            :loading="acting"
            @click="openStatus(3)"
          >
            标记待客户确认
          </el-button>
          <el-button
            v-if="access?.canOperate && detail.ticket.status !== 4 && detail.ticket.status !== 5"
            type="primary"
            :loading="acting"
            @click="openStatus(4)"
          >
            标记已解决
          </el-button>
          <span class="spacer" />
          <el-button
            v-if="access?.canOperate && detail.ticket.status === 5"
            :loading="acting"
            @click="openStatus(2)"
          >
            重新打开
          </el-button>
          <el-button
            v-else-if="access?.canOperate"
            type="danger"
            plain
            :loading="acting"
            @click="openStatus(5)"
          >
            关闭工单
          </el-button>
        </div>
      </template>
    </el-drawer>
    <!-- 新建工单 -->
    <el-dialog
      v-model="createOpen"
      :title="createForm.platform ? '新建平台工单' : '新建工单'"
      width="560px"
    >
      <div v-if="createForm.platform" class="dialog-tip">
        提交后进入「平台支持队列」，由平台运营认领处理（企业侧不指定处理人）；
        处理进展和平台回复会出现在「提交给平台支持」页签与右上角消息中心。
      </div>
      <el-form label-width="86px">
        <el-form-item label="主题" required>
          <el-input v-model="createForm.title" maxlength="128" placeholder="一句话说清客户的问题" />
        </el-form-item>
        <el-form-item v-if="!createForm.platform" label="客户名称">
          <el-input v-model="createForm.customerName" maxlength="64" placeholder="例：陈博文（会话转单会自动带上）" />
        </el-form-item>
        <el-form-item v-if="!createForm.platform" label="来源渠道">
          <el-select v-model="createForm.sourceChannel" class="full-width">
            <el-option v-for="item in CHANNEL_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="createForm.category" class="full-width">
            <el-option v-for="item in CATEGORY_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="优先级">
          <el-radio-group v-model="createForm.priority">
            <el-radio-button v-for="item in PRIORITY_OPTIONS" :key="item.value" :value="item.value">
              {{ item.label }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>
        <!-- 平台支持工单不选处理人：企业不知道平台谁在线、谁管什么模块，
             工单落到平台处理池由平台认领（谁接谁负责），指派是平台内部的事 -->
        <el-form-item v-if="!createForm.platform" label="处理人">
          <el-select v-model="createForm.assigneeId" clearable placeholder="不选就先放到待处理池" class="full-width">
            <el-option v-for="item in colleagues" :key="item.userId" :label="item.name" :value="item.userId" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="createForm.content"
            type="textarea"
            :rows="4"
            maxlength="1000"
            show-word-limit
            placeholder="补充信息（从会话转单时会自动带上会话号、客户与最近对话摘要）"
          />
        </el-form-item>
        <el-form-item v-if="createForm.sessionNo" label="来源会话">
          <span class="mono">{{ createForm.sessionNo }}</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button type="primary" :loading="acting" @click="submitCreate">
          {{ createForm.platform ? '提交给平台' : '创建工单' }}
        </el-button>
      </template>
    </el-dialog>
    <!-- 转派 -->
    <el-dialog v-model="assignOpen" title="转派工单" width="460px">
      <el-form label-width="86px">
        <el-form-item label="处理人" required>
          <el-select v-model="assignForm.toUserId" placeholder="选择同事" class="full-width">
            <el-option v-for="item in colleagues" :key="item.userId" :label="item.name" :value="item.userId" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="assignForm.remark" maxlength="120" placeholder="例如：这块你更熟，帮忙跟一下" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="assignOpen = false">取消</el-button>
        <el-button type="primary" :loading="acting" @click="submitAssign">确认转派</el-button>
      </template>
    </el-dialog>
    <!-- 状态流转 -->
    <el-dialog v-model="statusOpen" :title="`${statusForm.actionText}工单`" width="460px">
      <el-form label-width="86px">
        <el-form-item label="备注">
          <el-input
            v-model="statusForm.remark"
            type="textarea"
            :rows="3"
            maxlength="200"
            :placeholder="statusForm.status === 4 ? '例如：已补发，预计 2 个工作日到账' : '写一句说明，流转记录里会留痕'"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="statusOpen = false">取消</el-button>
        <el-button type="primary" :loading="acting" @click="submitStatus">确认</el-button>
      </template>
    </el-dialog>
    <!-- SLA 规则 -->
    <el-dialog v-model="slaOpen" title="SLA 规则" width="600px">
      <p class="sla-tip">
        按优先级配置"多久内必须首次响应"和"多久内必须解决"。规则改了只影响**新工单**——
        建单时会把当时的时长快照进工单，存量工单的考核口径不会被改口。
      </p>
      <el-table :data="slaRules" size="small">
        <el-table-column label="优先级" width="90">
          <template #default="{ row }">{{ row.priorityText }}</template>
        </el-table-column>
        <el-table-column label="首次响应（分钟）">
          <template #default="{ row }">
            <el-input-number v-model="row.firstResponseMinutes" :min="1" :max="10080" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="解决（分钟）">
          <template #default="{ row }">
            <el-input-number v-model="row.resolveMinutes" :min="1" :max="43200" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="启用" width="80">
          <template #default="{ row }"><el-switch v-model="row.enabled" size="small" /></template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="slaOpen = false">取消</el-button>
        <el-button type="primary" :loading="acting" @click="submitSlaRules">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  BellFilled,
  CircleCheckFilled,
  Clock,
  Download,
  Plus,
  Promotion,
  Refresh,
  Service,
  Stamp,
  Tickets,
  Timer,
  WarningFilled,
} from '@element-plus/icons-vue'
import {
  assignTicket,
  changeTicketStatus,
  createTicket,
  createPlatformTicket,
  downloadTicketsCsv,
  escalateTicket,
  fetchTicketDetail,
  fetchTicketAccess,
  fetchTicketOverview,
  fetchTicketSlaRules,
  fetchTickets,
  replyTicket,
  saveTicketSlaRules,
  scanTicketSla,
  supplementTicket,
  type TicketDetail,
  type TicketItem,
  type TicketOverview,
  type TicketPage,
  type TicketQuery,
  type TicketSlaRule,
  type TicketAccess,
} from '../../api/customer/ticket'
import { listColleagues, type ColleagueOption } from '../../api/member'
import { copyText } from '../../utils/clipboard'

const router = useRouter()

const STATUS_OPTIONS = [
  { value: 1, label: '待处理' },
  { value: 2, label: '处理中' },
  { value: 3, label: '待客户确认' },
  { value: 4, label: '已解决' },
  { value: 5, label: '已关闭' },
]
const PRIORITY_OPTIONS = [
  { value: 1, label: '低' },
  { value: 2, label: '中' },
  { value: 3, label: '高' },
  { value: 4, label: '紧急' },
]
const CATEGORY_OPTIONS = [
  { value: 1, label: '订单' },
  { value: 2, label: '退款售后' },
  { value: 3, label: '物流' },
  { value: 4, label: '商品' },
  { value: 5, label: '其他' },
]
/** 来源渠道：客户从哪来（和"工单怎么来的"是两回事，原型里叫"来源渠道"） */
const CHANNEL_OPTIONS = [
  { value: 1, label: '在线会话' },
  { value: 2, label: '电话热线' },
  { value: 3, label: '邮件' },
  { value: 4, label: '工单导入' },
  { value: 5, label: '其它' },
]
/** 看板列（按工单状态分列，拖拽即改状态） */
const KANBAN_COLUMNS = [
  { status: 1, label: '待处理', color: 'amber' },
  { status: 2, label: '处理中', color: 'blue' },
  { status: 3, label: '待客户确认', color: 'gray' },
  { status: 4, label: '已解决', color: 'green' },
  { status: 5, label: '已关闭', color: 'gray' },
]

const loadingList = ref(false)
const scanning = ref(false)
const acting = ref(false)
const overview = ref<TicketOverview | null>(null)
/** 当前登录人的工单权限：按钮显隐用它；真正的校验在后端 */
const access = ref<TicketAccess | null>(null)
const rows = ref<TicketItem[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
/** 页签：1-企业内部工单、2-提交给平台支持（影响列表的 ticketType 查询条件） */
const ticketTypeTab = ref(1)
/** 视图：表格 / 看板（看板是原型里的默认视图，这里做成可切换） */
const viewMode = ref<'table' | 'kanban'>('table')
const kanbanRows = ref<TicketItem[]>([])
const colleagues = ref<ColleagueOption[]>([])
const assigneeFilter = ref('')

const query = reactive<TicketQuery>({
  status: undefined,
  priority: undefined,
  category: undefined,
  slaState: undefined,
  keyword: '',
})

const detailOpen = ref(false)
const detail = ref<TicketDetail | null>(null)
/** 当前看的是不是"提交给平台支持"的工单：决定详情里显示回复框还是补充说明框 */
const isPlatformTicket = computed(() => detail.value?.ticket.ticketType === 2)
const replyForm = reactive({ content: '', visibleToCustomer: true })

const createOpen = ref(false)
const createForm = reactive({
  title: '',
  content: '',
  category: 5,
  priority: 2,
  assigneeId: '' as string | undefined,
  sessionNo: '' as string | undefined,
  customerName: '',
  sourceChannel: 1 as number | undefined,
  /** true = 提交给平台支持（工单类型 2） */
  platform: false,
})

const assignOpen = ref(false)
const assignForm = reactive({ toUserId: '' as string | undefined, remark: '' })

const statusOpen = ref(false)
const statusForm = reactive({ status: 4, remark: '', actionText: '标记已解决' })

const slaOpen = ref(false)
const slaRules = ref<TicketSlaRule[]>([])

let timer: number | undefined

/**
 * 工单列表 + 看板数字。
 *
 * 列表本身不轮询（坐席正在筛选时被刷新会打断），只让看板数字每 30 秒自己更新一次；
 * 超时提醒另有两条路：长连接推送（工作台在线时秒到）+ 左侧菜单角标。
 */
async function loadOverview() {
  try {
    overview.value = await fetchTicketOverview()
  } catch {
    // 看板数字拿不到不影响列表：这里静默，避免刷屏
  }
}

async function loadList() {
  loadingList.value = true
  try {
    const result = (await fetchTickets({
      ...query,
      // 页签决定看哪类工单：企业内部 or 提交给平台
      ticketType: ticketTypeTab.value,
      keyword: query.keyword?.trim() || undefined,
      mineOnly: assigneeFilter.value === 'mine' || undefined,
      unassignedOnly: assigneeFilter.value === 'unassigned' || undefined,
      page: page.value,
      pageSize: pageSize.value,
    })) as TicketPage | TicketItem[]
    // 后端可能是"还没重启的旧版"（直接返回数组，没有 total/list）：两种都认，
    // 免得刷新页面直接白屏；后端重启后就是真正的服务端分页
    const list = Array.isArray(result) ? result : (result?.list ?? [])
    const totalValue = Array.isArray(result) ? result.length : (result?.total ?? list.length)
    rows.value = list
    // 后端 Long 会被全局序列化成字符串（防雪花 ID 精度丢失），total 也是 Long：
    // el-pagination 的 total 只收 Number，这里统一转一下
    total.value = Number(totalValue) || 0
    // 翻到最后一页又被删/过滤掉几条时，往回退一页再拉，避免停在空白页
    if (!list.length && page.value > 1 && totalValue > 0) {
      page.value = Math.max(1, page.value - 1)
      await loadList()
    }
    // 看板一次要看全（后端单页上限 100 条，够用；再多请用表格分页）
    if (viewMode.value === 'kanban') {
      const board = await fetchTickets({ ...query, ticketType: ticketTypeTab.value, page: 1, pageSize: 100 })
      kanbanRows.value = board.list ?? []
    }
  } finally {
    loadingList.value = false
  }
}

/** 翻页：和「企业审核」那一页同一套写法（显式 props + 事件，不用 v-model） */
function onPageChange(next: number) {
  page.value = next
  void loadList()
}

/** 改每页条数：回到第一页（否则可能出现"第 3 页只剩 2 条"的错觉） */
function onPageSizeChange(size: number) {
  pageSize.value = size
  page.value = 1
  void loadList()
}

// ------------------------------------------------------------------ 看板与拖拽

function kanbanRowsOf(status: number) {
  return kanbanRows.value.filter((row) => row.status === status)
}

let draggingRow: TicketItem | null = null

function onDragStart(row: TicketItem) {
  draggingRow = row
}

/** 拖到另一列 = 改状态（平台支持工单不在这里改，它由平台维护） */
async function onDropTo(status: number) {
  const row = draggingRow
  draggingRow = null
  if (!row || row.status === status) {
    return
  }
  if (!access.value?.canOperate) {
    ElMessage.warning('当前角色只能查看工单')
    return
  }
  if (row.ticketType === 2) {
    ElMessage.warning('提交给平台的支持工单由平台维护状态')
    return
  }
  if (row.status === 5) {
    ElMessage.warning('已关闭的工单请先在详情里重新打开')
    return
  }
  try {
    await changeTicketStatus(row.ticketNo, { status, remark: '看板拖拽流转' })
    ElMessage.success(`已流转为「${KANBAN_COLUMNS.find((c) => c.status === status)?.label}」`)
  } finally {
    await refreshAll()
  }
}

/** 导出 CSV：按当前筛选（含页签类型），后端带 BOM，Excel 打开不乱码 */
async function exportCsv() {
  try {
    await downloadTicketsCsv({
      ...query,
      ticketType: ticketTypeTab.value,
      keyword: query.keyword?.trim() || undefined,
      mineOnly: assigneeFilter.value === 'mine' || undefined,
      unassignedOnly: assigneeFilter.value === 'unassigned' || undefined,
    })
    ElMessage.success('已开始下载')
  } catch {
    ElMessage.error('导出失败，请稍后再试')
  }
}

/** 改筛选条件：也回到第一页（换了条件再停在第 3 页，看到的东西会莫名其妙） */
function reloadFromFirstPage() {
  page.value = 1
  void loadList()
}

function onAssigneeFilter() {
  reloadFromFirstPage()
}

async function refreshAll() {
  await Promise.all([loadOverview(), loadList()])
}

async function loadColleagues() {
  try {
    colleagues.value = await listColleagues()
  } catch {
    colleagues.value = []
  }
}

onMounted(async () => {
  // 从消息中心点"平台回复/升级了你的支持工单"进来时带 ?ticketType=2，直接切到平台页签
  if (String(router.currentRoute.value.query.ticketType ?? '') === '2') {
    ticketTypeTab.value = 2
  }
  await Promise.all([refreshAll(), loadColleagues(), loadAccess()])
  timer = window.setInterval(loadOverview, 30_000)
})

onUnmounted(() => {
  if (timer) {
    window.clearInterval(timer)
  }
})

// ------------------------------------------------------------------ 展示

/** 拉一次当前角色权限（拿不到就按"能看"处理，写操作由后端拦） */
async function loadAccess() {
  try {
    access.value = await fetchTicketAccess()
  } catch {
    access.value = null
  }
}

function priorityTag(priority: number) {
  if (priority === 4) return 'danger'
  if (priority === 3) return 'warning'
  // 注意：Element Plus 的 el-tag 只认 primary/success/info/warning/danger，
  // 返回空字符串会报 "Invalid prop: type check failed for prop type"
  if (priority === 2) return 'primary'
  return 'info'
}

function statusTag(status: number) {
  if (status === 1) return 'warning'
  if (status === 2) return 'primary'
  if (status === 3) return 'info'
  if (status === 4) return 'success'
  return 'info'
}

function rowClass({ row }: { row: TicketItem }) {
  if (row.slaState === 3) return 'row-overdue'
  if (row.slaState === 2) return 'row-warning'
  return ''
}

function eventDot(event: { eventType: number }) {
  if (event.eventType === 8) return 'danger'
  if (event.eventType === 1) return 'primary'
  if (event.eventType === 2) return 'warning'
  if (event.eventType === 3) return 'success'
  return 'info'
}

/** 剩余时间：正数是"还剩"，负数是"已超" */
function formatMinutes(minutes?: number | null) {
  if (minutes === undefined || minutes === null) {
    return '—'
  }
  const value = Math.abs(minutes)
  if (value < 60) {
    return `${value} 分钟`
  }
  if (value < 60 * 24) {
    const hours = Math.floor(value / 60)
    const rest = value % 60
    return rest ? `${hours} 小时 ${rest} 分` : `${hours} 小时`
  }
  const days = Math.floor(value / (60 * 24))
  const hours = Math.floor((value % (60 * 24)) / 60)
  return hours ? `${days} 天 ${hours} 小时` : `${days} 天`
}

function slaDetail(row: TicketItem) {
  if (row.status === 4 || row.status === 5) {
    return row.finishText || '已办结'
  }
  const remain = row.remainMinutes
  if (remain === undefined || remain === null) {
    return '—'
  }
  return remain >= 0 ? `还剩 ${formatMinutes(remain)}` : `已超 ${formatMinutes(-remain)}`
}

// ------------------------------------------------------------------ 操作

async function runScan() {
  scanning.value = true
  try {
    const result = await scanTicketSla()
    ElMessage.success(`扫描完成：在办 ${result.scanned} 条，超时 ${result.overdue} 条，本轮提醒 ${result.alerted} 条`)
    await refreshAll()
  } finally {
    scanning.value = false
  }
}

async function openDetail(ticketNo: string) {
  detail.value = await fetchTicketDetail(ticketNo)
  replyForm.content = ''
  replyForm.visibleToCustomer = true
  detailOpen.value = true
}

/** 列表里直接回复：打开详情并聚焦到回复框（详情里就是回复区） */
async function openReply(ticketNo: string) {
  await openDetail(ticketNo)
}

function openCreate(preset?: Partial<typeof createForm>) {
  createForm.title = preset?.title ?? ''
  createForm.content = preset?.content ?? ''
  createForm.category = preset?.category ?? 5
  createForm.priority = preset?.priority ?? 2
  createForm.assigneeId = undefined
  createForm.sessionNo = preset?.sessionNo
  createForm.customerName = ''
  createForm.sourceChannel = 1
  createForm.platform = preset?.platform ?? ticketTypeTab.value === 2
  createOpen.value = true
}

async function submitCreate() {
  if (!createForm.title.trim()) {
    ElMessage.warning('请填写工单主题')
    return
  }
  acting.value = true
  try {
    const payload = {
      title: createForm.title.trim(),
      content: createForm.content.trim() || undefined,
      category: createForm.category,
      priority: createForm.priority,
      assigneeId: createForm.assigneeId || undefined,
      assigneeName: colleagues.value.find((item) => item.userId === createForm.assigneeId)?.name,
      sessionNo: createForm.sessionNo || undefined,
      customerName: createForm.customerName.trim() || undefined,
      sourceChannel: createForm.sourceChannel,
    }
    // 提交给平台支持走另一个接口：工单类型=2，处理人是平台，企业侧只读
    const created = createForm.platform
      ? await createPlatformTicket({
        title: payload.title,
        content: payload.content,
        category: payload.category,
        priority: payload.priority,
        sessionNo: payload.sessionNo,
      })
      : await createTicket(payload)
    ElMessage.success(createForm.platform
      ? `已提交给平台支持：${created.ticketNo}，处理进展会在「提交给平台支持」页签里更新`
      : `工单 ${created.ticketNo} 已创建`)
    createOpen.value = false
    if (createForm.platform) {
      ticketTypeTab.value = 2
    }
    await refreshAll()
    await openDetail(created.ticketNo)
  } finally {
    acting.value = false
  }
}

async function claim(row: TicketItem) {
  acting.value = true
  try {
    await assignTicket(row.ticketNo, {})
    ElMessage.success('已认领，工单进入处理中')
    await refreshAll()
    if (detailOpen.value) {
      await openDetail(row.ticketNo)
    }
  } finally {
    acting.value = false
  }
}

function openAssign() {
  assignForm.toUserId = undefined
  assignForm.remark = ''
  assignOpen.value = true
}

async function submitAssign() {
  if (!detail.value || !assignForm.toUserId) {
    ElMessage.warning('请选择要转派的同事')
    return
  }
  acting.value = true
  try {
    const ticketNo = detail.value.ticket.ticketNo
    await assignTicket(ticketNo, {
      toUserId: assignForm.toUserId,
      toUserName: colleagues.value.find((item) => item.userId === assignForm.toUserId)?.name,
      remark: assignForm.remark.trim() || undefined,
    })
    ElMessage.success('已转派')
    assignOpen.value = false
    await refreshAll()
    await openDetail(ticketNo)
  } finally {
    acting.value = false
  }
}

async function submitReply() {
  if (!detail.value) {
    return
  }
  if (!replyForm.content.trim()) {
    ElMessage.warning(isPlatformTicket.value ? '请先写补充内容' : '请先写回复内容')
    return
  }
  acting.value = true
  try {
    const ticketNo = detail.value.ticket.ticketNo
    if (isPlatformTicket.value) {
      // 平台支持工单：企业只能补充说明（不算平台首次响应，也不改状态）
      await supplementTicket(ticketNo, replyForm.content.trim())
      ElMessage.success('已补充说明，平台侧能看到')
    } else {
      await replyTicket(ticketNo, {
        content: replyForm.content.trim(),
        visibleToCustomer: replyForm.visibleToCustomer,
      })
      ElMessage.success(replyForm.visibleToCustomer ? '已回复（计入首次响应）' : '已记录内部备注')
    }
    replyForm.content = ''
    await refreshAll()
    await openDetail(ticketNo)
  } finally {
    acting.value = false
  }
}

function openStatus(status: number) {
  statusForm.status = status
  statusForm.remark = ''
  statusForm.actionText = status === 5 ? '关闭' : status === 4 ? '标记已解决' : status === 3 ? '标记待客户确认' : '重新打开'
  statusOpen.value = true
}

async function submitStatus() {
  if (!detail.value) {
    return
  }
  acting.value = true
  try {
    const ticketNo = detail.value.ticket.ticketNo
    await changeTicketStatus(ticketNo, { status: statusForm.status, remark: statusForm.remark.trim() || undefined })
    ElMessage.success('状态已更新')
    statusOpen.value = false
    await refreshAll()
    await openDetail(ticketNo)
  } finally {
    acting.value = false
  }
}

async function openSlaRules() {
  slaRules.value = await fetchTicketSlaRules()
  slaOpen.value = true
}

async function submitSlaRules() {
  acting.value = true
  try {
    slaRules.value = await saveTicketSlaRules(
      slaRules.value.map((rule) => ({
        priority: rule.priority,
        firstResponseMinutes: rule.firstResponseMinutes,
        resolveMinutes: rule.resolveMinutes,
        enabled: rule.enabled,
      })),
    )
    ElMessage.success('SLA 规则已保存（只影响之后新建的工单）')
    slaOpen.value = false
  } finally {
    acting.value = false
  }
}

/** 从工单跳回工作台，坐席能看到当时的完整对话（工单闭环的关键一步） */
function goSession(sessionNo?: string | null) {
  if (!sessionNo) {
    return
  }
  void router.push({ path: '/modules/workspace', query: { sessionNo } })
}

/**
 * 升级工单：优先级提一档 + 记一条流转 + 通知处理人与全组。
 *
 * <p>产品口径：升级只提优先级，**不重算 SLA 截止时间**——否则"一升级就不超时"，考核就废了。</p>
 */
async function escalate() {
  if (!detail.value) {
    return
  }
  let remark = ''
  try {
    const result = await ElMessageBox.prompt(
      '升级会把优先级提一档（最高到紧急），并提醒处理人和客服主管；已经在流程里的 SLA 截止时间不会变。',
      '升级工单',
      {
        inputPlaceholder: '升级原因（可选，例如：客户已投诉 / 影响范围扩大）',
        confirmButtonText: '确认升级',
        cancelButtonText: '取消',
      },
    )
    remark = String(result.value || '')
  } catch {
    return // 点了取消
  }
  acting.value = true
  try {
    const ticketNo = detail.value.ticket.ticketNo
    detail.value = await escalateTicket(ticketNo, { remark: remark.trim() || undefined })
    ElMessage.success('已升级：优先级提高一档，处理人与主管都会收到提醒')
    await refreshAll()
  } finally {
    acting.value = false
  }
}

/** 复制租户号：提给平台排查时要和工单号一起给（工单号只在租户内唯一） */
async function copyTenant(code?: string | null) {
  if (!code) {
    return
  }
  const ok = await copyText(code)
  if (ok) {
    ElMessage.success('已复制租户号')
  } else {
    ElMessage.warning('复制失败，请手动选中复制')
  }
}

/** 从工作台"会话转工单"时带着会话号进来（query: sessionNo=...） */
function openCreateFromQuery() {
  const sessionNo = String(router.currentRoute.value.query.sessionNo ?? '').trim()
  if (!sessionNo) {
    return
  }
  // 只读角色（质检专员 / AI运营）从工作台点"转工单"过来：直接说清楚，别让人填完表单才报错
  if (access.value && !access.value.canOperate) {
    ElMessage.warning(`当前角色（${access.value.roleName}）只能查看工单，不能新建`)
    void router.replace({ path: '/modules/tickets' })
    return
  }
  openCreate({ sessionNo, title: '会话转工单', content: '' })
  void router.replace({ path: '/modules/tickets' })
}

onMounted(() => {
  openCreateFromQuery()
})

defineExpose({ openCreate })
</script>
<style scoped>
.ticket-page { width: 100%; }
.page-title-row { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 16px; gap: 12px; }
.page-title { margin: 0; font-size: 20px; color: #0f172a; }
.page-sub { margin: 6px 0 0; font-size: 13px; color: #94a3b8; }
.role-line { margin: 6px 0 0; font-size: 12px; color: #94a3b8; }
.role-line b { color: #2563eb; }
.head-actions { display: flex; align-items: center; gap: 12px; }
.btn-icon { margin-right: 4px; }
.stat-row { display: grid; grid-template-columns: repeat(6, 1fr); gap: 12px; margin-bottom: 14px; }
.stat-card { display: flex; align-items: center; gap: 12px; background: #fff; border: 1px solid #e6e8ef; border-radius: 14px; padding: 14px 16px; }
.stat-icon { width: 38px; height: 38px; border-radius: 10px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.stat-icon.blue { background: #eef4ff; color: #2563eb; }
.stat-icon.amber { background: #fff7ed; color: #d97706; }
.stat-icon.green { background: #ecfdf5; color: #059669; }
.stat-icon.red { background: #fef2f2; color: #dc2626; }
.stat-icon.purple { background: #f5f3ff; color: #7c3aed; }
.stat-value { font-size: 22px; font-weight: 800; color: #0f172a; line-height: 1.2; }
.stat-label { margin-top: 3px; font-size: 12px; color: #94a3b8; }
.main-card { width: 100%; }
.filter-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; flex-wrap: wrap; }
.filter-left { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.f-select { width: 138px; }
.f-search { width: 240px; }
.mine-hint { font-size: 13px; color: #94a3b8; }
.filter-right { display: flex; align-items: center; gap: 12px; }
/* 看板：原型里的四/五列布局，卡片拖到别的列就是改状态 */
.kanban { display: grid; grid-template-columns: repeat(5, minmax(200px, 1fr)); gap: 12px; }
.kcol { background: #f8fafc; border: 1px solid #eef2f7; border-radius: 12px; padding: 10px; min-height: 200px; }
.kcol-head { display: flex; align-items: center; gap: 6px; padding: 2px 2px 10px; }
.kcol-head .kt { font-weight: 700; color: #334155; font-size: 13px; }
.kcol-head .kn { margin-left: auto; font-size: 12px; color: #94a3b8; }
.kcol-head .dot { width: 7px; height: 7px; border-radius: 50%; display: inline-block; }
.kcol-head .dot.amber { background: #f59e0b; }
.kcol-head .dot.blue { background: #2563eb; }
.kcol-head .dot.green { background: #16a34a; }
.kcol-head .dot.gray { background: #94a3b8; }
.kcol-body { display: flex; flex-direction: column; gap: 8px; }
.kcard { background: #fff; border: 1px solid #e6e8ef; border-radius: 10px; padding: 10px 12px; cursor: pointer; }
.kcard:hover { border-color: #bfdbfe; box-shadow: 0 2px 8px rgba(37, 99, 235, 0.08); }
.kcard.is-draggable { cursor: grab; }
.kc-top { display: flex; align-items: flex-start; gap: 8px; }
.kc-title { flex: 1; font-size: 13px; font-weight: 600; color: #1e293b; line-height: 1.5; }
.kc-sub { margin-top: 6px; font-size: 12px; color: #94a3b8; }
.kc-foot { margin-top: 8px; display: flex; align-items: center; gap: 6px; font-size: 11px; color: #94a3b8; }
.kc-sla { margin-left: auto; display: inline-flex; align-items: center; gap: 3px; font-weight: 600; }
.badge-wait { padding: 1px 6px; border-radius: 4px; background: #f1f5f9; color: #64748b; }
.kempty { padding: 14px 0; text-align: center; color: #cbd5e1; font-size: 12px; }
.ticket-table :deep(.row-overdue) { background: #fff7f7; }
.ticket-table :deep(.row-warning) { background: #fffdf5; }
.ticket-title { font-weight: 600; color: #1e293b; }
.muted { color: #94a3b8; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
.cell-sub { color: #94a3b8; font-size: 12px; margin-top: 2px; }
.member-cell { display: inline-flex; align-items: center; gap: 6px; color: #475569; }
.member-avatar { width: 24px; height: 24px; border-radius: 7px; color: #fff; display: inline-flex; align-items: center; justify-content: center; font-weight: 700; background: linear-gradient(135deg, #1d4ed8, #3b82f6); font-size: 12px; }
.sla-cell { display: inline-flex; align-items: center; gap: 4px; font-weight: 600; font-size: 13px; }
.sla-1 { color: #16a34a; }
.sla-2 { color: #d97706; }
.sla-3 { color: #dc2626; }
.table-foot { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 12px; font-size: 12px; flex-wrap: wrap; }
/* 分页条：和「企业审核」那一页保持同一种观感（右对齐、不额外留上边距） */
.page-bar { margin-left: auto; }
.detail-panel { padding: 0 4px 8px; }
.detail-head { display: flex; flex-direction: column; gap: 10px; }
.detail-title { font-size: 17px; font-weight: 700; color: #0f172a; }
.detail-badges { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.sla-pill { display: inline-flex; align-items: center; gap: 4px; border-radius: 999px; padding: 3px 10px; font-size: 12px; font-weight: 600; background: #f1f5f9; }
.sla-track { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin: 14px 0; }
.sla-track-item { border: 1px solid #e6e8ef; border-radius: 12px; padding: 12px 14px; background: #f8fafc; }
.track-label { font-size: 12px; color: #94a3b8; }
.track-value { margin-top: 6px; font-weight: 700; color: #b45309; }
.track-value.done { color: #16a34a; }
.info-card { background: #f8fafc; border: 1px solid #e6e8ef; border-radius: 12px; padding: 6px 16px; }
.info-row { display: flex; justify-content: space-between; gap: 20px; padding: 9px 0; color: #475569; font-size: 13px; }
.info-row + .info-row { border-top: 1px dashed #e2e8f0; }
.info-row .v { color: #0f172a; text-align: right; }
.detail-section-title { margin: 18px 0 10px; font-weight: 700; color: #0f172a; font-size: 14px; }
.section-sub { margin-left: 8px; font-weight: 400; font-size: 12px; color: #94a3b8; }
.detail-content { margin: 0; padding: 12px 14px; background: #f8fafc; border: 1px solid #eef2f7; border-radius: 10px; font-size: 13px; color: #334155; line-height: 1.8; white-space: pre-wrap; word-break: break-word; font-family: inherit; }
.event-line { padding-left: 2px; }
.event-head { display: flex; align-items: center; gap: 8px; }
.event-type { font-weight: 700; color: #1e293b; font-size: 13px; }
.event-who { color: #64748b; font-size: 12px; }
.event-flow { color: #2563eb; font-size: 12px; }
.event-note { color: #b45309; background: #fff7ed; border-radius: 4px; padding: 0 6px; font-size: 11px; }
.event-content { margin-top: 4px; font-size: 13px; color: #334155; white-space: pre-wrap; word-break: break-word; }
.reply-actions { display: flex; align-items: center; gap: 10px; margin-top: 10px; }
.spacer { flex: 1; }
.drawer-foot { display: flex; align-items: center; gap: 10px; width: 100%; }
.full-width { width: 100%; }
.sla-tip { margin: 0 0 14px; font-size: 13px; color: #64748b; line-height: 1.7; }
.closed-tip { margin-bottom: 8px; padding: 8px 12px; border-radius: 8px; background: #f1f5f9; color: #64748b; font-size: 12px; }
.dialog-tip { margin: -4px 0 14px; padding: 9px 12px; border-radius: 8px; background: #fff7ed; border: 1px solid #fed7aa; color: #b45309; font-size: 12px; line-height: 1.7; }
</style>

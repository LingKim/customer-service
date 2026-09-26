<template>
  <div class="customer-page">
    <div class="page-title-row">
      <div>
        <h2 class="page-title">客户 360</h2>
        <p class="page-sub">
          客户画像 · 标签体系 · 会话轨迹：接起会话之前，先看清对面是谁、之前发生过什么
        </p>
        <p v-if="access" class="role-line">
          当前角色：<b>{{ access.roleName }}</b>
          <span v-if="access.hint">· {{ access.hint }}</span>
        </p>
      </div>
      <div class="head-actions">
        <el-button :loading="loading" @click="refreshAll">
          <el-icon class="btn-icon"><Refresh /></el-icon>
          刷新
        </el-button>
        <el-button @click="router.push('/modules/customers/tags')">
          <el-icon class="btn-icon"><PriceTag /></el-icon>
          标签体系
        </el-button>
        <el-button @click="exportCsv">
          <el-icon class="btn-icon"><Download /></el-icon>
          导出
        </el-button>
      </div>
    </div>
    <div class="stat-row">
      <div class="stat-card">
        <div class="stat-icon blue"><el-icon :size="18"><UserFilled /></el-icon></div>
        <div><div class="stat-value">{{ overview?.total ?? '—' }}</div><div class="stat-label">客户总数</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon green"><el-icon :size="18"><Plus /></el-icon></div>
        <div><div class="stat-value">{{ overview?.monthNew ?? '—' }}</div><div class="stat-label">本月新增</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon purple"><el-icon :size="18"><ChatDotRound /></el-icon></div>
        <div><div class="stat-value">{{ overview?.active7d ?? '—' }}</div><div class="stat-label">近 7 天活跃</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon red"><el-icon :size="18"><WarningFilled /></el-icon></div>
        <div><div class="stat-value">{{ overview?.riskCount ?? '—' }}</div><div class="stat-label">风险 / 关注客户</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon amber"><el-icon :size="18"><PriceTag /></el-icon></div>
        <div><div class="stat-value">{{ overview?.taggedCount ?? '—' }}</div><div class="stat-label">已打标签</div></div>
      </div>
    </div>
    <el-card shadow="never" class="filter-card">
      <el-form class="filter-form">
        <el-form-item>
          <el-input
            v-model="query.keyword"
            class="w-240"
            clearable
            placeholder="姓名 / 客户编号 / 手机号"
            @keydown.enter="reload"
          />
        </el-form-item>
        <el-form-item>
          <el-select v-model="query.level" class="w-140" placeholder="全部等级" @change="reload">
            <el-option :value="0" label="全部等级" />
            <el-option v-for="item in levels" :key="item.value" :value="item.value" :label="item.label" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-select v-model="query.riskLevel" class="w-140" placeholder="全部风险等级" @change="reload">
            <el-option :value="0" label="全部风险等级" />
            <el-option :value="1" label="正常" />
            <el-option :value="2" label="关注" />
            <el-option :value="3" label="风险" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-select v-model="query.customerType" class="w-140" placeholder="全部客户类型" @change="reload">
            <el-option :value="0" label="全部客户类型" />
            <el-option :value="1" label="个人客户" />
            <el-option :value="2" label="企业客户" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-select v-model="query.tagId" class="w-180" placeholder="全部标签" @change="reload">
            <el-option value="" label="全部标签" />
            <el-option v-for="tag in tagDefs" :key="tag.id" :value="tag.id" :label="`${tag.tagGroup} · ${tag.tagName}`" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-select v-model="query.activeDays" class="w-140" placeholder="活跃时间" @change="reload">
            <el-option :value="0" label="不限活跃" />
            <el-option :value="1" label="今天活跃" />
            <el-option :value="7" label="近 7 天活跃" />
            <el-option :value="30" label="近 30 天活跃" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="query.hasTicket" @change="reload">只看有工单的</el-checkbox>
        </el-form-item>
        <el-form-item class="filter-right">
          <el-select v-model="query.sort" class="w-160" @change="reload">
            <el-option value="active" label="按最近活跃" />
            <el-option value="sessions" label="按会话数" />
            <el-option value="csat" label="按满意度" />
            <el-option value="level" label="按会员等级" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="reload">查询</el-button>
          <el-button @click="resetFilter">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>
    <el-card shadow="never" class="main-card">
      <!-- 列表接口失败时把原因摆在脸上：以前这里只是"空列表 + 半截分页"，
           看起来像分页坏了，其实多半是数据库结构没跟上（列不存在会 500） -->
      <el-alert
        v-if="loadError"
        class="load-error"
        type="error"
        show-icon
        :closable="false"
        :title="`客户列表加载失败：${loadError}`"
      >
        <div class="load-error-tip">
          如果提示某个列 / 表不存在（例如 column "customer_type" does not exist），说明数据库结构没跟上：
          执行 <code>bash scripts/migrate-customer-db.sh</code>，或重启 customer-service（启动自检会自动补齐），然后刷新本页。
        </div>
      </el-alert>
      <!-- 批量动作条：勾选客户之后才有意义，所以放在表格上方、默认禁用 -->
      <div class="bulk-bar">
        <span class="bulk-info">已选 <b>{{ selected.length }}</b> 位客户</span>
        <el-select v-model="batchTagId" class="w-200" placeholder="选择要打的标签" filterable :disabled="!access?.canOperate">
          <el-option-group v-for="group in tagGroupNames" :key="group" :label="group">
            <el-option
              v-for="tag in tagsOfGroup(group)"
              :key="tag.id"
              :value="tag.id"
              :label="tag.tagName"
            />
          </el-option-group>
        </el-select>
        <el-button
          type="primary"
          plain
          :disabled="!access?.canOperate || !selected.length || !batchTagId"
          :loading="batching"
          @click="submitBatchTag"
        >
          批量打标
        </el-button>
        <el-button :disabled="!access?.canOperate" @click="importVisible = true">
          <el-icon class="btn-icon"><Upload /></el-icon>
          导入客户
        </el-button>
        <el-button v-if="access?.canManageTag" :loading="recalculating" @click="recalculateAll">
          <el-icon class="btn-icon"><MagicStick /></el-icon>
          重算规则标签
        </el-button>
        <span class="spacer"></span>
      </div>
      <el-table
        ref="tableRef"
        v-loading="loading"
        :data="rows"
        stripe
        class="customer-table"
        @selection-change="onSelectionChange"
      >
        <el-table-column type="selection" width="46" />
        <el-table-column type="index" label="序号" width="76" align="center" />
        <el-table-column label="客户" min-width="270">
          <template #default="{ row }">
            <div class="customer-cell">
              <div class="avatar" :class="avatarClass(row)">{{ avatarText(row.name) }}</div>
              <div class="c-main">
                <div class="c-name">{{ row.name }}</div>
                <el-tooltip :content="row.customerNo" placement="top">
                  <div class="c-no mono">{{ row.customerNo }}</div>
                </el-tooltip>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="会员等级" width="100">
          <template #default="{ row }">
            <span class="level-text" :class="`lv-${row.level}`">{{ row.levelText }}</span>
          </template>
        </el-table-column>
        <el-table-column label="客户类型" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.customerType === 2" size="small" effect="light" type="warning">企业客户</el-tag>
            <span v-else class="muted">个人客户</span>
          </template>
        </el-table-column>
        <el-table-column label="风险" width="84">
          <template #default="{ row }">
            <el-tag v-if="row.riskLevel >= 2" size="small" effect="dark" :type="row.riskLevel === 3 ? 'danger' : 'warning'">
              {{ row.riskText }}
            </el-tag>
            <span v-else class="muted">正常</span>
          </template>
        </el-table-column>
        <el-table-column label="标签" min-width="200">
          <template #default="{ row }">
            <div class="tag-cell">
              <el-tag
                v-for="tag in row.tags.slice(0, 3)"
                :key="tag.tagId || tag.tagName"
                size="small"
                effect="light"
                :type="tagTypeOf(tag.color)"
              >
                {{ tag.tagName }}
              </el-tag>
              <el-tooltip v-if="row.tags.length > 3" :content="tagNames(row.tags)">
                <span class="more-tag">+{{ row.tags.length - 3 }}</span>
              </el-tooltip>
              <span v-if="!row.tags.length" class="muted">—</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="互动" width="140">
          <template #default="{ row }">
            <div class="cell-sub">会话 {{ row.sessionCount ?? 0 }} · 转人工 {{ row.humanCount ?? 0 }}</div>
            <div class="cell-sub">工单 {{ row.ticketCount ?? 0 }}</div>
          </template>
        </el-table-column>
        <el-table-column label="满意度" width="84">
          <template #default="{ row }">
            <span v-if="row.csat" class="csat" :class="csatClass(row.csat)">{{ Number(row.csat).toFixed(1) }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="最近一次会话" min-width="190">
          <template #default="{ row }">
            <div v-if="row.lastSessionNo">
              <div class="cell-sub">意图：{{ row.lastIntent || '未识别' }} · 情绪：{{ row.lastEmotion || '中性' }}</div>
              <el-tooltip :content="row.lastSessionNo" placement="top">
                <div class="cell-sub mono nowrap">{{ row.lastSessionNo }}</div>
              </el-tooltip>
            </div>
            <span v-else class="muted">还没有会话</span>
          </template>
        </el-table-column>
        <el-table-column label="最近活跃" width="160">
          <template #default="{ row }">{{ formatTime(row.lastActive) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="176" fixed="right" class-name="op-cell">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">客户 360</el-button>
            <el-button link type="primary" @click="openSessions(row)">会话轨迹</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
    <!-- 分页条放在卡片外面并吸在视口底部：
         表格不再限高（滚动条交回页面，永远贴在最右侧），翻页也不会被长表格顶到屏幕外 -->
    <div class="table-foot sticky-foot">
      <span class="muted">
        <template v-if="total">共 {{ total }} 位客户 · {{ sortLabel }} · 本页 {{ rows.length }} 位</template>
        <template v-else>
          还没有客户数据：可执行 <code>bash scripts/init-customer-demo.sh</code> 灌演示数据；
          访客进线也会自动建档
        </template>
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
    <!-- 客户 360 抽屉：画像 / 标签 / 会话轨迹 / 客户动态 -->
    <el-drawer v-model="detailVisible" size="62%" :destroy-on-close="true" class="customer-drawer">
      <template #header>
        <div v-if="detail" class="drawer-head">
          <div class="avatar-lg" :class="avatarClass(detail.customer)">{{ avatarText(detail.customer.name) }}</div>
          <div class="head-main">
            <div class="head-name">
              {{ detail.customer.name }}
              <span class="level-text" :class="`lv-${detail.customer.level}`">{{ detail.customer.levelText }}</span>
              <el-tag v-if="detail.customer.customerType === 2" size="small" effect="light" type="warning">企业客户</el-tag>
              <el-tag v-if="detail.customer.riskLevel >= 2" size="small" effect="dark"
                      :type="detail.customer.riskLevel === 3 ? 'danger' : 'warning'">
                {{ detail.customer.riskText }}
              </el-tag>
            </div>
            <div class="head-sub">
              <span class="mono">{{ detail.customer.customerNo }}</span>
              <span>· {{ fullPhone || detail.customer.phone || '未留手机号' }}</span>
              <el-button
                v-if="access?.canSeePhone && detail.customer.phone"
                link
                type="primary"
                :loading="revealing"
                @click="revealPhone"
              >
                {{ fullPhone ? '已显示完整号码' : '查看完整手机号' }}
              </el-button>
              <el-tooltip
                v-else-if="detail.customer.phone"
                content="完整手机号只有企业管理员 / 客服主管能看，且每次查看都会留痕"
                placement="top"
              >
                <span class="lock-hint"><el-icon><Lock /></el-icon> 已脱敏</span>
              </el-tooltip>
              <span v-if="detail.customer.channel">· 常用渠道 {{ detail.customer.channel }}</span>
              <el-tag v-if="detail.customer.anonymizedAt" size="small" type="info" effect="plain">已匿名化</el-tag>
            </div>
          </div>
        </div>
      </template>
      <div v-if="detail" class="detail-body">
        <div class="metric-row">
          <div class="metric"><div class="mv">{{ detail.customer.sessionCount ?? 0 }}</div><div class="ml">累计会话</div></div>
          <div class="metric"><div class="mv">{{ detail.customer.humanCount ?? 0 }}</div><div class="ml">转人工</div></div>
          <div class="metric"><div class="mv">{{ detail.customer.ticketCount ?? 0 }}</div><div class="ml">工单</div></div>
          <div class="metric">
            <div class="mv" :class="csatClass(detail.customer.csat)">
              {{ detail.customer.csat ? Number(detail.customer.csat).toFixed(1) : '—' }}
            </div>
            <div class="ml">满意度</div>
          </div>
        </div>
        <el-tabs v-model="detailTab" class="detail-tabs">
          <el-tab-pane label="画像" name="profile">
            <div class="info-card">
              <div class="info-row">
                <span>最近一次会话</span>
                <span class="v">
                  <span v-if="detail.customer.lastSessionNo" class="mono">{{ detail.customer.lastSessionNo }}</span>
                  <span v-else class="muted">还没有会话</span>
                </span>
              </div>
              <div class="info-row">
                <span>最近意图 / 情绪</span>
                <span class="v">{{ detail.customer.lastIntent || '未识别' }} / {{ detail.customer.lastEmotion || '中性' }}</span>
              </div>
              <div class="info-row"><span>最近会话时间</span><span class="v">{{ formatTime(detail.customer.lastSessionAt) }}</span></div>
              <div class="info-row"><span>最近活跃时间</span><span class="v">{{ formatTime(detail.customer.lastActive) }}</span></div>
              <div class="info-row"><span>建档时间</span><span class="v">{{ formatTime(detail.customer.createTime) }}</span></div>
              <div class="info-row"><span>客户备注</span><span class="v">{{ detail.customer.remark || '—' }}</span></div>
            </div>
            <div class="section-title">
              关联工单<span class="section-sub">近 {{ detail.tickets.length }} 张</span>
              <el-button
                v-if="access?.canOperate"
                class="title-action"
                link
                type="primary"
                @click="createTicketForCustomer"
              >
                + 新建工单
              </el-button>
            </div>
            <el-table :data="pagedTickets" size="small" class="mini-table" empty-text="这个客户还没有工单"
                      @row-click="gotoTicket">
              <el-table-column type="index" label="序号" width="64" align="center" />
              <el-table-column prop="ticketNo" label="工单号" min-width="180" show-overflow-tooltip />
              <el-table-column prop="title" label="主题" min-width="220" show-overflow-tooltip />
              <el-table-column label="优先级" width="90">
                <template #default="{ row }">
                  <el-tag size="small" effect="plain"
                          :type="row.priority >= 4 ? 'danger' : row.priority === 3 ? 'warning' : 'info'">
                    {{ row.priorityText }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="statusText" label="状态" width="110" />
              <el-table-column label="处理人" width="110">
                <template #default="{ row }">{{ row.assigneeName || '未认领' }}</template>
              </el-table-column>
              <el-table-column label="创建时间" width="170">
                <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
              </el-table-column>
            </el-table>
            <div v-if="detail.tickets.length" class="mini-foot">
              <span class="muted">共 {{ detail.tickets.length }} 张</span>
              <el-pagination
                v-model:current-page="ticketPage"
                :page-size="minPageSize"
                :total="detail.tickets.length"
                layout="total, prev, pager, next"
                background
                size="small"
              />
            </div>
          </el-tab-pane>
          <el-tab-pane :label="`标签（${detail.customer.tags.length}）`" name="tags">
            <div v-if="access?.canOperate" class="tag-editor">
              <el-select v-model="tagToAdd" class="w-240" placeholder="选择一个标签" filterable>
                <el-option-group v-for="group in tagGroupNames" :key="group" :label="group">
                  <el-option
                    v-for="tag in tagsOfGroup(group)"
                    :key="tag.id"
                    :value="tag.id"
                    :label="tag.tagName"
                    :disabled="hasTag(tag.id)"
                  />
                </el-option-group>
              </el-select>
              <el-input v-model="tagRemark" class="w-240" maxlength="64" placeholder="打标说明（可选，会写进客户动态）" />
              <el-button type="primary" :loading="saving" @click="submitTag">打标</el-button>
              <el-button @click="router.push('/modules/customers/tags')">管理标签体系</el-button>
            </div>
            <p v-else class="muted tip-line">当前角色只能查看客户资料，不能打标（需要打标请找客服同事）。</p>
            <div v-for="group in tagGroupsWithTags" :key="group.name" class="tag-group">
              <div class="tg-head">{{ group.name }}<span class="tg-count">{{ group.tags.length }}</span></div>
              <div class="tg-body">
                <el-tag
                  v-for="tag in group.tags"
                  :key="tag.tagId || tag.tagName"
                  size="large"
                  effect="light"
                  :type="tagTypeOf(tag.color)"
                  :closable="Boolean(access?.canOperate)"
                  @close="submitRemoveTag(tag)"
                >
                  {{ tag.tagName }}
                  <span v-if="tag.sourceText" class="tag-src">{{ tag.sourceText }}</span>
                </el-tag>
                <span v-if="!group.tags.length" class="muted">这一组还没有标签</span>
              </div>
            </div>
            <p v-if="!detail.customer.tags.length" class="muted tip-line">这个客户还没有任何标签。</p>
          </el-tab-pane>
          <el-tab-pane label="会话轨迹" name="sessions">
            <el-table v-loading="traceLoading" :data="pagedTraces" size="small" class="mini-table" empty-text="还没有会话记录">
              <el-table-column type="index" label="序号" width="64" align="center" />
              <el-table-column label="会话号" min-width="190">
                <template #default="{ row }"><span class="mono">{{ row.sessionNo }}</span></template>
              </el-table-column>
              <el-table-column label="渠道" width="110">
                <template #default="{ row }">{{ row.channelName || row.source || '—' }}</template>
              </el-table-column>
              <el-table-column label="接待" width="110">
                <template #default="{ row }">
                  <el-tag size="small" effect="light" :type="sessionTagType(row.status)">{{ row.statusText }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="意图 / 情绪" min-width="150">
                <template #default="{ row }">{{ row.intent || '未识别' }} / {{ row.emotion || '中性' }}</template>
              </el-table-column>
              <el-table-column label="消息" width="80">
                <template #default="{ row }">{{ row.msgCount ?? 0 }}</template>
              </el-table-column>
              <el-table-column label="时长" width="100">
                <template #default="{ row }">{{ row.durationMinutes == null ? '—' : row.durationMinutes + ' 分钟' }}</template>
              </el-table-column>
              <el-table-column label="满意度" width="90">
                <template #default="{ row }">
                  <span v-if="row.csatScore" :class="csatClass(row.csatScore)">{{ row.csatScore }} 分</span>
                  <span v-else class="muted">未评价</span>
                </template>
              </el-table-column>
              <el-table-column label="工单" width="130">
                <template #default="{ row }">
                  <el-button v-if="row.ticketNo" link type="primary" @click="gotoTicket({ ticketNo: row.ticketNo })">
                    {{ row.ticketNo }}
                  </el-button>
                  <span v-else class="muted">—</span>
                </template>
              </el-table-column>
              <el-table-column label="开始时间" width="170">
                <template #default="{ row }">{{ formatTime(row.startTime) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="110" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" @click="openTranscript(row)">聊天记录</el-button>
                  <el-button v-if="row.status !== 4" link type="primary" @click="gotoWorkspace(row)">去接待</el-button>
                </template>
              </el-table-column>
            </el-table>
            <div v-if="traces.length" class="mini-foot">
              <span class="muted">共 {{ traces.length }} 条会话</span>
              <el-pagination
                v-model:current-page="tracePage"
                :page-size="minPageSize"
                :total="traces.length"
                layout="total, prev, pager, next"
                background
                size="small"
              />
            </div>
            <p class="muted tip-line">
              「接待」这一列能看出哪几次是机器人先接的、哪几次直接进了人工；转人工原因记在会话上
              （客户情绪激动 / 答不上来 / 命中转人工意图），复盘"为什么没解决"时先看这一列。
            </p>
          </el-tab-pane>
          <el-tab-pane :label="`客户动态（${events.length}）`" name="events">
            <el-timeline v-if="events.length" class="event-timeline">
              <el-timeline-item
                v-for="item in events"
                :key="item.id"
                :timestamp="formatTime(item.eventTime)"
                placement="top"
                :type="eventColor(item.eventType)"
              >
                <div class="event-item">
                  <span class="event-type">{{ item.eventTypeText }}</span>
                  <span class="event-title">{{ item.title }}</span>
                  <span class="event-who">{{ item.operatorName }}</span>
                </div>
                <div v-if="item.content" class="event-content">{{ item.content }}</div>
              </el-timeline-item>
            </el-timeline>
            <el-empty v-else description="还没有客户动态" />
          </el-tab-pane>
        </el-tabs>
      </div>
      <template #footer>
        <div class="drawer-foot">
          <el-button v-if="access?.canOperate" @click="openProfileEdit">
            <el-icon class="btn-icon"><EditPen /></el-icon>
            编辑档案
          </el-button>
          <el-button v-if="access?.canOperate" :loading="recounting" @click="recountTags">
            <el-icon class="btn-icon"><MagicStick /></el-icon>
            重算自动标签
          </el-button>
          <el-dropdown v-if="access?.canManageTag" trigger="click" @command="handleAdminCommand">
            <el-button>
              <el-icon class="btn-icon"><Operation /></el-icon>
              更多
              <el-icon class="caret"><ArrowDown /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="merge">合并到另一个客户</el-dropdown-item>
                <el-dropdown-item command="anonymize" divided>匿名化（抹 PII 保留统计）</el-dropdown-item>
                <el-dropdown-item command="delete">删除客户</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <span class="spacer"></span>
          <el-button @click="detailVisible = false">关闭</el-button>
        </div>
      </template>
    </el-drawer>
    <!-- 合并客户 -->
    <el-dialog v-model="mergeVisible" title="合并到另一个客户" width="560px">
      <div class="dialog-tip">
        会把当前客户的会话 / 工单 / 订单 / 标签 / 动态迁到目标客户，当前客户从列表里消失（可回溯）。
        访客清缓存再进线会重复建档，同一个人出现两条时用这个。
      </div>
      <el-form label-width="90px">
        <el-form-item label="当前客户">
          <span class="mono">{{ detail?.customer.customerNo }}（{{ detail?.customer.name }}）</span>
        </el-form-item>
        <el-form-item label="合并到">
          <el-select v-model="mergeTarget" class="full-width" filterable remote :remote-method="searchMergeTargets"
                     placeholder="输入姓名 / 客户编号搜索">
            <el-option
              v-for="item in mergeCandidates"
              :key="item.customerNo"
              :value="item.customerNo"
              :label="`${item.name}（${item.customerNo}）`"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="mergeVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!mergeTarget" :loading="saving" @click="submitMerge">确认合并</el-button>
      </template>
    </el-dialog>
    <!-- 导入客户 -->
    <el-dialog v-model="importVisible" title="导入客户（CSV）" width="620px">
      <div class="dialog-tip">
        第一行是表头：<code>姓名,手机号,会员等级,标签,备注,客户类型</code>。会员等级可写 1~5 或中文名
        （普通/银卡/金卡/铂金/钻石会员）；客户类型写「个人 / 企业」；标签用 <code>|</code> 分隔
        （标签体系里没有的名字会被跳过并提示）。按手机号匹配已有客户，匹配不到就新建。
      </div>
      <el-upload
        drag
        :auto-upload="false"
        :limit="1"
        accept=".csv,text/csv"
        :on-change="onImportFileChange"
        :on-remove="() => (importFile = null)"
      >
        <el-icon class="el-icon--upload"><Upload /></el-icon>
        <div class="el-upload__text">把 CSV 拖到这里，或<em>点击选择文件</em></div>
      </el-upload>
      <div v-if="importResult" class="import-result">
        <div class="ir-line">
          共 {{ importResult.total }} 行：新建 <b>{{ importResult.created }}</b> 位、
          更新 <b>{{ importResult.updated }}</b> 位、打标 <b>{{ importResult.tagged }}</b> 次
          <span v-if="importResult.failures.length">，{{ importResult.failures.length }} 行有问题</span>
        </div>
        <ul v-if="importResult.failures.length" class="ir-failures">
          <li v-for="(item, index) in importResult.failures.slice(0, 20)" :key="index">
            第 {{ item.line }} 行：{{ item.reason }}
          </li>
        </ul>
      </div>
      <template #footer>
        <el-button @click="importVisible = false">关闭</el-button>
        <el-button type="primary" :disabled="!importFile" :loading="importing" @click="submitImport">开始导入</el-button>
      </template>
    </el-dialog>
    <!-- 编辑档案：等级 / 风险 / 备注 -->
    <el-dialog v-model="profileVisible" title="编辑客户档案" width="520px">
      <el-form label-width="88px">
        <el-form-item label="客户姓名">
          <el-input v-model="profileForm.name" maxlength="64" placeholder="客户姓名 / 昵称" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="profileForm.phone" maxlength="20" placeholder="11 位手机号" />
        </el-form-item>
        <el-form-item label="会员等级">
          <el-select v-model="profileForm.level" class="full-width">
            <el-option v-for="item in levels" :key="item.value" :value="item.value" :label="item.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="客户类型">
          <el-select v-model="profileForm.customerType" class="full-width">
            <el-option :value="1" label="个人客户" />
            <el-option :value="2" label="企业客户（B 端采购）" />
          </el-select>
        </el-form-item>
        <el-form-item label="风险等级">
          <el-select v-model="profileForm.riskLevel" class="full-width">
            <el-option :value="1" label="正常（常规接待）" />
            <el-option :value="2" label="关注（话术收敛、重点记录）" />
            <el-option :value="3" label="风险（升级给主管处理）" />
          </el-select>
        </el-form-item>
        <el-form-item label="客户备注">
          <el-input
            v-model="profileForm.remark"
            type="textarea"
            :rows="3"
            maxlength="255"
            show-word-limit
            placeholder="例如：连续三次投诉物流，接待时注意先安抚"
          />
        </el-form-item>
      </el-form>
      <div class="dialog-tip">等级 / 风险等级 / 备注的改动会写进「客户动态」，交接时别人能看到是谁改的。</div>
      <template #footer>
        <el-button @click="profileVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitProfile">保存</el-button>
      </template>
    </el-dialog>
    <!-- 会话聊天记录（复用在线客服那套消息接口） -->
    <el-dialog v-model="transcriptVisible" :title="`聊天记录 · ${transcriptNo}`" width="720px">
      <div v-loading="transcriptLoading" class="transcript">
        <div v-for="(msg, index) in transcript" :key="index" class="msg-row" :class="`sender-${msg.senderType}`">
          <div class="msg-who">{{ senderName(msg.senderType) }}</div>
          <div class="msg-bubble">{{ messageText(msg.content) }}</div>
          <div class="msg-time">{{ formatTime(msg.sendTime) }}</div>
        </div>
        <el-empty v-if="!transcriptLoading && !transcript.length" description="这条会话没有消息" />
      </div>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  addCustomerTag,
  anonymizeCustomer,
  batchAddCustomerTag,
  deleteCustomer,
  downloadCustomersCsv,
  fetchCustomerAccess,
  fetchCustomerDetail,
  fetchCustomerEvents,
  fetchCustomerOverview,
  fetchCustomers,
  fetchCustomerSessions,
  fetchTagDefs,
  importCustomers,
  mergeCustomer,
  recalculateAllTags,
  recalculateCustomerTags,
  removeCustomerTag,
  revealCustomerPhone,
  tagTypeOf,
  updateCustomerProfile,
  type CustomerAccess,
  type CustomerDetail,
  type CustomerEvent,
  type CustomerItem,
  type CustomerOverview,
  type CustomerQuery,
  type CustomerTag,
  type ImportResult,
  type SessionTrace,
  type TagDef,
} from '../../api/customer/customer'
import { listSessionMessages, messageTextOf, type SessionMessageItem } from '../../api/customer/session'

const router = useRouter()

const loading = ref(false)
/** 列表接口的错误信息：失败时留在页面上，而不是只闪一条 toast */
const loadError = ref('')
const saving = ref(false)
const traceLoading = ref(false)
const transcriptLoading = ref(false)

const rows = ref<CustomerItem[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
const overview = ref<CustomerOverview | null>(null)
const access = ref<CustomerAccess | null>(null)
const tagDefs = ref<TagDef[]>([])

const levels = [
  { value: 1, label: '普通会员' },
  { value: 2, label: '银卡会员' },
  { value: 3, label: '金卡会员' },
  { value: 4, label: '铂金会员' },
  { value: 5, label: '钻石会员' },
]

/** 标签分组：打标时先想"这是哪一类"，比在一长串里找更快 */
const tagGroupNames = ['价值', '服务', '风险', '偏好', '来源', '其它']

const query = reactive({
  keyword: '',
  /** 0 = 全部 */
  level: 0,
  riskLevel: 0,
  customerType: 0,
  tagId: '',
  activeDays: 0,
  hasTicket: false,
  sort: 'active',
})

const detailVisible = ref(false)
const detailTab = ref('profile')
const detail = ref<CustomerDetail | null>(null)
const traces = ref<SessionTrace[]>([])
const events = ref<CustomerEvent[]>([])
const tagToAdd = ref('')
const tagRemark = ref('')

const profileVisible = ref(false)
const profileForm = reactive({ name: '', phone: '', level: 1, customerType: 1, riskLevel: 1, remark: '' })

/* ---------------- 抽屉里三张明细表的分页（前端分页：数据量本来就不大，一次拉全更省事） ---------------- */
const minPageSize = 10
const ticketPage = ref(1)
const tracePage = ref(1)

const pagedTickets = computed(() => slicePage(detail.value?.tickets ?? [], ticketPage.value))
const pagedTraces = computed(() => slicePage(traces.value, tracePage.value))

/** 分页左边那句人话：说清楚"按什么排的"，坐席不用猜 */
const sortLabel = computed(() => {
  const map: Record<string, string> = {
    active: '按最近活跃倒序',
    sessions: '按会话数倒序',
    csat: '按满意度倒序',
    level: '按会员等级倒序',
  }
  return map[query.sort] ?? '按最近活跃倒序'
})


/** 页内切片：第 N 页取第 (N-1)*size 到 N*size 条 */
function slicePage<T>(list: T[], current: number): T[] {
  const start = (current - 1) * minPageSize
  return list.slice(start, start + minPageSize)
}

const transcriptVisible = ref(false)
const transcriptNo = ref('')
const transcript = ref<SessionMessageItem[]>([])

/** 批量打标 / 导入 / 重算 */
const tableRef = ref()
const selected = ref<CustomerItem[]>([])
const batchTagId = ref('')
const batching = ref(false)
const recalculating = ref(false)
const recounting = ref(false)
const importing = ref(false)
const importVisible = ref(false)
const importFile = ref<File | null>(null)
const importResult = ref<ImportResult | null>(null)

/** 完整手机号：默认不拉，点了「查看完整手机号」才拉（拉一次就留一次痕） */
const fullPhone = ref('')
const revealing = ref(false)

const mergeVisible = ref(false)
const mergeTarget = ref('')
const mergeCandidates = ref<CustomerItem[]>([])

/** 这个客户身上的标签按分组摆开 */
const tagGroupsWithTags = computed(() => {
  const tags = detail.value?.customer.tags ?? []
  const groups = new Map<string, CustomerTag[]>()
  for (const tag of tags) {
    const key = tag.tagGroup || '其它'
    groups.set(key, [...(groups.get(key) ?? []), tag])
  }
  return [...groups.entries()].map(([name, list]) => ({ name, tags: list }))
})

onMounted(() => {
  void refreshAll()
  openFromQuery()
})

/**
 * 从在线客服的「客户 360」点进来（query: customerNo=...）：直接落到这位客户的画像。
 *
 * 打开后把 query 清掉——刷新页面时不该又弹一次抽屉，坐席也可能接着在页面里翻别的客户。
 */
function openFromQuery() {
  const customerNo = String(router.currentRoute.value.query.customerNo ?? '').trim()
  if (!customerNo) {
    return
  }
  detailTab.value = 'profile'
  detailVisible.value = true
  void loadDetail(customerNo)
  void router.replace({ path: '/modules/customers' })
}

async function refreshAll() {
  await Promise.all([loadList(), loadOverview(), loadAccess(), loadTagDefs()])
}

async function loadList() {
  loading.value = true
  loadError.value = ''
  try {
    const data = await fetchCustomers(buildQuery())
    rows.value = data.list ?? []
    // 基础模块统一把 Long 序列化成字符串（防 JS 精度丢失），但 el-pagination 的 total
    // 只收 Number，不转会一直报 "Expected Number with value 65, got String"（工单中心也是这么处理的）
    total.value = Number(data.total) || 0
  } catch (error) {
    // 失败时把行清空、总数归零，并把原因留在页面顶部（否则只会看到一个"空列表"）
    rows.value = []
    total.value = 0
    loadError.value = error instanceof Error ? error.message : String(error)
  } finally {
    loading.value = false
  }
}

async function loadOverview() {
  overview.value = await fetchCustomerOverview()
}

async function loadAccess() {
  access.value = await fetchCustomerAccess()
}

async function loadTagDefs() {
  tagDefs.value = await fetchTagDefs()
}

function buildQuery(): CustomerQuery {
  const params: CustomerQuery = {
    page: page.value,
    pageSize: pageSize.value,
    sort: query.sort,
  }
  if (query.keyword.trim()) {
    params.keyword = query.keyword.trim()
  }
  if (query.level) {
    params.level = query.level
  }
  if (query.riskLevel) {
    params.riskLevel = query.riskLevel
  }
  if (query.customerType) {
    params.customerType = query.customerType
  }
  if (query.tagId) {
    params.tagId = query.tagId
  }
  if (query.activeDays) {
    params.activeDays = query.activeDays
  }
  if (query.hasTicket) {
    params.hasTicket = true
  }
  return params
}

/** 改了筛选条件就回到第 1 页，否则会出现"第 3 页没有数据"的假空列表 */
function reload() {
  page.value = 1
  void loadList()
}

/**
 * 翻页：**必须把新页码写回 `page`**。
 *
 * <p>Element Plus 的 `current-change` 只是把页码当参数抛出来，不会自动改我们的状态。
 * 如果这里直接绑 `loadList`（参数被丢掉），`page` 永远是 1 →
 * 第 2 页请求的还是第 1 页数据，分页控件也会跳回第 1 页。
 * 这就是"点第 2 页还是显示第 1 页内容"的根因。</p>
 */
function onPageChange(next: number) {
  page.value = next
  void loadList()
}

/** 改每页条数：回到第 1 页（否则会出现"第 3 页只剩 2 条"的错觉） */
function onPageSizeChange(size: number) {
  pageSize.value = size
  page.value = 1
  void loadList()
}

function resetFilter() {
  query.keyword = ''
  query.level = 0
  query.riskLevel = 0
  query.customerType = 0
  query.tagId = ''
  query.activeDays = 0
  query.hasTicket = false
  query.sort = 'active'
  reload()
}

async function openDetail(row: CustomerItem) {
  detailTab.value = 'profile'
  detailVisible.value = true
  await loadDetail(row.customerNo)
}

/** 从列表直接看会话轨迹：抽屉打开后落在轨迹页签 */
async function openSessions(row: CustomerItem) {
  detailTab.value = 'sessions'
  detailVisible.value = true
  await loadDetail(row.customerNo)
}

async function loadDetail(customerNo: string) {
  fullPhone.value = ''
  // 换客户时明细表回到第 1 页，否则会出现"新客户 + 停在第 3 页"的空白
  ticketPage.value = 1
  tracePage.value = 1
  detail.value = await fetchCustomerDetail(customerNo)
  await Promise.all([loadTraces(customerNo), loadEvents(customerNo)])
}

function onSelectionChange(rows: CustomerItem[]) {
  selected.value = rows
}

/** 查看完整手机号：只在管理员 / 主管可见（后端也会再校验一次），每次查看都会写进客户动态 */
async function revealPhone() {
  if (!detail.value) {
    return
  }
  revealing.value = true
  try {
    const data = await revealCustomerPhone(detail.value.customer.customerNo)
    fullPhone.value = data.phone || data.masked || ''
    ElMessage.success('已显示完整手机号，这次查看已记入客户动态')
    await loadEvents(detail.value.customer.customerNo)
  } finally {
    revealing.value = false
  }
}

/** 批量打标：勾一批客户打同一个标签 */
async function submitBatchTag() {
  if (!selected.value.length || !batchTagId.value) {
    return
  }
  batching.value = true
  try {
    const result = await batchAddCustomerTag(
      selected.value.map((item) => item.customerNo),
      batchTagId.value,
    )
    ElMessage.success(`已给 ${result.tagged} 位客户打标（跳过 ${result.skipped} 位已有该标签的）`)
    tableRef.value?.clearSelection()
    batchTagId.value = ''
    await refreshAll()
  } finally {
    batching.value = false
  }
}

/** 全量重算规则标签（改了规则阈值之后必须按一次） */
async function recalculateAll() {
  recalculating.value = true
  try {
    const result = await recalculateAllTags()
    ElMessage.success(`已重算 ${result.scanned} 位客户：新打标 ${result.tagged} 次、摘标 ${result.untagged} 次`)
    await refreshAll()
  } finally {
    recalculating.value = false
  }
}

/** 单客户重算（会话结束后后端也会自动跑，这里是手工补算） */
async function recountTags() {
  if (!detail.value) {
    return
  }
  recounting.value = true
  try {
    const result = await recalculateCustomerTags(detail.value.customer.customerNo)
    ElMessage.success(`已按 ${result.ruleCount} 条规则重算：新打标 ${result.tagged}、摘标 ${result.untagged}`)
    await Promise.all([loadDetail(detail.value.customer.customerNo), loadList(), loadOverview()])
  } finally {
    recounting.value = false
  }
}

function onImportFileChange(file: { raw?: File }) {
  importFile.value = file.raw ?? null
  importResult.value = null
}

async function submitImport() {
  if (!importFile.value) {
    return
  }
  importing.value = true
  try {
    importResult.value = await importCustomers(importFile.value)
    ElMessage.success(`导入完成：新建 ${importResult.value.created}、更新 ${importResult.value.updated}`)
    await refreshAll()
  } finally {
    importing.value = false
  }
}

/** 从客户 360 直接建工单：带上客户名，工单中心那边会预填 */
function createTicketForCustomer() {
  if (!detail.value) {
    return
  }
  const customer = detail.value.customer
  void router.push({
    path: '/modules/tickets',
    query: { customerName: customer.name, title: `${customer.name} 的咨询` },
  })
}

/** 进行中的会话：直接去在线客服接手（客户 360 里不重复造一个聊天入口） */
function gotoWorkspace(row: SessionTrace) {
  void router.push({ path: '/modules/workspace', query: { sessionNo: row.sessionNo } })
}

async function searchMergeTargets(keyword: string) {
  const data = await fetchCustomers({ keyword: keyword.trim(), page: 1, pageSize: 20 })
  mergeCandidates.value = (data.list ?? []).filter(
    (item) => item.customerNo !== detail.value?.customer.customerNo,
  )
}

function handleAdminCommand(command: string) {
  if (!detail.value) {
    return
  }
  const customer = detail.value.customer
  if (command === 'merge') {
    mergeTarget.value = ''
    mergeCandidates.value = []
    mergeVisible.value = true
    return
  }
  if (command === 'anonymize') {
    void ElMessageBox.confirm(
      `确定对「${customer.name}」做匿名化吗？姓名、手机号、备注会被抹除，会话与工单统计保留。`,
      '客户匿名化',
      { type: 'warning', confirmButtonText: '匿名化', cancelButtonText: '取消' },
    ).then(async () => {
      detail.value = await anonymizeCustomer(customer.customerNo)
      ElMessage.success('已匿名化（会话与统计保留）')
      await Promise.all([loadList(), loadOverview(), loadEvents(customer.customerNo)])
    })
    return
  }
  if (command === 'delete') {
    void ElMessageBox.confirm(
      `确定删除「${customer.name}」吗？删除后列表里不再出现，但会话与工单仍保留（便于追溯）。`,
      '删除客户',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    ).then(async () => {
      await deleteCustomer(customer.customerNo)
      detailVisible.value = false
      ElMessage.success('客户已删除')
      await Promise.all([loadList(), loadOverview()])
    })
  }
}

async function submitMerge() {
  if (!detail.value || !mergeTarget.value) {
    return
  }
  saving.value = true
  try {
    const result = await mergeCustomer(detail.value.customer.customerNo, mergeTarget.value)
    mergeVisible.value = false
    detailVisible.value = false
    ElMessage.success(
      `已合并：迁入 ${result.sessions} 条会话 / ${result.tickets} 张工单 / ${result.orders} 笔订单 / ${result.tags} 个标签`,
    )
    await refreshAll()
  } finally {
    saving.value = false
  }
}

async function loadTraces(customerNo: string) {
  traceLoading.value = true
  try {
    traces.value = await fetchCustomerSessions(customerNo)
  } finally {
    traceLoading.value = false
  }
}

async function loadEvents(customerNo: string) {
  events.value = await fetchCustomerEvents(customerNo)
}

function tagsOfGroup(group: string) {
  return tagDefs.value.filter((tag) => tag.tagGroup === group && tag.enabled)
}

function hasTag(tagId: string) {
  return (detail.value?.customer.tags ?? []).some((tag) => tag.tagId === tagId)
}

/** 标签太多的行不铺满整列，鼠标停上去再看完整列表 */
function tagNames(tags: CustomerTag[]) {
  return tags.map((tag) => tag.tagName).join('、')
}

async function submitTag() {
  if (!detail.value) {
    return
  }
  if (!tagToAdd.value) {
    ElMessage.warning('先选一个标签')
    return
  }
  saving.value = true
  try {
    detail.value = await addCustomerTag(detail.value.customer.customerNo, tagToAdd.value, tagRemark.value)
    tagToAdd.value = ''
    tagRemark.value = ''
    ElMessage.success('已打标')
    await Promise.all([loadList(), loadOverview(), loadTagDefs()])
  } finally {
    saving.value = false
  }
}

async function submitRemoveTag(tag: CustomerTag) {
  if (!detail.value || !tag.tagId) {
    return
  }
  saving.value = true
  try {
    detail.value = await removeCustomerTag(detail.value.customer.customerNo, tag.tagId)
    ElMessage.success(`已摘掉标签「${tag.tagName}」`)
    await Promise.all([loadList(), loadOverview(), loadTagDefs()])
  } finally {
    saving.value = false
  }
}

function openProfileEdit() {
  if (!detail.value) {
    return
  }
  const customer = detail.value.customer
  profileForm.name = customer.name
  profileForm.phone = customer.phone ?? ''
  profileForm.level = customer.level
  profileForm.customerType = customer.customerType ?? 1
  profileForm.riskLevel = customer.riskLevel
  profileForm.remark = customer.remark ?? ''
  profileVisible.value = true
}

async function submitProfile() {
  if (!detail.value) {
    return
  }
  saving.value = true
  try {
    detail.value = await updateCustomerProfile(detail.value.customer.customerNo, {
      name: profileForm.name,
      phone: profileForm.phone,
      level: profileForm.level,
      customerType: profileForm.customerType,
      riskLevel: profileForm.riskLevel,
      remark: profileForm.remark,
    })
    profileVisible.value = false
    ElMessage.success('客户档案已更新')
    await Promise.all([loadList(), loadOverview(), loadTagDefs()])
  } finally {
    saving.value = false
  }
}

async function openTranscript(row: SessionTrace) {
  transcriptNo.value = row.sessionNo
  transcript.value = []
  transcriptVisible.value = true
  transcriptLoading.value = true
  try {
    transcript.value = await listSessionMessages(row.sessionNo, { limit: 200 })
  } finally {
    transcriptLoading.value = false
  }
}

/** 工单中心支持 ?ticketNo= 直接定位，点一下就跳过去 */
function gotoTicket(row: { ticketNo?: string | null }) {
  if (row.ticketNo) {
    void router.push({ path: '/modules/tickets', query: { ticketNo: row.ticketNo } })
  }
}

async function exportCsv() {
  const params = buildQuery()
  delete params.page
  delete params.pageSize
  await downloadCustomersCsv(params, '客户列表.csv')
}

function avatarText(name?: string | null) {
  return (name || '客').slice(0, 1)
}

function avatarClass(row: { riskLevel?: number | null; level?: number | null }) {
  if ((row.riskLevel ?? 1) >= 3) {
    return 'risk'
  }
  if ((row.level ?? 1) >= 4) {
    return 'vip'
  }
  return ''
}

function csatClass(score?: number | null) {
  if (score == null) {
    return ''
  }
  if (score >= 4.5) {
    return 'csat-good'
  }
  if (score >= 3.5) {
    return 'csat-mid'
  }
  return 'csat-bad'
}

function sessionTagType(status: number) {
  if (status === 4) {
    return 'info'
  }
  if (status === 3) {
    return 'success'
  }
  return 'warning'
}

function eventColor(eventType: number) {
  if (eventType === 3 || eventType === 6) {
    return 'danger'
  }
  if (eventType === 2 || eventType === 4) {
    return 'warning'
  }
  return 'primary'
}

function senderName(senderType: number) {
  switch (senderType) {
    case 1:
      return '客户'
    case 2:
      return '客服'
    case 3:
      return '智能客服'
    default:
      return '系统'
  }
}

function messageText(content?: string | null) {
  return messageTextOf({ msgType: 1, content }) || '（空消息）'
}

/** 时间统一显示到秒：客户 360 是复盘用的，分钟级精度不够 */
function formatTime(value?: string | null) {
  if (!value) {
    return '—'
  }
  return String(value).replace('T', ' ').slice(0, 19)
}
</script>
<style scoped>
.customer-page { width: 100%; }
.page-title-row { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 16px; gap: 12px; }
.page-title { margin: 0; font-size: 20px; color: #0f172a; }
.page-sub { margin: 6px 0 0; font-size: 13px; color: #94a3b8; }
.role-line { margin: 6px 0 0; font-size: 12px; color: #94a3b8; }
.role-line b { color: #2563eb; }
.head-actions { display: flex; align-items: center; gap: 12px; }
.btn-icon { margin-right: 4px; }

.stat-row { display: grid; grid-template-columns: repeat(5, 1fr); gap: 12px; margin-bottom: 14px; }
.stat-card { display: flex; align-items: center; gap: 12px; background: #fff; border: 1px solid #e6e8ef; border-radius: 14px; padding: 14px 16px; }
.stat-icon { width: 38px; height: 38px; border-radius: 10px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.stat-icon.blue { background: #eef4ff; color: #2563eb; }
.stat-icon.amber { background: #fff7ed; color: #d97706; }
.stat-icon.green { background: #ecfdf5; color: #059669; }
.stat-icon.red { background: #fef2f2; color: #dc2626; }
.stat-icon.purple { background: #f5f3ff; color: #7c3aed; }
.stat-value { font-size: 22px; font-weight: 800; color: #0f172a; line-height: 1.2; }
.stat-label { margin-top: 3px; font-size: 12px; color: #94a3b8; }

.filter-card { width: 100%; margin-bottom: 14px; }
.filter-form { display: flex; flex-wrap: wrap; align-items: center; gap: 2px 12px; }
.filter-form :deep(.el-form-item) { margin: 4px 0; }
.filter-right { margin-left: auto; }
.w-140 { width: 140px; }
.w-160 { width: 160px; }
.w-180 { width: 180px; }
.w-240 { width: 240px; }
.main-card { width: 100%; }
.load-error { margin-bottom: 12px; }
.load-error-tip { margin-top: 4px; font-size: 12px; line-height: 1.8; }
.load-error-tip code { padding: 1px 5px; border-radius: 4px; background: #fff; color: #b91c1c; font-size: 12px; }
.bulk-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.bulk-info { font-size: 13px; color: #64748b; }
.bulk-info b { color: #2563eb; }
.w-200 { width: 200px; }
.title-action { margin-left: 10px; font-weight: 400; }
.lock-hint { display: inline-flex; align-items: center; gap: 2px; color: #94a3b8; font-size: 12px; margin-left: 6px; }
.import-result { margin-top: 12px; padding: 10px 12px; border-radius: 8px; background: #f8fafc; border: 1px solid #eef2f7; font-size: 13px; color: #475569; }
.ir-line b { color: #0f172a; }
.ir-failures { margin: 8px 0 0; padding-left: 18px; color: #b45309; font-size: 12px; line-height: 1.8; }

/* 客户列：头像 + 姓名 + 客户编号。编号是 27 位随机串，必须给够宽度，
   同时用 nowrap + 省略号兜住窄屏（鼠标停上去有 tooltip 看全） */
.customer-cell { display: flex; align-items: center; gap: 10px; min-width: 0; }
.c-main { min-width: 0; flex: 1; }
.avatar { width: 34px; height: 34px; border-radius: 10px; color: #fff; display: inline-flex; align-items: center; justify-content: center; font-weight: 700; background: linear-gradient(135deg, #1d4ed8, #3b82f6); flex-shrink: 0; }
.avatar.vip { background: linear-gradient(135deg, #7c3aed, #a78bfa); }
.avatar.risk { background: linear-gradient(135deg, #dc2626, #f87171); }
.avatar-lg { width: 44px; height: 44px; border-radius: 12px; color: #fff; display: inline-flex; align-items: center; justify-content: center; font-weight: 800; font-size: 18px; background: linear-gradient(135deg, #1d4ed8, #3b82f6); flex-shrink: 0; }
.avatar-lg.vip { background: linear-gradient(135deg, #7c3aed, #a78bfa); }
.avatar-lg.risk { background: linear-gradient(135deg, #dc2626, #f87171); }
.c-name { font-weight: 600; color: #1e293b; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
/* 客户编号是 27 位随机串，用 11px 等宽字体：整串能放下，又不抢名字的视觉权重 */
.c-no { color: #94a3b8; margin-top: 2px; font-size: 11px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; letter-spacing: -0.2px; }
.nowrap { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.level-text { font-size: 12px; font-weight: 600; color: #64748b; }
.level-text.lv-3 { color: #0f766e; }
.level-text.lv-4 { color: #7c3aed; }
.level-text.lv-5 { color: #b45309; }
.tag-cell { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.more-tag { font-size: 12px; color: #94a3b8; cursor: default; }
.money { font-weight: 700; color: #0f172a; }
.csat { font-weight: 700; }
.csat-good { color: #16a34a; }
.csat-mid { color: #d97706; }
.csat-bad { color: #dc2626; }
.muted { color: #94a3b8; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
.cell-sub { color: #94a3b8; font-size: 12px; margin-top: 2px; }
.table-foot { display: flex; align-items: center; justify-content: space-between; gap: 16px; font-size: 12px; flex-wrap: wrap; }
/* 分页条吸在视口底部：
   ① 表格不再内部滚动 → 页面滚动条回到最右侧（用户要的）；
   ② 长表格也不会把分页顶到屏幕外，翻页随时可见。
   白底 + 上边框 + 轻阴影：滚动内容从它下面穿过时不会打架。 */
.sticky-foot { position: sticky; bottom: 0; z-index: 3; margin-top: 12px; padding: 10px 16px; background: #fff; border: 1px solid #e6e8ef; border-radius: 12px; box-shadow: 0 -2px 12px rgba(15, 23, 42, 0.06); }
.foot-tip { color: #94a3b8; }
.foot-tip code { padding: 1px 5px; border-radius: 4px; background: #f1f5f9; color: #475569; font-size: 12px; }
.page-bar { margin-left: auto; }
/* 抽屉里三张明细表的分页：右侧对齐，和主列表同一套观感 */
.mini-foot { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 8px; font-size: 12px; }
/* 「客户 360 / 会话轨迹」两个链接按钮不许换行（换行会变成两行，很难看） */
.customer-table :deep(.op-cell .cell) { white-space: nowrap; }

/* 抽屉：头部信息 + 数字条 + 四个页签 */
.drawer-head { display: flex; align-items: center; gap: 12px; }
.head-main { display: flex; flex-direction: column; gap: 4px; }
.head-name { display: flex; align-items: center; gap: 8px; font-size: 17px; font-weight: 700; color: #0f172a; }
.head-sub { font-size: 12px; color: #94a3b8; }
.detail-body { padding: 0 2px 8px; }
.metric-row { display: grid; grid-template-columns: repeat(6, 1fr); gap: 10px; margin-bottom: 14px; }
.metric { background: #f8fafc; border: 1px solid #eef2f7; border-radius: 12px; padding: 12px; text-align: center; }
.metric .mv { font-size: 18px; font-weight: 800; color: #0f172a; }
.metric .ml { margin-top: 4px; font-size: 12px; color: #94a3b8; }
.detail-tabs :deep(.el-tabs__header) { margin-bottom: 14px; }
.section-title { margin: 18px 0 10px; font-weight: 700; color: #0f172a; font-size: 14px; }
.section-sub { margin-left: 8px; font-weight: 400; font-size: 12px; color: #94a3b8; }
.info-card { background: #f8fafc; border: 1px solid #e6e8ef; border-radius: 12px; padding: 6px 16px; }
.info-row { display: flex; justify-content: space-between; gap: 20px; padding: 9px 0; color: #475569; font-size: 13px; }
.info-row + .info-row { border-top: 1px dashed #e2e8f0; }
.info-row .v { color: #0f172a; text-align: right; }
.mini-table { width: 100%; }
.tag-editor { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 16px; }
.tip-line { margin: 12px 0 0; font-size: 12px; line-height: 1.8; }
.tag-group { margin-bottom: 14px; }
.tg-head { display: flex; align-items: center; gap: 8px; font-size: 13px; font-weight: 700; color: #334155; margin-bottom: 8px; }
.tg-count { padding: 0 6px; border-radius: 999px; background: #f1f5f9; color: #64748b; font-size: 11px; font-weight: 600; }
.tg-body { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.tag-src { margin-left: 6px; font-size: 11px; opacity: 0.75; }
.event-timeline { padding-left: 2px; }
.event-item { display: flex; align-items: center; gap: 8px; }
.event-type { font-weight: 700; color: #1e293b; font-size: 13px; }
.event-title { color: #334155; font-size: 13px; }
.event-who { color: #94a3b8; font-size: 12px; }
.event-content { margin-top: 4px; font-size: 13px; color: #475569; }
.drawer-foot { display: flex; align-items: center; gap: 10px; width: 100%; }
.spacer { flex: 1; }
.full-width { width: 100%; }
.dialog-tip { margin-top: 4px; padding: 9px 12px; border-radius: 8px; background: #f8fafc; border: 1px solid #eef2f7; color: #64748b; font-size: 12px; line-height: 1.7; }
.transcript { max-height: 460px; overflow-y: auto; padding: 4px 2px; }
.msg-row { display: flex; flex-direction: column; gap: 4px; margin-bottom: 12px; max-width: 78%; }
.msg-row.sender-1 { margin-left: auto; align-items: flex-end; }
.msg-who { font-size: 12px; color: #94a3b8; }
.msg-bubble { padding: 9px 12px; border-radius: 10px; background: #f1f5f9; color: #1e293b; font-size: 13px; line-height: 1.7; white-space: pre-wrap; word-break: break-word; }
.msg-row.sender-1 .msg-bubble { background: #2563eb; color: #fff; }
.msg-row.sender-3 .msg-bubble { background: #f0fdf4; color: #166534; }
.msg-time { font-size: 11px; color: #cbd5e1; }
</style>

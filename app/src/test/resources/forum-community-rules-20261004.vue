<script setup lang="ts">
import { CheckOutlined, CloseOutlined } from '@vicons/material';
import { RouterLink } from 'vue-router';

const permissions = [
  { site: '论坛', name: '管理帖子收藏', allowed: [true, true, true] },
  { site: '论坛', name: '发表、编辑帖子', allowed: [true, true, false] },
  { site: '论坛', name: '发表、编辑评论', allowed: [true, true, false] },
  { site: '小说', name: '发表、编辑评论', allowed: [false, true, false] },
  { site: '小说', name: '管理小说收藏', allowed: [true, true, true] },
  { site: '小说', name: '更新网页小说', allowed: [false, true, false] },
  { site: '小说', name: '编辑网页小说', allowed: [false, true, false] },
  { site: '小说', name: '创建、编辑文库小说', allowed: [false, true, false] },
  { site: '小说', name: '上传文库小说', allowed: [true, true, true] },
  { site: '小说', name: '编辑术语表', allowed: [false, true, false] },
];
</script>

<template>
  <div class="page-container py-4 md:py-6">
    <section class="mx-auto max-w-2xl text-sm leading-6">
      <header class="mb-5">
        <h1 class="text-2xl font-bold tracking-tight text-ink">社区守则</h1>
      </header>

      <h2 class="mt-6 text-lg font-semibold">违规处理</h2>
      <p class="mt-3">
        <span>一般违规行为会受到记分处罚，</span>
        <strong class="font-semibold">100 天内处罚累计达到 3 分</strong>
        <span>，账号将转为受限状态，权限按上述说明限制。账号受限后</span>
        <strong class="font-semibold">不会自动解除</strong>
        <span>。处罚原因、依据和分值可在「</span>
        <RouterLink
          :to="{ name: 'strikes' }"
          class="font-medium text-primary underline underline-offset-2 hover:text-primary-hover focus-visible:rounded-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
        >
          处罚记录
        </RouterLink>
        <span>」中查看。</span>
      </p>

      <p class="mt-4 font-medium">以下行为会受到记分处罚：</p>
      <ul class="mt-2 list-disc space-y-1 pl-5">
        <li>侮辱、骚扰、攻击他人。</li>
        <li>刷屏、灌水、重复发布或持续发表无关内容。</li>
        <li>发布网赚盘。</li>
        <li>乱改小说元数据。</li>
        <li>其他破坏正常讨论秩序的行为。</li>
      </ul>

      <p class="mt-6">
        <span>严重违规将</span>
        <strong class="font-semibold">直接封禁账号</strong>
        <span>，包括：</span>
      </p>
      <ul class="mt-2 list-disc space-y-1 pl-5">
        <li>以营利为目的，发布广告或其他恶意推广内容。</li>
        <li>发布违法内容、暴力威胁，或泄露、传播他人隐私。</li>
        <li>规避处罚、批量刷屏，或持续、恶意破坏社区。</li>
        <li>滥用术语表。</li>
        <li>
          宣扬、支持、美化或为法西斯主义及其侵略行为辩护，否认、美化侵略战争或战败历史。
        </li>
      </ul>

      <p class="mt-6 font-medium">
        遇到违反社区守则的行为，不要发评论争吵，直接加群联系管理员处理。
      </p>

      <p class="mt-6">
        <span>如果认为处罚或封禁有误，请</span>
        <strong class="font-semibold">联系管理员申请复核</strong>
        <span>。社区守则无法穷尽所有不当行为，最终解释权归管理员所有。</span>
      </p>

      <h2 class="mt-6 text-lg font-semibold">建政小说处理办法</h2>
      <p class="mt-3">
        对于以二战或者现实战争为核心内容，涉及法西斯、反战败元素的小说，请用QQ或者Telegram私信管理员，符合标准会屏蔽。不要在论坛发帖，或者在群里讨论。
      </p>
      <p class="mt-3">
        不要特地去找这种小说，也不要截图到处传播。不要在机翻站建政。无论你觉得你的观点多么正义，也要挑选说话的场合。冲到电影院大喊1+1=2以至于被保安拖了出去，不是因为大家不认同你的观点，是你是个SB。
      </p>

      <h2 class="mt-6 text-lg font-semibold">用户权限</h2>
      <div class="mt-2 overflow-x-auto">
        <table class="w-full min-w-120 border-collapse text-left">
          <thead>
            <tr class="border-b border-current/15">
              <th scope="col" class="px-3 py-2 font-medium">站点</th>
              <th scope="col" class="px-3 py-2 font-medium">操作</th>
              <th scope="col" class="px-3 py-2 text-center font-medium">
                普通用户
                <br />
                未满月
              </th>
              <th scope="col" class="px-3 py-2 text-center font-medium">
                普通用户
                <br />
                已满月
              </th>
              <th scope="col" class="px-3 py-2 text-center font-medium">
                受限用户
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="permission in permissions"
              :key="permission.name"
              class="border-b border-current/10"
            >
              <td class="whitespace-nowrap px-3 py-2">
                {{ permission.site }}
              </td>
              <th scope="row" class="px-3 py-2 font-normal">
                {{ permission.name }}
              </th>
              <td
                v-for="(allowed, index) in permission.allowed"
                :key="index"
                class="px-3 py-2 text-center"
              >
                <span
                  class="inline-flex align-middle"
                  :aria-label="allowed ? '允许' : '不允许'"
                  role="img"
                >
                  <CheckOutlined
                    v-if="allowed"
                    class="size-5 text-green-600"
                    aria-hidden="true"
                  />
                  <CloseOutlined
                    v-else
                    class="size-5 text-red-500"
                    aria-hidden="true"
                  />
                </span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>

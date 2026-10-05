<script setup lang="ts">
import { RouterLink } from 'vue-router';
</script>

<template>
  <div class="page-container py-4 md:py-6">
    <section class="mx-auto max-w-2xl text-sm leading-6 text-muted">
      <p class="text-ink">
        <span>一般违规行为会受到记分处罚，</span>
        <strong class="font-semibold">100 天内处罚累计达到 3 分</strong>
        <span>，账号将转为受限状态。受限用户与新注册用户权限相同，</span>
        <strong class="font-semibold">无法发布或编辑帖子、评论</strong>
        <span>。账号受限后</span>
        <strong class="font-semibold">不会自动解除</strong>
        <span>。处罚原因、依据和分值可在「</span>
        <RouterLink
          :to="{ name: 'my-strikes' }"
          class="font-medium text-primary underline underline-offset-2 hover:text-primary-hover focus-visible:rounded-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
        >
          处罚记录
        </RouterLink>
        <span>」中查看。</span>
      </p>

      <p class="mt-4 font-medium text-ink">以下行为会受到记分处罚：</p>
      <ul class="mt-2 list-disc space-y-1 pl-5">
        <li>侮辱、骚扰、攻击他人。</li>
        <li>刷屏、灌水、重复发布或持续发表无关内容。</li>
        <li>其他破坏正常讨论秩序的行为。</li>
      </ul>

      <p class="mt-6 text-ink">
        <span>严重违规将</span>
        <strong class="font-semibold">直接封禁账号</strong>
        <span>，包括：</span>
      </p>
      <ul class="mt-2 list-disc space-y-1 pl-5">
        <li>以营利为目的，发布广告或其他恶意推广内容。</li>
        <li>发布违法内容、暴力威胁，或泄露、传播他人隐私。</li>
        <li>规避处罚、批量刷屏，或持续、恶意破坏社区。</li>
        <li>
          宣扬、支持、美化或为法西斯主义及其侵略行为辩护，否认、美化侵略战争或战败历史。
        </li>
      </ul>

      <p class="mt-6 text-ink">
        <span>如果认为处罚或封禁有误，请</span>
        <strong class="font-semibold">联系管理员申请复核</strong>
        <span>。社区守则无法穷尽所有不当行为，最终解释权归管理员所有。</span>
      </p>
    </section>
  </div>
</template>

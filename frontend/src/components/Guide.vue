<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from '../composables/useI18n';
import guidesData from '../data/guides.json';

const props = defineProps<{
  slug?: string | null;
}>();

const emit = defineEmits<{
  (e: 'navigate-guide', slug: string): void
}>();

const { t } = useI18n();

const guides = guidesData.guides as Array<{
  slug: string;
  title: string;
  description: string;
  summary: string;
  publishedAt: string;
  html: string;
}>;

const currentGuide = computed(() => {
  if (!props.slug) return null;
  return guides.find((g) => g.slug === props.slug) ?? null;
});
</script>

<template>
  <article v-if="currentGuide" class="bg-white dark:bg-slate-800 rounded-md p-6 sm:p-10 border border-slate-200 dark:border-slate-700 animate-fade-in text-slate-800 dark:text-slate-200">
    <nav class="text-sm text-slate-500 dark:text-slate-400 mb-4">
      <a href="/" class="hover:underline">{{ t('guide.breadcrumbHome') }}</a>
      <span class="mx-2">›</span>
      <a href="/guide" class="hover:underline">{{ t('guide.breadcrumbList') }}</a>
      <span class="mx-2">›</span>
      <span>{{ currentGuide.title }}</span>
    </nav>
    <h1 class="text-2xl sm:text-3xl font-bold mb-3 text-slate-900 dark:text-white">
      {{ currentGuide.title }}
    </h1>
    <p class="text-sm text-slate-500 dark:text-slate-400 mb-8">
      {{ t('guide.publishedAt', { date: currentGuide.publishedAt }) }}
    </p>
    <div class="guide-body prose prose-slate dark:prose-invert max-w-none leading-relaxed" v-html="currentGuide.html"></div>
    <div class="mt-12 pt-6 border-t border-slate-200 dark:border-slate-700">
      <a href="/guide" class="text-blue-600 dark:text-blue-400 hover:underline">{{ t('guide.backToList') }}</a>
    </div>
  </article>

  <section v-else class="bg-white dark:bg-slate-800 rounded-md p-6 sm:p-10 border border-slate-200 dark:border-slate-700 animate-fade-in text-slate-800 dark:text-slate-200">
    <h1 class="text-2xl sm:text-3xl font-bold mb-3 text-slate-900 dark:text-white">
      {{ t('guide.indexTitle') }}
    </h1>
    <p class="text-slate-600 dark:text-slate-300 leading-relaxed mb-8">
      {{ t('guide.indexDesc') }}
    </p>
    <ul class="space-y-4">
      <li v-for="g in guides" :key="g.slug">
        <a :href="`/guide/${g.slug}`" @click.prevent="emit('navigate-guide', g.slug)" class="block bg-slate-50 dark:bg-slate-900/40 hover:bg-blue-50 dark:hover:bg-blue-900/30 rounded-md p-5 border border-slate-200 dark:border-slate-700 transition-colors">
          <h2 class="text-lg font-bold text-slate-900 dark:text-white mb-1">{{ g.title }}</h2>
          <p class="text-sm text-slate-600 dark:text-slate-300">{{ g.summary }}</p>
          <p class="text-xs text-slate-400 dark:text-slate-500 mt-2">{{ g.publishedAt }}</p>
        </a>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.guide-body :deep(h2) {
  font-size: 1.25rem;
  font-weight: 800;
  margin-top: 2rem;
  margin-bottom: 0.75rem;
  color: #1e293b;
}
.dark .guide-body :deep(h2) {
  color: #f1f5f9;
}
.guide-body :deep(h3) {
  font-size: 1.05rem;
  font-weight: 700;
  margin-top: 1.5rem;
  margin-bottom: 0.5rem;
}
.guide-body :deep(p) {
  margin-bottom: 1rem;
  line-height: 1.85;
}
.guide-body :deep(ul),
.guide-body :deep(ol) {
  padding-left: 1.5rem;
  margin-bottom: 1rem;
  list-style: disc;
  line-height: 1.85;
}
.guide-body :deep(ol) {
  list-style: decimal;
}
.guide-body :deep(li) {
  margin-bottom: 0.25rem;
}
.guide-body :deep(table) {
  width: 100%;
  margin: 1rem 0;
  border-collapse: collapse;
}
.guide-body :deep(th),
.guide-body :deep(td) {
  padding: 0.5rem 0.75rem;
  border: 1px solid #e2e8f0;
  text-align: left;
}
.dark .guide-body :deep(th),
.dark .guide-body :deep(td) {
  border-color: #334155;
}
.guide-body :deep(a) {
  color: #2563eb;
  text-decoration: underline;
}
.dark .guide-body :deep(a) {
  color: #60a5fa;
}
.guide-body :deep(strong) {
  font-weight: 700;
}
</style>

<script setup lang="ts">
import { ref } from 'vue';

const isDragging = ref(false);
const fileInput = ref<HTMLInputElement | null>(null);

const emit = defineEmits<{
  (e: 'file-dropped', file: File): void
}>();

const handleDragOver = (e: DragEvent) => {
  e.preventDefault();
  isDragging.value = true;
};

const handleDragLeave = (e: DragEvent) => {
  e.preventDefault();
  isDragging.value = false;
};

const handleDrop = (e: DragEvent) => {
  e.preventDefault();
  isDragging.value = false;
  
  if (e.dataTransfer && e.dataTransfer.files.length > 0) {
    const file = e.dataTransfer.files[0];
    validateAndEmit(file);
  }
};

const handleFileSelect = (e: Event) => {
  const target = e.target as HTMLInputElement;
  if (target.files && target.files.length > 0) {
    validateAndEmit(target.files[0]);
  }
};

const triggerFileInput = () => {
  fileInput.value?.click();
};

const validateAndEmit = (file: File) => {
  // Check if it's a CSV based on name or type
  if (file.name.toLowerCase().endsWith('.csv') || file.type === 'text/csv' || file.type === 'application/vnd.ms-excel') {
    emit('file-dropped', file);
  } else {
    alert('CSVファイルをアップロードしてください。'); // Simple alert for now
  }
};
</script>

<template>
  <div 
    class="w-full max-w-2xl mx-auto p-12 border-2 border-dashed rounded-2xl transition-all duration-200 flex flex-col items-center justify-center cursor-pointer bg-white shadow-sm"
    :class="{
      'border-blue-500 bg-blue-50/50 scale-[1.02] shadow-md': isDragging,
      'border-slate-300 hover:border-slate-400 hover:bg-slate-50': !isDragging
    }"
    @dragover="handleDragOver"
    @dragleave="handleDragLeave"
    @drop="handleDrop"
    @click="triggerFileInput"
  >
    <div class="bg-blue-100 text-blue-600 p-4 rounded-full mb-4">
      <svg xmlns="http://www.w3.org/2000/svg" class="h-8 w-8" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
        <path stroke-linecap="round" stroke-linejoin="round" d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" />
      </svg>
    </div>
    <h3 class="text-xl font-bold text-slate-700 mb-2">
      CSVファイルをドロップ
    </h3>
    <p class="text-slate-500 text-center text-sm mb-6">
      またはクリックしてファイルを選択してください。<br/>
      beatmania IIDX公式のスコアデータCSVに対応しています。
    </p>
    
    <button 
      class="px-6 py-2.5 bg-blue-600 hover:bg-blue-700 text-white font-medium rounded-lg transition-colors shadow-sm"
      @click.stop="triggerFileInput"
    >
      ファイルを選択
    </button>
    <input 
      type="file" 
      ref="fileInput" 
      accept=".csv,text/csv" 
      class="hidden" 
      @change="handleFileSelect"
    />
  </div>
</template>

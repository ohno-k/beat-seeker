<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-[110] bg-slate-50 flex flex-col animate-fade-in">
      <!-- Header -->
      <div class="px-8 py-6 border-b border-slate-100 flex justify-between items-center bg-white sticky top-0 z-10">
        <div>
          <h3 class="text-2xl font-black text-slate-800">Beat-Tier 統計システム</h3>
          <p class="text-[10px] font-bold text-slate-400 mt-0.5 uppercase tracking-widest">システム解説 と 対象楽曲リスト</p>
        </div>
        <button @click="$emit('close')" class="p-2 text-slate-400 hover:text-slate-600 hover:bg-slate-100 rounded-full transition-all">
          <svg class="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      <!-- Content -->
      <div class="flex-1 overflow-y-auto custom-scrollbar bg-slate-50/50">
        <!-- Tabs -->
        <div class="px-8 pt-6 sticky top-0 bg-white/80 backdrop-blur-md z-10">
          <div class="flex border-b border-slate-200 gap-8">
            <button 
              @click="activeTab = 'about'" 
              :class="['pb-4 text-sm font-black transition-all relative', activeTab === 'about' ? 'text-blue-600' : 'text-slate-400 hover:text-slate-600']"
            >
              仕組み・ランク
              <div v-if="activeTab === 'about'" class="absolute bottom-0 left-0 right-0 h-1 bg-blue-600 rounded-full"></div>
            </button>
            <button 
              @click="activeTab = 'songs'" 
              :class="['pb-4 text-sm font-black transition-all relative', activeTab === 'songs' ? 'text-blue-600' : 'text-slate-400 hover:text-slate-600']"
            >
              対象曲リスト
              <div v-if="activeTab === 'songs'" class="absolute bottom-0 left-0 right-0 h-1 bg-blue-600 rounded-full"></div>
            </button>
          </div>
        </div>

        <div class="p-8">
          <!-- About Tab -->
          <div v-if="activeTab === 'about'" class="space-y-8 animate-fade-in">
            <section>
              <h4 class="text-lg font-black text-slate-800 mb-3 flex items-center gap-2">
                <span class="w-1.5 h-6 bg-blue-600 rounded-full"></span>
                Beat-Tier システムとは？
              </h4>
              <p class="text-slate-600 leading-relaxed text-sm font-medium">
                Beat-Tierは、プレイヤーの真の地力を可視化するためのランキングシステムです。<br/>
                非公式難易度表（☆12 / ☆11）に掲載されている楽曲を対象とし、高スコアを出すほど多くのポイントを獲得できます。<br/>
                合計ポイントは、全対象曲のうち**獲得ポイントが高い上位100曲**の合算によって決定されます。
              </p>
            </section>

            <section class="bg-blue-600 rounded-2xl p-6 text-white shadow-lg shadow-blue-200">
              <h4 class="text-xs font-black uppercase tracking-widest mb-4 opacity-80">計算式</h4>
              <div class="flex flex-col md:flex-row items-center justify-between gap-6">
                <div class="flex-1 text-center md:text-left">
                  <p class="text-3xl font-black mb-1">Beat-PT = (Rate%)² × Weight</p>
                  <p class="text-xs font-bold opacity-70">スコアレートの2乗に、譜面ごとの重み（Weight）を掛け合わせて算出します。</p>
                </div>
                <div class="h-px md:h-20 w-full md:w-px bg-white/20"></div>
                <div class="flex-1 text-sm font-bold leading-relaxed">
                  <p>• 重みは非公式難易度に基づき、11.0(150pt)〜12.9(188pt)の範囲で設定されます。</p>
                </div>
              </div>
            </section>

            <section>
              <h4 class="text-lg font-black text-slate-800 mb-4 flex items-center gap-2">
                <span class="w-1.5 h-6 bg-purple-600 rounded-full"></span>
                ランク別ボーダーライン
              </h4>
              <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
                <div 
                  v-for="(rank, idx) in displayRanks" 
                  :key="idx"
                  class="bg-white p-4 rounded-xl border border-slate-100 shadow-sm flex items-center justify-between transition-hover hover:border-blue-200"
                >
                  <div class="flex flex-col">
                    <span :class="['text-sm font-black', rank.color]">
                      {{ rank.name }} {{ rank.tier || '' }}
                    </span>
                    <span class="text-[10px] font-bold text-slate-400 uppercase tracking-tighter">必要ポイント</span>
                  </div>
                  <div class="text-right">
                    <span class="text-sm font-black text-slate-700">{{ rank.minPoints.toLocaleString() }} pt</span>
                  </div>
                </div>
              </div>
            </section>
          </div>

          <!-- Songs Tab -->
          <div v-else class="space-y-6 animate-fade-in h-full flex flex-col">
            <div class="flex flex-col sm:flex-row gap-4">
              <div class="relative flex-1">
                <input 
                  v-model="songSearch" 
                  type="text" 
                  placeholder="楽曲名で検索..." 
                  class="w-full pl-10 pr-4 py-2.5 bg-white border border-slate-200 rounded-xl text-sm font-medium focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition-all shadow-sm"
                />
                <svg class="w-5 h-5 absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
                </svg>
              </div>
            </div>

            <div class="space-y-4">
              <div v-for="group in filteredSongGroups" :key="group.rank" class="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm">
                <div class="px-5 py-3 bg-slate-50 border-b border-slate-200 flex items-center justify-between">
                  <div class="flex items-center gap-2">
                    <span class="text-xs font-black text-slate-500 uppercase tracking-widest">非公式難易度</span>
                    <span class="text-lg font-black text-slate-800">{{ group.rank }}</span>
                  </div>
                  <div class="flex items-center gap-2">
                    <span class="text-xs font-bold text-slate-400">重み</span>
                    <span class="text-sm font-black text-blue-600">{{ group.weight }} pt</span>
                  </div>
                </div>
                <div class="p-4 grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-x-6 gap-y-2">
                  <div 
                    v-for="song in group.songs" 
                    :key="song"
                    class="text-[13px] font-bold text-slate-700 flex items-center gap-2 truncate py-0.5"
                    :title="song"
                  >
                    <div class="w-1.5 h-1.5 rounded-full bg-slate-300"></div>
                    {{ song }}
                  </div>
                </div>
              </div>
              <div v-if="filteredSongGroups.length === 0" class="py-20 text-center text-slate-400 font-bold">
                一致する形式が見つかりませんでした。
              </div>
            </div>
          </div>
        </div>
      </div>
      
      <!-- Footer -->
      <div class="px-8 py-5 bg-slate-50 border-t border-slate-100 text-center">
        <p class="text-[10px] font-bold text-slate-400 uppercase tracking-widest">
          データは現在の非公式難易度表の定義に基づいています • {{ new Date().toLocaleDateString() }}
        </p>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import { RANKS, WEIGHTS } from '../utils/beatTier';
import diffTableRaw from '../data/difficulty_table.json';

defineEmits(['close']);

const activeTab = ref<'about' | 'songs'>('about');
const songSearch = ref('');

const displayRanks = computed(() => {
  // Only show the major rank boundaries or filter appropriately to avoid overwhelming UI
  return [...RANKS].reverse();
});

const songGroups = computed(() => {
  return diffTableRaw.ranks.map(r => ({
    rank: r.rank,
    weight: WEIGHTS[r.rank] || 0,
    songs: r.songs
  })).filter(g => g.weight > 0);
});

const filteredSongGroups = computed(() => {
  if (!songSearch.value) return songGroups.value;
  
  return songGroups.value.map(group => {
    const matchedSongs = group.songs.filter(s => 
      s.toLowerCase().includes(songSearch.value.toLowerCase())
    );
    return { ...group, songs: matchedSongs };
  }).filter(group => group.songs.length > 0);
});
</script>

<style scoped>
.custom-scrollbar::-webkit-scrollbar {
  width: 6px;
}
.custom-scrollbar::-webkit-scrollbar-track {
  background: transparent;
}
.custom-scrollbar::-webkit-scrollbar-thumb {
  background: #e2e8f0;
  border-radius: 10px;
}
.custom-scrollbar::-webkit-scrollbar-thumb:hover {
  background: #cbd5e1;
}

.animate-fade-in {
  animation: fadeIn 0.2s ease-out;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}

.transition-hover {
  transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
}
</style>

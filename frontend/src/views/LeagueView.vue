<script setup lang="ts">
/**
 * 【View の役割】 リーグモード（/league）のメイン画面（スコアリーグ）。
 *
 * 以下を表示する:
 *  - 参加/離脱カード（未参加時は参加の流れとプライバシー注記を表示）
 *  - 進行中の週: 期間・締切カウントダウン・課題曲 3 曲・自分のグループのライブ順位表
 *    （昇格圏 = 緑 / 降格圏 = 赤 の帯表示。行の perSong で曲別の有効状況も見せる）
 *  - 他グループの順位表（観戦）
 *  - 自分の過去週成績（アコーディオン）
 *  - 昇降格ニュース（全ユーザーの昇格/降格を直近の締め済み週から新しい順に）
 *  - 管理者セクション（useAdmin.isAdmin のときのみ表示。サーバ側でも管理者判定される）:
 *    draft 週の課題曲差し替え・再抽選、週次処理の手動実行
 */
import { ref, computed, onMounted, onUnmounted, watch } from 'vue';
import { useI18n } from '../composables/useI18n';
import { useAuth } from '../composables/useAuth';
import { useAdmin } from '../composables/useAdmin';
import LeagueInfoModal from '../components/LeagueInfoModal.vue';
import LeagueRankingModal from '../components/LeagueRankingModal.vue';
import LeaguePointGauge from '../components/LeaguePointGauge.vue';
import LeagueStandingsTable from '../components/LeagueStandingsTable.vue';
import RankIcon from '../components/RankIcon.vue';
import { getRankInfo } from '../utils/beatTier';
import {
  useLeague,
  type LadderType,
  type LeagueCurrent,
  type LeagueEntry,
  type LeagueHistoryRow,
  type LeagueNewsWeek,
  type LeagueTierOverview,
  type LeagueAdminLadder,
  type LeagueAdminHistoryWeek,
  type LeagueAdminWeek,
  type LeagueAdminMember,
  type LeagueStandingRow,
  type LeagueSongInfo,
  type LeaguePerSong,
  type LeaguePreview,
  type LeaguePoolSong,
  type LeagueSongPool,
} from '../composables/useLeague';

const { t } = useI18n();
const { user, isLoggedIn } = useAuth();
const { isAdmin } = useAdmin();
const league = useLeague();

/** 運用中のラダー（現在はスコアのみ）。 */
const ladder: LadderType = 'score';

/** 進行中の週の情報（課題曲・自分のグループの順位表）。 */
const current = ref<LeagueCurrent | null>(null);
/** 自分の参加エントリー。 */
const entries = ref<LeagueEntry[]>([]);
/** 自分の過去週成績。 */
const history = ref<LeagueHistoryRow[]>([]);
/** 進行中の週の階級/グループ構成（観戦用）。 */
const overviewTiers = ref<LeagueTierOverview[]>([]);
/** 観戦用に開いた他グループの順位表。 */
const otherStandings = ref<{ tier: number; groupIndex: number; songs: LeagueSongInfo[]; standings: LeagueStandingRow[] } | null>(null);
/** 過去成績アコーディオンの開閉。 */
const showHistory = ref(false);
/** 過去成績で展開中の週 ID（1 行ずつ開く）。閉じているときは null。 */
const openHistoryWeekId = ref<number | null>(null);
/** 展開中の週の順位表（開催中の週と同じ形）。 */
const historyDetail = ref<{ songs: LeagueSongInfo[]; standings: LeagueStandingRow[] } | null>(null);
/** 展開中の週の読み込み状態・エラー。 */
const historyDetailLoading = ref(false);
const historyDetailError = ref('');
/** 昇降格ニュース（全ユーザー分。直近の締め済み週から新しい順）。 */
const news = ref<LeagueNewsWeek[]>([]);
/** 昇降格ニュースの読み込み状態・エラー。 */
const newsLoading = ref(false);
const newsError = ref('');
/** 昇降格ニュースの開閉（既定は開いた状態＝読み物として目に入るようにする）。 */
const showNews = ref(true);
/**
 * 昇降格ニュースで中身を開いている開催回の weekId。
 * 週ごとに独立して折りたためる（過去成績と違い、複数の回を同時に開ける）。
 * 既定は最新の回だけ開く＝古い回は見出しの人数だけで一覧できるようにする。
 */
const openNewsWeekIds = ref<number[]>([]);

/** ルール説明モーダルの開閉。 */
const showInfo = ref(false);
/** DIVISION 別ランキングモーダルの開閉。 */
const showRanking = ref(false);
/** 管理者 overview（管理者のみ取得）。 */
const adminLadders = ref<LeagueAdminLadder[]>([]);
/** 管理者 overview の取得に失敗した理由（取得できないと編成ブロックが空になるので理由を出す）。 */
const adminError = ref('');
/** 仮編成プレビュー（管理者が生成したときのみ。DB は更新しない）。 */
const preview = ref<LeaguePreview | null>(null);
/** 全リーグ履歴（全週の一覧。管理者のみ、アコーディオンを開いたときに取得）。 */
const adminHistory = ref<LeagueAdminHistoryWeek[]>([]);
/** 全リーグ履歴アコーディオンの開閉。 */
const showAdminHistory = ref(false);
/** 全リーグ履歴の読み込み状態・エラー。 */
const adminHistoryLoading = ref(false);
const adminHistoryError = ref('');
/** 全リーグ履歴で展開中の週 ID（1 週ずつ開く）。閉じているときは null。 */
const openAdminWeekId = ref<number | null>(null);
/** 展開中の週で開いているグループ。週を閉じる / 切り替えると null に戻す。 */
const openAdminGroup = ref<{ tier: number; groupIndex: number } | null>(null);
/** 開いているグループの順位表（管理者用: 各スコアを伏せない）。 */
const adminGroupDetail = ref<{ songs: LeagueSongInfo[]; standings: LeagueStandingRow[] } | null>(null);
/** グループ順位表の読み込み状態・エラー。 */
const adminGroupLoading = ref(false);
const adminGroupError = ref('');

const error = ref('');
const notice = ref('');
const busy = ref(false);

/** カウントダウン表示用の現在時刻（30 秒ごとに更新）。 */
const now = ref(Date.now());
let timer: ReturnType<typeof setInterval> | null = null;

/** 自分のエントリー。 */
const myEntry = computed(() => entries.value.find(e => e.ladderType === ladder) ?? null);
/** 参加中（active なエントリーがある）か。 */
const isJoined = computed(() => myEntry.value?.active === true);
/**
 * 参加登録は済んでいるが、開催中の週の編成には入っていない状態（＝次週から参戦）。
 * この状態で今週の課題曲を出すと「もう参加している」と誤解されるため、表示を切り替える。
 */
const isPendingNextWeek = computed(() =>
  isJoined.value && !!current.value?.week && !current.value?.member
);
/** 順位表の自分の行（ハイライト用）。 */
const myRow = computed(() =>
  current.value?.standings?.find(r => r.userId === user.value?.id) ?? null
);

/** 週の締切までの残り時間表示（"2日 5時間" / "3時間 12分"）。終了後は空。 */
const countdown = computed(() => {
  const endsAt = current.value?.week?.endsAt;
  if (!endsAt) return '';
  const diff = new Date(endsAt).getTime() - now.value;
  if (diff <= 0) return '';
  const minutes = Math.floor(diff / 60000);
  const days = Math.floor(minutes / 1440);
  const hours = Math.floor((minutes % 1440) / 60);
  const mins = minutes % 60;
  if (days > 0) return t('league.countdownDh', { d: days, h: hours });
  if (hours > 0) return t('league.countdownHm', { h: hours, m: mins });
  return t('league.countdownM', { m: mins });
});

/** 日付を「7/21」のような短い形式で表示する。 */
const shortDate = (iso: string) => {
  const d = new Date(iso);
  return `${d.getMonth() + 1}/${d.getDate()}`;
};

/** 日時を「7/21 12:00」のような形式で表示する（週の開始/終了は時刻が重要）。 */
const shortDateTime = (iso: string) => {
  const d = new Date(iso);
  const hm = `${d.getHours()}:${String(d.getMinutes()).padStart(2, '0')}`;
  return `${d.getMonth() + 1}/${d.getDate()} ${hm}`;
};

/**
 * 開催回の表示名。通し番号があれば「#3」、無ければ「プレシーズン」。
 * 番号は 2026-08-10 週の #1 から連番で振られ、それ以前の週は番号なし。
 */
const weekLabel = (weekNo: number | null | undefined) =>
  weekNo == null ? t('league.preseason') : t('league.weekNo', { n: weekNo });

/** 昇降格ニュースで、その開催回の中身を開いているか。 */
const isNewsWeekOpen = (weekId: number) => openNewsWeekIds.value.includes(weekId);

/** 昇降格ニュースの開催回を開閉する（他の回の状態はそのまま）。 */
const toggleNewsWeek = (weekId: number) => {
  openNewsWeekIds.value = isNewsWeekOpen(weekId)
    ? openNewsWeekIds.value.filter(id => id !== weekId)
    : [...openNewsWeekIds.value, weekId];
};

/** その開催回の昇格・降格の人数（見出しに出す内訳）。 */
const newsCounts = (w: LeagueNewsWeek) => ({
  promote: w.items.filter(i => i.movement === 'promote').length,
  relegate: w.items.filter(i => i.movement === 'relegate').length,
});

/** DIVISION の表示名（tier 0 = DIVISION LEGEND、1..10 = DIVISION n）。 */
const divisionName = (tier: number | null | undefined) => {
  if (tier == null) return '';
  return tier === 0 ? t('league.divisionLegend') : t('league.divisionN', { n: tier });
};

/** 昇降格PTの符号付き表示（+3 / -2 / 0）。null は '-'。 */
const fmtPt = (p: number | null | undefined) => {
  if (p == null) return '-';
  return p > 0 ? `+${p}` : `${p}`;
};

/** 得点（着順ポイント）の表示。整数は小数点なし、半端は1桁。null は '-'。 */
const fmtPts = (p: number | null | undefined) => {
  if (p == null) return '-';
  return Number.isInteger(p) ? String(p) : p.toFixed(1);
};

/** 総合 BEAT-PT から Beat-Tier ランク情報（名前・ティア）を得る（順位表のティアアイコン用）。 */
const beatTier = (pt: number | null | undefined) => getRankInfo(pt ?? 0);

/** 立場バッジの短い記号とクラス（チャレンジ=挑 / ディフェンス=防）。normal は null。 */
const roleBadge = (role: string | null | undefined) => {
  if (role === 'challenge') return { label: t('league.roleChallenge'), cls: 'bg-orange-100 dark:bg-orange-900/40 text-orange-700 dark:text-orange-300' };
  if (role === 'defense') return { label: t('league.roleDefense'), cls: 'bg-sky-100 dark:bg-sky-900/40 text-sky-700 dark:text-sky-300' };
  return null;
};

/** 課題曲のライン（有効化に必要な目標記録）の表示文字列。 */
const lineLabel = (ps: LeaguePerSong, song: LeagueSongInfo) => {
  if (ps.lineEx == null) return t('league.lineNone');
  const rate = song.notes > 0 ? ((ps.lineEx / (song.notes * 2)) * 100).toFixed(2) : '?';
  return t('league.lineScore', { ex: ps.lineEx, rate });
};

/** グループ共通のライン（song.lineEx / lineRate）からラベルを作る（観戦・他グループ表示用）。 */
const songLineLabel = (song: LeagueSongInfo) => {
  if (song.lineEx == null) return t('league.lineNone');
  const rate = song.lineRate != null
    ? song.lineRate.toFixed(2)
    : (song.notes > 0 ? ((song.lineEx / (song.notes * 2)) * 100).toFixed(2) : '?');
  return t('league.lineScore', { ex: song.lineEx, rate });
};

/** DIVISION の短縮表記（0=LEGEND、1..10=D1..D10）。立場タグの補足表示用。 */
const divisionShort = (tier: number | null | undefined) => {
  if (tier == null) return '';
  return tier === 0 ? 'LEGEND' : `D${tier}`;
};

/**
 * 有効曲数の分母（無効化された課題曲を除いた曲数）。
 * 管理者が解禁不可能な曲を無効化した週は 3 曲ではなくなるため、曲リストから数える。
 */
const scoredSongCount = (songs: LeagueSongInfo[]) =>
  songs.length ? songs.filter(s => !s.disabled).length : 3;

/** 成績値（着順ポイントの3曲合計＝得点）の表示。null は "-"。 */
const formatResult = (value: number | null) => fmtPts(value);

/** 順位表の行の帯色（昇格圏 = 緑 / 降格圏 = 赤）。 */
const zoneClass = (row: LeagueStandingRow) => {
  if (row.zone === 'promote') return 'bg-emerald-50 dark:bg-emerald-900/20';
  if (row.zone === 'relegate') return 'bg-rose-50 dark:bg-rose-900/20';
  return '';
};

/** 進行中の週・順位表・履歴を読み込む。 */
const loadCurrent = async () => {
  error.value = '';
  otherStandings.value = null;
  try {
    current.value = await league.fetchCurrent(ladder);
    const ov = await league.fetchOverview(ladder);
    overviewTiers.value = ov.tiers;
    history.value = await league.fetchHistory(ladder);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  }
};

/** 昇降格ニュース（全ユーザー分）を読み込む。 */
const loadNews = async () => {
  newsLoading.value = true;
  newsError.value = '';
  try {
    news.value = await league.fetchNews(ladder);
    // 既定では最新の回だけ開く（古い回は見出しの人数だけ見えていれば十分なので畳んでおく）。
    openNewsWeekIds.value = news.value.length ? [news.value[0].weekId] : [];
  } catch (e) {
    news.value = [];
    openNewsWeekIds.value = [];
    newsError.value = e instanceof Error ? e.message : String(e);
  } finally {
    newsLoading.value = false;
  }
};

/** 参加状態を読み込む。 */
const loadMe = async () => {
  try {
    entries.value = await league.fetchMe();
  } catch {
    entries.value = [];
  }
};

/** 管理者 overview を読み込む（管理者のみ）。 */
const loadAdmin = async () => {
  if (!isAdmin.value) return;
  try {
    adminLadders.value = await league.fetchAdminOverview();
    adminError.value = '';
  } catch (e) {
    // 握り潰すと編成ブロックが黙って消えて原因が分からなくなるので、理由を管理者に見せる。
    adminLadders.value = [];
    adminError.value = e instanceof Error ? e.message : String(e);
  }
  // 差し替え UI を表示する DIVISION（編集を開いている / 未編成）の選曲プールを先読みする。
  for (const al of adminLadders.value) {
    const draft = al.draftWeek;
    if (!draft) continue;
    for (const tierInfo of draft.tiers) {
      if (!tierInfo.groups.length || isSongEditOpen(draft.id, tierInfo.tier)) {
        // 候補はグループごとに異なる（拮抗判定がそのグループの参加者に依存する）。
        for (const gi of songGroupIndexes(tierInfo)) ensureSongPool(draft.id, tierInfo.tier, gi);
      }
    }
  }
};

/** 参加する。 */
const handleJoin = async () => {
  busy.value = true;
  error.value = '';
  try {
    await league.join(ladder);
    notice.value = t('league.joinedNotice');
    await loadMe();
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
};

/** 離脱する。 */
const handleLeave = async () => {
  if (!confirm(t('league.confirmLeave'))) return;
  busy.value = true;
  error.value = '';
  try {
    await league.leave(ladder);
    notice.value = '';
    await loadMe();
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
};

/** 他グループの順位表を開く。 */
const openGroup = async (tier: number, groupIndex: number) => {
  const week = current.value?.week;
  if (!week) return;
  try {
    const res = await league.fetchStandings(week.id, tier, groupIndex);
    otherStandings.value = { tier, groupIndex, songs: res.songs, standings: res.standings };
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  }
};

/**
 * 過去成績の 1 行を開閉する。開いたときにその週・自分のグループの順位表を取得し、
 * 開催中の週と同じ表（課題曲ごとの内訳付き）を折り畳みの中に出す。
 * 同じ行をもう一度押すと閉じる。行は同時に 1 つだけ開く。
 */
const toggleHistoryDetail = async (h: LeagueHistoryRow) => {
  if (openHistoryWeekId.value === h.weekId) {
    openHistoryWeekId.value = null;
    return;
  }
  openHistoryWeekId.value = h.weekId;
  historyDetail.value = null;
  historyDetailError.value = '';
  historyDetailLoading.value = true;
  try {
    const res = await league.fetchStandings(h.weekId, h.tier, h.groupIndex);
    // 取得中に別の行へ切り替えられていたら破棄する（応答の追い越し対策）。
    if (openHistoryWeekId.value !== h.weekId) return;
    historyDetail.value = { songs: res.songs, standings: res.standings };
  } catch (e) {
    if (openHistoryWeekId.value !== h.weekId) return;
    historyDetailError.value = e instanceof Error ? e.message : String(e);
  } finally {
    historyDetailLoading.value = false;
  }
};

// -------------------------------------------------------------------
// 管理者: 全リーグ履歴（すべての開催回を横断して閲覧する）
// -------------------------------------------------------------------

/**
 * 全リーグ履歴（全週の一覧）を読み込む。
 *
 * 自分の過去成績（{@link loadCurrent} の history）が「自分が参加した closed 週」だけなのに対し、
 * こちらは draft / active / closed のすべての週を返す。アコーディオンを開いたときに読むので、
 * 管理者以外・閉じたままの場合は取得しない。
 */
const loadAdminHistory = async () => {
  if (!isAdmin.value) return;
  adminHistoryLoading.value = true;
  adminHistoryError.value = '';
  try {
    adminHistory.value = await league.fetchAdminHistory(ladder);
  } catch (e) {
    adminHistory.value = [];
    adminHistoryError.value = e instanceof Error ? e.message : String(e);
  } finally {
    adminHistoryLoading.value = false;
  }
};

/** 全リーグ履歴アコーディオンを開閉する。初回に開いたときだけ取得する。 */
const toggleAdminHistory = async () => {
  showAdminHistory.value = !showAdminHistory.value;
  if (showAdminHistory.value && !adminHistory.value.length && !adminHistoryLoading.value) {
    await loadAdminHistory();
  }
};

/** 週の行を開閉する。週を切り替えたら、開いていたグループの順位表は閉じる。 */
const toggleAdminWeek = (weekId: number) => {
  openAdminWeekId.value = openAdminWeekId.value === weekId ? null : weekId;
  openAdminGroup.value = null;
  adminGroupDetail.value = null;
  adminGroupError.value = '';
};

/**
 * 展開中の週の 1 グループを開き、そのグループの順位表を取得する。
 * 同じグループをもう一度押すと閉じる。グループは同時に 1 つだけ開く。
 *
 * 取得は管理者用エンドポイントなので、当事者と同じく各曲の EX・スコアレート・BP まで見える
 * （プレイヤー向けの観戦では他人の未達スコアは伏せられる）。
 */
const openAdminGroupStandings = async (weekId: number, tier: number, groupIndex: number) => {
  const same = openAdminGroup.value?.tier === tier && openAdminGroup.value?.groupIndex === groupIndex;
  if (same) {
    openAdminGroup.value = null;
    adminGroupDetail.value = null;
    return;
  }
  openAdminGroup.value = { tier, groupIndex };
  adminGroupDetail.value = null;
  adminGroupError.value = '';
  adminGroupLoading.value = true;
  try {
    const res = await league.fetchAdminStandings(weekId, tier, groupIndex);
    // 取得中に別のグループへ切り替えられていたら破棄する（応答の追い越し対策）。
    if (openAdminGroup.value?.tier !== tier || openAdminGroup.value?.groupIndex !== groupIndex) return;
    adminGroupDetail.value = { songs: res.songs, standings: res.standings };
  } catch (e) {
    if (openAdminGroup.value?.tier !== tier || openAdminGroup.value?.groupIndex !== groupIndex) return;
    adminGroupError.value = e instanceof Error ? e.message : String(e);
  } finally {
    adminGroupLoading.value = false;
  }
};

/** 週のステータスに応じたバッジのクラス（開催中 = 緑 / 編成前 = 灰 / 締め済み = 青）。 */
const weekStatusClass = (status: string) => {
  if (status === 'active') return 'bg-emerald-100 dark:bg-emerald-900/40 text-emerald-700 dark:text-emerald-300';
  if (status === 'draft') return 'bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-400';
  return 'bg-sky-100 dark:bg-sky-900/40 text-sky-700 dark:text-sky-300';
};

// -------------------------------------------------------------------
// 管理者操作
// -------------------------------------------------------------------

/**
 * 差し替えドロップダウンの選択肢（グループ単位）。抽選と同じ選曲基準（拮抗判定・直近出題除外）を
 * 通した候補なので、週 × 階級 × グループごとに内容が変わる。キーは `${weekId}-${tier}-${groupIndex}`。
 */
const songPools = ref<Record<string, LeagueSongPool>>({});

/** 選曲プールのキャッシュキー（候補はグループごとに異なる）。 */
const poolKey = (weekId: number, tier: number, groupIndex: number) => `${weekId}-${tier}-${groupIndex}`;

/** 選曲候補を取得する（取得済み・取得中なら何もしない）。 */
const ensureSongPool = async (weekId: number, tier: number, groupIndex: number) => {
  const key = poolKey(weekId, tier, groupIndex);
  if (songPools.value[key] || poolLoading.has(key)) return;
  poolLoading.add(key);
  try {
    songPools.value[key] = await league.fetchSongPool(tier, weekId, groupIndex);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    poolLoading.delete(key);
  }
};
const poolLoading = new Set<string>();

/** その課題曲の枠に対応する選曲候補（未取得なら null）。 */
const poolFor = (weekId: number, tier: number, song: LeagueSongInfo): LeagueSongPool | null =>
  songPools.value[poolKey(weekId, tier, song.groupIndex ?? 0)] ?? null;

/** draft 週のその階級に存在するグループ番号（課題曲から拾う。未編成なら [0]）。 */
const songGroupIndexes = (tierInfo: LeagueAdminWeek['tiers'][number]): number[] => {
  const set = new Set<number>(tierInfo.songs.map(s => s.groupIndex ?? 0));
  return set.size ? [...set].sort((a, b) => a - b) : [0];
};

/**
 * 差し替えドロップダウンの選択肢。候補に加え、現在の課題曲が候補外（基準を満たさない曲が
 * 抽選で入った・手で差し替え済みなど）の場合は先頭に足して、選択状態が空にならないようにする。
 */
const songOptions = (weekId: number, tier: number, song: LeagueSongInfo): LeaguePoolSong[] => {
  const pool = poolFor(weekId, tier, song)?.songs ?? [];
  const inPool = pool.some(p => p.title === song.title && p.difficultyName === song.difficultyName);
  if (inPool) return pool;
  return [{ title: song.title, difficultyName: song.difficultyName, level: song.level, notes: song.notes }, ...pool];
};

/** 現在の課題曲が選択肢の何番目か（select の value）。 */
const currentOptionIndex = (weekId: number, tier: number, song: LeagueSongInfo) =>
  songOptions(weekId, tier, song).findIndex(o => o.title === song.title && o.difficultyName === song.difficultyName);

/**
 * 選曲プールから課題曲を選んで即時に差し替える（draft 週のみ）。
 * 差し替え後に overview を取り直すので、そのグループのライン・ライン保持者・各メンバーの
 * 自己ベストがその場で更新される。
 */
const handlePickSong = async (weekId: number, tier: number, song: LeagueSongInfo, ev: Event) => {
  const index = Number((ev.target as HTMLSelectElement).value);
  const picked = songOptions(weekId, tier, song)[index];
  if (!picked || (picked.title === song.title && picked.difficultyName === song.difficultyName)) return;
  busy.value = true;
  error.value = '';
  try {
    await league.replaceSong(weekId, song.id, picked.title, picked.difficultyName);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    // 候補は「この週・階級で既に出したタイトル」を除外して作るので、差し替えたら作り直す。
    delete songPools.value[poolKey(weekId, tier, song.groupIndex ?? 0)];
    // 成功時は新しいライン・保持者を、失敗時は元の選択状態を描き直す。
    await loadAdmin();
    busy.value = false;
  }
};

/**
 * 課題曲 1 曲の有効 / 無効を切り替える（draft・開催中どちらでも可）。
 *
 * 解禁できない譜面が抽選で入ってしまったときの救済。無効にした曲は集計から外れ、
 * そのグループは残りの曲だけで競う（開催中は差し替えるとラインが無くなるため、無効化で対応する）。
 */
const handleToggleSongDisabled = async (weekId: number, song: LeagueSongInfo) => {
  const next = !song.disabled;
  if (next && !confirm(t('league.admin.confirmDisableSong', { title: song.title }))) return;
  busy.value = true;
  error.value = '';
  notice.value = '';
  try {
    await league.setSongDisabled(weekId, song.id, next);
    notice.value = next ? t('league.admin.disabledSongDone') : t('league.admin.enabledSongDone');
    // 順位表（有効曲数・着順ポイント）も変わるので、管理表と自分の週表示の両方を取り直す。
    await Promise.all([loadAdmin(), loadCurrent()]);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
};

/** 指定階級の課題曲を再抽選する（draft 週のみ）。 */
const handleRedraw = async (weekId: number, tier: number) => {
  busy.value = true;
  error.value = '';
  try {
    await league.redrawTier(weekId, tier);
    await loadAdmin();
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
};

/** 週次処理（締め → 編成 → 開始）を手動実行する。 */
const handleRunWeekly = async (ladder: LadderType) => {
  if (!confirm(t('league.admin.confirmRunWeekly'))) return;
  busy.value = true;
  error.value = '';
  try {
    await league.runWeekly(ladder);
    await Promise.all([loadAdmin(), loadCurrent(), loadMe()]);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
};

/** draft 週を手動作成する。 */
const handleCreateDraft = async (ladder: LadderType) => {
  busy.value = true;
  error.value = '';
  try {
    await league.createDraft(ladder);
    await loadAdmin();
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
};

/** 参加締切後に draft 週を編成する（開始はしない。本番と同じ卓・グループ・課題曲を確定して確認できる）。 */
const handleForm = async (ladder: LadderType) => {
  busy.value = true;
  error.value = '';
  notice.value = '';
  try {
    await league.formDraft(ladder);
    notice.value = t('league.admin.formDone');
    await loadAdmin();
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
};

/** 誤って開始した週を中止し、開始前の状態（空 draft）に戻す。昇降格 PT・DIVISION には影響しない。 */
const handleAbort = async (ladder: LadderType) => {
  if (!confirm(t('league.admin.confirmAbort'))) return;
  busy.value = true;
  error.value = '';
  notice.value = '';
  try {
    await league.abortWeek(ladder);
    notice.value = t('league.admin.abortDone');
    await Promise.all([loadAdmin(), loadCurrent(), loadMe()]);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
};

/** 仮編成プレビューを生成する（DB は更新しない）。押すたびにランダムで組み直す。 */
const handlePreview = async (ladder: LadderType) => {
  busy.value = true;
  error.value = '';
  try {
    preview.value = await league.fetchAdminPreview(ladder);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
};

/**
 * 表示中の仮編成プレビューをそのまま draft 週へ適用する（既存の編成を置き換える）。
 * 生成後に参加者が増減している場合はサーバ側で拒否されるので、その時は作り直してから押す。
 */
const handleApplyPreview = async (ladder: LadderType) => {
  if (!preview.value) return;
  if (!confirm(t('league.admin.preview.confirmApply'))) return;
  busy.value = true;
  error.value = '';
  notice.value = '';
  try {
    await league.applyPreview(ladder, preview.value);
    notice.value = t('league.admin.preview.applyDone');
    await loadAdmin();
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
};

/** レート(%)の表示（小数第2位）。null は '-'。 */
const fmtRate = (r: number | null | undefined) => (r == null ? '-' : `${r.toFixed(2)}%`);

/** draft 課題曲をグループ→スロット順に並べる（グループ別編成の確認を見やすくする）。 */
const orderedSongs = (songs: LeagueSongInfo[]) =>
  [...songs].sort((a, b) => ((a.groupIndex ?? -1) - (b.groupIndex ?? -1)) || (a.slot - b.slot));

/** draft 週の指定グループの課題曲（スロット順）。編成表の列に使う。 */
const groupSongs = (tierInfo: LeagueAdminWeek['tiers'][number], groupIndex: number) =>
  tierInfo.songs.filter(s => (s.groupIndex ?? 0) === groupIndex).sort((a, b) => a.slot - b.slot);

/** 編成表のセル（メンバーの指定スロットの自己ベスト）。未取得なら null。 */
const memberCell = (member: LeagueAdminMember, slot: number) =>
  member.bests?.find(b => b.slot === slot) ?? null;

/** その DIVISION（卓）の編成人数。 */
const tierMemberCount = (tierInfo: LeagueAdminWeek['tiers'][number]) =>
  tierInfo.groups.reduce((sum, g) => sum + g.members.length, 0);

/** 抽選のフォールバック補填で埋まった曲が含まれるか（説明文を出すかの判定に使う）。 */
const hasFallbackSong = (songs: { fallback?: boolean }[]) => songs.some(s => s.fallback);

/** その週の課題曲にフォールバック補填の枠があるか。 */
const weekHasFallback = (week: LeagueAdminWeek | null | undefined) =>
  !!week && week.tiers.some(ti => hasFallbackSong(ti.songs));

/** 仮編成プレビューにフォールバック補填の枠があるか。 */
const previewHasFallback = computed(() =>
  !!preview.value && preview.value.tiers.some(tp => tp.groups.some(g => hasFallbackSong(g.songs))));

/** 課題曲の無効化パネルを開いている週 ID。既定は畳んでおく（管理画面が縦に長くなりすぎるため）。 */
const disablePanelOpen = ref<Set<number>>(new Set());
const isDisablePanelOpen = (weekId: number) => disablePanelOpen.value.has(weekId);
const toggleDisablePanel = (weekId: number) => {
  if (disablePanelOpen.value.has(weekId)) {
    disablePanelOpen.value.delete(weekId);
  } else {
    disablePanelOpen.value.add(weekId);
  }
};

/** その週で無効化されている課題曲の数（畳んでいても件数だけは見えるようにする）。 */
const disabledSongCount = (week: LeagueAdminWeek) =>
  week.tiers.reduce((sum, ti) => sum + ti.songs.filter(s => s.disabled).length, 0);

/** 課題曲の差し替えフォームを開いている (weekId, tier)。既定は畳んで編成表を見やすくする。 */
const songEditOpen = ref<Set<string>>(new Set());
const isSongEditOpen = (weekId: number, tier: number) => songEditOpen.value.has(`${weekId}-${tier}`);
const toggleSongEdit = (weekId: number, tier: number, groupIndexes: number[]) => {
  const key = `${weekId}-${tier}`;
  if (songEditOpen.value.has(key)) {
    songEditOpen.value.delete(key);
  } else {
    songEditOpen.value.add(key);
    // 開いたタイミングで各グループの選曲候補を取りに行く。
    for (const gi of groupIndexes) ensureSongPool(weekId, tier, gi);
  }
};

watch(isLoggedIn, (v) => {
  if (v) {
    loadMe();
    loadCurrent();
    loadNews();
    loadAdmin();
  }
});

onMounted(() => {
  timer = setInterval(() => { now.value = Date.now(); }, 30000);
  if (isLoggedIn.value) {
    loadMe();
    loadCurrent();
    loadNews();
    loadAdmin();
  }
});
onUnmounted(() => {
  if (timer) clearInterval(timer);
});
</script>

<template>
  <div class="space-y-6">
    <!-- ヘッダー -->
    <div>
      <div class="flex items-center gap-3">
        <h1 class="text-2xl font-bold text-slate-800 dark:text-slate-100">{{ t('league.title') }}</h1>
        <button
          class="flex items-center gap-1 text-xs px-2.5 py-1 rounded-full border border-indigo-300 dark:border-indigo-700 text-indigo-600 dark:text-indigo-400 hover:bg-indigo-50 dark:hover:bg-indigo-900/30 transition-colors"
          @click="showInfo = true"
        >
          <svg class="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
          </svg>
          {{ t('league.info') }}
        </button>
        <!-- DIVISION 別ランキング（各 DIVISION を昇降格 PT の降順で一覧する）。 -->
        <button
          v-if="isLoggedIn"
          class="flex items-center gap-1 text-xs px-2.5 py-1 rounded-full border border-amber-300 dark:border-amber-700 text-amber-600 dark:text-amber-400 hover:bg-amber-50 dark:hover:bg-amber-900/30 transition-colors"
          @click="showRanking = true"
        >
          <svg class="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M16 11V3H8v8M4 21h16M6 21v-6h12v6M9 7h6" />
          </svg>
          {{ t('league.rankingModal.open') }}
        </button>
      </div>
      <p class="mt-1 text-sm text-slate-500 dark:text-slate-400">{{ t('league.subtitle') }}</p>
      <p class="mt-2 text-xs text-slate-500 dark:text-slate-400">{{ t('league.scoreDesc') }}</p>
    </div>

    <!-- 未ログイン -->
    <div v-if="!isLoggedIn" class="bg-white dark:bg-slate-800 rounded-xl shadow p-6 text-center text-slate-500 dark:text-slate-400">
      {{ t('league.loginRequired') }}
    </div>

    <template v-else>
      <!-- エラー / 通知 -->
      <div v-if="error" class="bg-rose-50 dark:bg-rose-900/30 border border-rose-200 dark:border-rose-800 text-rose-700 dark:text-rose-300 rounded-lg px-4 py-3 text-sm">
        {{ error }}
      </div>
      <div v-if="notice" class="bg-emerald-50 dark:bg-emerald-900/30 border border-emerald-200 dark:border-emerald-800 text-emerald-700 dark:text-emerald-300 rounded-lg px-4 py-3 text-sm">
        {{ notice }}
      </div>

      <!-- 参加/離脱カード -->
      <div class="bg-white dark:bg-slate-800 rounded-xl shadow p-5">
        <div class="flex flex-wrap items-center justify-between gap-3">
          <div>
            <div class="flex items-center gap-2">
              <span class="font-semibold text-slate-800 dark:text-slate-100">
                {{ isJoined ? t('league.joined') : t('league.notJoined') }}
              </span>
              <span v-if="isJoined && myEntry?.currentTier != null"
                    class="text-xs px-2 py-0.5 rounded-full bg-indigo-100 dark:bg-indigo-900/40 text-indigo-700 dark:text-indigo-300 font-semibold">
                {{ divisionName(myEntry.currentTier) }}
              </span>
              <!-- 参加登録済みだが今週の編成には入っていない（次週から参戦）。 -->
              <span v-if="isPendingNextWeek"
                    class="text-xs px-2 py-0.5 rounded-full bg-amber-100 dark:bg-amber-900/40 text-amber-700 dark:text-amber-300 font-semibold">
                {{ t('league.pendingPlacement') }}
              </span>
            </div>
            <!-- 昇降格 PT のインジケーター（あとどれくらいで昇格/降格かを可視化）。 -->
            <LeaguePointGauge
              v-if="isJoined && myEntry"
              class="mt-2"
              :points="myEntry.points"
              :tier="myEntry.currentTier"
            />
            <p v-if="!isJoined" class="mt-1 text-xs text-slate-500 dark:text-slate-400">{{ t('league.joinNote') }}</p>
            <p v-if="!isJoined" class="mt-1 text-xs text-amber-600 dark:text-amber-400">{{ t('league.privacyNote') }}</p>
          </div>
          <button
            v-if="!isJoined"
            class="px-5 py-2 rounded-lg bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-semibold disabled:opacity-50"
            :disabled="busy"
            @click="handleJoin"
          >{{ t('league.join') }}</button>
          <button
            v-else
            class="px-4 py-2 rounded-lg border border-slate-300 dark:border-slate-600 text-slate-600 dark:text-slate-300 text-sm hover:bg-slate-100 dark:hover:bg-slate-700 disabled:opacity-50"
            :disabled="busy"
            @click="handleLeave"
          >{{ t('league.leave') }}</button>
        </div>
      </div>

      <!-- 開催中の週 -->
      <div v-if="!current?.week" class="bg-white dark:bg-slate-800 rounded-xl shadow p-6 text-center text-slate-500 dark:text-slate-400 text-sm">
        {{ t('league.noWeek') }}
      </div>
      <template v-else>
        <!-- 週ヘッダー + 課題曲 -->
        <div class="bg-white dark:bg-slate-800 rounded-xl shadow p-5">
          <div class="flex flex-wrap items-center justify-between gap-2">
            <h2 class="font-bold text-slate-800 dark:text-slate-100">
              <span class="mr-2 inline-block align-middle text-xs font-bold px-2 py-0.5 rounded-full bg-indigo-600 text-white dark:bg-indigo-500">
                {{ weekLabel(current.week.weekNo) }}
              </span>
              {{ t('league.weekOf', { start: shortDateTime(current.week.startsAt), end: shortDateTime(current.week.endsAt) }) }}
              <span v-if="current.member" class="ml-2 text-xs font-semibold text-indigo-600 dark:text-indigo-400">
                {{ divisionName(current.member.tier) }} / {{ t('league.groupN', { n: current.member.groupIndex + 1 }) }}
              </span>
              <span v-else-if="isPendingNextWeek"
                    class="ml-2 text-xs font-semibold px-2 py-0.5 rounded-full bg-amber-100 dark:bg-amber-900/40 text-amber-700 dark:text-amber-300">
                {{ t('league.notInThisWeek') }}
              </span>
            </h2>
            <span v-if="countdown" class="text-xs font-semibold text-amber-600 dark:text-amber-400">
              {{ t('league.endsIn', { time: countdown }) }}
            </span>
          </div>
          <!-- チャレンジ/ディフェンス（他卓に着席中）の説明 -->
          <div v-if="current.member && current.member.role && current.member.role !== 'normal'"
               class="mt-2 text-xs rounded-lg px-3 py-2"
               :class="current.member.role === 'challenge'
                 ? 'bg-orange-50 dark:bg-orange-900/20 text-orange-700 dark:text-orange-300'
                 : 'bg-sky-50 dark:bg-sky-900/20 text-sky-700 dark:text-sky-300'">
            {{ current.member.role === 'challenge'
              ? t('league.challengeNote', { home: divisionName(current.member.homeTier), table: divisionName(current.member.tier) })
              : t('league.defenseNote', { home: divisionName(current.member.homeTier), table: divisionName(current.member.tier) }) }}
          </div>

          <!-- 次週から参戦: 今週の課題曲は自分の対象ではないので出さない（参加中との誤解を防ぐ）。 -->
          <div v-if="isPendingNextWeek"
               class="mt-4 rounded-lg border border-amber-200 dark:border-amber-700/50 bg-amber-50 dark:bg-amber-900/20 px-4 py-3">
            <p class="text-sm font-semibold text-amber-700 dark:text-amber-300">{{ t('league.pendingTitle') }}</p>
            <p class="mt-1 text-xs leading-relaxed text-amber-700/90 dark:text-amber-300/90">{{ t('league.pendingNote') }}</p>
          </div>

          <template v-else>
          <h3 class="mt-4 text-sm font-semibold text-slate-600 dark:text-slate-300">
            <template v-if="current.member">{{ t('league.songs') }}</template>
            <template v-else>
              {{ t('league.songsPreview') }}
              <span v-if="current.previewTier != null" class="ml-1 text-indigo-600 dark:text-indigo-400">{{ divisionName(current.previewTier) }}</span>
            </template>
          </h3>
          <div class="mt-2 grid grid-cols-1 sm:grid-cols-3 gap-3">
            <div
              v-for="song in current.songs"
              :key="song.id"
              class="rounded-lg border border-slate-200 dark:border-slate-700 p-3"
              :class="song.disabled ? 'bg-slate-50 dark:bg-slate-900/40' : ''"
            >
              <div class="text-xs text-slate-400 dark:text-slate-500">
                {{ song.difficultyName }} <span v-if="song.level">☆{{ song.level }}</span>
              </div>
              <div class="mt-0.5 font-semibold text-sm break-words"
                   :class="song.disabled ? 'line-through text-slate-400 dark:text-slate-500' : 'text-slate-800 dark:text-slate-100'">{{ song.title }}</div>
              <!-- 無効化された曲（解禁不可能な選曲など）は集計対象外。ラインや達成状況は出さない。 -->
              <div v-if="song.disabled" class="mt-2 text-xs">
                <span class="px-1.5 py-0.5 rounded font-semibold bg-rose-100 dark:bg-rose-900/40 text-rose-700 dark:text-rose-300">
                  {{ t('league.songDisabled') }}
                </span>
                <p class="mt-1 text-[11px] leading-relaxed text-slate-500 dark:text-slate-400">{{ t('league.songDisabledNote') }}</p>
              </div>
              <template v-else-if="myRow">
                <div v-for="ps in myRow.perSong.filter(p => p.slot === song.slot)" :key="ps.slot" class="mt-2 text-xs space-y-1">
                  <div class="text-amber-600 dark:text-amber-400 font-semibold">{{ lineLabel(ps, song) }}</div>
                  <div>
                    <span
                      class="px-1.5 py-0.5 rounded font-semibold"
                      :class="ps.valid
                        ? 'bg-emerald-100 dark:bg-emerald-900/40 text-emerald-700 dark:text-emerald-300'
                        : 'bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-400'"
                    >{{ ps.valid ? t('league.played') : t('league.notPlayed') }}</span>
                    <span class="ml-2 text-slate-600 dark:text-slate-300">
                      {{ ps.rate != null ? `${ps.rate.toFixed(2)}% (${ps.bestEx})` : '-' }}
                    </span>
                    <span v-if="ps.rank != null" class="ml-2 font-semibold text-indigo-600 dark:text-indigo-400">{{ t('league.songRank', { n: ps.rank }) }}</span>
                  </div>
                </div>
              </template>
            </div>
          </div>
          <p v-if="current.member" class="mt-3 text-xs text-slate-400 dark:text-slate-500">{{ t('league.playRequired') }}</p>
          </template>
        </div>

        <!-- 自分のグループの順位表 -->
        <div v-if="current.standings" class="bg-white dark:bg-slate-800 rounded-xl shadow p-5">
          <div class="flex items-center justify-between">
            <h3 class="font-bold text-slate-800 dark:text-slate-100">{{ t('league.standings') }}</h3>
            <div class="flex gap-3 text-xs">
              <span class="flex items-center gap-1 text-emerald-600 dark:text-emerald-400">
                <span class="w-2.5 h-2.5 rounded-sm bg-emerald-200 dark:bg-emerald-900/60 inline-block"></span>{{ t('league.promoteZone') }}
              </span>
              <span class="flex items-center gap-1 text-rose-600 dark:text-rose-400">
                <span class="w-2.5 h-2.5 rounded-sm bg-rose-200 dark:bg-rose-900/60 inline-block"></span>{{ t('league.relegateZone') }}
              </span>
            </div>
          </div>
          <div class="mt-3">
            <LeagueStandingsTable :songs="current.songs" :standings="current.standings" :my-user-id="user?.id" />
          </div>
          <p class="mt-2 text-[11px] leading-relaxed text-slate-400 dark:text-slate-500">{{ t('league.songPointsHint') }}</p>
        </div>

        <!-- 他グループ（観戦） -->
        <div v-if="overviewTiers.length" class="bg-white dark:bg-slate-800 rounded-xl shadow p-5">
          <h3 class="font-bold text-slate-800 dark:text-slate-100">{{ t('league.otherGroups') }}</h3>
          <div class="mt-3 space-y-2">
            <div v-for="tierInfo in overviewTiers" :key="tierInfo.tier" class="flex flex-wrap items-center gap-2">
              <span class="text-xs font-semibold text-slate-500 dark:text-slate-400 w-32">{{ divisionName(tierInfo.tier) }}</span>
              <button
                v-for="g in tierInfo.groups"
                :key="g.groupIndex"
                class="text-xs px-3 py-1 rounded-lg border border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700"
                @click="openGroup(tierInfo.tier, g.groupIndex)"
              >
                {{ t('league.groupN', { n: g.groupIndex + 1 }) }} ({{ g.memberCount }})
              </button>
            </div>
          </div>

          <!-- 開いた他グループの順位表 -->
          <div v-if="otherStandings" class="mt-4 border-t border-slate-200 dark:border-slate-700 pt-3">
            <div class="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-2">
              {{ divisionName(otherStandings.tier) }} / {{ t('league.groupN', { n: otherStandings.groupIndex + 1 }) }}
            </div>
            <!-- 他グループの課題曲 + 有効ライン -->
            <div v-if="otherStandings.songs.length" class="grid grid-cols-1 sm:grid-cols-3 gap-2 mb-3">
              <div v-for="song in otherStandings.songs" :key="song.slot"
                   class="rounded-lg border border-slate-200 dark:border-slate-700 p-2">
                <div class="text-[10px] text-slate-400 dark:text-slate-500">
                  {{ song.difficultyName }} <span v-if="song.level">☆{{ song.level }}</span>
                </div>
                <div class="text-xs font-semibold break-words"
                     :class="song.disabled ? 'line-through text-slate-400 dark:text-slate-500' : 'text-slate-800 dark:text-slate-100'">{{ song.title }}</div>
                <div v-if="song.disabled" class="mt-0.5 text-[11px] font-semibold text-rose-600 dark:text-rose-400">{{ t('league.songDisabled') }}</div>
                <div v-else class="mt-0.5 text-[11px] font-semibold text-amber-600 dark:text-amber-400">{{ songLineLabel(song) }}</div>
              </div>
            </div>
            <div class="overflow-x-auto">
              <table class="w-full text-sm">
                <thead>
                  <tr class="text-left text-xs text-slate-400 dark:text-slate-500 border-b border-slate-200 dark:border-slate-700">
                    <th class="py-1.5 pr-2 w-10">{{ t('league.rank') }}</th>
                    <th class="py-1.5 pr-2">{{ t('league.player') }}</th>
                    <th class="py-1.5 pr-2 text-center">{{ t('league.validSongs') }}</th>
                    <th class="py-1.5 pr-2 text-right">{{ t('league.leaguePoints') }}</th>
                    <th class="py-1.5 pr-2 text-center">{{ t('league.points') }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="row in otherStandings.standings" :key="row.userId"
                      class="border-b border-slate-100 dark:border-slate-700/50" :class="zoneClass(row)">
                    <td class="py-1.5 pr-2">{{ row.rank }}</td>
                    <td class="py-1.5 pr-2 break-words">
                      <span class="inline-flex items-center gap-1.5 align-middle">
                        <RankIcon :rank-name="beatTier(row.totalBeatPt).name" :tier="beatTier(row.totalBeatPt).tier" size="2xs" lite disable-party />
                        <span>{{ row.displayName }}</span>
                        <span v-if="roleBadge(row.role)"
                              class="inline-flex items-center gap-0.5 px-1.5 py-px rounded text-[10px] font-bold leading-none"
                              :class="roleBadge(row.role)!.cls"
                              :title="roleBadge(row.role)!.label + (row.homeTier != null ? ' / ' + divisionName(row.homeTier) : '')">{{ roleBadge(row.role)!.label }}<span v-if="row.homeTier != null" class="font-semibold opacity-80">{{ divisionShort(row.homeTier) }}</span></span>
                      </span>
                    </td>
                    <td class="py-1.5 pr-2 text-center">{{ row.validSongs }}/{{ scoredSongCount(otherStandings.songs) }}</td>
                    <td class="py-1.5 pr-2 text-right tabular-nums">{{ formatResult(row.resultValue) }}</td>
                    <td class="py-1.5 pr-2 text-center tabular-nums whitespace-nowrap">
                      {{ fmtPt(row.points) }}
                      <span class="text-xs text-slate-400 dark:text-slate-500">({{ fmtPt(row.pointDelta) }})</span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </template>

      <!-- 過去の成績 -->
      <div class="bg-white dark:bg-slate-800 rounded-xl shadow p-5">
        <button class="w-full flex items-center justify-between text-left" @click="showHistory = !showHistory">
          <h3 class="font-bold text-slate-800 dark:text-slate-100">{{ t('league.history') }}</h3>
          <span class="text-slate-400">{{ showHistory ? '▲' : '▼' }}</span>
        </button>
        <div v-if="showHistory" class="mt-3">
          <p v-if="!history.length" class="text-sm text-slate-400 dark:text-slate-500">{{ t('league.noHistory') }}</p>
          <table v-else class="w-full text-sm">
            <thead>
              <tr class="text-left text-xs text-slate-400 dark:text-slate-500 border-b border-slate-200 dark:border-slate-700">
                <th class="py-2 pr-2">{{ t('league.roundLabel') }}</th>
                <th class="py-2 pr-2">{{ t('league.week') }}</th>
                <th class="py-2 pr-2">{{ t('league.tier') }}</th>
                <th class="py-2 pr-2 text-center">{{ t('league.rank') }}</th>
                <th class="py-2 pr-2 text-right">{{ t('league.leaguePoints') }}</th>
                <th class="py-2 pr-2 text-center">{{ t('league.points') }}</th>
                <th class="py-2 pr-2 text-center">{{ t('league.movementLabel') }}</th>
                <th class="py-2 pr-2 w-8"></th>
              </tr>
            </thead>
            <tbody>
              <template v-for="h in history" :key="h.weekId">
              <tr class="border-b border-slate-100 dark:border-slate-700/50 cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-700/30"
                  @click="toggleHistoryDetail(h)">
                <td class="py-2 pr-2 font-semibold whitespace-nowrap">{{ weekLabel(h.weekNo) }}</td>
                <td class="py-2 pr-2">{{ shortDate(h.startsAt) }}〜{{ shortDate(h.endsAt) }}</td>
                <td class="py-2 pr-2">{{ divisionName(h.tier) }}</td>
                <td class="py-2 pr-2 text-center">{{ h.finalRank ?? '-' }}</td>
                <td class="py-2 pr-2 text-right tabular-nums">{{ formatResult(h.resultValue) }}</td>
                <td class="py-2 pr-2 text-center tabular-nums">{{ fmtPt(h.pointDelta) }}</td>
                <td class="py-2 pr-2 text-center">
                  <span
                    v-if="h.movement"
                    class="text-xs px-2 py-0.5 rounded-full font-semibold"
                    :class="h.movement === 'promote'
                      ? 'bg-emerald-100 dark:bg-emerald-900/40 text-emerald-700 dark:text-emerald-300'
                      : h.movement === 'relegate'
                        ? 'bg-rose-100 dark:bg-rose-900/40 text-rose-700 dark:text-rose-300'
                        : 'bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-400'"
                  >{{ t(`league.movement.${h.movement}`) }}</span>
                  <span v-else>-</span>
                </td>
                <td class="py-2 pr-2 text-center text-slate-400">{{ openHistoryWeekId === h.weekId ? '▲' : '▼' }}</td>
              </tr>
              <!-- 折り畳み: その週の自分のグループの順位表（開催中の週と同じ表）。 -->
              <tr v-if="openHistoryWeekId === h.weekId" class="border-b border-slate-100 dark:border-slate-700/50">
                <td colspan="8" class="py-3 px-1 bg-slate-50 dark:bg-slate-900/30">
                  <p v-if="historyDetailError" class="text-sm text-rose-500">{{ historyDetailError }}</p>
                  <p v-else-if="historyDetailLoading" class="text-sm text-slate-400 dark:text-slate-500">{{ t('common.loading') }}</p>
                  <template v-else-if="historyDetail">
                    <div class="text-xs text-slate-500 dark:text-slate-400 mb-2">
                      {{ divisionName(h.tier) }} / {{ t('league.groupN', { n: h.groupIndex + 1 }) }}
                    </div>
                    <!-- 課題曲の一覧（曲別セルの列がどの曲か分かるように） -->
                    <ul class="mb-2 text-xs text-slate-500 dark:text-slate-400 space-y-0.5">
                      <li v-for="s in historyDetail.songs" :key="s.id">
                        <span :class="s.disabled ? 'line-through text-slate-400 dark:text-slate-500' : ''">
                          {{ s.slot }}. {{ s.title }}
                          <span class="text-slate-400 dark:text-slate-500">［{{ s.difficultyName }}］</span>
                        </span>
                        <span v-if="s.disabled" class="ml-1 text-rose-600 dark:text-rose-400 font-semibold">{{ t('league.songDisabled') }}</span>
                      </li>
                    </ul>
                    <LeagueStandingsTable :songs="historyDetail.songs" :standings="historyDetail.standings" :my-user-id="user?.id" />
                  </template>
                </td>
              </tr>
              </template>
            </tbody>
          </table>
        </div>
      </div>

      <!-- 昇降格ニュース（全ユーザー。締め済みの週だけが対象なので、開催中の週はまだ出ない） -->
      <div class="bg-white dark:bg-slate-800 rounded-xl shadow p-5">
        <button class="w-full flex items-center justify-between text-left" @click="showNews = !showNews">
          <h3 class="font-bold text-slate-800 dark:text-slate-100">{{ t('league.news.title') }}</h3>
          <span class="text-slate-400">{{ showNews ? '▲' : '▼' }}</span>
        </button>
        <div v-if="showNews" class="mt-3">
          <p class="text-xs text-slate-500 dark:text-slate-400 mb-3">{{ t('league.news.desc') }}</p>
          <p v-if="newsError" class="text-sm text-rose-500">{{ newsError }}</p>
          <p v-else-if="newsLoading" class="text-sm text-slate-400 dark:text-slate-500">{{ t('common.loading') }}</p>
          <p v-else-if="!news.length" class="text-sm text-slate-400 dark:text-slate-500">{{ t('league.news.empty') }}</p>
          <div v-else class="space-y-4">
            <div v-for="w in news" :key="w.weekId">
              <button
                class="w-full flex items-baseline flex-wrap gap-x-2 gap-y-1 text-left pb-1 border-b border-slate-200 dark:border-slate-700"
                @click="toggleNewsWeek(w.weekId)"
              >
                <span class="text-sm font-semibold text-slate-700 dark:text-slate-200">{{ weekLabel(w.weekNo) }}</span>
                <span class="text-xs text-slate-400 dark:text-slate-500">{{ shortDate(w.startsAt) }}〜{{ shortDate(w.endsAt) }}</span>
                <span v-if="newsCounts(w).promote"
                      class="text-[11px] px-1.5 py-0.5 rounded-full font-semibold bg-emerald-100 dark:bg-emerald-900/40 text-emerald-700 dark:text-emerald-300">
                  {{ t('league.news.promoted', { n: newsCounts(w).promote }) }}
                </span>
                <span v-if="newsCounts(w).relegate"
                      class="text-[11px] px-1.5 py-0.5 rounded-full font-semibold bg-rose-100 dark:bg-rose-900/40 text-rose-700 dark:text-rose-300">
                  {{ t('league.news.relegated', { n: newsCounts(w).relegate }) }}
                </span>
                <span class="ml-auto text-xs text-slate-400 dark:text-slate-500">{{ isNewsWeekOpen(w.weekId) ? '▲' : '▼' }}</span>
              </button>
              <ul v-if="isNewsWeekOpen(w.weekId)" class="space-y-1 mt-2">
                <li
                  v-for="item in w.items"
                  :key="`${w.weekId}-${item.userId}`"
                  class="flex items-center gap-2 text-sm py-1 px-2 rounded"
                  :class="[
                    item.movement === 'promote'
                      ? 'bg-emerald-50 dark:bg-emerald-900/20'
                      : 'bg-rose-50 dark:bg-rose-900/20',
                    item.userId === user?.id ? 'font-semibold' : '',
                  ]"
                >
                  <span
                    class="text-xs px-2 py-0.5 rounded-full font-semibold shrink-0"
                    :class="item.movement === 'promote'
                      ? 'bg-emerald-100 dark:bg-emerald-900/40 text-emerald-700 dark:text-emerald-300'
                      : 'bg-rose-100 dark:bg-rose-900/40 text-rose-700 dark:text-rose-300'"
                  >{{ t(`league.movement.${item.movement}`) }}</span>
                  <RankIcon :rank-name="beatTier(item.totalBeatPt).name" :tier="beatTier(item.totalBeatPt).tier" size="2xs" lite disable-party />
                  <span class="truncate text-slate-700 dark:text-slate-200">{{ item.displayName }}</span>
                  <span v-if="item.userId === user?.id" class="text-[10px] text-indigo-500 dark:text-indigo-400 shrink-0">YOU</span>
                  <span class="ml-auto text-xs text-slate-500 dark:text-slate-400 whitespace-nowrap shrink-0">
                    {{ divisionName(item.fromTier) }}
                    <span class="mx-0.5" :class="item.movement === 'promote'
                      ? 'text-emerald-600 dark:text-emerald-400'
                      : 'text-rose-600 dark:text-rose-400'">→</span>
                    <span class="font-semibold text-slate-700 dark:text-slate-200">{{ divisionName(item.toTier) }}</span>
                  </span>
                </li>
              </ul>
            </div>
          </div>
        </div>
      </div>

      <!-- 管理者セクション -->
      <div v-if="isAdmin" class="bg-white dark:bg-slate-800 rounded-xl shadow p-5 border-2 border-amber-300 dark:border-amber-700">
        <h3 class="font-bold text-amber-700 dark:text-amber-400">{{ t('league.admin.title') }}</h3>
        <!-- overview が取れないと編成ブロックが丸ごと消えるので、原因（サーバのエラー文）を出す。 -->
        <div v-if="adminError"
             class="mt-2 rounded-lg border border-rose-300 dark:border-rose-700 bg-rose-50 dark:bg-rose-900/20 px-3 py-2">
          <p class="text-xs font-semibold text-rose-700 dark:text-rose-300">{{ t('league.admin.overviewError') }}</p>
          <p class="mt-1 text-[11px] break-words text-rose-600 dark:text-rose-400">{{ adminError }}</p>
          <button class="mt-1.5 text-[11px] px-2 py-1 rounded border border-rose-300 dark:border-rose-700 text-rose-600 dark:text-rose-400 hover:bg-rose-100 dark:hover:bg-rose-900/40 disabled:opacity-50"
                  :disabled="busy" @click="loadAdmin()">{{ t('league.admin.retry') }}</button>
        </div>

        <!-- 全リーグ履歴: すべての開催回を横断して、任意のグループの順位表を開く -->
        <div class="mt-4 border-t border-slate-200 dark:border-slate-700 pt-3">
          <button class="w-full flex items-center justify-between text-left" @click="toggleAdminHistory()">
            <span class="font-semibold text-slate-700 dark:text-slate-200">{{ t('league.admin.history.title') }}</span>
            <span class="text-slate-400">{{ showAdminHistory ? '▲' : '▼' }}</span>
          </button>
          <p class="mt-1 text-[11px] text-slate-500 dark:text-slate-400">{{ t('league.admin.history.desc') }}</p>

          <div v-if="showAdminHistory" class="mt-3">
            <div v-if="adminHistoryError"
                 class="rounded-lg border border-rose-300 dark:border-rose-700 bg-rose-50 dark:bg-rose-900/20 px-3 py-2">
              <p class="text-[11px] break-words text-rose-600 dark:text-rose-400">{{ adminHistoryError }}</p>
              <button class="mt-1.5 text-[11px] px-2 py-1 rounded border border-rose-300 dark:border-rose-700 text-rose-600 dark:text-rose-400 hover:bg-rose-100 dark:hover:bg-rose-900/40"
                      @click="loadAdminHistory()">{{ t('league.admin.retry') }}</button>
            </div>
            <p v-else-if="adminHistoryLoading" class="text-sm text-slate-400 dark:text-slate-500">{{ t('common.loading') }}</p>
            <p v-else-if="!adminHistory.length" class="text-sm text-slate-400 dark:text-slate-500">{{ t('league.admin.history.empty') }}</p>

            <div v-else class="overflow-x-auto">
              <table class="w-full text-sm">
                <thead>
                  <tr class="text-left text-xs text-slate-400 dark:text-slate-500 border-b border-slate-200 dark:border-slate-700">
                    <th class="py-2 pr-2">{{ t('league.roundLabel') }}</th>
                    <th class="py-2 pr-2">{{ t('league.week') }}</th>
                    <th class="py-2 pr-2">{{ t('league.admin.history.status') }}</th>
                    <th class="py-2 pr-2 text-center">{{ t('league.admin.history.members') }}</th>
                    <th class="py-2 pr-2 text-center" :title="t('league.admin.history.scorersHint')">
                      {{ t('league.admin.history.scorers') }}
                    </th>
                    <th class="py-2 pr-2 text-center" :title="t('league.admin.history.playersHint')">
                      {{ t('league.admin.history.players') }}
                    </th>
                    <th class="py-2 pr-2 text-center">{{ t('league.admin.history.divisions') }}</th>
                    <th class="py-2 pr-2 w-8"></th>
                  </tr>
                </thead>
                <tbody>
                  <template v-for="w in adminHistory" :key="w.id">
                    <tr class="border-b border-slate-100 dark:border-slate-700/50 cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-700/30"
                        @click="toggleAdminWeek(w.id)">
                      <td class="py-2 pr-2 font-semibold whitespace-nowrap">{{ weekLabel(w.weekNo) }}</td>
                      <td class="py-2 pr-2 whitespace-nowrap">{{ shortDate(w.startsAt) }}〜{{ shortDate(w.endsAt) }}</td>
                      <td class="py-2 pr-2">
                        <span class="text-xs px-2 py-0.5 rounded-full font-semibold" :class="weekStatusClass(w.status)">
                          {{ t(`league.admin.history.status_${w.status}`) }}
                        </span>
                      </td>
                      <td class="py-2 pr-2 text-center tabular-nums">{{ w.memberCount }}</td>
                      <td class="py-2 pr-2 text-center tabular-nums">
                        <template v-if="w.validMemberCount != null">
                          {{ w.validMemberCount }}
                          <span v-if="w.memberCount > 0" class="ml-1 text-xs text-slate-400 dark:text-slate-500">
                            ({{ Math.round((w.validMemberCount / w.memberCount) * 100) }}%)
                          </span>
                        </template>
                        <span v-else class="text-slate-400 dark:text-slate-500">-</span>
                      </td>
                      <!-- プレーあり: ラインに届かなくても課題曲を遊んだ人数（有効ありを含む）。 -->
                      <td class="py-2 pr-2 text-center tabular-nums">
                        <template v-if="w.playedMemberCount != null">
                          {{ w.playedMemberCount }}
                          <span v-if="w.memberCount > 0" class="ml-1 text-xs text-slate-400 dark:text-slate-500">
                            ({{ Math.round((w.playedMemberCount / w.memberCount) * 100) }}%)
                          </span>
                        </template>
                        <span v-else class="text-slate-400 dark:text-slate-500">-</span>
                      </td>
                      <td class="py-2 pr-2 text-center tabular-nums">{{ w.tiers.length }}</td>
                      <td class="py-2 pr-2 text-center text-slate-400">{{ openAdminWeekId === w.id ? '▲' : '▼' }}</td>
                    </tr>

                    <!-- 折り畳み: その週の DIVISION / グループ一覧と、選んだグループの順位表 -->
                    <tr v-if="openAdminWeekId === w.id" class="border-b border-slate-100 dark:border-slate-700/50">
                      <td colspan="8" class="py-3 px-1 bg-slate-50 dark:bg-slate-900/30">
                        <p v-if="!w.tiers.length" class="text-sm text-slate-400 dark:text-slate-500">
                          {{ t('league.admin.notFormed') }}
                        </p>
                        <template v-else>
                          <div v-for="tr in w.tiers" :key="tr.tier" class="mb-2 flex flex-wrap items-center gap-1.5">
                            <span class="text-xs font-semibold text-slate-600 dark:text-slate-300 w-32 shrink-0">
                              {{ divisionName(tr.tier) }}
                            </span>
                            <button v-for="g in tr.groups" :key="g.groupIndex"
                                    class="text-xs px-2 py-1 rounded border transition-colors"
                                    :class="openAdminGroup?.tier === tr.tier && openAdminGroup?.groupIndex === g.groupIndex
                                      ? 'bg-amber-500 border-amber-500 text-white'
                                      : 'border-slate-300 dark:border-slate-600 text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700'"
                                    @click="openAdminGroupStandings(w.id, tr.tier, g.groupIndex)">
                              {{ t('league.groupN', { n: g.groupIndex + 1 }) }}
                              <span class="opacity-70">({{ g.memberCount }})</span>
                            </button>
                          </div>

                          <!-- 選んだグループの順位表（各スコアは当事者と同じ内訳が見える） -->
                          <div v-if="openAdminGroup" class="mt-3 border-t border-slate-200 dark:border-slate-700 pt-3">
                            <p v-if="adminGroupError" class="text-sm text-rose-500">{{ adminGroupError }}</p>
                            <p v-else-if="adminGroupLoading" class="text-sm text-slate-400 dark:text-slate-500">{{ t('common.loading') }}</p>
                            <template v-else-if="adminGroupDetail">
                              <div class="text-xs text-slate-500 dark:text-slate-400 mb-2">
                                {{ divisionName(openAdminGroup.tier) }} / {{ t('league.groupN', { n: openAdminGroup.groupIndex + 1 }) }}
                              </div>
                              <ul class="mb-2 text-xs text-slate-500 dark:text-slate-400 space-y-0.5">
                                <li v-for="s in adminGroupDetail.songs" :key="s.id">
                                  <span :class="s.disabled ? 'line-through text-slate-400 dark:text-slate-500' : ''">
                                    {{ s.slot }}. {{ s.title }}
                                    <span class="text-slate-400 dark:text-slate-500">［{{ s.difficultyName }}］</span>
                                  </span>
                                  <span class="ml-1 text-slate-400 dark:text-slate-500">{{ songLineLabel(s) }}</span>
                                  <span v-if="s.disabled" class="ml-1 text-rose-600 dark:text-rose-400 font-semibold">{{ t('league.songDisabled') }}</span>
                                </li>
                              </ul>
                              <p v-if="!adminGroupDetail.standings.length" class="text-sm text-slate-400 dark:text-slate-500">
                                {{ t('league.admin.history.noMembers') }}
                              </p>
                              <LeagueStandingsTable v-else
                                                    :songs="adminGroupDetail.songs"
                                                    :standings="adminGroupDetail.standings"
                                                    :my-user-id="user?.id" />
                            </template>
                          </div>
                        </template>
                      </td>
                    </tr>
                  </template>
                </tbody>
              </table>
            </div>
          </div>
        </div>

        <div v-for="al in adminLadders" :key="al.ladder" class="mt-4 border-t border-slate-200 dark:border-slate-700 pt-3">
          <div class="flex flex-wrap items-center justify-between gap-2">
            <div class="font-semibold text-slate-700 dark:text-slate-200">
              {{ t('league.scoreLadder') }}
              <span class="ml-2 text-xs text-slate-400">{{ t('league.admin.entryCount') }}: {{ al.activeEntryCount }}</span>
            </div>
            <div class="flex flex-wrap gap-2">
              <button class="text-xs px-3 py-1.5 rounded-lg bg-slate-600 hover:bg-slate-700 text-white disabled:opacity-50"
                      :disabled="busy" @click="handleCreateDraft(al.ladder)">{{ t('league.admin.createDraft') }}</button>
              <button class="text-xs px-3 py-1.5 rounded-lg bg-teal-600 hover:bg-teal-700 text-white disabled:opacity-50"
                      :disabled="busy" @click="handleForm(al.ladder)">{{ t('league.admin.form') }}</button>
              <button class="text-xs px-3 py-1.5 rounded-lg bg-amber-600 hover:bg-amber-700 text-white disabled:opacity-50"
                      :disabled="busy" @click="handleRunWeekly(al.ladder)">{{ t('league.admin.runWeekly') }}</button>
              <button v-if="al.activeWeek" class="text-xs px-3 py-1.5 rounded-lg bg-rose-600 hover:bg-rose-700 text-white disabled:opacity-50"
                      :disabled="busy" @click="handleAbort(al.ladder)">{{ t('league.admin.abort') }}</button>
            </div>
          </div>

          <!-- draft 週の課題曲編集 -->
          <div v-if="al.draftWeek" class="mt-3">
            <div class="text-xs font-semibold text-slate-500 dark:text-slate-400">
              {{ t('league.admin.draftWeek') }}: {{ weekLabel(al.draftWeek.weekNo) }}
              {{ shortDateTime(al.draftWeek.startsAt) }}〜{{ shortDateTime(al.draftWeek.endsAt) }}
              <span v-if="al.draftWeek.memberCount" class="ml-1 text-teal-600 dark:text-teal-400">
                / {{ t('league.admin.formedCount', { n: al.draftWeek.memberCount }) }}
              </span>
              <span v-else class="ml-1 text-slate-400">/ {{ t('league.admin.notFormed') }}</span>
            </div>
            <!-- 抽選の選曲基準を満たせず補填で埋まった枠がある場合だけ、色分けの意味を説明する。 -->
            <p v-if="weekHasFallback(al.draftWeek)"
               class="mt-1 max-w-2xl text-[11px] leading-relaxed text-violet-600 dark:text-violet-400">
              {{ t('league.admin.songFallbackHint') }}
            </p>
            <div v-for="tierInfo in al.draftWeek.tiers" :key="tierInfo.tier" class="mt-3 rounded-lg border border-slate-200 dark:border-slate-700 p-3">
              <div class="flex flex-wrap items-center justify-between gap-2">
                <div class="text-sm font-bold text-slate-700 dark:text-slate-200">
                  {{ divisionName(tierInfo.tier) }}
                  <span class="text-xs font-normal text-slate-400">({{ tierMemberCount(tierInfo) }})</span>
                </div>
                <div class="flex flex-wrap gap-2">
                  <button class="text-xs px-2 py-1 rounded border border-slate-300 dark:border-slate-600 text-slate-500 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-700"
                          @click="toggleSongEdit(al.draftWeek!.id, tierInfo.tier, songGroupIndexes(tierInfo))">
                    {{ isSongEditOpen(al.draftWeek!.id, tierInfo.tier) ? t('league.admin.editSongsClose') : t('league.admin.editSongs') }}
                  </button>
                  <button class="text-xs px-2 py-1 rounded border border-slate-300 dark:border-slate-600 text-slate-500 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-700 disabled:opacity-50"
                          :disabled="busy" @click="handleRedraw(al.draftWeek!.id, tierInfo.tier)">{{ t('league.admin.redraw') }}</button>
                </div>
              </div>

              <!-- 課題曲の差し替え（既定は畳んでおく。未編成の draft では常に出す）。
                   選曲プールから選ぶと即時に差し替わり、ライン・保持者・自己ベスト表が更新される。 -->
              <div v-if="isSongEditOpen(al.draftWeek.id, tierInfo.tier) || !tierInfo.groups.length"
                   class="mt-2 rounded-lg bg-slate-50 dark:bg-slate-900/40 p-2 space-y-1.5">
                <!-- 候補数はグループごとに出す（拮抗判定がそのグループの参加者に依存するため）。 -->
                <div v-for="gi in songGroupIndexes(tierInfo)" :key="`pool-${gi}`" class="text-[11px] text-slate-400">
                  <template v-if="songPools[poolKey(al.draftWeek.id, tierInfo.tier, gi)]">
                    <span class="text-slate-500 dark:text-slate-400">{{ t('league.groupN', { n: gi + 1 }) }}:</span>
                    {{ songPools[poolKey(al.draftWeek.id, tierInfo.tier, gi)].filtered
                      ? t('league.admin.candidateCount', { n: songPools[poolKey(al.draftWeek.id, tierInfo.tier, gi)].songs.length })
                      : t('league.admin.poolCount', { n: songPools[poolKey(al.draftWeek.id, tierInfo.tier, gi)].songs.length }) }}
                  </template>
                  <template v-else>{{ t('league.admin.poolLoading') }}</template>
                </div>
                <div v-for="song in orderedSongs(tierInfo.songs)" :key="song.id"
                     class="flex flex-wrap items-center gap-2 text-xs rounded px-1 py-0.5"
                     :class="song.fallback ? 'bg-violet-100 dark:bg-violet-900/30' : ''">
                  <span v-if="song.groupIndex != null"
                        class="text-[10px] px-1.5 py-0.5 rounded bg-slate-200 dark:bg-slate-700 text-slate-500 dark:text-slate-400 whitespace-nowrap">
                    {{ t('league.groupN', { n: song.groupIndex + 1 }) }}
                  </span>
                  <span class="text-slate-400 w-4">{{ song.slot }}.</span>
                  <select
                    class="flex-1 min-w-40 px-2 py-1 rounded border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-900 text-slate-700 dark:text-slate-200 disabled:opacity-50"
                    :class="song.disabled ? 'opacity-60' : ''"
                    :disabled="busy || !poolFor(al.draftWeek.id, tierInfo.tier, song)"
                    :value="String(currentOptionIndex(al.draftWeek.id, tierInfo.tier, song))"
                    @change="handlePickSong(al.draftWeek!.id, tierInfo.tier, song, $event)"
                  >
                    <option v-for="(opt, i) in songOptions(al.draftWeek.id, tierInfo.tier, song)" :key="`${opt.title}|${opt.difficultyName}`" :value="String(i)">
                      {{ opt.title }} [{{ opt.difficultyName }}{{ opt.level ? ` ☆${opt.level}` : '' }}]
                    </option>
                  </select>
                  <span v-if="song.fallback"
                        class="text-[10px] px-1.5 py-0.5 rounded bg-violet-200 dark:bg-violet-900/60 text-violet-800 dark:text-violet-200 font-semibold whitespace-nowrap"
                        :title="t('league.admin.songFallbackHint')">
                    {{ t('league.admin.songFallback') }}
                  </span>
                  <span v-if="song.disabled"
                        class="text-[10px] px-1.5 py-0.5 rounded bg-rose-100 dark:bg-rose-900/40 text-rose-700 dark:text-rose-300 font-semibold whitespace-nowrap">
                    {{ t('league.songDisabled') }}
                  </span>
                  <button class="text-[11px] px-2 py-1 rounded border whitespace-nowrap disabled:opacity-50"
                          :class="song.disabled
                            ? 'border-emerald-300 dark:border-emerald-700 text-emerald-600 dark:text-emerald-400 hover:bg-emerald-50 dark:hover:bg-emerald-900/30'
                            : 'border-rose-300 dark:border-rose-700 text-rose-600 dark:text-rose-400 hover:bg-rose-50 dark:hover:bg-rose-900/30'"
                          :disabled="busy"
                          @click="handleToggleSongDisabled(al.draftWeek!.id, song)">
                    {{ song.disabled ? t('league.admin.enableSong') : t('league.admin.disableSong') }}
                  </button>
                </div>
              </div>

              <!-- グループごとの編成（仮編成プレビューと同じ表: 課題曲 × メンバーの自己ベスト） -->
              <div v-for="g in tierInfo.groups" :key="g.groupIndex" class="mt-3">
                <div class="text-xs font-semibold text-slate-500 dark:text-slate-400 mb-1">
                  {{ t('league.groupN', { n: g.groupIndex + 1 }) }} ({{ g.members.length }})
                </div>
                <div class="overflow-x-auto">
                  <table class="min-w-full text-xs border-collapse">
                    <thead>
                      <tr class="align-bottom">
                        <th class="text-left font-semibold py-1 pr-3 whitespace-nowrap text-slate-500 dark:text-slate-400">
                          {{ t('league.admin.preview.player') }}
                        </th>
                        <th v-for="s in groupSongs(tierInfo, g.groupIndex)" :key="s.id"
                            class="text-left font-semibold py-1 px-2 align-bottom min-w-[9rem]"
                            :class="s.fallback ? 'bg-violet-100 dark:bg-violet-900/30' : ''">
                          <div class="break-words leading-tight"
                               :class="s.disabled ? 'line-through text-slate-400 dark:text-slate-500' : 'text-slate-700 dark:text-slate-200'">{{ s.title }}</div>
                          <div v-if="s.fallback" class="text-[10px] font-semibold text-violet-600 dark:text-violet-400">{{ t('league.admin.songFallbackFull') }}</div>
                          <div v-if="s.disabled" class="text-[10px] font-semibold text-rose-600 dark:text-rose-400">{{ t('league.songDisabled') }}</div>
                          <div class="text-[10px] font-normal text-slate-400">
                            {{ s.difficultyName }} <span v-if="s.level">☆{{ s.level }}</span>
                          </div>
                          <div class="text-[10px] font-semibold text-amber-600 dark:text-amber-400">
                            {{ t('league.admin.preview.lineLabel') }}:
                            <template v-if="s.lineEx != null">{{ s.lineEx }} ({{ fmtRate(s.lineRate) }})</template>
                            <template v-else>{{ t('league.lineNone') }}</template>
                          </div>
                          <div v-if="s.lineHolders && s.lineHolders.length"
                               class="text-[10px] font-normal text-slate-500 dark:text-slate-400 break-words leading-tight">
                            {{ t('league.admin.preview.lineHolder') }}: {{ s.lineHolders.join(' / ') }}
                          </div>
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="mem in g.members" :key="mem.userId"
                          class="border-t border-slate-100 dark:border-slate-700/60">
                        <td class="py-1 pr-3 whitespace-nowrap text-slate-700 dark:text-slate-200">
                          {{ mem.displayName || mem.iidxId || '—' }}
                          <span v-if="roleBadge(mem.role)"
                                class="ml-1 inline-flex items-center gap-0.5 px-1 py-0.5 rounded text-[10px]"
                                :class="roleBadge(mem.role)!.cls">
                            {{ roleBadge(mem.role)!.label }}<span class="font-semibold opacity-80">{{ divisionShort(mem.homeTier) }}</span>
                          </span>
                        </td>
                        <td v-for="s in groupSongs(tierInfo, g.groupIndex)" :key="s.id"
                            class="py-1 px-2 whitespace-nowrap tabular-nums"
                            :class="memberCell(mem, s.slot)?.isLine
                              ? 'bg-amber-100 dark:bg-amber-900/40 font-bold text-amber-800 dark:text-amber-200'
                              : 'text-slate-600 dark:text-slate-300'">
                          <template v-if="memberCell(mem, s.slot)?.played">
                            {{ memberCell(mem, s.slot)!.ex }}
                            <span class="text-[10px]"
                                  :class="memberCell(mem, s.slot)!.isLine ? 'text-amber-700 dark:text-amber-300' : 'text-slate-400'">({{ fmtRate(memberCell(mem, s.slot)!.rate) }})</span>
                            <span v-if="memberCell(mem, s.slot)!.isLine" class="ml-0.5 text-[10px]">◆</span>
                          </template>
                          <span v-else class="text-slate-300 dark:text-slate-600">—</span>
                        </td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          </div>
          <div v-else class="mt-2 text-xs text-slate-400">{{ t('league.admin.draftWeek') }}: {{ t('league.admin.none') }}</div>

          <!-- active 週のサマリ -->
          <div class="mt-2 text-xs text-slate-400">
            {{ t('league.admin.activeWeek') }}:
            <template v-if="al.activeWeek">
              {{ weekLabel(al.activeWeek.weekNo) }}
              {{ shortDateTime(al.activeWeek.startsAt) }}〜{{ shortDateTime(al.activeWeek.endsAt) }}
              ({{ al.activeWeek.memberCount }})
            </template>
            <template v-else>{{ t('league.admin.none') }}</template>
          </div>

          <!-- 開催中の週の課題曲: グループごとに無効化を切り替える。
               開始後はラインが凍結済みで差し替えができないため、解禁不可能な曲はここで集計から外す。 -->
          <div v-if="al.activeWeek && al.activeWeek.tiers.length" class="mt-2">
            <!-- 全 DIVISION × 全課題曲を並べると縦に長いので、既定は畳んでおく。 -->
            <button class="text-xs px-2 py-1 rounded border border-slate-300 dark:border-slate-600 text-slate-500 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-700"
                    @click="toggleDisablePanel(al.activeWeek.id)">
              {{ isDisablePanelOpen(al.activeWeek.id)
                ? t('league.admin.closeDisablePanel')
                : t('league.admin.openDisablePanel') }}
              <span v-if="disabledSongCount(al.activeWeek)" class="ml-1 text-rose-600 dark:text-rose-400 font-semibold">
                ({{ disabledSongCount(al.activeWeek) }})
              </span>
            </button>
            <template v-if="isDisablePanelOpen(al.activeWeek.id)">
            <p class="mt-2 text-[11px] text-slate-400 max-w-2xl leading-relaxed">{{ t('league.admin.disableHint') }}</p>
            <p v-if="weekHasFallback(al.activeWeek)"
               class="mt-1 max-w-2xl text-[11px] leading-relaxed text-violet-600 dark:text-violet-400">
              {{ t('league.admin.songFallbackHint') }}
            </p>
            <div v-for="tierInfo in al.activeWeek.tiers" :key="tierInfo.tier"
                 class="mt-2 rounded-lg border border-slate-200 dark:border-slate-700 p-2">
              <div class="text-xs font-bold text-slate-700 dark:text-slate-200">{{ divisionName(tierInfo.tier) }}</div>
              <div v-if="!tierInfo.songs.length" class="mt-1 text-[11px] text-slate-400">{{ t('league.admin.none') }}</div>
              <div v-for="song in orderedSongs(tierInfo.songs)" :key="song.id"
                   class="mt-1 flex flex-wrap items-center gap-2 text-xs rounded px-1 py-0.5"
                   :class="song.fallback ? 'bg-violet-100 dark:bg-violet-900/30' : ''">
                <span v-if="song.groupIndex != null"
                      class="text-[10px] px-1.5 py-0.5 rounded bg-slate-200 dark:bg-slate-700 text-slate-500 dark:text-slate-400 whitespace-nowrap">
                  {{ t('league.groupN', { n: song.groupIndex + 1 }) }}
                </span>
                <span class="text-slate-400 w-4">{{ song.slot }}.</span>
                <span class="flex-1 min-w-40 break-words"
                      :class="song.disabled ? 'line-through text-slate-400 dark:text-slate-500' : 'text-slate-700 dark:text-slate-200'">
                  {{ song.title }}
                  <span class="text-slate-400">[{{ song.difficultyName }}{{ song.level ? ` ☆${song.level}` : '' }}]</span>
                </span>
                <span v-if="song.fallback"
                      class="text-[10px] px-1.5 py-0.5 rounded bg-violet-200 dark:bg-violet-900/60 text-violet-800 dark:text-violet-200 font-semibold whitespace-nowrap"
                      :title="t('league.admin.songFallbackHint')">
                  {{ t('league.admin.songFallback') }}
                </span>
                <span v-if="song.disabled"
                      class="text-[10px] px-1.5 py-0.5 rounded bg-rose-100 dark:bg-rose-900/40 text-rose-700 dark:text-rose-300 font-semibold whitespace-nowrap">
                  {{ t('league.songDisabled') }}
                </span>
                <button class="text-[11px] px-2 py-1 rounded border whitespace-nowrap disabled:opacity-50"
                        :class="song.disabled
                          ? 'border-emerald-300 dark:border-emerald-700 text-emerald-600 dark:text-emerald-400 hover:bg-emerald-50 dark:hover:bg-emerald-900/30'
                          : 'border-rose-300 dark:border-rose-700 text-rose-600 dark:text-rose-400 hover:bg-rose-50 dark:hover:bg-rose-900/30'"
                        :disabled="busy"
                        @click="handleToggleSongDisabled(al.activeWeek!.id, song)">
                  {{ song.disabled ? t('league.admin.enableSong') : t('league.admin.disableSong') }}
                </button>
              </div>
            </div>
            </template>
          </div>
        </div>

        <!-- 仮編成プレビュー（DB 非更新のテスト用） -->
        <div class="mt-5 border-t border-slate-200 dark:border-slate-700 pt-3">
          <div class="flex flex-wrap items-start justify-between gap-2">
            <div>
              <div class="font-semibold text-slate-700 dark:text-slate-200">{{ t('league.admin.preview.title') }}</div>
              <p class="text-xs text-slate-400 mt-0.5 max-w-lg">{{ t('league.admin.preview.desc') }}</p>
            </div>
            <div class="flex flex-wrap gap-2">
              <button class="text-xs px-3 py-1.5 rounded-lg bg-teal-600 hover:bg-teal-700 text-white disabled:opacity-50 whitespace-nowrap"
                      :disabled="busy" @click="handlePreview(ladder)">{{ t('league.admin.preview.generate') }}</button>
              <button v-if="preview && preview.tiers.length"
                      class="text-xs px-3 py-1.5 rounded-lg bg-rose-600 hover:bg-rose-700 text-white disabled:opacity-50 whitespace-nowrap"
                      :disabled="busy" @click="handleApplyPreview(ladder)">{{ t('league.admin.preview.apply') }}</button>
            </div>
          </div>

          <div v-if="preview" class="mt-3">
            <div class="text-xs text-slate-500 dark:text-slate-400">
              {{ t('league.admin.preview.entryCount', { n: preview.entryCount }) }}
              <span class="ml-2 text-slate-400">{{ t('league.admin.preview.lineHint') }}</span>
            </div>
            <p class="mt-1 text-[11px] text-slate-400">{{ t('league.admin.preview.applyHint') }}</p>
            <p v-if="previewHasFallback" class="mt-1 max-w-2xl text-[11px] leading-relaxed text-violet-600 dark:text-violet-400">
              {{ t('league.admin.songFallbackHint') }}
            </p>
            <div v-if="!preview.tiers.length" class="mt-2 text-xs text-slate-400">{{ t('league.admin.preview.empty') }}</div>

            <!-- 卓（host DIVISION）ごと -->
            <div v-for="tp in preview.tiers" :key="tp.host"
                 class="mt-3 rounded-lg border border-slate-200 dark:border-slate-700 p-3">
              <div class="text-sm font-bold text-slate-700 dark:text-slate-200">
                {{ divisionName(tp.host) }}
                <span class="text-xs font-normal text-slate-400">({{ tp.memberCount }})</span>
              </div>

              <!-- グループごと -->
              <div v-for="g in tp.groups" :key="g.groupIndex" class="mt-3">
                <div class="text-xs font-semibold text-slate-500 dark:text-slate-400 mb-1">
                  {{ t('league.groupN', { n: g.groupIndex + 1 }) }} ({{ g.memberCount }})
                </div>
                <div class="overflow-x-auto">
                  <table class="min-w-full text-xs border-collapse">
                    <thead>
                      <tr class="align-bottom">
                        <th class="text-left font-semibold py-1 pr-3 whitespace-nowrap text-slate-500 dark:text-slate-400">
                          {{ t('league.admin.preview.player') }}
                        </th>
                        <th v-for="s in g.songs" :key="s.slot"
                            class="text-left font-semibold py-1 px-2 align-bottom min-w-[9rem]"
                            :class="s.fallback ? 'bg-violet-100 dark:bg-violet-900/30' : ''">
                          <div class="text-slate-700 dark:text-slate-200 break-words leading-tight">{{ s.title }}</div>
                          <div v-if="s.fallback" class="text-[10px] font-semibold text-violet-600 dark:text-violet-400">{{ t('league.admin.songFallbackFull') }}</div>
                          <div class="text-[10px] font-normal text-slate-400">
                            {{ s.difficultyName }} <span v-if="s.level">☆{{ s.level }}</span>
                          </div>
                          <div class="text-[10px] font-semibold text-amber-600 dark:text-amber-400">
                            {{ t('league.admin.preview.lineLabel') }}:
                            <template v-if="s.lineEx != null">{{ s.lineEx }} ({{ fmtRate(s.lineRate) }})</template>
                            <template v-else>{{ t('league.lineNone') }}</template>
                          </div>
                          <div v-if="s.lineHolders && s.lineHolders.length"
                               class="text-[10px] font-normal text-slate-500 dark:text-slate-400 break-words leading-tight">
                            {{ t('league.admin.preview.lineHolder') }}: {{ s.lineHolders.join(' / ') }}
                          </div>
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="(p, pi) in g.players" :key="pi"
                          class="border-t border-slate-100 dark:border-slate-700/60">
                        <td class="py-1 pr-3 whitespace-nowrap text-slate-700 dark:text-slate-200">
                          {{ p.displayName || '—' }}
                          <span v-if="roleBadge(p.role)"
                                class="ml-1 inline-flex items-center gap-0.5 px-1 py-0.5 rounded text-[10px]"
                                :class="roleBadge(p.role)!.cls">
                            {{ roleBadge(p.role)!.label }}<span class="font-semibold opacity-80">{{ divisionShort(p.homeTier) }}</span>
                          </span>
                        </td>
                        <td v-for="cell in p.bests" :key="cell.slot"
                            class="py-1 px-2 whitespace-nowrap tabular-nums"
                            :class="cell.isLine
                              ? 'bg-amber-100 dark:bg-amber-900/40 font-bold text-amber-800 dark:text-amber-200'
                              : 'text-slate-600 dark:text-slate-300'">
                          <template v-if="cell.played">
                            {{ cell.ex }}
                            <span class="text-[10px]" :class="cell.isLine ? 'text-amber-700 dark:text-amber-300' : 'text-slate-400'">({{ fmtRate(cell.rate) }})</span>
                            <span v-if="cell.isLine" class="ml-0.5 text-[10px]">◆</span>
                          </template>
                          <span v-else class="text-slate-300 dark:text-slate-600">—</span>
                        </td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>

    <!-- ルール説明モーダル -->
    <LeagueInfoModal v-if="showInfo" @close="showInfo = false" />

    <!-- DIVISION 別ランキングモーダル -->
    <LeagueRankingModal v-if="showRanking" :ladder="ladder" :my-user-id="user?.id" @close="showRanking = false" />
  </div>
</template>

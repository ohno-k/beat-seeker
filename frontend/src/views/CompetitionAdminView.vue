<script setup lang="ts">
/**
 * 【View の役割】 大会主催 (Competition セクションのホワイトリスト 4 ID) 向け管理画面。
 *
 * 機能:
 *  - 大会一覧 (新しい順) + 「新規作成」ボタン
 *  - 大会詳細: 5 チーム × 4 名の編成 / TL 兼任設定 / 招待 URL コピー / open 遷移
 *
 * draft 状態の間のみチーム名・参加者の編集が可能。
 * open に遷移すると 10 matchup × 3 戦 = 30 試合がサーバ側で自動生成される。
 * 試合へのプレイヤーアサインは別画面 (TL 専用) で行う想定 (フェーズ 2 では未実装)。
 *
 * 4 ID 判定はサーバ側で行うため、本 View はサイドバーガードと併せた二重防御の片側として、
 * 表示上の権限警告のみクライアントで出す (実際のリクエストブロックはサーバ側)。
 */
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue';
import { useAuth } from '../composables/useAuth';
import {
  useCompetitionAdmin,
  type CompetitionParticipantDto,
  type CompetitionTeamDto,
  type CompetitionMatchDto,
  type CompetitionMatchupDto,
  type CompetitionSongGenre,
  type CompetitionRevealData,
  type CompetitionStandingsDto,
  type CompetitionIndividualMatchDto,
  type CompetitionIndividualStandingsDto,
  type CompetitionFormat,
  type IndividualResultPayload,
  type MatchResultPayload,
  type ChatThreadDto,
} from '../composables/useCompetitionAdmin';
import { useToast } from '../composables/useToast';
import { teamColorClass, genreSelectClass } from '../composables/competitionColors';
import SongPickerModal from '../components/SongPickerModal.vue';

const { user } = useAuth();
const toast = useToast();
const {
  competitions,
  currentCompetition,
  isLoading,
  fetchCompetitions,
  createCompetition,
  fetchCompetition,
  renameTeam,
  addParticipant,
  updateParticipant,
  deleteParticipant,
  openCompetition,
  setMatchGenre,
  publishLineup,
  configureMatchup,
  publishPick,
  setDeadline,
  deleteCompetition,
  regenerateParticipantToken,
  regenerateTlToken,
  setMatchResult,
  clearMatchResult,
  fetchStandings,
  generateFinals,
  fetchRevealData,
  addIndividualParticipant,
  updateIndividualParticipant,
  deleteIndividualParticipant,
  regenerateIndividualParticipantToken,
  openIndividualCompetition,
  setIndividualMatchResult,
  clearIndividualMatchResult,
  fetchIndividualStandings,
  generateIndividualFinals,
  openIndividualWithNumbers,
  assignIndividualLottery,
  regenerateObsToken,
  regenerateSpectatorToken,
  fetchChatThreads,
  sendChatReply,
  markChatRead,
} = useCompetitionAdmin();

/** 試合に指定可能なジャンル (Strategy Card プールと同じ 7 種)。 */
const ALL_GENRES: CompetitionSongGenre[] = ['NOTES', 'PEAK', 'CHORD', 'CHARGE', 'SCRATCH', 'SOF-LAN', 'INSANE'];
const KIND_LABEL: Record<'vanguard' | 'middle' | 'captain', string> = {
  vanguard: '先鋒戦',
  middle: '中堅戦',
  captain: '大将戦',
};

/** Competition セクション (Strategy Card / Song Reveal / 自選曲送信) と同じ 4 ID。 */
const ORGANIZER_IDS = [18, 19, 23, 210];
const isOrganizer = computed(() => !!user.value && ORGANIZER_IDS.includes(user.value.id));

// ── 一覧/詳細モード切替 ───────────────────────────────────
/**
 * 詳細モードに入っているかは currentCompetition の有無で判定。
 * 「← 一覧へ」ボタンで null に戻す。
 */
const backToList = () => {
  currentCompetition.value = null;
};

// ── 新規作成フォーム ──────────────────────────────────────
const createName = ref('');
/** 作成時に選択する大会フォーマット。デフォルト team5 で既存挙動を維持。 */
const createFormat = ref<CompetitionFormat>('team5');
const isCreating = ref(false);
const handleCreate = async () => {
  if (!createName.value.trim()) {
    toast.error('大会名を入力してください');
    return;
  }
  isCreating.value = true;
  try {
    await createCompetition(createName.value.trim(), createFormat.value);
    createName.value = '';
    toast.success('大会を作成しました');
    await fetchCompetitions();
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    isCreating.value = false;
  }
};

// ── 大会選択 + Reveal データ ──────────────────────────
/**
 * 「両側の自選曲が提出済」か判定するため、詳細ロード時に reveal データも取得する。
 * 大会一覧 → 大会選択時、と「再読込」相当の場面で更新される。
 */
const revealData = ref<CompetitionRevealData | null>(null);
/** reveal データの取得状態。サイレント失敗を可視化するために導入。 */
const revealLoadState = ref<'idle' | 'loading' | 'ready' | 'error'>('idle');
const revealLoadError = ref<string>('');

const refreshRevealData = async (compId: number) => {
  revealLoadState.value = 'loading';
  revealLoadError.value = '';
  try {
    revealData.value = await fetchRevealData(compId);
    revealLoadState.value = 'ready';
  } catch (e) {
    const msg = (e as Error).message;
    console.error('[CompetitionAdmin] reveal データ取得失敗:', e);
    revealData.value = null;
    revealLoadState.value = 'error';
    revealLoadError.value = msg;
    toast.error('提出状況の読込に失敗しました: ' + msg);
  }
};

const handleOpenCompetition = async (id: number) => {
  try {
    await fetchCompetition(id);
    if (currentCompetition.value?.format === 'individual4') {
      // individual4: Strategy/Reveal は使わないので standings のみ
      await refreshIndividualStandings();
    } else {
      await refreshRevealData(id);
      await refreshStandings();
      // 運営チャットスレッドも初回ロード (team5 のみ)
      chatThreads.value = [];
      selectedChatTeamId.value = null;
      await loadChatThreads();
    }
  } catch (e) {
    toast.error((e as Error).message);
  }
};

// ── チーム名編集 ──────────────────────────────────────────
/** リネーム編集中のチーム ID と入力値。1 つのチームだけ編集可能。 */
const editingTeamId = ref<number | null>(null);
const editingTeamName = ref('');

const beginRenameTeam = (team: CompetitionTeamDto) => {
  editingTeamId.value = team.id;
  editingTeamName.value = team.teamName;
};
const cancelRenameTeam = () => {
  editingTeamId.value = null;
  editingTeamName.value = '';
};
const commitRenameTeam = async (team: CompetitionTeamDto) => {
  if (!currentCompetition.value) return;
  if (!editingTeamName.value.trim() || editingTeamName.value.trim() === team.teamName) {
    cancelRenameTeam();
    return;
  }
  try {
    await renameTeam(currentCompetition.value.id, team.id, editingTeamName.value.trim());
    toast.success('チーム名を変更しました');
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    cancelRenameTeam();
  }
};

// ── 参加者追加 ────────────────────────────────────────────
/** どのチームに対して追加フォームを開いているか。同時に開けるのは 1 つ。 */
const addingForTeamId = ref<number | null>(null);
const addingDisplayName = ref('');
const addingIsTl = ref(false);

const beginAddParticipant = (teamId: number) => {
  addingForTeamId.value = teamId;
  addingDisplayName.value = '';
  addingIsTl.value = false;
};
const cancelAddParticipant = () => {
  addingForTeamId.value = null;
  addingDisplayName.value = '';
  addingIsTl.value = false;
};
const commitAddParticipant = async () => {
  if (!currentCompetition.value || addingForTeamId.value === null) return;
  if (!addingDisplayName.value.trim()) {
    toast.error('表示名を入力してください');
    return;
  }
  try {
    await addParticipant(currentCompetition.value.id, addingForTeamId.value, {
      displayName: addingDisplayName.value.trim(),
      isTl: addingIsTl.value,
    });
    toast.success('参加者を追加しました');
    cancelAddParticipant();
  } catch (e) {
    toast.error((e as Error).message);
  }
};

// ── 参加者編集 (DJ NAME 変更 / TL 昇格 / 削除) ────────────
const handleToggleTl = async (p: CompetitionParticipantDto) => {
  if (!currentCompetition.value) return;
  try {
    await updateParticipant(currentCompetition.value.id, p.id, { isTl: !p.isTl });
    toast.success(p.isTl ? 'TL を解除しました' : 'TL に設定しました');
  } catch (e) {
    toast.error((e as Error).message);
  }
};

/** team5 参加者の表示名 (DJ NAME) を inline 編集。1 人だけ編集状態にできる。 */
const editingMemberId = ref<number | null>(null);
const editingMemberName = ref('');
const beginEditMember = (p: CompetitionParticipantDto) => {
  editingMemberId.value = p.id;
  editingMemberName.value = p.displayName;
};
const cancelEditMember = () => {
  editingMemberId.value = null;
  editingMemberName.value = '';
};
const commitEditMember = async (p: CompetitionParticipantDto) => {
  if (!currentCompetition.value) return;
  const name = editingMemberName.value.trim();
  if (!name || name === p.displayName) {
    cancelEditMember();
    return;
  }
  try {
    await updateParticipant(currentCompetition.value.id, p.id, { displayName: name });
    toast.success('DJ NAME を変更しました');
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    cancelEditMember();
  }
};

const handleDeleteParticipant = async (p: CompetitionParticipantDto) => {
  if (!currentCompetition.value) return;
  if (!confirm(`「${p.displayName}」を削除しますか?`)) return;
  try {
    await deleteParticipant(currentCompetition.value.id, p.id);
    toast.success('参加者を削除しました');
  } catch (e) {
    toast.error((e as Error).message);
  }
};

// ── トークン再発行 (誤公開時のリカバリ) ──────────────
const handleRegenerateParticipantToken = async (p: CompetitionParticipantDto) => {
  if (!currentCompetition.value) return;
  if (!confirm(`「${p.displayName}」の招待 URL を再発行します。旧 URL は即無効になります。続けますか?`)) return;
  try {
    await regenerateParticipantToken(currentCompetition.value.id, p.id);
    toast.success('招待 URL を再発行しました');
  } catch (e) {
    toast.error((e as Error).message);
  }
};

const handleRegenerateTlToken = async (team: CompetitionTeamDto) => {
  if (!currentCompetition.value) return;
  if (!confirm(`「${team.teamName}」の TL URL を再発行します。旧 URL は即無効になります。続けますか?`)) return;
  try {
    await regenerateTlToken(currentCompetition.value.id, team.id);
    toast.success('TL URL を再発行しました');
  } catch (e) {
    toast.error((e as Error).message);
  }
};

// ── 招待 URL コピー ───────────────────────────────────────
/** Origin (例: https://beat-seeker.com) + token から完全 URL を組み立てる。 */
const buildPlayerUrl = (token: string) => `${window.location.origin}/competition/player/${token}`;
const buildTlUrl = (token: string) => `${window.location.origin}/competition/tl/${token}`;

const copyToClipboard = async (text: string, label: string) => {
  try {
    await navigator.clipboard.writeText(text);
    toast.success(`${label} をコピーしました`);
  } catch {
    toast.error('コピーに失敗しました');
  }
};

// ── open 遷移 ─────────────────────────────────────────────
const handleOpenStatus = async () => {
  if (!currentCompetition.value) return;
  if (!confirm('この大会を open に遷移しますか?\n10 組 × 3 戦 = 30 試合が「未設定」状態で生成されます。\nopen 後、対戦表で実施する対戦を 1 つずつ選んで設定してください。')) return;
  try {
    await openCompetition(currentCompetition.value.id);
    toast.success('open に遷移しました');
  } catch (e) {
    toast.error((e as Error).message);
  }
};

// ── 削除 ─────────────────────────────────────────────────
/**
 * 大会を関連データごと完全削除する。
 * 2 段階確認 (1回目: 警告ダイアログ、2回目: 大会名を入力させる) で誤操作を防ぐ。
 */
const handleDeleteCompetition = async (id: number, name: string) => {
  if (!confirm(`大会「${name}」を削除します。\nチーム / 参加者 / 試合 / 自選曲 / StrategyCard 使用記録まで全て消えます。続けますか?`)) return;
  const typed = prompt(`確認のため、もう一度大会名「${name}」を入力してください:`);
  if (typed !== name) {
    toast.error('入力が一致しなかったため中止しました');
    return;
  }
  try {
    await deleteCompetition(id);
    toast.success('大会を削除しました');
    await fetchCompetitions();
  } catch (e) {
    toast.error((e as Error).message);
  }
};

// ── 補助: チーム別に参加者を絞る ─────────────────────────
/** チームに所属する参加者だけを返す (作成順、最大 4 名)。 */
const membersOf = (teamId: number): CompetitionParticipantDto[] => {
  if (!currentCompetition.value) return [];
  return currentCompetition.value.participants.filter(p => p.teamId === teamId);
};

/** チームの残り空きスロット数 (0〜4)。0 になったら追加 UI を隠す。 */
const remainingSlotsOf = (teamId: number): number => Math.max(0, 4 - membersOf(teamId).length);

// ── ライフサイクル ────────────────────────────────────────
onMounted(() => {
  if (isOrganizer.value) {
    fetchCompetitions().catch(e => toast.error((e as Error).message));
  }
});

// ── 対戦表 (matchup ごとの試合と運営ジャンル指定) ────────
/** チーム ID → チーム名。matchup の表示用。 */
const teamNameOf = (teamId: number | null): string => {
  if (!currentCompetition.value || teamId === null) return '?';
  return currentCompetition.value.teams.find(t => t.id === teamId)?.teamName ?? '?';
};

/**
 * チーム ID から色クラスを引く (matchup は teamId しか持たないため)。
 * 配色ルールの本体は competitionColors の {@link teamColorClass} に集約。
 */
const teamColorClassById = (teamId: number | null): string => teamColorClass(teamNameOf(teamId));

/** 参加者 ID → 表示名。matchup でアサイン済みプレイヤー表示に使う。 */
const participantNameOf = (participantId: number | null): string => {
  if (!currentCompetition.value || participantId === null) return '未割当';
  return currentCompetition.value.participants.find(p => p.id === participantId)?.displayName ?? '?';
};

/** matchup ID に紐づく 3 試合 (vanguard → middle → captain) を返す。 */
const matchesForMatchup = (matchupId: number): CompetitionMatchDto[] => {
  if (!currentCompetition.value?.matches) return [];
  const KIND_ORDER: Record<string, number> = { vanguard: 0, middle: 1, captain: 2 };
  return currentCompetition.value.matches
    .filter(m => m.matchupId === matchupId)
    .sort((a, b) => KIND_ORDER[a.matchKind] - KIND_ORDER[b.matchKind]);
};

/**
 * ジャンルセレクタの change ハンドラ。
 * 空文字 ('') を選んだら null を送ってサーバ側で指定解除する。
 */
const handleGenreChange = async (match: CompetitionMatchDto, raw: string) => {
  if (!currentCompetition.value) return;
  const genre = raw === '' ? null : (raw as CompetitionSongGenre);
  // INSANE × 非 captain の組み合わせはサーバが拒否するが、UI 側でも先回りで止める
  if (genre === 'INSANE' && match.matchKind !== 'captain') {
    toast.error('INSANE は大将戦のみ指定できます');
    return;
  }
  try {
    await setMatchGenre(currentCompetition.value.id, match.id, genre);
    toast.success(genre === null ? 'ジャンル指定を解除しました' : `${genre} に指定しました`);
  } catch (e) {
    toast.error((e as Error).message);
  }
};

/** matchKind 制約に応じてセレクタに出すジャンル候補。INSANE は captain のみ。 */
const genresForKind = (matchKind: 'vanguard' | 'middle' | 'captain'): CompetitionSongGenre[] => {
  return ALL_GENRES.filter(g => !(g === 'INSANE' && matchKind !== 'captain'));
};

// ── 公開トグル ────────────────────────────────────────────
const handlePublishLineup = async (matchupId: number, side: 'a' | 'b' | 'both', published: boolean) => {
  if (!currentCompetition.value) return;
  try {
    await publishLineup(currentCompetition.value.id, matchupId, side, published);
    toast.success(published ? 'ラインアップを公開しました' : 'ラインアップ公開を解除しました');
  } catch (e) {
    toast.error((e as Error).message);
  }
};

const handlePublishPick = async (matchId: number, side: 'a' | 'b' | 'both', published: boolean) => {
  if (!currentCompetition.value) return;
  try {
    await publishPick(currentCompetition.value.id, matchId, side, published);
    toast.success(published ? '自選曲を公開しました' : '自選曲公開を解除しました');
  } catch (e) {
    toast.error((e as Error).message);
  }
};

// ── 起用クローズ日時 (JST) ── 手動ロックの代替 ──────────
/**
 * datetime-local 入力の現在値。currentCompetition.deadlineAt から初期化し、
 * 大会切替時に同期する。形式は "YYYY-MM-DDTHH:mm" (秒なし)。
 */
const deadlineInput = ref<string>('');

/** サーバの ISO 日時文字列 (例 "2026-06-20T21:00:00") を datetime-local 値 "2026-06-20T21:00" に整形。 */
const toDatetimeLocal = (iso: string | null): string => (iso ? iso.slice(0, 16) : '');

watch(
  () => currentCompetition.value?.deadlineAt,
  (iso) => { deadlineInput.value = toDatetimeLocal(iso ?? null); },
  { immediate: true },
);

const isSavingDeadline = ref(false);
/** 入力中の日時を保存 (空なら締切解除)。 */
const handleSaveDeadline = async () => {
  if (!currentCompetition.value) return;
  isSavingDeadline.value = true;
  try {
    await setDeadline(currentCompetition.value.id, deadlineInput.value || null);
    toast.success(deadlineInput.value ? '起用クローズ日時を設定しました' : 'クローズ日時を解除しました');
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    isSavingDeadline.value = false;
  }
};
/** 締切を解除 (入力クリア + 保存)。 */
const handleClearDeadline = async () => {
  deadlineInput.value = '';
  await handleSaveDeadline();
};

// ── 運営チャット (TL ⇄ 運営) ────────────────────────────
const chatThreads = ref<ChatThreadDto[]>([]);
const selectedChatTeamId = ref<number | null>(null);
const chatReplyDraft = ref('');
const isSendingChatReply = ref(false);
const chatListEl = ref<HTMLElement | null>(null);
let chatPollTimer: ReturnType<typeof setInterval> | null = null;

/** 現在選択中のチームスレッド。 */
const selectedThread = computed<ChatThreadDto | null>(() =>
  chatThreads.value.find(t => t.teamId === selectedChatTeamId.value) ?? null);

/** 全チーム合計の未読数 (セクション見出しのバッジ用)。 */
const totalChatUnread = computed<number>(() =>
  chatThreads.value.reduce((sum, t) => sum + t.unreadCount, 0));

const scrollChatToBottom = async () => {
  await nextTick();
  if (chatListEl.value) chatListEl.value.scrollTop = chatListEl.value.scrollHeight;
};

/** チャットスレッド取得。ポーリングでも呼ぶためエラーはサイレント。 */
const loadChatThreads = async () => {
  if (!currentCompetition.value || currentCompetition.value.format === 'individual4') return;
  try {
    chatThreads.value = await fetchChatThreads(currentCompetition.value.id);
    if (selectedChatTeamId.value !== null) scrollChatToBottom();
  } catch {
    /* ポーリング失敗は無視 */
  }
};

/** チームのスレッドを開く (既読化 + 末尾へスクロール)。 */
const handleSelectChatTeam = async (teamId: number) => {
  selectedChatTeamId.value = teamId;
  await scrollChatToBottom();
  if (!currentCompetition.value) return;
  const thread = chatThreads.value.find(t => t.teamId === teamId);
  if (thread && thread.unreadCount > 0) {
    try {
      await markChatRead(currentCompetition.value.id, teamId);
      thread.unreadCount = 0;
    } catch { /* 既読化失敗は無視 */ }
  }
};

/** 運営返信を送信。 */
const handleSendChatReply = async () => {
  const body = chatReplyDraft.value.trim();
  if (!body || isSendingChatReply.value || !currentCompetition.value || selectedChatTeamId.value === null) return;
  isSendingChatReply.value = true;
  try {
    const msg = await sendChatReply(currentCompetition.value.id, selectedChatTeamId.value, body);
    const thread = chatThreads.value.find(t => t.teamId === selectedChatTeamId.value);
    if (thread) thread.messages.push(msg);
    chatReplyDraft.value = '';
    scrollChatToBottom();
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    isSendingChatReply.value = false;
  }
};

const onChatReplyKeydown = (e: KeyboardEvent) => {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    handleSendChatReply();
  }
};

const formatChatTime = (iso: string): string => {
  try {
    return new Date(iso).toLocaleString('ja-JP', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' });
  } catch { return ''; }
};

onMounted(() => {
  chatPollTimer = setInterval(loadChatThreads, 20000);
});
onBeforeUnmount(() => {
  if (chatPollTimer) clearInterval(chatPollTimer);
});

// ── REVEAL 再生 (Song Reveal 連携) ────────────────────
/**
 * 試合 1 件分の reveal メタを返す (両側自選曲の提出有無を判定するため)。
 * revealData がまだロードされていない場合は null。
 */
const revealMatchOf = (matchId: number) => {
  if (!revealData.value) return null;
  return revealData.value.matches.find(rm => rm.matchId === matchId) ?? null;
};

/**
 * 指定試合・サイドの自選曲を返す。管理画面では公開状態 (選曲公開) に関係なく常に開示する。
 * reveal データは主催認証 (requireOrganizer) 経由で取得しており、未公開の picks も含むため表示してよい。
 */
const pickForSide = (matchId: number, side: 'a' | 'b') => {
  const rm = revealMatchOf(matchId);
  if (!rm) return null;
  return side === 'a' ? rm.playerAPick : rm.playerBPick;
};
/** 自選曲の表示用ラベル。未取得 / 未提出 / 曲名+メタ を区別する。 */
const pickLabel = (matchId: number, side: 'a' | 'b'): string => {
  if (!revealData.value) return '(提出状況未取得)';
  const p = pickForSide(matchId, side);
  if (!p) return '未提出';
  return `${p.songTitle} [${p.songGenre}☆${p.songLevel}${p.songDiff}]`;
};

/** REVEAL を再生できるか: 両プレイヤーがアサイン済み + 両側に自選曲提出済み。 */
const canReveal = (matchId: number): boolean => {
  const rm = revealMatchOf(matchId);
  if (!rm) return false;
  return !!rm.playerAName && !!rm.playerBName && !!rm.playerAPick && !!rm.playerBPick;
};

/**
 * 新規タブで Song Reveal を開く。
 * URL パラメータ {@code competitionId} / {@code matchId} を渡すと、SongRevealView は
 * 該当試合を自動で取り込み、REVEAL フェーズに遷移する。
 */
const handleOpenReveal = (matchId: number) => {
  if (!currentCompetition.value) return;
  const url = `/song-reveal?competitionId=${currentCompetition.value.id}&matchId=${matchId}`;
  window.open(url, '_blank');
};

/** 手動で reveal データを再取得 (プレイヤー提出状況を最新化したいとき)。 */
const handleRefreshRevealData = async () => {
  if (!currentCompetition.value) return;
  await refreshRevealData(currentCompetition.value.id);
  toast.success('提出状況を再読込しました');
};

// ── 試合結果記録 (R-4: スコアベース) ───────────────────
/**
 * 試合の結果入力。matchId 単位で開閉し、両曲ぶんの管理番号 / スコア を受け付ける。
 * 両スコアが揃った曲だけ判定対象。サーバ側で aSongsWon / bSongsWon を自動算出する。
 */
const resultEditingMatchId = ref<number | null>(null);
const resultDraft = ref<MatchResultPayload>({
  song1StrategyId: null, song1Title: null,
  song1ScoreA: null, song1ScoreB: null,
  song2StrategyId: null, song2Title: null,
  song2ScoreA: null, song2ScoreB: null,
});

/**
 * 結果記録モーダルを開く。既に記録済の値があればそれを優先、なければ以下のロジックで自動入力する:
 *
 * 1 戦 = 2 曲制を「song1 = A 側が演奏する曲 / song2 = B 側が演奏する曲」と定義し、
 *  - 通常 (strategy 未発動): song1 = A の自選曲、song2 = B の自選曲
 *  - B 側が strategy 申告 → A 側の曲がランダム化: song1 = revealMatch.playerBStrategyResult
 *  - A 側が strategy 申告 → B 側の曲がランダム化: song2 = revealMatch.playerAStrategyResult
 *
 * これによりスコア欄を開いた瞬間に管理番号 / 曲名がプリセットされる。
 */
const beginResultEdit = (match: CompetitionMatchDto) => {
  resultEditingMatchId.value = match.id;
  const rm = revealMatchOf(match.id);

  // song1 = A 側が演奏する曲
  // B が strategy 申告 → playerBStrategyResult が A 側の演奏曲
  // 申告無し → A の自選曲
  let defaultSong1Id: number | null = null;
  let defaultSong1Title: string | null = null;
  if (rm?.playerBStrategyResult) {
    defaultSong1Id = rm.playerBStrategyResult.songStrategyId;
    defaultSong1Title = rm.playerBStrategyResult.songTitle;
  } else if (rm?.playerAPick) {
    defaultSong1Id = rm.playerAPick.songStrategyId;
    defaultSong1Title = rm.playerAPick.songTitle;
  }

  // song2 = B 側が演奏する曲
  // A が strategy 申告 → playerAStrategyResult が B 側の演奏曲
  let defaultSong2Id: number | null = null;
  let defaultSong2Title: string | null = null;
  if (rm?.playerAStrategyResult) {
    defaultSong2Id = rm.playerAStrategyResult.songStrategyId;
    defaultSong2Title = rm.playerAStrategyResult.songTitle;
  } else if (rm?.playerBPick) {
    defaultSong2Id = rm.playerBPick.songStrategyId;
    defaultSong2Title = rm.playerBPick.songTitle;
  }

  resultDraft.value = {
    // 既存記録があればそれを尊重、無ければ自動入力で埋める。
    song1StrategyId: match.song1StrategyId ?? defaultSong1Id,
    song1Title: match.song1Title ?? defaultSong1Title,
    song1ScoreA: match.song1ScoreA,
    song1ScoreB: match.song1ScoreB,
    song2StrategyId: match.song2StrategyId ?? defaultSong2Id,
    song2Title: match.song2Title ?? defaultSong2Title,
    song2ScoreA: match.song2ScoreA,
    song2ScoreB: match.song2ScoreB,
  };
};
const cancelResultEdit = () => {
  resultEditingMatchId.value = null;
};
const handleSaveResult = async (matchId: number) => {
  if (!currentCompetition.value) return;
  // 部分入力 OK だが、負の数は弾く
  const allScores = [
    resultDraft.value.song1ScoreA, resultDraft.value.song1ScoreB,
    resultDraft.value.song2ScoreA, resultDraft.value.song2ScoreB,
  ];
  if (allScores.some(v => v !== null && (v as number) < 0)) {
    toast.error('スコアは 0 以上の整数で入力してください');
    return;
  }
  try {
    await setMatchResult(currentCompetition.value.id, matchId, resultDraft.value);
    await refreshStandings();
    toast.success('結果を記録しました');
    cancelResultEdit();
  } catch (e) {
    toast.error((e as Error).message);
  }
};
const handleClearResult = async (matchId: number) => {
  if (!currentCompetition.value) return;
  if (!confirm('この試合の結果を未記録に戻しますか?')) return;
  try {
    await clearMatchResult(currentCompetition.value.id, matchId);
    await refreshStandings();
    toast.success('結果を未記録に戻しました');
    cancelResultEdit();
  } catch (e) {
    toast.error((e as Error).message);
  }
};

/**
 * 編集中ドラフトから勝敗プレビューを計算 (リアルタイム表示)。
 * 両スコアが揃った曲だけ判定対象 (片方欠けてる曲はスキップ)。
 * 同スコア (引分) の曲は、両者が勝ったものとして両側に +1 する (運営仕様)。
 * サーバ側 setMatchResult と同じロジックなので、プレビューと保存後の値が一致する。
 */
const draftWinnerPreview = computed<{ a: number; b: number; verdict: string }>(() => {
  const d = resultDraft.value;
  let a = 0, b = 0, recorded = 0;
  if (d.song1ScoreA !== null && d.song1ScoreB !== null) {
    recorded++;
    if (d.song1ScoreA > d.song1ScoreB) a++;
    else if (d.song1ScoreA < d.song1ScoreB) b++;
    else { a++; b++; }
  }
  if (d.song2ScoreA !== null && d.song2ScoreB !== null) {
    recorded++;
    if (d.song2ScoreA > d.song2ScoreB) a++;
    else if (d.song2ScoreA < d.song2ScoreB) b++;
    else { a++; b++; }
  }
  let verdict = '未記録';
  if (recorded > 0) {
    if (a > b) verdict = 'A 勝ち';
    else if (a < b) verdict = 'B 勝ち';
    else verdict = '引分';
  }
  return { a, b, verdict };
});

// ── 順位表 / 決勝生成 ─────────────────────────────────
const standings = ref<CompetitionStandingsDto | null>(null);
const refreshStandings = async () => {
  if (!currentCompetition.value) return;
  try {
    standings.value = await fetchStandings(currentCompetition.value.id);
  } catch {
    standings.value = null;
  }
};

const handleGenerateFinals = async () => {
  if (!currentCompetition.value) return;
  if (!confirm('予選 TOP2 チームで決勝 matchup を生成しますか?')) return;
  try {
    await generateFinals(currentCompetition.value.id);
    await refreshStandings();
    await refreshRevealData(currentCompetition.value.id);
    toast.success('決勝を生成しました');
  } catch (e) {
    toast.error((e as Error).message);
  }
};

// ── 個人戦 (individual4) ───────────────────────────────────

/** 個人戦の参加者追加フォームの入力値。 */
const addingIndividualName = ref('');
const isAddingIndividual = ref(false);
/** 個人戦の順位表 (取得済み)。 */
const individualStandings = ref<CompetitionIndividualStandingsDto | null>(null);

const refreshIndividualStandings = async () => {
  if (!currentCompetition.value) return;
  try {
    individualStandings.value = await fetchIndividualStandings(currentCompetition.value.id);
  } catch {
    individualStandings.value = null;
  }
};

/** 個人戦参加者を 1 名追加。 */
const handleAddIndividualParticipant = async () => {
  if (!currentCompetition.value) return;
  const name = addingIndividualName.value.trim();
  if (!name) {
    toast.error('表示名を入力してください');
    return;
  }
  isAddingIndividual.value = true;
  try {
    await addIndividualParticipant(currentCompetition.value.id, name);
    addingIndividualName.value = '';
    toast.success('参加者を追加しました');
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    isAddingIndividual.value = false;
  }
};

/** 個人戦参加者の表示名を編集。inline 編集状態を管理。 */
const editingIndividualParticipantId = ref<number | null>(null);
const editingIndividualName = ref('');
const beginEditIndividualParticipant = (p: CompetitionParticipantDto) => {
  editingIndividualParticipantId.value = p.id;
  editingIndividualName.value = p.displayName;
};
const cancelEditIndividualParticipant = () => {
  editingIndividualParticipantId.value = null;
  editingIndividualName.value = '';
};
const commitEditIndividualParticipant = async (p: CompetitionParticipantDto) => {
  if (!currentCompetition.value) return;
  const name = editingIndividualName.value.trim();
  if (!name || name === p.displayName) {
    cancelEditIndividualParticipant();
    return;
  }
  try {
    await updateIndividualParticipant(currentCompetition.value.id, p.id, name);
    toast.success('表示名を変更しました');
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    cancelEditIndividualParticipant();
  }
};

const handleDeleteIndividualParticipant = async (p: CompetitionParticipantDto) => {
  if (!currentCompetition.value) return;
  if (!confirm(`「${p.displayName}」を削除しますか?`)) return;
  try {
    await deleteIndividualParticipant(currentCompetition.value.id, p.id);
    toast.success('参加者を削除しました');
  } catch (e) {
    toast.error((e as Error).message);
  }
};

const handleRegenerateIndividualToken = async (p: CompetitionParticipantDto) => {
  if (!currentCompetition.value) return;
  if (!confirm(`「${p.displayName}」の招待 URL を再発行します。続けますか?`)) return;
  try {
    await regenerateIndividualParticipantToken(currentCompetition.value.id, p.id);
    toast.success('招待 URL を再発行しました');
  } catch (e) {
    toast.error((e as Error).message);
  }
};

/** 個人戦の参加者数 (現在登録済)。12 / 16 のときだけ open に進める。 */
const individualParticipantCount = computed<number>(() => {
  return currentCompetition.value?.participants?.length ?? 0;
});
const canOpenIndividual = computed<boolean>(() => {
  const n = individualParticipantCount.value;
  return n === 12 || n === 16;
});

const handleOpenIndividualStatus = async () => {
  if (!currentCompetition.value) return;
  const n = individualParticipantCount.value;
  if (!confirm(`この大会を open に遷移しますか?\n参加者 ${n} 名から予選試合表が自動生成されます。`)) return;
  try {
    await openIndividualCompetition(currentCompetition.value.id);
    await refreshIndividualStandings();
    toast.success('open に遷移しました');
  } catch (e) {
    toast.error((e as Error).message);
  }
};

// ── 抽選番号モードでの open 処理 ─────────────────────────
/**
 * 抽選番号モード用のテキストエリア入力。1 行 1 試合、スペース/カンマ区切りで 4 つの番号。
 * 例:
 *   1 2 3 4
 *   5 6 7 8
 *   9 10 11 12
 *
 * 12 名→6 試合 × 3 テーブル = 18 行 / 16 名→5 試合 × 4 テーブル = 20 行を想定。
 */
const numberModeText = ref('');
const isOpeningWithNumbers = ref(false);

const parseNumberModeText = (text: string): number[][] => {
  const lines = text.split(/\r?\n/).map(l => l.trim()).filter(l => l.length > 0);
  const out: number[][] = [];
  for (const line of lines) {
    const nums = line.split(/[\s,]+/).map(s => Number(s));
    out.push(nums);
  }
  return out;
};

const handleOpenWithNumbers = async () => {
  if (!currentCompetition.value) return;
  const matches = parseNumberModeText(numberModeText.value);
  if (matches.length === 0) {
    toast.error('番号入力が空です。1 行 1 試合、4 つの番号を入力してください。');
    return;
  }
  for (let i = 0; i < matches.length; i++) {
    if (matches[i].length !== 4 || matches[i].some(n => !Number.isInteger(n) || n <= 0)) {
      toast.error(`試合 ${i + 1}: 4 つの正の整数を入力してください`);
      return;
    }
  }
  if (!confirm(`抽選番号モードで open に遷移しますか?\n${matches.length} 試合 / 参加者数 ${individualParticipantCount.value} 名`)) return;
  isOpeningWithNumbers.value = true;
  try {
    await openIndividualWithNumbers(currentCompetition.value.id, matches);
    await refreshIndividualStandings();
    numberModeText.value = '';
    toast.success(`open に遷移しました (${matches.length} 試合生成)`);
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    isOpeningWithNumbers.value = false;
  }
};

// ── 抽選結果割当 (番号 → 参加者) ─────────────────────────
/**
 * 試合スロットから抽出した「使用中の番号」のソート済みリスト。
 * 各番号に対して draft 1 件のドロップダウンを並べる UI で使う。
 */
const numbersInUse = computed<number[]>(() => {
  const set = new Set<number>();
  for (const m of currentCompetition.value?.individualMatches ?? []) {
    for (const s of m.slots) {
      if (s.slotNumber != null) set.add(s.slotNumber);
    }
  }
  return Array.from(set).sort((a, b) => a - b);
});

/** 現在の参加者 ID → 表示名のマップ (重複番号チェック用)。 */
const lotteryDraft = ref<Record<number, number | ''>>({});

/** 既に試合スロットに割当済の (番号 → 参加者 ID) を初期値として読み出す。 */
const seedLotteryDraft = () => {
  const seed: Record<number, number | ''> = {};
  for (const num of numbersInUse.value) {
    seed[num] = '';
  }
  for (const m of currentCompetition.value?.individualMatches ?? []) {
    for (const s of m.slots) {
      if (s.slotNumber != null && s.participantId != null) {
        seed[s.slotNumber] = s.participantId;
      }
    }
  }
  lotteryDraft.value = seed;
};

watch(numbersInUse, () => {
  if (numbersInUse.value.length > 0 && Object.keys(lotteryDraft.value).length === 0) {
    seedLotteryDraft();
  }
}, { immediate: true });

const isAssigningLottery = ref(false);
const handleAssignLottery = async () => {
  if (!currentCompetition.value) return;
  const entries = Object.entries(lotteryDraft.value)
    .filter(([, pid]) => pid !== '' && pid != null)
    .map(([num, pid]) => ({ number: Number(num), participantId: Number(pid) }));
  if (entries.length === 0) {
    toast.error('1 件以上の対応を入力してください');
    return;
  }
  // 参加者重複の早期検知 (サーバ側でも弾くが、UI で先に知らせる)
  const pidSet = new Set<number>();
  for (const e of entries) {
    if (pidSet.has(e.participantId)) {
      toast.error(`同じ参加者が複数の番号に割り当てられています`);
      return;
    }
    pidSet.add(e.participantId);
  }
  isAssigningLottery.value = true;
  try {
    await assignIndividualLottery(currentCompetition.value.id, entries);
    await refreshIndividualStandings();
    toast.success(`${entries.length} 件の番号を割り当てました`);
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    isAssigningLottery.value = false;
  }
};

const hasUnassignedSlots = computed<boolean>(() => {
  for (const m of currentCompetition.value?.individualMatches ?? []) {
    for (const s of m.slots) {
      if (s.slotNumber != null && s.participantId == null) return true;
    }
  }
  return false;
});

// ── OBS ブラウザソース URL ──────────────────────────────
const obsUrl = computed<string>(() => {
  const token = currentCompetition.value?.obsToken;
  if (!token) return '';
  return `${window.location.origin}/obs/individual/${token}`;
});

const isGeneratingObsToken = ref(false);
const handleGenerateObsToken = async () => {
  if (!currentCompetition.value) return;
  if (currentCompetition.value.obsToken
      && !confirm('OBS URL を再発行しますか?\n旧 URL は無効になります。')) return;
  isGeneratingObsToken.value = true;
  try {
    await regenerateObsToken(currentCompetition.value.id);
    toast.success('OBS URL を発行しました');
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    isGeneratingObsToken.value = false;
  }
};

// ── 観戦用 対戦表 URL (team5) ───────────────────────────
/** 発行済みなら観戦客向け対戦表の完全 URL。未発行なら空文字。 */
const spectatorUrl = computed<string>(() => {
  const token = currentCompetition.value?.spectatorToken;
  if (!token) return '';
  return `${window.location.origin}/competition/spectator/${token}`;
});

const isGeneratingSpectatorToken = ref(false);
const handleGenerateSpectatorToken = async () => {
  if (!currentCompetition.value) return;
  if (currentCompetition.value.spectatorToken
      && !confirm('観戦用 URL を再発行しますか?\n旧 URL は無効になります。')) return;
  isGeneratingSpectatorToken.value = true;
  try {
    await regenerateSpectatorToken(currentCompetition.value.id);
    toast.success('観戦用 URL を発行しました');
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    isGeneratingSpectatorToken.value = false;
  }
};

/**
 * 個人戦 試合結果記録: 編集中の matchId と 4 曲メタ + 4 スロット × 4 曲順位の入力 draft。
 * IIDX ARENA モード相当の 4×4 グリッドを管理する。クリック 1 回で順位を 1→2→3→4→未選択 にサイクル。
 */
const editingIndividualMatchId = ref<number | null>(null);
const individualResultDraft = ref<IndividualResultPayload>({
  song1StrategyId: null, song1Title: null,
  song2StrategyId: null, song2Title: null,
  song3StrategyId: null, song3Title: null,
  song4StrategyId: null, song4Title: null,
  slots: [
    { slotPosition: 1, rank1: null, rank2: null, rank3: null, rank4: null },
    { slotPosition: 2, rank1: null, rank2: null, rank3: null, rank4: null },
    { slotPosition: 3, rank1: null, rank2: null, rank3: null, rank4: null },
    { slotPosition: 4, rank1: null, rank2: null, rank3: null, rank4: null },
  ],
});

const beginEditIndividualResult = (m: CompetitionIndividualMatchDto) => {
  editingIndividualMatchId.value = m.id;
  individualResultDraft.value = {
    song1StrategyId: m.song1StrategyId, song1Title: m.song1Title,
    song2StrategyId: m.song2StrategyId, song2Title: m.song2Title,
    song3StrategyId: m.song3StrategyId, song3Title: m.song3Title,
    song4StrategyId: m.song4StrategyId, song4Title: m.song4Title,
    slots: m.slots.map(s => ({
      slotPosition: s.slotPosition,
      rank1: s.rank1,
      rank2: s.rank2,
      rank3: s.rank3,
      rank4: s.rank4,
    })),
  };
};
const cancelEditIndividualResult = () => {
  editingIndividualMatchId.value = null;
};

/** クリック時の順位サイクル: null → 1 → 2 → 3 → 4 → null。 */
const cycleRank = (cur: number | null): number | null => {
  if (cur === null) return 1;
  if (cur >= 4) return null;
  return cur + 1;
};

/**
 * 指定スロット (slotIdx 0-3) × 曲 (songIdx 1-4) の順位を 1 段サイクルさせる。
 */
const bumpDraftRank = (slotIdx: number, songIdx: number) => {
  const slot = individualResultDraft.value.slots[slotIdx];
  if (!slot) return;
  const key = `rank${songIdx}` as 'rank1' | 'rank2' | 'rank3' | 'rank4';
  slot[key] = cycleRank(slot[key]);
};

const handleSaveIndividualResult = async (matchId: number) => {
  if (!currentCompetition.value) return;
  try {
    await setIndividualMatchResult(currentCompetition.value.id, matchId, individualResultDraft.value);
    await refreshIndividualStandings();
    toast.success('結果を記録しました');
    cancelEditIndividualResult();
  } catch (e) {
    toast.error((e as Error).message);
  }
};

// ── 楽曲選択モーダル ──────────────────────────────
/**
 * 楽曲選択モーダルの開閉状態と編集対象 (どの song slot 1〜4 を編集中か)。
 * 個人戦の試合結果編集モードでのみ使用。
 */
const songPickerOpen = ref(false);
const songPickerTargetSlot = ref<1 | 2 | 3 | 4 | null>(null);

const openSongPicker = (slot: 1 | 2 | 3 | 4) => {
  songPickerTargetSlot.value = slot;
  songPickerOpen.value = true;
};
const closeSongPicker = () => {
  songPickerOpen.value = false;
  songPickerTargetSlot.value = null;
};
const handleSongPicked = (song: { strategyId: number; title: string }) => {
  const slot = songPickerTargetSlot.value;
  if (slot === null) {
    closeSongPicker();
    return;
  }
  if (slot === 1) { individualResultDraft.value.song1StrategyId = song.strategyId; individualResultDraft.value.song1Title = song.title; }
  else if (slot === 2) { individualResultDraft.value.song2StrategyId = song.strategyId; individualResultDraft.value.song2Title = song.title; }
  else if (slot === 3) { individualResultDraft.value.song3StrategyId = song.strategyId; individualResultDraft.value.song3Title = song.title; }
  else if (slot === 4) { individualResultDraft.value.song4StrategyId = song.strategyId; individualResultDraft.value.song4Title = song.title; }
  closeSongPicker();
};

/** 編集中ドラフトの曲タイトル 4 つを配列で参照しやすくする。 */
const draftSongTitle = (idx: 1 | 2 | 3 | 4): string | null => {
  if (idx === 1) return individualResultDraft.value.song1Title;
  if (idx === 2) return individualResultDraft.value.song2Title;
  if (idx === 3) return individualResultDraft.value.song3Title;
  return individualResultDraft.value.song4Title;
};

const handleClearIndividualResult = async (matchId: number) => {
  if (!currentCompetition.value) return;
  if (!confirm('この試合の結果を未記録に戻しますか?')) return;
  try {
    await clearIndividualMatchResult(currentCompetition.value.id, matchId);
    await refreshIndividualStandings();
    toast.success('結果を未記録に戻しました');
    cancelEditIndividualResult();
  } catch (e) {
    toast.error((e as Error).message);
  }
};

const handleGenerateIndividualFinals = async () => {
  if (!currentCompetition.value) return;
  if (!confirm('予選順位に基づき決勝試合 (4 人ずつのバケット) を生成しますか?')) return;
  try {
    await generateIndividualFinals(currentCompetition.value.id);
    await refreshIndividualStandings();
    toast.success('決勝を生成しました');
  } catch (e) {
    toast.error((e as Error).message);
  }
};

/**
 * 編集中ドラフトから 4 曲 × 4 スロットのポイントプレビュー + 各スロット総ポイントを算出。
 * 順位はユーザーがクリックで直接指定するため、ここでは「順位 → ポイント」のマッピングと
 * 「全 16 セルが揃ったときの総和」を計算するだけ。
 */
type DraftRankRow = {
  slotPosition: number;
  ranks: Array<number | null>;
  points: Array<number | null>;
  total: number | null;
};
const rankToPoints = (rank: number | null): number | null => {
  if (rank === null) return null;
  if (rank === 1) return 2;
  if (rank === 2) return 1;
  return 0;
};
const draftIndividualRanks = computed<DraftRankRow[]>(() => {
  const slots = individualResultDraft.value.slots;
  const getRank = (s: typeof slots[number], idx: number): number | null => {
    return idx === 0 ? s.rank1 : idx === 1 ? s.rank2 : idx === 2 ? s.rank3 : s.rank4;
  };
  const rows: DraftRankRow[] = slots.map(s => {
    const ranks: Array<number | null> = [s.rank1, s.rank2, s.rank3, s.rank4];
    const points = ranks.map(rankToPoints);
    return {
      slotPosition: s.slotPosition,
      ranks,
      points,
      total: null,
    };
  });
  // 試合全 16 セルが揃っている場合のみ total を出す (部分入力時は null のまま)
  const allRanked = slots.every(s =>
    s.rank1 !== null && s.rank2 !== null && s.rank3 !== null && s.rank4 !== null);
  if (allRanked) {
    for (let i = 0; i < rows.length; i++) {
      rows[i].total =
        (rows[i].points[0] ?? 0) + (rows[i].points[1] ?? 0)
        + (rows[i].points[2] ?? 0) + (rows[i].points[3] ?? 0);
    }
  }
  // getRank は将来 (順位の直接表示用に) 使えるようエクスポートしないが lint 警告を消すため touch
  void getRank;
  return rows;
});

/** 個人戦の試合一覧を予選 → 決勝の順で返す。 */
const individualPrelimMatches = computed<CompetitionIndividualMatchDto[]>(() => {
  return (currentCompetition.value?.individualMatches ?? []).filter(m => !m.isFinals);
});
const individualFinalsMatches = computed<CompetitionIndividualMatchDto[]>(() => {
  return (currentCompetition.value?.individualMatches ?? []).filter(m => m.isFinals);
});

/**
 * 予選順位に応じた背景色クラス。決勝バケット (1-4 / 5-8 / 9-12 / 13-16) ごとに別色。
 * 4 人ずつ決勝で同卓するので、視覚的に同卓予定者がグループ化されて見える効果も狙う。
 */
const prelimBucketRowClass = (rank: number | null | undefined): string => {
  if (rank == null) return '';
  if (rank <= 4) return 'bg-amber-50/60 dark:bg-amber-900/15 font-bold';
  if (rank <= 8) return 'bg-sky-50/60 dark:bg-sky-900/15';
  if (rank <= 12) return 'bg-emerald-50/60 dark:bg-emerald-900/15';
  if (rank <= 16) return 'bg-violet-50/60 dark:bg-violet-900/15';
  return '';
};

/**
 * 1 試合内 (= 4 スロット) の totalPoints から各スロットの総合順位を算出する。
 * 「自分より高 totalPoints の人数 + 1」を rank に。タイは両者を上位扱い。
 * totalPoints が null (未確定) のスロットは null を返す。
 */
const overallMatchRank = (slot: { totalPoints: number | null }, allSlots: { totalPoints: number | null }[]): number | null => {
  if (slot.totalPoints === null) return null;
  let better = 0;
  for (const other of allSlots) {
    if (other.totalPoints !== null && other.totalPoints > slot.totalPoints) better++;
  }
  return better + 1;
};

/**
 * 編集中ドラフトから idx 番目スロットの総合順位 (プレビュー用) を計算する。
 * 全 16 セル揃った時点で確定値となる (draftIndividualRanks の total が null でなくなる)。
 */
const draftOverallRank = (idx: number): number | null => {
  const myT = draftIndividualRanks.value[idx]?.total;
  if (myT === null || myT === undefined) return null;
  let better = 0;
  for (const r of draftIndividualRanks.value) {
    if (r.total !== null && r.total !== undefined && r.total > myT) better++;
  }
  return better + 1;
};

// ── 途中経過マトリクス 用ヘルパ ───────────────────────
/**
 * row × col セルの表示内容。
 *  - null: 対角 (同チーム同士)
 *  - undefined: 該当 matchup が未記録
 *  - object: row 視点の戦ポイント + 相手の戦ポイント + 勝敗マーカー
 *    例) テクノが ADX に対し戦pt 13、ADX は 8 で記録済 → { rowPts: 13, colPts: 8, marker: '○' }
 */
interface MatrixCell {
  /** 行チームが獲得した戦ポイント (3 戦合計、勝ち点は含まない)。 */
  rowPts: number;
  /** 列チームが獲得した戦ポイント。 */
  colPts: number;
  /** 行視点の勝敗: ○=勝ち / ×=負け / △=引分。 */
  marker: '○' | '×' | '△';
}

const matrixCellOf = (rowTeamId: number, colTeamId: number): MatrixCell | null | undefined => {
  if (rowTeamId === colTeamId) return null;
  const breakdown = standings.value?.matchupBreakdown ?? [];
  for (const e of breakdown) {
    const isAB = e.teamAId === rowTeamId && e.teamBId === colTeamId;
    const isBA = e.teamBId === rowTeamId && e.teamAId === colTeamId;
    if (!isAB && !isBA) continue;
    if (!e.recorded) return undefined;
    const rowPts = isAB ? e.aSongPoints : e.bSongPoints;
    const colPts = isAB ? e.bSongPoints : e.aSongPoints;
    const marker: '○' | '×' | '△' =
      rowPts > colPts ? '○' :
      rowPts < colPts ? '×' : '△';
    return { rowPts, colPts, marker };
  }
  return undefined;
};

/** standings から指定チームの勝ち点合計 (matchup 勝点) を取得 (見つからなければ 0)。 */
const teamMatchupPoints = (teamId: number): number => {
  return standings.value?.rows.find(r => r.teamId === teamId)?.matchupPoints ?? 0;
};

/** matchup の両側ラインアップ公開状態を判定 (none / partial / both)。 */
const lineupPublishStateOf = (mu: { lineupPublishedA: boolean; lineupPublishedB: boolean }): 'none' | 'partial' | 'both' => {
  if (mu.lineupPublishedA && mu.lineupPublishedB) return 'both';
  if (mu.lineupPublishedA || mu.lineupPublishedB) return 'partial';
  return 'none';
};

// ── 対戦表: 設定済み / 未設定の振り分け ──────────────────
/** 設定済み matchup (実施対象)。matchupOrder 昇順 (= 運営が設定した順)。 */
const configuredMatchups = computed<CompetitionMatchupDto[]>(() => {
  return (currentCompetition.value?.matchups ?? [])
    .filter(mu => mu.configured)
    .slice()
    .sort((a, b) => a.matchupOrder - b.matchupOrder);
});

/** 未設定 matchup (運営がまだ実施対象にしていない組み合わせ)。チーム順で安定表示。 */
const unconfiguredMatchups = computed<CompetitionMatchupDto[]>(() => {
  return (currentCompetition.value?.matchups ?? [])
    .filter(mu => !mu.configured && !mu.isFinals)
    .slice()
    .sort((a, b) => (a.teamAId - b.teamAId) || (a.teamBId - b.teamBId));
});

/**
 * matchup を設定済み ⇄ 未設定 に切り替える。
 * 設定すると選んだ順に第 N 試合として採番され、プレイヤー/TL に公開される。
 */
const handleConfigureMatchup = async (matchupId: number, configured: boolean) => {
  if (!currentCompetition.value) return;
  if (!configured && !confirm('この対戦を未設定に戻しますか?\nプレイヤー/TL から見えなくなり、以降の試合番号が繰り上がります。')) return;
  try {
    await configureMatchup(currentCompetition.value.id, matchupId, configured);
    toast.success(configured ? '対戦を設定しました' : '設定を解除しました');
  } catch (e) {
    toast.error((e as Error).message);
  }
};

// ── ステータスバッジ ──────────────────────────────────────
const statusLabel = (s: string) => ({
  draft: '編成中',
  open: '受付中',
  locked: 'ロック済',
  finished: '終了',
} as Record<string, string>)[s] ?? s;
const statusColor = (s: string) => ({
  draft: 'bg-slate-200 text-slate-700 dark:bg-slate-700 dark:text-slate-200',
  open: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300',
  locked: 'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300',
  finished: 'bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-400',
} as Record<string, string>)[s] ?? 'bg-slate-100 text-slate-700';
</script>

<template>
  <div class="competition-admin-view bg-slate-50 dark:bg-slate-900 text-slate-800 dark:text-slate-100 p-4 sm:p-8">
    <!-- 権限が無いユーザー向けの注意書き (4 ID 以外がこの URL に直接来た場合) -->
    <div v-if="!isOrganizer" class="max-w-2xl mx-auto bg-rose-50 dark:bg-rose-900/30 border border-rose-200 dark:border-rose-700 rounded-md p-6 text-center">
      <p class="text-lg font-bold text-rose-700 dark:text-rose-300">大会管理画面</p>
      <p class="text-sm text-rose-600 dark:text-rose-400 mt-2">主催権限がありません。サイドバーから他のページへ戻ってください。</p>
    </div>

    <template v-else>
      <!-- ────────── 一覧モード ────────── -->
      <div v-if="!currentCompetition" class="max-w-5xl mx-auto space-y-6">
        <div>
          <h1 class="text-3xl font-bold tracking-tight">大会管理</h1>
          <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">5 チーム × 4 名の総当たり団体戦を作成・編成する</p>
        </div>

        <!-- 新規作成カード -->
        <div class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-md p-5 space-y-3">
          <p class="text-sm font-bold">新規大会を作成</p>
          <!-- フォーマット選択 (ラジオ) -->
          <div>
            <p class="text-[10px] font-mono text-slate-400 mb-2">フォーマット</p>
            <div class="flex flex-wrap gap-2">
              <label
                class="flex-1 min-w-[180px] cursor-pointer px-3 py-2 rounded-md border-2 transition-colors"
                :class="createFormat === 'team5'
                  ? 'border-blue-500 bg-blue-50 dark:bg-blue-900/20'
                  : 'border-slate-200 dark:border-slate-700 hover:border-slate-300'"
              >
                <input type="radio" v-model="createFormat" value="team5" class="sr-only" :disabled="isCreating" />
                <p class="text-sm font-bold">団体戦 (5 チーム)</p>
                <p class="text-[11px] text-slate-500 mt-0.5">5 チーム × 4 名の総当たり、10 matchup × 3 戦</p>
              </label>
              <label
                class="flex-1 min-w-[180px] cursor-pointer px-3 py-2 rounded-md border-2 transition-colors"
                :class="createFormat === 'individual4'
                  ? 'border-blue-500 bg-blue-50 dark:bg-blue-900/20'
                  : 'border-slate-200 dark:border-slate-700 hover:border-slate-300'"
              >
                <input type="radio" v-model="createFormat" value="individual4" class="sr-only" :disabled="isCreating" />
                <p class="text-sm font-bold">個人戦 (4 人対戦)</p>
                <p class="text-[11px] text-slate-500 mt-0.5">12 or 16 名・各人 6 or 5 試合の予選 + 上位 4 名ずつの決勝</p>
              </label>
            </div>
          </div>
          <div class="flex flex-col sm:flex-row gap-2">
            <input
              v-model="createName"
              type="text"
              placeholder="大会名 (例: BPL 模擬戦 2026 春)"
              class="flex-1 px-4 py-2.5 bg-slate-50 dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded-md outline-none focus:border-blue-400"
              :disabled="isCreating"
              @keydown.enter="handleCreate"
            />
            <button
              type="button"
              @click="handleCreate"
              :disabled="isCreating || !createName.trim()"
              class="px-6 py-2.5 rounded-md font-bold bg-blue-600 text-white hover:bg-blue-700 disabled:bg-slate-300 dark:disabled:bg-slate-600 disabled:cursor-not-allowed transition-colors"
            >
              {{ isCreating ? '作成中…' : '作成' }}
            </button>
          </div>
        </div>

        <!-- 一覧 -->
        <div class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-md overflow-hidden">
          <div class="px-5 py-3 border-b border-slate-200 dark:border-slate-700 flex items-center justify-between">
            <p class="text-sm font-bold">既存大会</p>
            <button type="button" @click="fetchCompetitions" class="text-xs text-slate-500 hover:text-slate-700 dark:hover:text-slate-300">再読込</button>
          </div>
          <div v-if="isLoading && competitions.length === 0" class="px-5 py-8 text-center text-slate-400 text-sm">読み込み中…</div>
          <div v-else-if="competitions.length === 0" class="px-5 py-8 text-center text-slate-400 text-sm">大会はまだありません</div>
          <ul v-else class="divide-y divide-slate-200 dark:divide-slate-700">
            <li v-for="c in competitions" :key="c.id" class="px-5 py-3 flex items-center gap-3 hover:bg-slate-50 dark:hover:bg-slate-800/60 cursor-pointer" @click="handleOpenCompetition(c.id)">
              <div class="flex-1 min-w-0">
                <p class="font-bold truncate">{{ c.name }}</p>
                <p class="text-[11px] text-slate-400 font-mono">ID #{{ c.id }} · 作成 {{ new Date(c.createdAt).toLocaleString() }}</p>
              </div>
              <span
                class="text-[10px] font-bold px-2 py-0.5 rounded"
                :class="c.format === 'individual4'
                  ? 'bg-violet-100 text-violet-700 dark:bg-violet-900/40 dark:text-violet-300'
                  : 'bg-sky-100 text-sky-700 dark:bg-sky-900/40 dark:text-sky-300'"
              >{{ c.format === 'individual4' ? '個人戦' : '団体戦' }}</span>
              <span class="text-[10px] font-bold px-2 py-0.5 rounded" :class="statusColor(c.status)">{{ statusLabel(c.status) }}</span>
              <svg class="h-4 w-4 text-slate-400" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7" /></svg>
            </li>
          </ul>
        </div>
      </div>

      <!-- ────────── 詳細モード ────────── -->
      <div v-else class="max-w-6xl mx-auto space-y-6">
        <!-- ヘッダ -->
        <div class="flex flex-wrap items-center gap-3">
          <button type="button" @click="backToList" class="px-3 py-1.5 rounded-md text-sm font-bold bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600">
            ← 一覧へ
          </button>
          <h1 class="text-2xl sm:text-3xl font-bold tracking-tight">{{ currentCompetition.name }}</h1>
          <span
            class="text-[10px] font-bold px-2 py-0.5 rounded"
            :class="currentCompetition.format === 'individual4'
              ? 'bg-violet-100 text-violet-700 dark:bg-violet-900/40 dark:text-violet-300'
              : 'bg-sky-100 text-sky-700 dark:bg-sky-900/40 dark:text-sky-300'"
          >{{ currentCompetition.format === 'individual4' ? '個人戦' : '団体戦' }}</span>
          <span class="text-[10px] font-bold px-2 py-0.5 rounded" :class="statusColor(currentCompetition.status)">{{ statusLabel(currentCompetition.status) }}</span>
          <p class="text-xs text-slate-500 font-mono">ID #{{ currentCompetition.id }}</p>

          <!-- team5 用 Open ボタン -->
          <button
            v-if="currentCompetition.status === 'draft' && currentCompetition.format !== 'individual4'"
            type="button"
            @click="handleOpenStatus"
            class="ml-auto px-5 py-2 rounded-md text-sm font-bold bg-emerald-600 hover:bg-emerald-700 text-white"
          >
            ▶ Open に遷移
          </button>
          <!-- individual4 用 Open ボタン (12 or 16 名揃った時のみ active) -->
          <button
            v-else-if="currentCompetition.status === 'draft' && currentCompetition.format === 'individual4'"
            type="button"
            @click="handleOpenIndividualStatus"
            :disabled="!canOpenIndividual"
            class="ml-auto px-5 py-2 rounded-md text-sm font-bold transition-all"
            :class="canOpenIndividual
              ? 'bg-emerald-600 hover:bg-emerald-700 text-white'
              : 'bg-slate-300 dark:bg-slate-600 text-slate-500 cursor-not-allowed'"
            :title="canOpenIndividual ? '予選試合表を自動生成して open に遷移します' : '参加者を 12 名または 16 名 ちょうど登録してください'"
          >
            ▶ Open に遷移 ({{ individualParticipantCount }} / 12 or 16)
          </button>

          <!-- 削除ボタン (常時表示。2 段階確認付き) -->
          <button
            type="button"
            @click="handleDeleteCompetition(currentCompetition.id, currentCompetition.name)"
            :class="currentCompetition.status === 'draft' ? '' : 'ml-auto'"
            class="px-3 py-2 rounded-md text-xs font-bold bg-rose-50 text-rose-600 hover:bg-rose-100 dark:bg-rose-900/30 dark:text-rose-300 border border-rose-200 dark:border-rose-800"
          >
            🗑 大会を削除
          </button>
        </div>

        <!-- ────────── team5 専用セクション群 ────────── -->
        <template v-if="currentCompetition.format !== 'individual4'">
        <!-- 5 チームグリッド -->
        <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          <div
            v-for="team in currentCompetition.teams"
            :key="team.id"
            class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-md overflow-hidden"
          >
            <!-- チームヘッダ -->
            <div class="px-4 py-3 border-b border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/60">
              <div v-if="editingTeamId === team.id" class="flex items-center gap-2">
                <input
                  v-model="editingTeamName"
                  type="text"
                  class="flex-1 px-2 py-1 rounded-lg text-sm bg-white dark:bg-slate-800 border border-slate-300 dark:border-slate-600 outline-none focus:border-blue-400"
                  @keydown.enter="commitRenameTeam(team)"
                  @keydown.esc="cancelRenameTeam"
                />
                <button type="button" @click="commitRenameTeam(team)" class="text-xs font-bold text-blue-600 dark:text-blue-400">保存</button>
                <button type="button" @click="cancelRenameTeam" class="text-xs text-slate-500">×</button>
              </div>
              <div v-else class="flex items-center gap-2">
                <p class="flex-1 font-bold truncate" :class="teamColorClass(team.teamName)">{{ team.teamName }}</p>
                <button
                  v-if="currentCompetition.status === 'draft'"
                  type="button"
                  @click="beginRenameTeam(team)"
                  class="text-xs text-slate-400 hover:text-slate-600 dark:hover:text-slate-200"
                  title="チーム名を編集"
                >
                  ✎
                </button>
              </div>
              <!-- TL 専用 URL -->
              <div class="mt-2 flex items-center gap-2 text-[10px] font-mono text-slate-400">
                <span class="truncate">TL: {{ buildTlUrl(team.tlToken) }}</span>
                <button
                  type="button"
                  @click="copyToClipboard(buildTlUrl(team.tlToken), 'TL URL')"
                  class="shrink-0 px-2 py-0.5 rounded bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200"
                >コピー</button>
                <button
                  type="button"
                  @click="handleRegenerateTlToken(team)"
                  class="shrink-0 px-2 py-0.5 rounded bg-amber-100 dark:bg-amber-900/40 hover:bg-amber-200 dark:hover:bg-amber-900/60 text-amber-700 dark:text-amber-300"
                  title="トークンを再発行 (誤公開時のリカバリ)"
                >再発行</button>
              </div>
            </div>

            <!-- 既存メンバーのみリスト表示 -->
            <ul class="divide-y divide-slate-100 dark:divide-slate-700/60">
              <li
                v-for="m in membersOf(team.id)"
                :key="m.id"
                class="px-4 py-3"
              >
                <div class="flex items-center gap-2">
                  <div class="flex-1 min-w-0">
                    <!-- DJ NAME 編集中 -->
                    <div v-if="editingMemberId === m.id" class="flex items-center gap-1.5">
                      <input
                        v-model="editingMemberName"
                        type="text"
                        maxlength="50"
                        @keyup.enter="commitEditMember(m)"
                        @keyup.esc="cancelEditMember"
                        class="flex-1 min-w-0 px-2 py-1 text-sm font-bold rounded-lg bg-white dark:bg-slate-900 border border-blue-400 outline-none"
                      />
                      <button
                        type="button"
                        @click="commitEditMember(m)"
                        class="shrink-0 px-2 py-1 rounded text-[10px] font-bold bg-blue-600 text-white hover:bg-blue-700"
                      >保存</button>
                      <button
                        type="button"
                        @click="cancelEditMember"
                        class="shrink-0 px-2 py-1 rounded text-[10px] font-bold bg-slate-200 dark:bg-slate-700 text-slate-500 hover:bg-slate-300 dark:hover:bg-slate-600"
                      >取消</button>
                    </div>
                    <!-- 通常表示 -->
                    <div v-else class="flex items-center gap-1.5">
                      <p class="font-bold truncate">{{ m.displayName }}</p>
                      <button
                        type="button"
                        @click="beginEditMember(m)"
                        class="shrink-0 text-slate-400 hover:text-blue-500 dark:hover:text-blue-300"
                        title="DJ NAME を変更"
                      >✎</button>
                      <span
                        v-if="m.isTl"
                        class="text-[9px] font-bold px-1.5 py-0.5 rounded bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300"
                      >TL</span>
                    </div>
                    <div class="mt-0.5 flex items-center gap-1.5 text-[10px] font-mono text-slate-400">
                      <span class="truncate">{{ buildPlayerUrl(m.inviteToken) }}</span>
                    </div>
                  </div>
                  <button
                    type="button"
                    @click="copyToClipboard(buildPlayerUrl(m.inviteToken), '参加者 URL')"
                    class="shrink-0 px-2 py-1 rounded text-[10px] font-bold bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200"
                  >URL</button>
                  <button
                    type="button"
                    @click="handleRegenerateParticipantToken(m)"
                    class="shrink-0 px-2 py-1 rounded text-[10px] font-bold bg-amber-100 dark:bg-amber-900/40 hover:bg-amber-200 dark:hover:bg-amber-900/60 text-amber-700 dark:text-amber-300"
                    title="招待 URL を再発行 (誤公開時)"
                  >再発行</button>
                  <button
                    type="button"
                    @click="handleToggleTl(m)"
                    class="shrink-0 px-2 py-1 rounded text-[10px] font-bold"
                    :class="m.isTl
                      ? 'bg-amber-100 text-amber-700 hover:bg-amber-200 dark:bg-amber-900/40 dark:text-amber-300'
                      : 'bg-slate-100 text-slate-500 hover:bg-slate-200 dark:bg-slate-800 dark:text-slate-400'"
                    :title="m.isTl ? 'TL を解除' : 'TL に設定'"
                  >
                    {{ m.isTl ? 'TL ✓' : 'TL' }}
                  </button>
                  <button
                    v-if="currentCompetition.status === 'draft'"
                    type="button"
                    @click="handleDeleteParticipant(m)"
                    class="shrink-0 px-2 py-1 rounded text-[10px] font-bold bg-rose-50 text-rose-600 hover:bg-rose-100 dark:bg-rose-900/30 dark:text-rose-300"
                    title="削除"
                  >×</button>
                </div>
              </li>
            </ul>

            <!--
              参加者追加 UI: チームごとに 1 つだけ表示。
              既存メンバーが 4 名揃ったら隠す。draft 状態以外でも (open 後の閲覧時) 隠す。
            -->
            <div
              v-if="currentCompetition.status === 'draft' && remainingSlotsOf(team.id) > 0"
              class="px-4 py-3 border-t border-slate-100 dark:border-slate-700/60"
            >
              <div v-if="addingForTeamId === team.id" class="space-y-2">
                <input
                  v-model="addingDisplayName"
                  type="text"
                  placeholder="表示名 (DJ 名)"
                  class="w-full px-3 py-1.5 text-sm rounded-lg bg-white dark:bg-slate-800 border border-slate-300 dark:border-slate-600 outline-none focus:border-blue-400"
                  @keydown.enter="commitAddParticipant"
                />
                <label class="flex items-center gap-2 text-xs text-slate-500">
                  <input type="checkbox" v-model="addingIsTl" class="rounded" />
                  TL (チームリーダー) として登録
                </label>
                <div class="flex gap-2">
                  <button type="button" @click="commitAddParticipant" class="flex-1 px-3 py-1.5 rounded-lg text-xs font-bold bg-blue-600 text-white hover:bg-blue-700">追加</button>
                  <button type="button" @click="cancelAddParticipant" class="px-3 py-1.5 rounded-lg text-xs font-bold bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600">×</button>
                </div>
              </div>
              <button
                v-else
                type="button"
                @click="beginAddParticipant(team.id)"
                class="w-full px-3 py-2 rounded-lg text-xs font-bold text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 border border-dashed border-slate-300 dark:border-slate-600 hover:border-blue-400 transition-colors"
              >
                + 参加者を追加 (残り {{ remainingSlotsOf(team.id) }} 名)
              </button>
            </div>

            <!-- 満員表示 (open 前後共通) -->
            <div
              v-else-if="membersOf(team.id).length === 4"
              class="px-4 py-2 text-[10px] font-mono text-slate-400 dark:text-slate-500 text-center border-t border-slate-100 dark:border-slate-700/60"
            >
              満員 4 / 4
            </div>
          </div>
        </div>

        <!-- 順位表 + 決勝生成ボタン (open 以降のみ表示) -->
        <section
          v-if="standings && standings.rows.length > 0"
          class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-md p-4 space-y-3"
        >
          <div class="flex items-center justify-between flex-wrap gap-2">
            <h2 class="text-sm font-bold text-slate-500">
              順位表 ({{ standings.prelimRecordedCount }} / {{ standings.prelimMatchupCount }} matchup 記録済)
            </h2>
            <button
              type="button"
              @click="refreshStandings"
              class="px-3 py-1 text-[10px] font-bold rounded-lg bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600"
            >🔄 再計算</button>
          </div>
          <table class="w-full text-sm">
            <thead>
              <tr class="text-[10px] font-mono text-slate-400 border-b border-slate-200 dark:border-slate-700">
                <th class="text-left py-1 px-2">順位</th>
                <th class="text-left py-1 px-2">チーム</th>
                <th class="text-right py-1 px-2">勝</th>
                <th class="text-right py-1 px-2">分</th>
                <th class="text-right py-1 px-2">負</th>
                <th class="text-right py-1 px-2">戦pt</th>
                <th class="text-right py-1 px-2 font-bold text-slate-700 dark:text-slate-200">勝点</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="row in standings.rows"
                :key="row.teamId"
                class="border-b border-slate-100 dark:border-slate-700/60"
                :class="row.rank <= 2 ? 'bg-amber-50/40 dark:bg-amber-900/10 font-bold' : ''"
              >
                <td class="py-1.5 px-2 tabular-nums">
                  <span v-if="row.rank === 1">🥇</span>
                  <span v-else-if="row.rank === 2">🥈</span>
                  <span v-else>{{ row.rank }}</span>
                </td>
                <td class="py-1.5 px-2 truncate font-bold" :class="teamColorClass(row.teamName)">{{ row.teamName }}</td>
                <td class="py-1.5 px-2 text-right tabular-nums text-emerald-600 dark:text-emerald-300">{{ row.wins }}</td>
                <td class="py-1.5 px-2 text-right tabular-nums text-slate-500">{{ row.draws }}</td>
                <td class="py-1.5 px-2 text-right tabular-nums text-rose-500 dark:text-rose-400">{{ row.losses }}</td>
                <td class="py-1.5 px-2 text-right tabular-nums">{{ row.songPoints }}</td>
                <td class="py-1.5 px-2 text-right tabular-nums font-bold">{{ row.matchupPoints }}</td>
              </tr>
            </tbody>
          </table>
          <!-- 決勝生成ボタン -->
          <div class="flex items-center justify-between gap-2 flex-wrap pt-2 border-t border-slate-100 dark:border-slate-700/40">
            <p class="text-[11px] text-slate-500">
              <span v-if="standings.finalsExists" class="text-amber-600 dark:text-amber-300 font-bold">🏆 決勝生成済</span>
              <span v-else-if="standings.allPrelimRecorded">予選全結果記録済。TOP2 で決勝を生成できます。</span>
              <span v-else>予選 {{ standings.prelimMatchupCount - standings.prelimRecordedCount }} 試合の結果記録待ち</span>
            </p>
            <button
              v-if="!standings.finalsExists"
              type="button"
              @click="handleGenerateFinals"
              :disabled="!standings.allPrelimRecorded"
              class="px-4 py-2 rounded-md text-xs font-bold transition-all"
              :class="standings.allPrelimRecorded
                ? 'bg-amber-500 hover:bg-amber-600 text-white'
                : 'bg-slate-300 dark:bg-slate-600 text-slate-500 cursor-not-allowed'"
            >
              🏆 決勝を生成
            </button>
          </div>
        </section>

        <!-- 途中経過マトリクス: 5×5 で各 matchup の row 視点の総合ポイントを表示 -->
        <section
          v-if="standings && currentCompetition.teams && currentCompetition.teams.length > 0"
          class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-md p-4 space-y-3"
        >
          <h2 class="text-sm font-bold text-slate-500">途中経過</h2>
          <p class="text-[11px] text-slate-500">
            セル「自軍戦pt ○/×/△ 相手戦pt」: ○=行チームが勝ち / ×=負け / △=引分。「?」 = 未記録、「-」 = 同チーム同士。合計列は勝ち点合計 (matchup 勝点のみ)。
          </p>
          <div class="overflow-x-auto">
            <table class="text-xs border-collapse">
              <thead>
                <tr>
                  <th class="py-1 px-2 text-[10px] font-mono text-slate-400"></th>
                  <th
                    v-for="colTeam in currentCompetition.teams"
                    :key="colTeam.id"
                    class="py-2 px-3 text-[10px] font-mono border-b border-slate-200 dark:border-slate-700 text-center min-w-[90px]"
                    :class="teamColorClass(colTeam.teamName)"
                  >
                    {{ colTeam.teamName }}
                  </th>
                  <th class="py-2 px-3 text-[10px] font-mono text-slate-700 dark:text-slate-200 font-bold border-b border-slate-200 dark:border-slate-700 text-center min-w-[80px]">
                    合計
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="rowTeam in currentCompetition.teams"
                  :key="rowTeam.id"
                  class="border-b border-slate-100 dark:border-slate-700/60"
                >
                  <th class="py-2 px-3 text-left text-xs font-bold whitespace-nowrap" :class="teamColorClass(rowTeam.teamName)">
                    {{ rowTeam.teamName }}
                  </th>
                  <td
                    v-for="colTeam in currentCompetition.teams"
                    :key="colTeam.id"
                    class="py-2 px-3 text-center tabular-nums"
                    :class="rowTeam.id === colTeam.id ? 'bg-slate-100 dark:bg-slate-900/40' : ''"
                  >
                    <template v-if="rowTeam.id === colTeam.id">
                      <span class="text-slate-400">-</span>
                    </template>
                    <template v-else-if="matrixCellOf(rowTeam.id, colTeam.id) === undefined">
                      <span class="text-slate-400">?</span>
                    </template>
                    <template v-else>
                      <span
                        class="font-bold whitespace-nowrap"
                        :class="(() => {
                          const c = matrixCellOf(rowTeam.id, colTeam.id);
                          if (!c) return '';
                          if (c.marker === '○') return 'text-emerald-600 dark:text-emerald-300';
                          if (c.marker === '×') return 'text-rose-500 dark:text-rose-400';
                          return 'text-amber-600 dark:text-amber-300';
                        })()"
                      >
                        {{ matrixCellOf(rowTeam.id, colTeam.id)?.rowPts }}<span class="mx-0.5">{{ matrixCellOf(rowTeam.id, colTeam.id)?.marker }}</span>{{ matrixCellOf(rowTeam.id, colTeam.id)?.colPts }}
                      </span>
                    </template>
                  </td>
                  <td class="py-2 px-3 text-center tabular-nums font-bold text-base bg-slate-50 dark:bg-slate-900/30">
                    {{ teamMatchupPoints(rowTeam.id) }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <!-- 起用クローズ日時 (JST): 手動ロックの代替。設定時刻を過ぎると TL 起用編集・プレイヤー自選曲提出が締切。 -->
        <section
          v-if="currentCompetition.status !== 'draft'"
          class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-md p-4 space-y-3"
        >
          <div class="flex items-center justify-between flex-wrap gap-2">
            <h2 class="text-sm font-bold text-slate-500">
              起用クローズ日時 (JST)
            </h2>
            <span
              class="text-[11px] font-bold px-2 py-0.5 rounded"
              :class="currentCompetition.lineupClosed
                ? 'bg-rose-100 text-rose-700 dark:bg-rose-900/40 dark:text-rose-300'
                : 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300'"
            >
              {{ currentCompetition.lineupClosed ? '🔒 起用クローズ済み' : '✏ 起用受付中' }}
            </span>
          </div>
          <p class="text-[11px] text-slate-500">
            設定した日時を過ぎると、<b>TL の起用編集 (選手の割り当て) のみ</b>が自動的に締め切られます (手動ロックは廃止)。
            プレイヤーの自選曲提出は締め切られません。日時はあなたの端末のローカル時刻 = JST 想定で扱われます。
          </p>
          <div class="flex items-center gap-2 flex-wrap">
            <input
              type="datetime-local"
              v-model="deadlineInput"
              :disabled="currentCompetition.status === 'finished'"
              class="flex-1 min-w-[200px] px-3 py-2 text-sm rounded-lg bg-slate-50 dark:bg-slate-900 border border-slate-300 dark:border-slate-600 outline-none focus:border-blue-400 disabled:opacity-50"
            />
            <button
              type="button"
              @click="handleSaveDeadline"
              :disabled="isSavingDeadline || currentCompetition.status === 'finished'"
              class="shrink-0 px-4 py-2 rounded-md text-xs font-bold bg-emerald-600 hover:bg-emerald-700 text-white disabled:bg-slate-300"
            >保存</button>
            <button
              v-if="currentCompetition.deadlineAt"
              type="button"
              @click="handleClearDeadline"
              :disabled="isSavingDeadline || currentCompetition.status === 'finished'"
              class="shrink-0 px-3 py-2 rounded-lg text-xs font-bold bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600 disabled:opacity-50"
            >締切解除</button>
          </div>
          <p v-if="currentCompetition.deadlineAt" class="text-[10px] font-mono text-slate-400">
            現在の設定: {{ new Date(currentCompetition.deadlineAt).toLocaleString('ja-JP') }}
          </p>
          <p v-else class="text-[10px] font-mono text-slate-400">
            未設定 (締め切らない)。日時を入れて「保存」すると有効になります。
          </p>
        </section>

        <!-- 対戦表: 全 30 試合に対する運営ジャンル指定 (open 以降のみ表示) -->
        <section
          v-if="currentCompetition.matchups && currentCompetition.matches && currentCompetition.matchups.length > 0"
          class="space-y-3"
        >
          <div class="flex items-center justify-between flex-wrap gap-2">
            <h2 class="text-sm font-bold text-slate-500">
              対戦表 (設定済み {{ configuredMatchups.length }} / 全 {{ currentCompetition.matchups.length }} 組)
            </h2>
            <button
              type="button"
              @click="handleRefreshRevealData"
              class="px-3 py-1 text-[10px] font-bold rounded-lg bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200"
              title="プレイヤーの提出状況を最新化します"
            >🔄 提出状況を再読込</button>
          </div>
          <p class="text-[11px] text-slate-500 leading-relaxed">
            下の「未設定の組み合わせ」から実施する対戦を 1 つずつ選んで設定します。設定した順に第 1・第 2 … 試合として並び、<b>設定済みの対戦だけがプレイヤー / TL に公開</b>されます。<br />
            各試合の運営指定ジャンルはセレクタから設定します。プレイヤーへのアサインは TL 専用 URL からチームごとに行います。両側の自選曲が揃った試合は「▶ REVEAL」で演出ページを開けます。
          </p>

          <!-- 未設定の組み合わせ: 運営が1つずつ選んで設定する -->
          <div
            v-if="unconfiguredMatchups.length > 0"
            class="bg-slate-50 dark:bg-slate-900/40 border border-dashed border-slate-300 dark:border-slate-700 rounded-md p-4 space-y-2"
          >
            <p class="text-[11px] font-bold text-slate-400">
              未設定の組み合わせ ({{ unconfiguredMatchups.length }} 組)
            </p>
            <div class="grid grid-cols-1 sm:grid-cols-2 gap-2">
              <div
                v-for="mu in unconfiguredMatchups"
                :key="mu.id"
                class="flex items-center justify-between gap-2 px-3 py-2 rounded-md bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700"
              >
                <p class="font-bold text-sm truncate">
                  <span :class="teamColorClassById(mu.teamAId)">{{ teamNameOf(mu.teamAId) }}</span>
                  <span class="text-slate-400 mx-1.5">vs</span>
                  <span :class="teamColorClassById(mu.teamBId)">{{ teamNameOf(mu.teamBId) }}</span>
                </p>
                <button
                  type="button"
                  @click="handleConfigureMatchup(mu.id, true)"
                  :disabled="currentCompetition.status === 'finished'"
                  class="shrink-0 px-3 py-1.5 rounded-lg text-[11px] font-bold bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50"
                >＋ この対戦を設定</button>
              </div>
            </div>
          </div>

          <p v-if="configuredMatchups.length === 0" class="text-[11px] text-slate-400 italic">
            まだ設定済みの対戦はありません。上の組み合わせから選んで設定してください。
          </p>

          <!-- 設定済み (実施順): 従来の対戦表 UI -->
          <div
            v-for="mu in configuredMatchups"
            :key="mu.id"
            class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-md overflow-hidden"
          >
            <!-- matchup ヘッダ + ラインアップ公開トグル -->
            <div class="px-4 py-3 border-b border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/60 space-y-2">
              <div class="flex items-center justify-between flex-wrap gap-2">
                <p class="font-bold text-sm">
                  <span :class="teamColorClassById(mu.teamAId)">{{ teamNameOf(mu.teamAId) }}</span>
                  <span class="text-slate-400 mx-2">vs</span>
                  <span :class="teamColorClassById(mu.teamBId)">{{ teamNameOf(mu.teamBId) }}</span>
                  <span v-if="mu.isFinals" class="ml-2 text-[10px] font-bold px-2 py-0.5 rounded bg-amber-500 text-white">
                    🏆 FINALS
                  </span>
                </p>
                <div class="flex items-center gap-2">
                  <p class="text-[10px] font-mono text-slate-400">
                    {{ mu.isFinals ? 'FINALS' : '予選第 ' + mu.matchupOrder + ' 試合' }}
                  </p>
                  <button
                    v-if="!mu.isFinals"
                    type="button"
                    @click="handleConfigureMatchup(mu.id, false)"
                    :disabled="currentCompetition.status === 'finished'"
                    class="px-2 py-0.5 rounded text-[10px] font-bold bg-slate-200 dark:bg-slate-700 text-slate-500 hover:bg-rose-100 hover:text-rose-600 dark:hover:bg-rose-900/40 dark:hover:text-rose-300 disabled:opacity-50"
                    title="この対戦を未設定に戻す"
                  >設定解除</button>
                </div>
              </div>
              <!-- ラインアップ公開ボタン群 -->
              <div class="flex items-center gap-2 flex-wrap text-[10px] font-mono">
                <span class="text-slate-400">起用公開:</span>
                <button
                  type="button"
                  @click="handlePublishLineup(mu.id, 'a', !mu.lineupPublishedA)"
                  class="px-2 py-1 rounded transition-colors"
                  :class="mu.lineupPublishedA
                    ? 'bg-emerald-500 text-white hover:bg-emerald-600'
                    : 'bg-slate-200 dark:bg-slate-700 text-slate-500 hover:bg-slate-300 dark:hover:bg-slate-600'"
                >
                  {{ teamNameOf(mu.teamAId) }} {{ mu.lineupPublishedA ? '✓ 公開中' : '未公開' }}
                </button>
                <button
                  type="button"
                  @click="handlePublishLineup(mu.id, 'b', !mu.lineupPublishedB)"
                  class="px-2 py-1 rounded transition-colors"
                  :class="mu.lineupPublishedB
                    ? 'bg-emerald-500 text-white hover:bg-emerald-600'
                    : 'bg-slate-200 dark:bg-slate-700 text-slate-500 hover:bg-slate-300 dark:hover:bg-slate-600'"
                >
                  {{ teamNameOf(mu.teamBId) }} {{ mu.lineupPublishedB ? '✓ 公開中' : '未公開' }}
                </button>
                <button
                  type="button"
                  @click="handlePublishLineup(mu.id, 'both', lineupPublishStateOf(mu) !== 'both')"
                  class="px-2 py-1 rounded bg-violet-500 text-white hover:bg-violet-600 ml-auto"
                  :title="lineupPublishStateOf(mu) === 'both' ? '両方解除' : '両方公開'"
                >
                  {{ lineupPublishStateOf(mu) === 'both' ? '両方解除' : '両方公開' }}
                </button>
              </div>
            </div>

            <!-- 3 試合 (vanguard → middle → captain) -->
            <ul class="divide-y divide-slate-100 dark:divide-slate-700/60">
              <li
                v-for="match in matchesForMatchup(mu.id)"
                :key="match.id"
                class="px-4 py-3 space-y-2"
              >
                <div class="grid grid-cols-1 sm:grid-cols-[120px_1fr_1fr_1fr] gap-3 items-center">
                  <!-- 戦表記 -->
                  <div>
                    <p class="font-bold text-sm">{{ KIND_LABEL[match.matchKind] }}</p>
                    <p class="text-[10px] font-mono text-slate-400">
                      Lv {{ match.matchKind === 'vanguard' ? '8-10' : match.matchKind === 'middle' ? '11' : '12' }}
                    </p>
                  </div>

                  <!-- A 側プレイヤー (起用は公開状態に関係なく管理者には常に表示) -->
                  <div class="text-xs">
                    <p class="text-[10px] font-mono text-slate-400">A 側 (<span :class="teamColorClassById(mu.teamAId)">{{ teamNameOf(mu.teamAId) }}</span>)</p>
                    <p class="font-bold truncate" :class="match.playerAId ? '' : 'italic text-slate-400'">
                      {{ participantNameOf(match.playerAId) }}
                    </p>
                    <!-- 自選曲 (選曲公開が未公開でも管理者には開示) -->
                    <p
                      class="text-[10px] mt-0.5 truncate"
                      :class="pickForSide(match.id, 'a') ? 'text-violet-600 dark:text-violet-300 font-bold' : 'text-slate-400 italic'"
                      :title="pickLabel(match.id, 'a')"
                    >🎵 {{ pickLabel(match.id, 'a') }}</p>
                    <!-- StrategyCard 発動予定 (TL が決定) -->
                    <span
                      v-if="match.strategyUsedA"
                      class="inline-block mt-0.5 text-[9px] font-bold px-1.5 py-0.5 rounded bg-fuchsia-600 text-white"
                    >⚡ 発動予定</span>
                  </div>

                  <!-- B 側プレイヤー (起用は公開状態に関係なく管理者には常に表示) -->
                  <div class="text-xs">
                    <p class="text-[10px] font-mono text-slate-400">B 側 (<span :class="teamColorClassById(mu.teamBId)">{{ teamNameOf(mu.teamBId) }}</span>)</p>
                    <p class="font-bold truncate" :class="match.playerBId ? '' : 'italic text-slate-400'">
                      {{ participantNameOf(match.playerBId) }}
                    </p>
                    <!-- 自選曲 (選曲公開が未公開でも管理者には開示) -->
                    <p
                      class="text-[10px] mt-0.5 truncate"
                      :class="pickForSide(match.id, 'b') ? 'text-violet-600 dark:text-violet-300 font-bold' : 'text-slate-400 italic'"
                      :title="pickLabel(match.id, 'b')"
                    >🎵 {{ pickLabel(match.id, 'b') }}</p>
                    <!-- StrategyCard 発動予定 (TL が決定) -->
                    <span
                      v-if="match.strategyUsedB"
                      class="inline-block mt-0.5 text-[9px] font-bold px-1.5 py-0.5 rounded bg-fuchsia-600 text-white"
                    >⚡ 発動予定</span>
                  </div>

                  <!-- ジャンル指定セレクタ -->
                  <div>
                    <p class="text-[10px] font-mono text-slate-400 mb-1">指定ジャンル</p>
                    <select
                      :value="match.requiredGenre ?? ''"
                      @change="handleGenreChange(match, ($event.target as HTMLSelectElement).value)"
                      :disabled="currentCompetition.status === 'finished'"
                      class="w-full px-2 py-1.5 rounded-lg text-sm bg-white dark:bg-slate-800 border outline-none focus:border-blue-400 disabled:opacity-50"
                      :class="genreSelectClass(match.requiredGenre)"
                    >
                      <option value="">未指定</option>
                      <option v-for="g in genresForKind(match.matchKind)" :key="g" :value="g">{{ g }}</option>
                    </select>
                  </div>
                </div>

                <!-- 起用ロックは大会全体の「起用クローズ日時」で自動制御 (上部セクション参照)。手動ロックは廃止。 -->

                <!-- 結果記録 UI (R-4: 曲管理番号 + スコア入力 → 勝敗自動表示) -->
                <div class="pt-1 border-t border-slate-100 dark:border-slate-700/40 text-[10px] font-mono">
                  <!-- 折り畳み: 編集中以外は 1 行サマリ -->
                  <template v-if="resultEditingMatchId !== match.id">
                    <div class="flex items-center gap-2 flex-wrap">
                      <span class="text-slate-400">結果:</span>
                      <span v-if="match.aSongsWon !== null && match.bSongsWon !== null" class="text-slate-300 dark:text-slate-200">
                        <span class="font-bold tabular-nums">{{ match.aSongsWon }} - {{ match.bSongsWon }}</span>
                        ({{ match.aSongsWon > match.bSongsWon ? 'A 勝ち' : match.aSongsWon < match.bSongsWon ? 'B 勝ち' : '引分' }})
                      </span>
                      <span v-else class="text-slate-400 italic">未記録</span>
                      <span v-if="match.song1ScoreA !== null && match.song1ScoreB !== null" class="text-slate-500">
                        | 1曲目: {{ match.song1ScoreA }} - {{ match.song1ScoreB }}
                      </span>
                      <span v-if="match.song2ScoreA !== null && match.song2ScoreB !== null" class="text-slate-500">
                        | 2曲目: {{ match.song2ScoreA }} - {{ match.song2ScoreB }}
                      </span>
                      <button type="button" @click="beginResultEdit(match)" class="ml-auto px-2 py-1 rounded bg-blue-600 text-white hover:bg-blue-700">
                        {{ match.aSongsWon !== null ? '編集' : '記録' }}
                      </button>
                      <button v-if="match.aSongsWon !== null" type="button" @click="handleClearResult(match.id)" class="px-2 py-1 rounded bg-rose-50 text-rose-600 hover:bg-rose-100 dark:bg-rose-900/30 dark:text-rose-300">クリア</button>
                    </div>
                  </template>
                  <!-- 編集モード: 2 曲 × (管理番号 + A スコア + B スコア) -->
                  <template v-else>
                    <p class="text-slate-400 mb-2">スコア記録 (曲名 = A/B 自選曲もしくは Strategy 抽選曲を自動入力)</p>
                    <div class="space-y-2">
                      <!-- Song 1 (A 側演奏曲) -->
                      <div class="grid grid-cols-[60px_60px_1fr_70px_70px] gap-2 items-center">
                        <span class="text-slate-500">1 曲目<br /><span class="text-[9px] text-slate-600">A 演奏</span></span>
                        <input
                          v-model.number="resultDraft.song1StrategyId"
                          type="number"
                          min="0"
                          placeholder="#"
                          class="px-2 py-1 rounded bg-slate-50 dark:bg-slate-900 border border-slate-300 dark:border-slate-600 text-xs tabular-nums"
                        />
                        <input
                          v-model="resultDraft.song1Title"
                          type="text"
                          placeholder="曲名"
                          class="px-2 py-1 rounded bg-slate-50 dark:bg-slate-900 border border-slate-300 dark:border-slate-600 text-xs truncate"
                        />
                        <input
                          v-model.number="resultDraft.song1ScoreA"
                          type="number"
                          min="0"
                          placeholder="A スコア"
                          class="px-2 py-1 rounded bg-slate-50 dark:bg-slate-900 border border-slate-300 dark:border-slate-600 text-xs tabular-nums"
                        />
                        <input
                          v-model.number="resultDraft.song1ScoreB"
                          type="number"
                          min="0"
                          placeholder="B スコア"
                          class="px-2 py-1 rounded bg-slate-50 dark:bg-slate-900 border border-slate-300 dark:border-slate-600 text-xs tabular-nums"
                        />
                      </div>
                      <!-- Song 2 (B 側演奏曲) -->
                      <div class="grid grid-cols-[60px_60px_1fr_70px_70px] gap-2 items-center">
                        <span class="text-slate-500">2 曲目<br /><span class="text-[9px] text-slate-600">B 演奏</span></span>
                        <input
                          v-model.number="resultDraft.song2StrategyId"
                          type="number"
                          min="0"
                          placeholder="#"
                          class="px-2 py-1 rounded bg-slate-50 dark:bg-slate-900 border border-slate-300 dark:border-slate-600 text-xs tabular-nums"
                        />
                        <input
                          v-model="resultDraft.song2Title"
                          type="text"
                          placeholder="曲名"
                          class="px-2 py-1 rounded bg-slate-50 dark:bg-slate-900 border border-slate-300 dark:border-slate-600 text-xs truncate"
                        />
                        <input
                          v-model.number="resultDraft.song2ScoreA"
                          type="number"
                          min="0"
                          placeholder="A スコア"
                          class="px-2 py-1 rounded bg-slate-50 dark:bg-slate-900 border border-slate-300 dark:border-slate-600 text-xs tabular-nums"
                        />
                        <input
                          v-model.number="resultDraft.song2ScoreB"
                          type="number"
                          min="0"
                          placeholder="B スコア"
                          class="px-2 py-1 rounded bg-slate-50 dark:bg-slate-900 border border-slate-300 dark:border-slate-600 text-xs tabular-nums"
                        />
                      </div>
                    </div>
                    <!-- 勝敗プレビュー -->
                    <div class="mt-2 flex items-center gap-2 flex-wrap">
                      <span class="text-slate-400">判定:</span>
                      <span class="font-bold tabular-nums text-sm">
                        A {{ draftWinnerPreview.a }} - {{ draftWinnerPreview.b }} B
                      </span>
                      <span
                        class="text-[10px] font-bold px-2 py-0.5 rounded"
                        :class="draftWinnerPreview.verdict === 'A 勝ち'
                          ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300'
                          : draftWinnerPreview.verdict === 'B 勝ち'
                            ? 'bg-rose-100 text-rose-700 dark:bg-rose-900/40 dark:text-rose-300'
                            : draftWinnerPreview.verdict === '引分'
                              ? 'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300'
                              : 'bg-slate-200 text-slate-500 dark:bg-slate-700 dark:text-slate-400'"
                      >{{ draftWinnerPreview.verdict }}</span>
                      <button type="button" @click="handleSaveResult(match.id)" class="ml-auto px-3 py-1 rounded bg-emerald-500 text-white hover:bg-emerald-600 font-bold">保存</button>
                      <button type="button" @click="cancelResultEdit" class="px-2 py-1 rounded bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600">×</button>
                    </div>
                  </template>
                </div>

                <!-- REVEAL 再生ボタン (両側自選曲が揃っている試合だけ有効) -->
                <div class="flex items-center gap-2 flex-wrap text-[10px] font-mono pt-1 border-t border-slate-100 dark:border-slate-700/40">
                  <span class="text-slate-400">Reveal:</span>
                  <span v-if="revealLoadState === 'loading'" class="text-slate-400 italic">提出状況読込中…</span>
                  <span v-else-if="revealLoadState === 'error'" class="text-rose-500 italic">
                    読込失敗 ({{ revealLoadError }}) — 🔄 ボタンで再試行
                  </span>
                  <span v-else-if="!canReveal(match.id)" class="text-slate-400 italic">
                    {{ revealMatchOf(match.id) === null ? '試合データ未取得' : '両側の自選曲提出待ち' }}
                  </span>
                  <button
                    v-else
                    type="button"
                    @click="handleOpenReveal(match.id)"
                    class="ml-auto px-3 py-1 rounded bg-blue-700 hover:bg-blue-800 dark:bg-blue-600 dark:hover:bg-blue-500 text-white font-bold transition-all"
                    title="新規タブで Song Reveal を開く"
                  >
                    ▶ REVEAL を再生
                  </button>
                </div>

                <!-- 自選曲公開トグル群 (試合直前に公開する想定) -->
                <div class="flex items-center gap-2 flex-wrap text-[10px] font-mono">
                  <span class="text-slate-400">選曲公開:</span>
                  <button
                    type="button"
                    @click="handlePublishPick(match.id, 'a', !match.pickPublishedA)"
                    class="px-2 py-1 rounded transition-colors"
                    :class="match.pickPublishedA
                      ? 'bg-fuchsia-500 text-white hover:bg-fuchsia-600'
                      : 'bg-slate-200 dark:bg-slate-700 text-slate-500 hover:bg-slate-300 dark:hover:bg-slate-600'"
                  >
                    A 側 {{ match.pickPublishedA ? '✓ 公開中' : '未公開' }}
                  </button>
                  <button
                    type="button"
                    @click="handlePublishPick(match.id, 'b', !match.pickPublishedB)"
                    class="px-2 py-1 rounded transition-colors"
                    :class="match.pickPublishedB
                      ? 'bg-fuchsia-500 text-white hover:bg-fuchsia-600'
                      : 'bg-slate-200 dark:bg-slate-700 text-slate-500 hover:bg-slate-300 dark:hover:bg-slate-600'"
                  >
                    B 側 {{ match.pickPublishedB ? '✓ 公開中' : '未公開' }}
                  </button>
                  <button
                    type="button"
                    @click="handlePublishPick(match.id, 'both', !(match.pickPublishedA && match.pickPublishedB))"
                    class="px-2 py-1 rounded bg-violet-500 text-white hover:bg-violet-600 ml-auto"
                  >
                    {{ match.pickPublishedA && match.pickPublishedB ? '両方解除' : '両方公開' }}
                  </button>
                </div>
              </li>
            </ul>
          </div>
        </section>

        <!-- 観戦用 対戦表 URL: open 以降。ログイン不要で対戦表を一覧公開する。 -->
        <section
          v-if="currentCompetition.status !== 'draft'"
          class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-md p-4 space-y-3"
        >
          <div class="flex items-center justify-between flex-wrap gap-2">
            <h2 class="text-sm font-bold text-slate-500">
              観戦用 対戦表 URL
            </h2>
            <p class="text-[11px] text-slate-500">
              一般の観戦客がログイン不要で対戦表を閲覧できる公開 URL。
            </p>
          </div>
          <div v-if="spectatorUrl" class="flex items-center gap-2">
            <input
              :value="spectatorUrl"
              readonly
              class="flex-1 min-w-0 px-3 py-2 text-xs font-mono rounded-lg bg-slate-50 dark:bg-slate-900 border border-slate-300 dark:border-slate-600"
            />
            <button
              type="button"
              @click="copyToClipboard(spectatorUrl, '観戦用 URL')"
              class="shrink-0 px-3 py-2 rounded-lg text-xs font-bold bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600"
            >コピー</button>
            <button
              type="button"
              @click="handleGenerateSpectatorToken"
              :disabled="isGeneratingSpectatorToken"
              class="shrink-0 px-3 py-2 rounded-lg text-xs font-bold bg-amber-100 dark:bg-amber-900/40 hover:bg-amber-200 dark:hover:bg-amber-900/60 text-amber-700 dark:text-amber-300"
            >再発行</button>
          </div>
          <div v-else class="flex items-center justify-between gap-2">
            <p class="text-[11px] text-slate-500">まだ発行されていません。</p>
            <button
              type="button"
              @click="handleGenerateSpectatorToken"
              :disabled="isGeneratingSpectatorToken"
              class="px-4 py-2 rounded-md text-xs font-bold bg-emerald-600 hover:bg-emerald-700 text-white"
            >▶ 観戦用 URL を発行</button>
          </div>
          <p class="text-[10px] text-slate-400">
            公開されるのは「<span class="font-bold">起用公開</span>済みのラインアップ・指定ジャンル・記録済みの結果」のみ。
            未公開の起用や自選曲は伏せられます。誤って共有した場合は「再発行」で旧 URL を無効化できます。
          </p>
        </section>

        <!-- 運営チャット: TL からの問い合わせ受信・返信 (open 以降) -->
        <section
          v-if="currentCompetition.status !== 'draft'"
          class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-md p-4 space-y-3"
        >
          <div class="flex items-center justify-between flex-wrap gap-2">
            <h2 class="text-sm font-bold text-slate-500 flex items-center gap-2">
              運営チャット
              <span
                v-if="totalChatUnread > 0"
                class="text-[10px] font-bold px-1.5 py-0.5 rounded bg-rose-500 text-white tracking-normal"
              >未読 {{ totalChatUnread }}</span>
            </h2>
            <button
              type="button"
              @click="loadChatThreads"
              class="px-3 py-1 text-[10px] font-bold rounded-lg bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600"
            >再読込</button>
          </div>
          <p class="text-[11px] text-slate-500">
            各チームの TL から届いたメッセージに返信できます (TL 送信時はあなたのメールにも通知が届きます)。
          </p>

          <div class="grid grid-cols-1 sm:grid-cols-[180px_1fr] gap-3">
            <!-- チーム一覧 -->
            <div class="space-y-1">
              <button
                v-for="th in chatThreads"
                :key="th.teamId"
                type="button"
                @click="handleSelectChatTeam(th.teamId)"
                class="w-full flex items-center justify-between gap-2 px-3 py-2 rounded-lg text-left text-sm border transition-colors"
                :class="selectedChatTeamId === th.teamId
                  ? 'bg-blue-50 dark:bg-blue-900/30 border-blue-300 dark:border-blue-600'
                  : 'bg-slate-50 dark:bg-slate-900/40 border-slate-200 dark:border-slate-700 hover:bg-slate-100 dark:hover:bg-slate-700/60'"
              >
                <span class="font-bold truncate" :class="teamColorClass(th.teamName)">{{ th.teamName }}</span>
                <span class="flex items-center gap-1 shrink-0">
                  <span class="text-[10px] text-slate-400">{{ th.messages.length }}</span>
                  <span
                    v-if="th.unreadCount > 0"
                    class="text-[10px] font-bold px-1.5 py-0.5 rounded bg-rose-500 text-white"
                  >{{ th.unreadCount }}</span>
                </span>
              </button>
            </div>

            <!-- 選択スレッド -->
            <div class="flex flex-col rounded-md border border-slate-200 dark:border-slate-700 overflow-hidden min-h-[260px]">
              <template v-if="selectedThread">
                <div ref="chatListEl" class="flex-1 overflow-y-auto px-3 py-3 space-y-2 bg-slate-50 dark:bg-slate-900/40 max-h-[360px]">
                  <p v-if="selectedThread.messages.length === 0" class="text-center text-[11px] text-slate-400 italic py-8">
                    まだメッセージはありません。
                  </p>
                  <div
                    v-for="m in selectedThread.messages"
                    :key="m.id"
                    class="flex flex-col"
                    :class="m.sender === 'admin' ? 'items-end' : 'items-start'"
                  >
                    <span v-if="m.sender === 'tl'" class="text-[9px] font-bold text-blue-500 dark:text-blue-300 mb-0.5 px-1">TL</span>
                    <div
                      class="max-w-[85%] px-3 py-2 rounded-md text-[13px] leading-relaxed whitespace-pre-wrap break-words"
                      :class="m.sender === 'admin'
                        ? 'bg-indigo-600 text-white rounded-br-sm'
                        : 'bg-white dark:bg-slate-700 text-slate-800 dark:text-slate-100 border border-slate-200 dark:border-slate-600 rounded-bl-sm'"
                    >{{ m.body }}</div>
                    <span class="text-[9px] text-slate-400 mt-0.5 px-1">{{ formatChatTime(m.createdAt) }}</span>
                  </div>
                </div>
                <div class="p-2 border-t border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800">
                  <div class="flex items-end gap-2">
                    <textarea
                      v-model="chatReplyDraft"
                      @keydown="onChatReplyKeydown"
                      rows="1"
                      placeholder="返信を入力 (Enterで送信)"
                      class="flex-1 resize-none max-h-24 px-3 py-2 text-[13px] rounded-md bg-slate-50 dark:bg-slate-900 border border-slate-300 dark:border-slate-600 outline-none focus:border-blue-400"
                    ></textarea>
                    <button
                      type="button"
                      @click="handleSendChatReply"
                      :disabled="isSendingChatReply || !chatReplyDraft.trim()"
                      class="shrink-0 px-3 py-2 rounded-md text-xs font-bold bg-indigo-600 text-white hover:bg-indigo-700 disabled:bg-slate-300 dark:disabled:bg-slate-600 disabled:cursor-not-allowed"
                    >送信</button>
                  </div>
                </div>
              </template>
              <p v-else class="m-auto text-[11px] text-slate-400 italic px-4 py-8 text-center">
                左のチームを選ぶと会話が表示されます。
              </p>
            </div>
          </div>
        </section>
        </template>
        <!-- ────────── /team5 専用セクション群 ────────── -->

        <!-- ────────── individual4 専用セクション群 ────────── -->
        <template v-if="currentCompetition.format === 'individual4'">
          <!-- 参加者リスト (draft 中は追加 UI、open 以降は閲覧のみ) -->
          <section class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-md p-4 space-y-3">
            <div class="flex items-center justify-between flex-wrap gap-2">
              <h2 class="text-sm font-bold text-slate-500">
                参加者 ({{ individualParticipantCount }} 名)
              </h2>
              <p class="text-[11px] text-slate-500">
                <span v-if="currentCompetition.status === 'draft'">
                  参加者を 12 名または 16 名 ちょうど登録して open に進んでください。
                </span>
                <span v-else>
                  open 後の参加者編集は不可。誤公開時のみ招待 URL を再発行できます。
                </span>
              </p>
            </div>

            <ul class="divide-y divide-slate-100 dark:divide-slate-700/60">
              <li
                v-for="(p, idx) in (currentCompetition.participants ?? [])"
                :key="p.id"
                class="py-2 flex items-center gap-2"
              >
                <span class="w-6 text-[10px] font-mono text-slate-400 tabular-nums text-right">{{ idx + 1 }}</span>
                <div class="flex-1 min-w-0">
                  <div v-if="editingIndividualParticipantId === p.id" class="flex items-center gap-2">
                    <input
                      v-model="editingIndividualName"
                      type="text"
                      class="flex-1 px-2 py-1 rounded-lg text-sm bg-white dark:bg-slate-800 border border-slate-300 dark:border-slate-600 outline-none focus:border-blue-400"
                      @keydown.enter="commitEditIndividualParticipant(p)"
                      @keydown.esc="cancelEditIndividualParticipant"
                    />
                    <button type="button" @click="commitEditIndividualParticipant(p)" class="text-xs font-bold text-blue-600 dark:text-blue-400">保存</button>
                    <button type="button" @click="cancelEditIndividualParticipant" class="text-xs text-slate-500">×</button>
                  </div>
                  <div v-else class="flex items-center gap-2">
                    <p class="font-bold truncate">{{ p.displayName }}</p>
                    <button
                      v-if="currentCompetition.status === 'draft'"
                      type="button"
                      @click="beginEditIndividualParticipant(p)"
                      class="text-xs text-slate-400 hover:text-slate-600 dark:hover:text-slate-200"
                      title="表示名を編集"
                    >✎</button>
                  </div>
                  <p class="text-[10px] font-mono text-slate-400 truncate">{{ buildPlayerUrl(p.inviteToken) }}</p>
                </div>
                <button
                  type="button"
                  @click="copyToClipboard(buildPlayerUrl(p.inviteToken), '参加者 URL')"
                  class="shrink-0 px-2 py-1 rounded text-[10px] font-bold bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200"
                >URL</button>
                <button
                  type="button"
                  @click="handleRegenerateIndividualToken(p)"
                  class="shrink-0 px-2 py-1 rounded text-[10px] font-bold bg-amber-100 dark:bg-amber-900/40 hover:bg-amber-200 dark:hover:bg-amber-900/60 text-amber-700 dark:text-amber-300"
                  title="招待 URL を再発行"
                >再発行</button>
                <button
                  v-if="currentCompetition.status === 'draft'"
                  type="button"
                  @click="handleDeleteIndividualParticipant(p)"
                  class="shrink-0 px-2 py-1 rounded text-[10px] font-bold bg-rose-50 text-rose-600 hover:bg-rose-100 dark:bg-rose-900/30 dark:text-rose-300"
                  title="削除"
                >×</button>
              </li>
            </ul>

            <!-- 参加者追加フォーム (draft かつ 16 名未満) -->
            <div
              v-if="currentCompetition.status === 'draft' && individualParticipantCount < 16"
              class="flex items-center gap-2 pt-2 border-t border-slate-100 dark:border-slate-700/60"
            >
              <input
                v-model="addingIndividualName"
                type="text"
                placeholder="新規参加者の表示名"
                class="flex-1 px-3 py-1.5 text-sm rounded-lg bg-slate-50 dark:bg-slate-900 border border-slate-300 dark:border-slate-600 outline-none focus:border-blue-400"
                @keydown.enter="handleAddIndividualParticipant"
                :disabled="isAddingIndividual"
              />
              <button
                type="button"
                @click="handleAddIndividualParticipant"
                :disabled="isAddingIndividual || !addingIndividualName.trim()"
                class="px-4 py-1.5 rounded-lg text-xs font-bold bg-blue-600 text-white hover:bg-blue-700 disabled:bg-slate-300 dark:disabled:bg-slate-600 disabled:cursor-not-allowed"
              >+ 追加</button>
            </div>
          </section>

          <!-- 抽選番号モード: draft 中、参加者 12 / 16 名揃ったら表示 -->
          <section
            v-if="currentCompetition.status === 'draft' && canOpenIndividual"
            class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-md p-4 space-y-3"
          >
            <div class="flex items-center justify-between flex-wrap gap-2">
              <h2 class="text-sm font-bold text-slate-500">
                抽選番号モードで open
              </h2>
              <p class="text-[11px] text-slate-500">
                ヘッダの ▶ Open は参加者を自動配置。こちらは番号を先に入れて後で抽選結果を割り当てます。
              </p>
            </div>
            <p class="text-[11px] text-slate-500">
              1 行 1 試合、半角スペースかカンマで区切って 4 つの番号 (1〜{{ individualParticipantCount }}) を入力。
              番号 → 参加者の対応は open 後の「抽選結果割当」フォームから入力します。
            </p>
            <textarea
              v-model="numberModeText"
              rows="8"
              placeholder="1 2 3 4&#10;5 6 7 8&#10;9 10 11 12&#10;…"
              class="w-full px-3 py-2 text-sm font-mono rounded-lg bg-slate-50 dark:bg-slate-900 border border-slate-300 dark:border-slate-600 outline-none focus:border-blue-400"
              :disabled="isOpeningWithNumbers"
            />
            <div class="flex items-center justify-end gap-2">
              <button
                type="button"
                @click="handleOpenWithNumbers"
                :disabled="isOpeningWithNumbers || !numberModeText.trim()"
                class="px-4 py-2 rounded-md text-xs font-bold bg-blue-700 hover:bg-blue-800 dark:bg-blue-600 dark:hover:bg-blue-500 text-white disabled:bg-slate-300 disabled:cursor-not-allowed"
              >
                ▶ 番号枠で open
              </button>
            </div>
          </section>

          <!-- 抽選結果割当: open 後、未割当スロットがあるとき表示 -->
          <section
            v-if="currentCompetition.status !== 'draft' && numbersInUse.length > 0"
            class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-md p-4 space-y-3"
          >
            <div class="flex items-center justify-between flex-wrap gap-2">
              <h2 class="text-sm font-bold text-slate-500">
                抽選結果割当 (番号 → 参加者)
              </h2>
              <span
                v-if="hasUnassignedSlots"
                class="text-[11px] font-bold text-amber-600 dark:text-amber-400"
              >⚠ 未割当のスロットがあります</span>
              <span
                v-else
                class="text-[11px] font-bold text-emerald-600 dark:text-emerald-400"
              >✓ 全スロット割当済</span>
            </div>
            <div class="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 gap-2">
              <div
                v-for="num in numbersInUse"
                :key="num"
                class="flex items-center gap-2 text-sm"
              >
                <span class="w-10 text-right font-mono font-bold tabular-nums">{{ num }} =</span>
                <select
                  v-model="lotteryDraft[num]"
                  class="flex-1 min-w-0 px-2 py-1 rounded-lg bg-slate-50 dark:bg-slate-900 border border-slate-300 dark:border-slate-600 outline-none focus:border-blue-400"
                >
                  <option value="">(未選択)</option>
                  <option
                    v-for="p in (currentCompetition.participants ?? [])"
                    :key="p.id"
                    :value="p.id"
                  >{{ p.displayName }}</option>
                </select>
              </div>
            </div>
            <div class="flex items-center justify-end gap-2">
              <button
                type="button"
                @click="seedLotteryDraft"
                class="px-3 py-1.5 rounded-lg text-[11px] font-bold bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600"
              >現在の割当を再読込</button>
              <button
                type="button"
                @click="handleAssignLottery"
                :disabled="isAssigningLottery"
                class="px-4 py-2 rounded-md text-xs font-bold bg-blue-700 hover:bg-blue-800 dark:bg-blue-600 dark:hover:bg-blue-500 text-white disabled:bg-slate-300"
              >
                ✓ 適用
              </button>
            </div>
          </section>

          <!-- OBS ブラウザソース URL: open 以降 -->
          <section
            v-if="currentCompetition.status !== 'draft'"
            class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-md p-4 space-y-3"
          >
            <div class="flex items-center justify-between flex-wrap gap-2">
              <h2 class="text-sm font-bold text-slate-500">
                OBS ブラウザソース (順位表)
              </h2>
              <p class="text-[11px] text-slate-500">
                透過背景。OBS の「ブラウザ」ソースにこの URL を貼り付けてください。
              </p>
            </div>
            <div v-if="obsUrl" class="flex items-center gap-2">
              <input
                :value="obsUrl"
                readonly
                class="flex-1 min-w-0 px-3 py-2 text-xs font-mono rounded-lg bg-slate-50 dark:bg-slate-900 border border-slate-300 dark:border-slate-600"
              />
              <button
                type="button"
                @click="copyToClipboard(obsUrl, 'OBS URL')"
                class="shrink-0 px-3 py-2 rounded-lg text-xs font-bold bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600"
              >コピー</button>
              <button
                type="button"
                @click="handleGenerateObsToken"
                :disabled="isGeneratingObsToken"
                class="shrink-0 px-3 py-2 rounded-lg text-xs font-bold bg-amber-100 dark:bg-amber-900/40 hover:bg-amber-200 dark:hover:bg-amber-900/60 text-amber-700 dark:text-amber-300"
              >再発行</button>
            </div>
            <div v-else class="flex items-center justify-between gap-2">
              <p class="text-[11px] text-slate-500">まだ発行されていません。</p>
              <button
                type="button"
                @click="handleGenerateObsToken"
                :disabled="isGeneratingObsToken"
                class="px-4 py-2 rounded-md text-xs font-bold bg-emerald-600 hover:bg-emerald-700 text-white"
              >▶ OBS URL を発行</button>
            </div>
            <p v-if="obsUrl" class="text-[10px] text-slate-400">
              背景を半透明黒にしたい場合は URL 末尾に <code class="px-1 bg-slate-100 dark:bg-slate-800 rounded">?bg=dark</code>、
              更新頻度を変えたい場合は <code class="px-1 bg-slate-100 dark:bg-slate-800 rounded">?interval=3000</code> (ms) を付与。
            </p>
          </section>

          <!-- 順位表 (open 以降) -->
          <section
            v-if="individualStandings && individualStandings.rows.length > 0"
            class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-md p-4 space-y-3"
          >
            <div class="flex items-center justify-between flex-wrap gap-2">
              <h2 class="text-sm font-bold text-slate-500">
                順位表 (予選 {{ individualStandings.prelimRecordedCount }} / {{ individualStandings.prelimMatchCount }} 試合記録済)
              </h2>
              <button
                type="button"
                @click="refreshIndividualStandings"
                class="px-3 py-1 text-[10px] font-bold rounded-lg bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600"
              >🔄 再計算</button>
            </div>
            <div class="overflow-x-auto">
              <table class="w-full text-sm">
                <thead>
                  <tr class="text-[10px] font-mono text-slate-400 border-b border-slate-200 dark:border-slate-700">
                    <th class="text-left py-1 px-2">順位</th>
                    <th class="text-left py-1 px-2">参加者</th>
                    <th class="text-right py-1 px-2 font-bold text-slate-700 dark:text-slate-200">予選pt</th>
                    <th class="text-right py-1 px-2">1位</th>
                    <th class="text-right py-1 px-2">2位</th>
                    <th class="text-right py-1 px-2">3位</th>
                    <th class="text-right py-1 px-2">4位</th>
                    <th class="text-right py-1 px-2">決勝</th>
                    <th class="text-right py-1 px-2 font-bold text-slate-700 dark:text-slate-200">最終</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="row in individualStandings.rows"
                    :key="row.participantId"
                    class="border-b border-slate-100 dark:border-slate-700/60"
                    :class="prelimBucketRowClass(row.prelimRank)"
                  >
                    <td class="py-1.5 px-2 tabular-nums">{{ row.prelimRank }}</td>
                    <td class="py-1.5 px-2 truncate">{{ row.displayName }}</td>
                    <td class="py-1.5 px-2 text-right tabular-nums font-bold">{{ row.prelimPoints }}</td>
                    <td class="py-1.5 px-2 text-right tabular-nums text-amber-600 dark:text-amber-300">{{ row.first }}</td>
                    <td class="py-1.5 px-2 text-right tabular-nums">{{ row.second }}</td>
                    <td class="py-1.5 px-2 text-right tabular-nums">{{ row.third }}</td>
                    <td class="py-1.5 px-2 text-right tabular-nums text-rose-500 dark:text-rose-400">{{ row.fourth }}</td>
                    <td class="py-1.5 px-2 text-right tabular-nums text-slate-500">
                      <span v-if="row.finalsBucket">B{{ row.finalsBucket }}</span>
                      <span v-else>-</span>
                      <span v-if="row.finalsRank"> / {{ row.finalsRank }}位</span>
                    </td>
                    <td class="py-1.5 px-2 text-right tabular-nums font-bold">
                      <span v-if="row.finalRank">{{ row.finalRank }}</span>
                      <span v-else class="text-slate-400">-</span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <!-- 決勝生成ボタン -->
            <div class="flex items-center justify-between gap-2 flex-wrap pt-2 border-t border-slate-100 dark:border-slate-700/40">
              <p class="text-[11px] text-slate-500">
                <span v-if="individualStandings.allFinalsRecorded" class="text-amber-600 dark:text-amber-300 font-bold">🏆 決勝記録完了 — 最終順位確定</span>
                <span v-else-if="individualStandings.finalsExists">決勝生成済 ({{ individualStandings.finalsRecordedCount }} / {{ individualStandings.finalsMatchCount }} 試合記録済)</span>
                <span v-else-if="individualStandings.allPrelimRecorded">予選全試合記録済。決勝を生成できます。</span>
                <span v-else>予選 {{ individualStandings.prelimMatchCount - individualStandings.prelimRecordedCount }} 試合の結果記録待ち</span>
              </p>
              <button
                v-if="!individualStandings.finalsExists"
                type="button"
                @click="handleGenerateIndividualFinals"
                :disabled="!individualStandings.allPrelimRecorded"
                class="px-4 py-2 rounded-md text-xs font-bold transition-all"
                :class="individualStandings.allPrelimRecorded
                  ? 'bg-amber-500 hover:bg-amber-600 text-white'
                  : 'bg-slate-300 dark:bg-slate-600 text-slate-500 cursor-not-allowed'"
              >
                🏆 決勝を生成
              </button>
            </div>
          </section>

          <!-- 予選試合一覧 + 結果入力 (open 以降) -->
          <section
            v-if="individualPrelimMatches.length > 0"
            class="space-y-3"
          >
            <h2 class="text-sm font-bold text-slate-500">
              予選 ({{ individualPrelimMatches.length }} 試合)
            </h2>
            <p class="text-[11px] text-slate-500">
              IIDX ARENA モードと同じく、4 人が共通の 4 曲をプレイ。曲ごとに 1 位 2pt / 2 位 1pt / 3 位・4 位 0pt。
            </p>
            <div
              v-for="m in individualPrelimMatches"
              :key="m.id"
              class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-md overflow-x-auto"
            >
              <div class="px-4 py-2 border-b border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/60 flex items-center justify-between flex-wrap gap-2">
                <p class="font-bold text-sm">予選 第 {{ m.matchOrder }} 試合</p>
                <p class="text-[10px] font-mono text-slate-400">
                  <span v-if="m.resultRecordedAt" class="text-emerald-600 dark:text-emerald-300">記録済 {{ new Date(m.resultRecordedAt).toLocaleString() }}</span>
                  <span v-else class="italic">未記録</span>
                </p>
              </div>

              <!-- 表示モード: 4x4 グリッド (プレイヤー × 4 曲) -->
              <template v-if="editingIndividualMatchId !== m.id">
                <table class="w-full text-xs min-w-[640px]">
                  <thead>
                    <tr class="bg-slate-50 dark:bg-slate-900/40 text-[10px] font-mono text-slate-400">
                      <th class="px-3 py-2 text-left">プレイヤー</th>
                      <th class="px-3 py-2 text-center">
                        <span class="block">曲1</span>
                        <span class="block normal-case text-slate-500 dark:text-slate-300 font-bold truncate max-w-[120px] mx-auto">{{ m.song1Title || '-' }}</span>
                      </th>
                      <th class="px-3 py-2 text-center">
                        <span class="block">曲2</span>
                        <span class="block normal-case text-slate-500 dark:text-slate-300 font-bold truncate max-w-[120px] mx-auto">{{ m.song2Title || '-' }}</span>
                      </th>
                      <th class="px-3 py-2 text-center">
                        <span class="block">曲3</span>
                        <span class="block normal-case text-slate-500 dark:text-slate-300 font-bold truncate max-w-[120px] mx-auto">{{ m.song3Title || '-' }}</span>
                      </th>
                      <th class="px-3 py-2 text-center">
                        <span class="block">曲4</span>
                        <span class="block normal-case text-slate-500 dark:text-slate-300 font-bold truncate max-w-[120px] mx-auto">{{ m.song4Title || '-' }}</span>
                      </th>
                      <th class="px-3 py-2 text-center text-slate-700 dark:text-slate-200 font-bold">総合順位</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="s in m.slots" :key="s.id" class="border-t border-slate-100 dark:border-slate-700/50">
                      <td class="px-3 py-2">
                        <span class="text-[10px] font-mono text-slate-400 mr-1">P{{ s.slotPosition }}</span>
                        <span class="font-bold">{{ s.participantName }}</span>
                      </td>
                      <td v-for="songIdx in [1,2,3,4]" :key="songIdx" class="px-3 py-2 text-center">
                        <span
                          v-if="(s as any)[`rank${songIdx}`]"
                          class="inline-block px-2 py-1 rounded text-xs font-bold tabular-nums"
                          :class="(s as any)[`rank${songIdx}`] === 1
                            ? 'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300'
                            : (s as any)[`rank${songIdx}`] === 2
                              ? 'bg-slate-200 text-slate-700 dark:bg-slate-600 dark:text-slate-100'
                              : (s as any)[`rank${songIdx}`] === 3
                                ? 'bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-400'
                                : 'bg-rose-50 text-rose-500 dark:bg-rose-900/30 dark:text-rose-300'"
                        >
                          {{ (s as any)[`points${songIdx}`] }}pt
                        </span>
                        <span v-else class="text-slate-300 italic text-xs">-</span>
                      </td>
                      <td class="px-3 py-2 text-center">
                        <span
                          v-if="overallMatchRank(s, m.slots) !== null"
                          class="inline-block px-2 py-1 rounded text-xs font-bold tabular-nums"
                          :class="overallMatchRank(s, m.slots) === 1
                            ? 'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300'
                            : overallMatchRank(s, m.slots) === 2
                              ? 'bg-slate-200 text-slate-700 dark:bg-slate-600 dark:text-slate-100'
                              : overallMatchRank(s, m.slots) === 3
                                ? 'bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-400'
                                : 'bg-rose-50 text-rose-500 dark:bg-rose-900/30 dark:text-rose-300'"
                        >
                          {{ overallMatchRank(s, m.slots) }}位 ({{ s.totalPoints }}pt)
                        </span>
                        <span v-else class="text-slate-300 italic text-xs">-</span>
                      </td>
                    </tr>
                  </tbody>
                </table>
                <div class="px-4 py-2 border-t border-slate-100 dark:border-slate-700/40 flex gap-2 justify-end">
                  <button type="button" @click="beginEditIndividualResult(m)" class="px-3 py-1 text-xs font-bold rounded-lg bg-blue-600 text-white hover:bg-blue-700">
                    {{ m.resultRecordedAt ? '編集' : '記録' }}
                  </button>
                  <button v-if="m.resultRecordedAt" type="button" @click="handleClearIndividualResult(m.id)" class="px-3 py-1 text-xs font-bold rounded-lg bg-rose-50 text-rose-600 hover:bg-rose-100 dark:bg-rose-900/30 dark:text-rose-300">クリア</button>
                </div>
              </template>

              <!-- 編集モード: 4 曲 (GUI 選択) + 4×4 順位ボタン -->
              <template v-else>
                <div class="px-4 py-3 space-y-3">
                  <p class="text-[10px] font-mono text-slate-400">
                    順位記録 (4 曲 × 4 人) — セルをクリックすると 1位→2位→3位→4位→未選択 でサイクル
                  </p>
                  <table class="w-full text-xs min-w-[700px]">
                    <thead>
                      <tr class="bg-slate-50 dark:bg-slate-900/40 text-[10px] font-mono text-slate-400">
                        <th class="px-2 py-2 text-left">プレイヤー</th>
                        <th v-for="songIdx in [1,2,3,4]" :key="songIdx" class="px-2 py-2 text-center min-w-[150px]">
                          <button
                            type="button"
                            @click="openSongPicker(songIdx as 1 | 2 | 3 | 4)"
                            class="w-full px-2 py-1 rounded font-bold normal-case truncate transition-colors"
                            :class="draftSongTitle(songIdx as 1 | 2 | 3 | 4)
                              ? 'bg-blue-100 dark:bg-blue-900/40 text-blue-700 dark:text-blue-200 hover:bg-blue-200 dark:hover:bg-blue-900/60'
                              : 'bg-white dark:bg-slate-800 border border-dashed border-slate-300 dark:border-slate-600 text-slate-400 hover:border-blue-400'"
                            :title="draftSongTitle(songIdx as 1 | 2 | 3 | 4) || `曲${songIdx} を選択`"
                          >
                            {{ draftSongTitle(songIdx as 1 | 2 | 3 | 4) || `🎵 曲${songIdx} を選択` }}
                          </button>
                        </th>
                        <th class="px-2 py-2 text-center text-slate-700 dark:text-slate-200 font-bold">総合順位</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="(draftSlot, idx) in individualResultDraft.slots" :key="draftSlot.slotPosition" class="border-t border-slate-100 dark:border-slate-700/50">
                        <td class="px-2 py-2">
                          <span class="text-[10px] font-mono text-slate-400 mr-1">P{{ draftSlot.slotPosition }}</span>
                          <span class="font-bold">{{ m.slots[idx]?.participantName }}</span>
                        </td>
                        <td v-for="songIdx in [1,2,3,4]" :key="songIdx" class="px-2 py-2 text-center">
                          <button
                            type="button"
                            @click="bumpDraftRank(idx, songIdx)"
                            class="w-full py-2 rounded font-bold text-sm transition-colors"
                            :class="(() => {
                              const r = draftIndividualRanks[idx]?.ranks[songIdx - 1];
                              if (r === 1) return 'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300 hover:bg-amber-200 dark:hover:bg-amber-900/60';
                              if (r === 2) return 'bg-slate-200 text-slate-700 dark:bg-slate-600 dark:text-slate-100 hover:bg-slate-300 dark:hover:bg-slate-500';
                              if (r === 3) return 'bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-400 hover:bg-slate-200 dark:hover:bg-slate-700';
                              if (r === 4) return 'bg-rose-50 text-rose-500 dark:bg-rose-900/30 dark:text-rose-300 hover:bg-rose-100 dark:hover:bg-rose-900/50';
                              return 'bg-slate-50 dark:bg-slate-900/60 text-slate-300 dark:text-slate-600 hover:bg-slate-100 dark:hover:bg-slate-800 border border-dashed border-slate-300 dark:border-slate-600';
                            })()"
                          >
                            <span v-if="draftIndividualRanks[idx]?.ranks[songIdx - 1]" class="block">
                              {{ draftIndividualRanks[idx]?.ranks[songIdx - 1] }}位
                            </span>
                            <span v-else class="block text-[11px] italic">クリック</span>
                            <span
                              v-if="draftIndividualRanks[idx]?.points[songIdx - 1] !== null"
                              class="block text-[10px] font-bold opacity-80"
                            >
                              {{ draftIndividualRanks[idx]?.points[songIdx - 1] }}pt
                            </span>
                          </button>
                        </td>
                        <td class="px-2 py-2 text-center">
                          <span
                            v-if="draftOverallRank(idx) !== null"
                            class="inline-block px-2 py-1 rounded text-xs font-bold tabular-nums"
                            :class="(() => {
                              const ovr = draftOverallRank(idx);
                              if (ovr === 1) return 'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300';
                              if (ovr === 2) return 'bg-slate-200 text-slate-700 dark:bg-slate-600 dark:text-slate-100';
                              if (ovr === 3) return 'bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-400';
                              return 'bg-rose-50 text-rose-500 dark:bg-rose-900/30 dark:text-rose-300';
                            })()"
                          >
                            {{ draftOverallRank(idx) }}位 ({{ draftIndividualRanks[idx]?.total }}pt)
                          </span>
                          <span v-else class="text-slate-300 italic text-xs">-</span>
                        </td>
                      </tr>
                    </tbody>
                  </table>
                  <div class="flex gap-2 justify-end pt-2 border-t border-slate-100 dark:border-slate-700/40">
                    <button type="button" @click="handleSaveIndividualResult(m.id)" class="px-3 py-1 text-xs font-bold rounded-lg bg-emerald-500 text-white hover:bg-emerald-600">保存</button>
                    <button type="button" @click="cancelEditIndividualResult" class="px-3 py-1 text-xs font-bold rounded-lg bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600">×</button>
                  </div>
                </div>
              </template>
            </div>
          </section>

          <!-- 決勝試合一覧 + 結果入力 (生成後) -->
          <section
            v-if="individualFinalsMatches.length > 0"
            class="space-y-3"
          >
            <h2 class="text-sm font-bold text-slate-500">
              🏆 決勝 ({{ individualFinalsMatches.length }} 試合)
            </h2>
            <p class="text-[11px] text-slate-500">
              バケット内総合ポイントが多い順 = 全体 (バケット-1)×4+順位。同点はタイ扱い。
            </p>
            <div
              v-for="m in individualFinalsMatches"
              :key="m.id"
              class="bg-white dark:bg-slate-800 border border-amber-300 dark:border-amber-700 rounded-md overflow-x-auto"
            >
              <div class="px-4 py-2 border-b border-amber-200 dark:border-amber-700/60 bg-amber-50 dark:bg-amber-900/20 flex items-center justify-between flex-wrap gap-2">
                <p class="font-bold text-sm">
                  バケット {{ m.finalsBucket }}: 全体 {{ (m.finalsBucket! - 1) * 4 + 1 }} 〜 {{ (m.finalsBucket! - 1) * 4 + 4 }} 位
                </p>
                <p class="text-[10px] font-mono text-slate-400">
                  <span v-if="m.resultRecordedAt" class="text-emerald-600 dark:text-emerald-300">記録済 {{ new Date(m.resultRecordedAt).toLocaleString() }}</span>
                  <span v-else class="italic">未記録</span>
                </p>
              </div>
              <template v-if="editingIndividualMatchId !== m.id">
                <table class="w-full text-xs min-w-[640px]">
                  <thead>
                    <tr class="bg-slate-50 dark:bg-slate-900/40 text-[10px] font-mono text-slate-400">
                      <th class="px-3 py-2 text-left">プレイヤー</th>
                      <th class="px-3 py-2 text-center">
                        <span class="block">曲1</span>
                        <span class="block normal-case text-slate-500 dark:text-slate-300 font-bold truncate max-w-[120px] mx-auto">{{ m.song1Title || '-' }}</span>
                      </th>
                      <th class="px-3 py-2 text-center">
                        <span class="block">曲2</span>
                        <span class="block normal-case text-slate-500 dark:text-slate-300 font-bold truncate max-w-[120px] mx-auto">{{ m.song2Title || '-' }}</span>
                      </th>
                      <th class="px-3 py-2 text-center">
                        <span class="block">曲3</span>
                        <span class="block normal-case text-slate-500 dark:text-slate-300 font-bold truncate max-w-[120px] mx-auto">{{ m.song3Title || '-' }}</span>
                      </th>
                      <th class="px-3 py-2 text-center">
                        <span class="block">曲4</span>
                        <span class="block normal-case text-slate-500 dark:text-slate-300 font-bold truncate max-w-[120px] mx-auto">{{ m.song4Title || '-' }}</span>
                      </th>
                      <th class="px-3 py-2 text-center text-slate-700 dark:text-slate-200 font-bold">総合順位</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="s in m.slots" :key="s.id" class="border-t border-slate-100 dark:border-slate-700/50">
                      <td class="px-3 py-2">
                        <span class="text-[10px] font-mono text-slate-400 mr-1">P{{ s.slotPosition }}</span>
                        <span class="font-bold">{{ s.participantName }}</span>
                      </td>
                      <td v-for="songIdx in [1,2,3,4]" :key="songIdx" class="px-3 py-2 text-center">
                        <span
                          v-if="(s as any)[`rank${songIdx}`]"
                          class="inline-block px-2 py-1 rounded text-xs font-bold tabular-nums"
                          :class="(s as any)[`rank${songIdx}`] === 1
                            ? 'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300'
                            : (s as any)[`rank${songIdx}`] === 2
                              ? 'bg-slate-200 text-slate-700 dark:bg-slate-600 dark:text-slate-100'
                              : (s as any)[`rank${songIdx}`] === 3
                                ? 'bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-400'
                                : 'bg-rose-50 text-rose-500 dark:bg-rose-900/30 dark:text-rose-300'"
                        >
                          {{ (s as any)[`points${songIdx}`] }}pt
                        </span>
                        <span v-else class="text-slate-300 italic text-xs">-</span>
                      </td>
                      <td class="px-3 py-2 text-center">
                        <span
                          v-if="overallMatchRank(s, m.slots) !== null"
                          class="inline-block px-2 py-1 rounded text-xs font-bold tabular-nums"
                          :class="overallMatchRank(s, m.slots) === 1
                            ? 'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300'
                            : overallMatchRank(s, m.slots) === 2
                              ? 'bg-slate-200 text-slate-700 dark:bg-slate-600 dark:text-slate-100'
                              : overallMatchRank(s, m.slots) === 3
                                ? 'bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-400'
                                : 'bg-rose-50 text-rose-500 dark:bg-rose-900/30 dark:text-rose-300'"
                        >
                          {{ overallMatchRank(s, m.slots) }}位 ({{ s.totalPoints }}pt)
                        </span>
                        <span v-else class="text-slate-300 italic text-xs">-</span>
                      </td>
                    </tr>
                  </tbody>
                </table>
                <div class="px-4 py-2 border-t border-slate-100 dark:border-slate-700/40 flex gap-2 justify-end">
                  <button type="button" @click="beginEditIndividualResult(m)" class="px-3 py-1 text-xs font-bold rounded-lg bg-blue-600 text-white hover:bg-blue-700">
                    {{ m.resultRecordedAt ? '編集' : '記録' }}
                  </button>
                  <button v-if="m.resultRecordedAt" type="button" @click="handleClearIndividualResult(m.id)" class="px-3 py-1 text-xs font-bold rounded-lg bg-rose-50 text-rose-600 hover:bg-rose-100 dark:bg-rose-900/30 dark:text-rose-300">クリア</button>
                </div>
              </template>
              <template v-else>
                <div class="px-4 py-3 space-y-3">
                  <p class="text-[10px] font-mono text-slate-400">
                    順位記録 (4 曲 × 4 人) — セルをクリックすると 1位→2位→3位→4位→未選択 でサイクル
                  </p>
                  <table class="w-full text-xs min-w-[700px]">
                    <thead>
                      <tr class="bg-slate-50 dark:bg-slate-900/40 text-[10px] font-mono text-slate-400">
                        <th class="px-2 py-2 text-left">プレイヤー</th>
                        <th v-for="songIdx in [1,2,3,4]" :key="songIdx" class="px-2 py-2 text-center min-w-[150px]">
                          <button
                            type="button"
                            @click="openSongPicker(songIdx as 1 | 2 | 3 | 4)"
                            class="w-full px-2 py-1 rounded font-bold normal-case truncate transition-colors"
                            :class="draftSongTitle(songIdx as 1 | 2 | 3 | 4)
                              ? 'bg-blue-100 dark:bg-blue-900/40 text-blue-700 dark:text-blue-200 hover:bg-blue-200 dark:hover:bg-blue-900/60'
                              : 'bg-white dark:bg-slate-800 border border-dashed border-slate-300 dark:border-slate-600 text-slate-400 hover:border-blue-400'"
                            :title="draftSongTitle(songIdx as 1 | 2 | 3 | 4) || `曲${songIdx} を選択`"
                          >
                            {{ draftSongTitle(songIdx as 1 | 2 | 3 | 4) || `🎵 曲${songIdx} を選択` }}
                          </button>
                        </th>
                        <th class="px-2 py-2 text-center text-slate-700 dark:text-slate-200 font-bold">総合順位</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="(draftSlot, idx) in individualResultDraft.slots" :key="draftSlot.slotPosition" class="border-t border-slate-100 dark:border-slate-700/50">
                        <td class="px-2 py-2">
                          <span class="text-[10px] font-mono text-slate-400 mr-1">P{{ draftSlot.slotPosition }}</span>
                          <span class="font-bold">{{ m.slots[idx]?.participantName }}</span>
                        </td>
                        <td v-for="songIdx in [1,2,3,4]" :key="songIdx" class="px-2 py-2 text-center">
                          <button
                            type="button"
                            @click="bumpDraftRank(idx, songIdx)"
                            class="w-full py-2 rounded font-bold text-sm transition-colors"
                            :class="(() => {
                              const r = draftIndividualRanks[idx]?.ranks[songIdx - 1];
                              if (r === 1) return 'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300 hover:bg-amber-200 dark:hover:bg-amber-900/60';
                              if (r === 2) return 'bg-slate-200 text-slate-700 dark:bg-slate-600 dark:text-slate-100 hover:bg-slate-300 dark:hover:bg-slate-500';
                              if (r === 3) return 'bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-400 hover:bg-slate-200 dark:hover:bg-slate-700';
                              if (r === 4) return 'bg-rose-50 text-rose-500 dark:bg-rose-900/30 dark:text-rose-300 hover:bg-rose-100 dark:hover:bg-rose-900/50';
                              return 'bg-slate-50 dark:bg-slate-900/60 text-slate-300 dark:text-slate-600 hover:bg-slate-100 dark:hover:bg-slate-800 border border-dashed border-slate-300 dark:border-slate-600';
                            })()"
                          >
                            <span v-if="draftIndividualRanks[idx]?.ranks[songIdx - 1]" class="block">
                              {{ draftIndividualRanks[idx]?.ranks[songIdx - 1] }}位
                            </span>
                            <span v-else class="block text-[11px] italic">クリック</span>
                            <span
                              v-if="draftIndividualRanks[idx]?.points[songIdx - 1] !== null"
                              class="block text-[10px] font-bold opacity-80"
                            >
                              {{ draftIndividualRanks[idx]?.points[songIdx - 1] }}pt
                            </span>
                          </button>
                        </td>
                        <td class="px-2 py-2 text-center">
                          <span
                            v-if="draftOverallRank(idx) !== null"
                            class="inline-block px-2 py-1 rounded text-xs font-bold tabular-nums"
                            :class="(() => {
                              const ovr = draftOverallRank(idx);
                              if (ovr === 1) return 'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300';
                              if (ovr === 2) return 'bg-slate-200 text-slate-700 dark:bg-slate-600 dark:text-slate-100';
                              if (ovr === 3) return 'bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-400';
                              return 'bg-rose-50 text-rose-500 dark:bg-rose-900/30 dark:text-rose-300';
                            })()"
                          >
                            {{ draftOverallRank(idx) }}位 ({{ draftIndividualRanks[idx]?.total }}pt)
                          </span>
                          <span v-else class="text-slate-300 italic text-xs">-</span>
                        </td>
                      </tr>
                    </tbody>
                  </table>
                  <div class="flex gap-2 justify-end pt-2 border-t border-slate-100 dark:border-slate-700/40">
                    <button type="button" @click="handleSaveIndividualResult(m.id)" class="px-3 py-1 text-xs font-bold rounded-lg bg-emerald-500 text-white hover:bg-emerald-600">保存</button>
                    <button type="button" @click="cancelEditIndividualResult" class="px-3 py-1 text-xs font-bold rounded-lg bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600">×</button>
                  </div>
                </div>
              </template>
            </div>
          </section>
        </template>
        <!-- ────────── /individual4 専用セクション群 ────────── -->
      </div>
    </template>

    <!-- 楽曲選択モーダル (個人戦の試合結果編集中のみアクティブ) -->
    <SongPickerModal
      :open="songPickerOpen"
      :current-title="songPickerTargetSlot ? draftSongTitle(songPickerTargetSlot) : null"
      @close="closeSongPicker"
      @select="handleSongPicked"
    />
  </div>
</template>

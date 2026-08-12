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
import {
  KIND_LABEL_JA,
  kindLevelLabel,
  kindOrder,
  isLevel12Only,
  pointsPerSong,
  type MatchKind,
} from '../composables/competitionMatchKinds';
import SongPickerModal from '../components/SongPickerModal.vue';
import SongSelect from '../components/SongSelect.vue';

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
  configureMatchup,
  swapMatchupSides,
  publishPick,
  setDeadline,
  setLineupPublishAt,
  setFinalsDeadline,
  setFinalsLineupPublishAt,
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
// 戦種別のラベル / Lv 帯 (予選 3 戦 / 決勝 7 戦) は competitionMatchKinds に集約。
const KIND_LABEL = KIND_LABEL_JA;

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

/** matchup ID に紐づく試合を先鋒 → … → 大将 の順で返す (予選 3 戦 / 決勝 7 戦)。 */
const matchesForMatchup = (matchupId: number): CompetitionMatchDto[] => {
  if (!currentCompetition.value?.matches) return [];
  return currentCompetition.value.matches
    .filter(m => m.matchupId === matchupId)
    .sort((a, b) => kindOrder(a.matchKind) - kindOrder(b.matchKind));
};

// ── matchup の総合結果 (先鋒〜大将の全戦合計) ────────────
/**
 * 1 matchup ぶんの総合成績。予選 3 戦 / 決勝 7 戦を合算したもの。
 *
 * 集計ルールは backend の {@code CompetitionTeamStandingsService} と同じ:
 * 勝ち曲数 × 戦ポイント (予選 先鋒2/中堅3/大将4) の合計が多い側が matchup 勝ち。
 * 未記録の戦は加算しないので、途中でも「ここまでの合計」として読める。
 */
interface MatchupTotal {
  /** A 側 / B 側の戦ポイント合計 (matchup の勝敗はこれで決まる)。 */
  aPoints: number;
  bPoints: number;
  /** A 側 / B 側の勝ち曲数合計 (参考表示)。 */
  aSongs: number;
  bSongs: number;
  /** 結果記録済みの戦数 / この matchup の総戦数。 */
  recorded: number;
  total: number;
  /** 全戦が記録済みか (勝敗が確定するのはこのときだけ)。 */
  allRecorded: boolean;
  /** 確定した勝敗。全戦記録済みになるまでは null (= 途中経過)。 */
  winner: 'a' | 'b' | 'draw' | null;
}

/** matchup ID → 総合成績。試合結果を記録すると即座に再計算される。 */
const matchupTotals = computed<Record<number, MatchupTotal>>(() => {
  const out: Record<number, MatchupTotal> = {};
  for (const mu of currentCompetition.value?.matchups ?? []) {
    const matches = matchesForMatchup(mu.id);
    let aPoints = 0, bPoints = 0, aSongs = 0, bSongs = 0, recorded = 0;
    for (const m of matches) {
      if (m.aSongsWon === null || m.bSongsWon === null) continue;
      recorded++;
      const pt = pointsPerSong(m.matchKind, mu.isFinals);
      aPoints += m.aSongsWon * pt;
      bPoints += m.bSongsWon * pt;
      aSongs += m.aSongsWon;
      bSongs += m.bSongsWon;
    }
    const allRecorded = matches.length > 0 && recorded === matches.length;
    out[mu.id] = {
      aPoints, bPoints, aSongs, bSongs,
      recorded,
      total: matches.length,
      allRecorded,
      winner: !allRecorded ? null : aPoints > bPoints ? 'a' : bPoints > aPoints ? 'b' : 'draw',
    };
  }
  return out;
});

/**
 * ジャンルセレクタの change ハンドラ。
 * 空文字 ('') を選んだら null を送ってサーバ側で指定解除する。
 */
const handleGenreChange = async (match: CompetitionMatchDto, raw: string) => {
  if (!currentCompetition.value) return;
  const genre = raw === '' ? null : (raw as CompetitionSongGenre);
  // INSANE プールは Lv12 しか無いので、Lv12 の戦以外はサーバが拒否する。UI 側でも先回りで止める。
  // (判定を 'captain' 決め打ちにすると、同じ Lv12 の決勝の三将戦・副将戦まで弾いてしまう)
  if (genre === 'INSANE' && !isLevel12Only(match.matchKind)) {
    toast.error('INSANE は Lv12 の戦にのみ指定できます');
    return;
  }
  try {
    await setMatchGenre(currentCompetition.value.id, match.id, genre);
    toast.success(genre === null ? 'ジャンル指定を解除しました' : `${genre} に指定しました`);
  } catch (e) {
    toast.error((e as Error).message);
  }
};

/**
 * matchKind 制約に応じてセレクタに出すジャンル候補。
 * INSANE はプールに Lv12 しか無いので、Lv12 の戦 (予選: 大将 / 決勝: 三将・副将・大将) のみ。
 */
const genresForKind = (matchKind: MatchKind): CompetitionSongGenre[] => {
  return ALL_GENRES.filter(g => !(g === 'INSANE' && !isLevel12Only(matchKind)));
};

// ── 公開トグル ────────────────────────────────────────────
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

// ── 起用公開日時 (JST) ── 起用クローズ日時とは独立に、オーダー(起用)を相手へ公開する時刻 ──────────
const lineupPublishInput = ref<string>('');

watch(
  () => currentCompetition.value?.lineupPublishAt,
  (iso) => { lineupPublishInput.value = toDatetimeLocal(iso ?? null); },
  { immediate: true },
);

const isSavingLineupPublish = ref(false);
/** 入力中の起用公開日時を保存 (空なら公開日時解除)。 */
const handleSaveLineupPublishAt = async () => {
  if (!currentCompetition.value) return;
  isSavingLineupPublish.value = true;
  try {
    await setLineupPublishAt(currentCompetition.value.id, lineupPublishInput.value || null);
    toast.success(lineupPublishInput.value ? '起用公開日時を設定しました' : '起用公開日時を解除しました');
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    isSavingLineupPublish.value = false;
  }
};
/** 起用公開日時を解除 (入力クリア + 保存)。 */
const handleClearLineupPublishAt = async () => {
  lineupPublishInput.value = '';
  await handleSaveLineupPublishAt();
};

// ── 決勝のスケジュール (JST) ─────────────────────────────
// 決勝は予選終了後に生成されるため、予選のクローズ日時 / 公開日時をそのまま使うと
// 生成直後から編集不可 & 即公開になってしまう。決勝専用の 2 日時で制御する。
// どちらも未設定の間は「決勝の起用は編集可 & 非公開」= 決勝生成直後の既定状態。
const finalsDeadlineInput = ref<string>('');
const finalsPublishInput = ref<string>('');

watch(
  () => currentCompetition.value?.finalsDeadlineAt,
  (iso) => { finalsDeadlineInput.value = toDatetimeLocal(iso ?? null); },
  { immediate: true },
);
watch(
  () => currentCompetition.value?.finalsLineupPublishAt,
  (iso) => { finalsPublishInput.value = toDatetimeLocal(iso ?? null); },
  { immediate: true },
);

const isSavingFinalsDeadline = ref(false);
/** 決勝の起用クローズ日時を保存 (空なら締切解除 = 決勝の起用を締め切らない)。 */
const handleSaveFinalsDeadline = async () => {
  if (!currentCompetition.value) return;
  isSavingFinalsDeadline.value = true;
  try {
    await setFinalsDeadline(currentCompetition.value.id, finalsDeadlineInput.value || null);
    toast.success(finalsDeadlineInput.value ? '決勝の起用クローズ日時を設定しました' : '決勝のクローズ日時を解除しました');
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    isSavingFinalsDeadline.value = false;
  }
};
const handleClearFinalsDeadline = async () => {
  finalsDeadlineInput.value = '';
  await handleSaveFinalsDeadline();
};

const isSavingFinalsPublish = ref(false);
/** 決勝の起用公開日時を保存 (空なら公開解除 = 決勝の起用を非公開のままにする)。 */
const handleSaveFinalsPublishAt = async () => {
  if (!currentCompetition.value) return;
  isSavingFinalsPublish.value = true;
  try {
    await setFinalsLineupPublishAt(currentCompetition.value.id, finalsPublishInput.value || null);
    toast.success(finalsPublishInput.value ? '決勝の起用公開日時を設定しました' : '決勝の起用公開日時を解除しました');
  } catch (e) {
    toast.error((e as Error).message);
  } finally {
    isSavingFinalsPublish.value = false;
  }
};
const handleClearFinalsPublishAt = async () => {
  finalsPublishInput.value = '';
  await handleSaveFinalsPublishAt();
};

/** 決勝 matchup が生成済みか (決勝スケジュール UI の表示条件)。 */
const finalsGenerated = computed<boolean>(() =>
  (currentCompetition.value?.matchups ?? []).some(mu => mu.isFinals),
);

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

/**
 * 指定試合・サイドの StrategyCard 意思決定状態。TL 画面の 2 ボタン (発動する / 発動しない) と対応する。
 *
 * - {@code use} / {@code skip}: TL が決定済み
 * - {@code undecided}: TL が決定できる状態 (起用クローズ + 起用公開 + 両者アサイン済) なのに未決定
 * - {@code pending}: そもそもまだ決定できないフェーズ (バッジを出さない)
 */
const strategyStatusOf = (
  match: CompetitionMatchDto,
  side: 'a' | 'b',
): 'use' | 'skip' | 'undecided' | 'pending' => {
  const decided = side === 'a' ? match.strategyDecidedA : match.strategyDecidedB;
  if (decided) return (side === 'a' ? match.strategyUsedA : match.strategyUsedB) ? 'use' : 'skip';
  const comp = currentCompetition.value;
  const decidable = !!comp && comp.status !== 'finished'
    && comp.lineupClosed && comp.lineupPublished
    && match.playerAId !== null && match.playerBId !== null;
  return decidable ? 'undecided' : 'pending';
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

/** 全体再読込の実行中フラグ (連打防止 + ボタンのラベル切替)。 */
const isRefreshingAll = ref(false);

/**
 * 手動で「表示中の大会に関するサーバ状態すべて」を取り直す。
 *
 * 大会中は提出状況だけでなく、TL のアサイン / StrategyCard 発動 / 他端末で入力された結果 /
 * 運営チャットも並行して動くため、1 ボタンでまとめて最新化する。
 *
 * 1. 大会詳細 (アサイン・ジャンル・結果・締切・公開日時) — これだけは失敗を toast する
 * 2. フォーマット別の付随データ
 *    - team5: 提出状況 (reveal) / 順位表 / 運営チャット
 *    - individual4: 個人戦順位表
 *
 * 付随データの取得はそれぞれの refresh 側でエラーを処理済み (reveal は toast、他はサイレント)
 * なので、ここでは並列に投げて 1 つ失敗しても残りを反映させる。
 */
const handleRefreshAll = async () => {
  if (!currentCompetition.value || isRefreshingAll.value) return;
  const id = currentCompetition.value.id;
  isRefreshingAll.value = true;
  try {
    await fetchCompetition(id);
    if (currentCompetition.value?.format === 'individual4') {
      await refreshIndividualStandings();
    } else {
      await Promise.all([
        refreshRevealData(id),
        refreshStandings(),
        loadChatThreads(),
      ]);
    }
    toast.success('最新の状況に更新しました');
  } catch (e) {
    toast.error('大会情報の再読込に失敗しました: ' + (e as Error).message);
  } finally {
    isRefreshingAll.value = false;
  }
};

// ── 試合結果記録 (R-4: スコアベース) ───────────────────
/**
 * 試合の結果入力。matchId 単位で開閉し、両曲ぶんのスコアを受け付ける。
 * 両スコアが揃った曲だけ判定対象。サーバ側で aSongsWon / bSongsWon を自動算出する。
 *
 * 曲は通常「自選曲、もしくは StrategyCard 発動時の抽選曲」から一意に導出される
 * ({@link autoSongsOf}) が、現地で曲が差し替わった場合などのために、管理番号つき全曲から
 * 選び直せるプルダウンも用意している。選び直した枠は {@code manualOverride} に載り、
 * 以降は自動導出で上書きされない (サーバにも {@code songXManual} として保存する)。
 */
const resultEditingMatchId = ref<number | null>(null);
/** スコアのみのドラフト。曲情報は保存時に導出結果 / 手動指定から合成する。 */
type MatchScoreDraft = Pick<
  MatchResultPayload,
  'song1ScoreA' | 'song1ScoreB' | 'song2ScoreA' | 'song2ScoreB'
>;
const resultDraft = ref<MatchScoreDraft>({
  song1ScoreA: null, song1ScoreB: null,
  song2ScoreA: null, song2ScoreB: null,
});

/**
 * 編集中パネルでの曲の手動指定。枠ごとに null (= 自動導出のまま) か、選ばれた曲を持つ。
 * パネルを開くたびに、その試合の保存済み手動指定 (songXManual) から復元する。
 */
type ManualSong = { strategyId: number; title: string };
const manualOverride = ref<{ song1: ManualSong | null; song2: ManualSong | null }>({
  song1: null, song2: null,
});

/**
 * サーバに保存済みの手動指定を復元する。
 * フラグが立っていても管理番号が無い枠は手動指定として扱わない (曲を特定できないため)。
 */
const savedManualSong = (manual: boolean, id: number | null, title: string | null): ManualSong | null =>
  manual && id !== null ? { strategyId: id, title: title ?? '' } : null;

/**
 * 演奏曲 1 枠ぶんの自動決定結果。
 * {@code source} は「どこから決まった曲か」の表示用ラベル判定に使う。
 */
interface AutoSong {
  strategyId: number | null;
  title: string | null;
  /**
   * manual = 運営がプルダウンで手動指定 / strategy = StrategyCard 抽選曲 / pick = 自選曲 /
   * recorded = 導出不能で記録済み値を維持 / none = 未決定
   */
  source: 'manual' | 'strategy' | 'pick' | 'recorded' | 'none';
  /**
   * 相殺で自選曲に戻った枠か (source === 'pick' のときのみ true になり得る)。
   * 「発動が無くて自選曲」と「両者発動 → 相殺で自選曲」を運営が見分けられるようにするための表示用フラグ。
   */
  canceled: boolean;
}

/**
 * 「実際に演奏される曲」を試合から自動導出する。
 *
 * 1 戦 = 2 曲制を「song1 = A 側が演奏する曲 / song2 = B 側が演奏する曲」と定義し、
 *  - 通常 (strategy 未発動): song1 = A の自選曲、song2 = B の自選曲
 *  - B 側が strategy 発動 → A 側の曲がランダム化: song1 = revealMatch.playerBStrategyResult
 *  - A 側が strategy 発動 → B 側の曲がランダム化: song2 = revealMatch.playerAStrategyResult
 *  - 両者が strategy 発動 → 相殺。抽選は行われず、両枠とも自選曲に戻る
 *    (サーバが strategyCanceled = true とともに playerXStrategyResult を null で返す)
 *
 * 運営がプルダウンで曲を選び直した枠 (manual) だけは、上のどれよりも優先する。現地で曲が
 * 差し替わった場合の記録が 🔄 再読込や StrategyCard の抽選確定で巻き戻ってしまわないようにするため。
 *
 * 抽選結果は reveal データ生成時にサーバで確定するため、reveal を取り直せばここも自動で
 * 抽選曲へ切り替わる。reveal データが無い / 自選曲未提出で導出できない枠だけ、既に記録済みの
 * 値へフォールバックして既存記録を消さないようにする。
 */
const autoSongsOf = (match: CompetitionMatchDto): { song1: AutoSong; song2: AutoSong } => {
  const rm = revealMatchOf(match.id);
  const canceled = rm?.strategyCanceled === true;
  const resolve = (
    manual: ManualSong | null,
    strategyResult: { songStrategyId: number; songTitle: string } | null | undefined,
    ownPick: { songStrategyId: number; songTitle: string } | null | undefined,
    recordedId: number | null,
    recordedTitle: string | null,
  ): AutoSong => {
    // 手動指定は運営の明示的な意思なので、自選曲でも抽選曲でも上書きしない。
    if (manual) {
      return { strategyId: manual.strategyId, title: manual.title, source: 'manual', canceled: false };
    }
    // 相殺時はサーバが strategyResult を null で返すが、古い reveal データを掴んでいても
    // 自選曲が選ばれるようここでも明示的に抽選曲を無視する。
    if (strategyResult && !canceled) {
      return { strategyId: strategyResult.songStrategyId, title: strategyResult.songTitle, source: 'strategy', canceled: false };
    }
    if (ownPick) {
      return { strategyId: ownPick.songStrategyId, title: ownPick.songTitle, source: 'pick', canceled };
    }
    if (recordedId !== null || recordedTitle !== null) {
      return { strategyId: recordedId, title: recordedTitle, source: 'recorded', canceled: false };
    }
    return { strategyId: null, title: null, source: 'none', canceled: false };
  };
  // 手動指定は、編集中の試合ならドラフト (manualOverride) を、それ以外は保存済みフラグを見る。
  const editing = resultEditingMatchId.value === match.id;
  const manual1 = editing
    ? manualOverride.value.song1
    : savedManualSong(match.song1Manual, match.song1StrategyId, match.song1Title);
  const manual2 = editing
    ? manualOverride.value.song2
    : savedManualSong(match.song2Manual, match.song2StrategyId, match.song2Title);
  return {
    song1: resolve(manual1, rm?.playerBStrategyResult, rm?.playerAPick, match.song1StrategyId, match.song1Title),
    song2: resolve(manual2, rm?.playerAStrategyResult, rm?.playerBPick, match.song2StrategyId, match.song2Title),
  };
};

/**
 * 編集中の試合の自動導出曲。reveal データ (= strategy 抽選結果) の更新に追従するので、
 * 編集パネルを開いたまま 🔄 再読込しても表示が最新の演奏曲へ切り替わる。
 */
const editingAutoSongs = computed<{ song1: AutoSong; song2: AutoSong } | null>(() => {
  const id = resultEditingMatchId.value;
  if (id === null) return null;
  const m = currentCompetition.value?.matches?.find(x => x.id === id);
  return m ? autoSongsOf(m) : null;
});

/** 導出元のバッジ表記。相殺で自選曲に戻った枠は通常の自選曲と区別できるようにする。 */
const autoSongSourceLabel = (song: AutoSong | null | undefined): string => {
  if (!song) return '未決定';
  if (song.source === 'manual') return '✎ 手動指定';
  if (song.source === 'strategy') return '⚡ 抽選曲';
  if (song.source === 'pick') return song.canceled ? '自選曲（⚡相殺）' : '自選曲';
  if (song.source === 'recorded') return '記録値';
  return '未決定';
};

/** 導出元バッジの配色。相殺は「発動はあったが打ち消された」ことが伝わるよう琥珀系にする。 */
const autoSongSourceClass = (song: AutoSong | null | undefined): string => {
  if (song?.source === 'manual') return 'bg-sky-600 text-white';
  if (song?.source === 'strategy') return 'bg-fuchsia-600 text-white';
  if (song?.source === 'pick') {
    return song.canceled
      ? 'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300'
      : 'bg-violet-100 text-violet-700 dark:bg-violet-900/40 dark:text-violet-300';
  }
  return 'bg-slate-200 text-slate-500 dark:bg-slate-700 dark:text-slate-400';
};

/** プルダウンで曲を選んだ。以降その枠は自動導出で上書きされない。 */
const handleSongSelected = (slot: 1 | 2, song: { strategyId: number; title: string }) => {
  const picked: ManualSong = { strategyId: song.strategyId, title: song.title };
  if (slot === 1) manualOverride.value.song1 = picked;
  else manualOverride.value.song2 = picked;
};

/** 手動指定を解除して自動導出 (自選曲 / 抽選曲) に戻す。 */
const clearSongOverride = (slot: 1 | 2) => {
  if (slot === 1) manualOverride.value.song1 = null;
  else manualOverride.value.song2 = null;
};

/**
 * 結果記録パネルを開く。
 *
 * StrategyCard の抽選はサーバ側の遅延抽選 (reveal データ生成時に確定) なので、大会を開いた
 * 時点の reveal データは「TL が発動を決める前」の可能性がある。開くたびに取り直すことで、
 * 発動後に差し替わった曲が自動で反映される。
 */
const beginResultEdit = async (match: CompetitionMatchDto) => {
  resultEditingMatchId.value = match.id;
  resultDraft.value = {
    song1ScoreA: match.song1ScoreA,
    song1ScoreB: match.song1ScoreB,
    song2ScoreA: match.song2ScoreA,
    song2ScoreB: match.song2ScoreB,
  };
  // 保存済みの手動指定を復元する。これをしないと開き直した瞬間に自動導出へ巻き戻ってしまう。
  manualOverride.value = {
    song1: savedManualSong(match.song1Manual, match.song1StrategyId, match.song1Title),
    song2: savedManualSong(match.song2Manual, match.song2StrategyId, match.song2Title),
  };
  if (currentCompetition.value) {
    await refreshRevealData(currentCompetition.value.id);
  }
};
const cancelResultEdit = () => {
  resultEditingMatchId.value = null;
  manualOverride.value = { song1: null, song2: null };
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
  // 曲情報は導出結果 (手動指定があればそれ) を保存する。
  // songXManual を一緒に送ることで、サーバ側でも以降 自動導出で上書きされなくなる。
  const auto = editingAutoSongs.value;
  const payload: MatchResultPayload = {
    song1StrategyId: auto?.song1.strategyId ?? null,
    song1Title: auto?.song1.title ?? null,
    song1ScoreA: resultDraft.value.song1ScoreA,
    song1ScoreB: resultDraft.value.song1ScoreB,
    song2StrategyId: auto?.song2.strategyId ?? null,
    song2Title: auto?.song2.title ?? null,
    song2ScoreA: resultDraft.value.song2ScoreA,
    song2ScoreB: resultDraft.value.song2ScoreB,
    song1Manual: manualOverride.value.song1 !== null,
    song2Manual: manualOverride.value.song2 !== null,
  };
  try {
    await setMatchResult(currentCompetition.value.id, matchId, payload);
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

// ── 個人成績 (選手ごとの曲単位成績) ───────────────────────
/**
 * 選手 1 人が演奏した曲 1 曲ぶんの記録。1 戦 = 2 曲制なので、起用された 1 戦につき 2 行できる。
 *
 * 「1 戦で演奏される 2 曲」は song1 = A 側が持ち込んだ曲 / song2 = B 側が持ち込んだ曲という
 * 定義だが、両者が両曲を叩くので、どちらの選手にも 2 曲ぶんの行を作る。
 */
interface PersonalSongRow {
  matchId: number;
  /** 所属 matchup の表示順 (決勝は matchupOrder を持たないので isFinals で区別する)。 */
  matchupOrder: number;
  isFinals: boolean;
  kind: MatchKind;
  /** 1 = A 側持ち込みの曲 / 2 = B 側持ち込みの曲。 */
  slot: 1 | 2;
  /** 曲名 ({@link autoSongsOf} の導出結果。未決定なら null)。 */
  title: string | null;
  /** この選手のスコア / 対戦相手のスコア。どちらか欠けていれば未記録扱い。 */
  ownScore: number | null;
  oppScore: number | null;
  opponentName: string;
  opponentTeamName: string;
  /** 勝敗。null = 未記録 (スコアが揃っていない)。 */
  result: 'win' | 'draw' | 'lose' | null;
  /** この曲で獲得した戦ポイント (勝ち / 引分なら戦ptが入る)。 */
  points: number;
}

/** 選手 1 人ぶんの集計行。{@link PersonalSongRow} を畳んだもの。 */
interface PersonalStatsRow {
  participantId: number;
  displayName: string;
  teamName: string;
  isTl: boolean;
  /** 順位 (獲得pt 降順。同 pt・同勝ち数は同順位)。 */
  rank: number;
  /** 曲単位の勝 / 分 / 敗 (記録済みの曲のみ)。 */
  wins: number;
  draws: number;
  losses: number;
  /** 獲得pt 合計 (勝った曲 + 引分の曲 × その戦の 1 曲あたりpt)。 */
  points: number;
  /** 記録済みの曲数 / 起用された戦数。 */
  recordedSongs: number;
  assignedMatches: number;
  songs: PersonalSongRow[];
}

/**
 * 選手ごとの曲単位成績を集計する。
 *
 * 勝敗とptの規則はサーバの結果記録 (setMatchResult) / 順位表
 * (CompetitionTeamStandingsService) と揃えている:
 *  - スコアが両側揃っている曲だけ集計対象 (片方欠けている曲は未記録)
 *  - 同スコアの曲は「両者の勝ち」として扱い、双方に戦ptが入る
 *  - 獲得pt = 勝った曲数 × その戦の 1 曲あたりpt (予選 先鋒2/中堅3/大将4、決勝は別表)
 *
 * つまり 1 チームの全選手の獲得ptを足すと、そのチームの戦pt (予選ぶん) と一致する。
 */
const personalStats = computed<PersonalStatsRow[]>(() => {
  const comp = currentCompetition.value;
  if (!comp || comp.format === 'individual4') return [];

  const rows = new Map<number, PersonalStatsRow>();
  for (const p of comp.participants) {
    rows.set(p.id, {
      participantId: p.id,
      displayName: p.displayName,
      teamName: teamNameOf(p.teamId),
      isTl: p.isTl,
      rank: 0,
      wins: 0, draws: 0, losses: 0, points: 0,
      recordedSongs: 0, assignedMatches: 0,
      songs: [],
    });
  }

  for (const m of comp.matches ?? []) {
    const mu = comp.matchups?.find(x => x.id === m.matchupId);
    if (!mu) continue;
    // 曲名は結果記録 UI と同じ導出 (手動指定 → 抽選曲 → 自選曲 → 記録値) を使う。
    const auto = autoSongsOf(m);
    const pt = pointsPerSong(m.matchKind, mu.isFinals);
    const slots = [
      { slot: 1 as const, title: auto.song1.title, scoreA: m.song1ScoreA, scoreB: m.song1ScoreB },
      { slot: 2 as const, title: auto.song2.title, scoreA: m.song2ScoreA, scoreB: m.song2ScoreB },
    ];
    for (const side of ['a', 'b'] as const) {
      const participantId = side === 'a' ? m.playerAId : m.playerBId;
      if (participantId === null) continue;
      const row = rows.get(participantId);
      if (!row) continue; // 参加者が削除済みなど (通常は起こらない)
      row.assignedMatches++;
      const opponentId = side === 'a' ? m.playerBId : m.playerAId;
      const opponentTeamId = side === 'a' ? mu.teamBId : mu.teamAId;
      for (const s of slots) {
        const ownScore = side === 'a' ? s.scoreA : s.scoreB;
        const oppScore = side === 'a' ? s.scoreB : s.scoreA;
        let result: PersonalSongRow['result'] = null;
        let earned = 0;
        if (ownScore !== null && oppScore !== null) {
          result = ownScore > oppScore ? 'win' : ownScore < oppScore ? 'lose' : 'draw';
          row.recordedSongs++;
          if (result === 'win') row.wins++;
          else if (result === 'draw') row.draws++;
          else row.losses++;
          // 引分は両者の勝ち扱いなので pt が入る (サーバの aSongsWon / bSongsWon と同じ)。
          if (result !== 'lose') earned = pt;
          row.points += earned;
        }
        row.songs.push({
          matchId: m.id,
          matchupOrder: mu.matchupOrder,
          isFinals: mu.isFinals,
          kind: m.matchKind,
          slot: s.slot,
          title: s.title,
          ownScore,
          oppScore,
          opponentName: participantNameOf(opponentId),
          opponentTeamName: teamNameOf(opponentTeamId),
          result,
          points: earned,
        });
      }
    }
  }

  const out = [...rows.values()];
  // 詳細は 予選 → 決勝、matchup 順 → 先鋒〜大将 → 曲順 に並べる。
  for (const r of out) {
    r.songs.sort((x, y) =>
      Number(x.isFinals) - Number(y.isFinals)
      || x.matchupOrder - y.matchupOrder
      || kindOrder(x.kind) - kindOrder(y.kind)
      || x.slot - y.slot);
  }
  // 並び: 獲得pt 降順 → 勝ち曲数 降順 → 表示名。順位は pt + 勝ち数が同じなら同順位にする。
  out.sort((x, y) =>
    y.points - x.points
    || y.wins - x.wins
    || x.displayName.localeCompare(y.displayName, 'ja'));
  let rank = 0;
  let prevKey = '';
  out.forEach((r, i) => {
    const key = `${r.points}/${r.wins}`;
    if (key !== prevKey) {
      rank = i + 1;
      prevKey = key;
    }
    r.rank = rank;
  });
  return out;
});

/** 個人成績セクションの見出しに出す「記録済み曲数」(選手視点の重複を除いた実曲数)。 */
const personalRecordedSongCount = computed<number>(() => {
  let n = 0;
  for (const m of currentCompetition.value?.matches ?? []) {
    if (m.song1ScoreA !== null && m.song1ScoreB !== null) n++;
    if (m.song2ScoreA !== null && m.song2ScoreB !== null) n++;
  }
  return n;
});

/** 詳細 (曲ごとのスコア) を開いている選手の ID。複数人ぶん同時に開ける。 */
const expandedPersonalIds = ref<number[]>([]);
const isPersonalExpanded = (participantId: number): boolean =>
  expandedPersonalIds.value.includes(participantId);
const togglePersonalDetail = (participantId: number) => {
  expandedPersonalIds.value = isPersonalExpanded(participantId)
    ? expandedPersonalIds.value.filter(id => id !== participantId)
    : [...expandedPersonalIds.value, participantId];
};
/** 全員ぶん開いているか (「すべて開く / 閉じる」ボタンのラベル判定)。 */
const allPersonalExpanded = computed<boolean>(() =>
  personalStats.value.length > 0
  && personalStats.value.every(r => isPersonalExpanded(r.participantId)));
const toggleAllPersonalDetails = () => {
  expandedPersonalIds.value = allPersonalExpanded.value
    ? []
    : personalStats.value.map(r => r.participantId);
};

/** 曲行の勝敗ラベル (未記録は「-」)。 */
const personalResultLabel = (result: PersonalSongRow['result']): string => {
  if (result === 'win') return '○';
  if (result === 'lose') return '×';
  if (result === 'draw') return '△';
  return '-';
};

/** 曲行の勝敗の配色。順位表のマトリクスと同じ配色ルールに揃えている。 */
const personalResultClass = (result: PersonalSongRow['result']): string => {
  if (result === 'win') return 'text-emerald-600 dark:text-emerald-300';
  if (result === 'lose') return 'text-rose-500 dark:text-rose-400';
  if (result === 'draw') return 'text-amber-600 dark:text-amber-300';
  return 'text-slate-400';
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

/** 対戦の左右 (A 側 / B 側) を入れ替える。teamA↔teamB と各試合の A/B データを対称に入れ替える。 */
const handleSwapMatchupSides = async (matchupId: number) => {
  if (!currentCompetition.value) return;
  try {
    await swapMatchupSides(currentCompetition.value.id, matchupId);
    await refreshRevealData(currentCompetition.value.id);
    toast.success('対戦の左右を入れ替えました');
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
                <th class="text-right py-1 px-2">ストラテジー</th>
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
                <!-- ストラテジー使用回数: 発動した試合の結果が記録された時点でカウントされる (matchup 単位)。 -->
                <td class="py-1.5 px-2 text-right tabular-nums">
                  <span
                    :class="row.strategyUsedMatchupCount >= row.strategyLimit
                      ? 'text-rose-500 dark:text-rose-400 font-bold'
                      : 'text-slate-500 dark:text-slate-400'"
                  >⚡ {{ row.strategyUsedMatchupCount }} / {{ row.strategyLimit }}</span>
                </td>
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

        <!--
          個人成績: 選手ごとに「曲単位の勝敗」と「獲得pt」を集計。行を開くと 1 曲ずつの詳細スコアが出る。
          集計は結果記録済みのスコアからクライアント側で導出するので、結果を保存すれば即座に反映される。
        -->
        <section
          v-if="currentCompetition.status !== 'draft' && personalStats.length > 0"
          class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-md p-4 space-y-3"
        >
          <div class="flex items-center justify-between flex-wrap gap-2">
            <h2 class="text-sm font-bold text-slate-500">
              個人成績 ({{ personalRecordedSongCount }} 曲 記録済)
            </h2>
            <button
              type="button"
              @click="toggleAllPersonalDetails"
              class="px-3 py-1 text-[10px] font-bold rounded-lg bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600"
            >{{ allPersonalExpanded ? '▲ すべて閉じる' : '▼ すべて開く' }}</button>
          </div>
          <p class="text-[11px] text-slate-500">
            曲単位の勝敗と獲得pt (予選 + 決勝の合計)。1 戦 = 2 曲制で<b>両者が両曲を叩く</b>ため、1 戦につき 2 曲ぶん記録されます。
            同スコアの曲は両者の勝ちとして扱い双方にptが入ります (順位表と同じ規則)。
            獲得pt = 勝った曲数 × その戦の 1 曲あたりpt (予選: 先鋒2 / 中堅3 / 大将4)。
            選手行をクリックすると 1 曲ごとの詳細スコアを開けます。
          </p>
          <div class="overflow-x-auto">
            <table class="w-full text-sm">
              <thead>
                <tr class="text-[10px] font-mono text-slate-400 border-b border-slate-200 dark:border-slate-700">
                  <th class="text-left py-1 px-2">順位</th>
                  <th class="text-left py-1 px-2">選手</th>
                  <th class="text-left py-1 px-2">チーム</th>
                  <th class="text-right py-1 px-2">勝</th>
                  <th class="text-right py-1 px-2">分</th>
                  <th class="text-right py-1 px-2">負</th>
                  <th class="text-right py-1 px-2 font-bold text-slate-700 dark:text-slate-200">獲得pt</th>
                  <th class="text-right py-1 px-2">起用</th>
                  <th class="py-1 px-2"></th>
                </tr>
              </thead>
              <tbody>
                <template v-for="row in personalStats" :key="row.participantId">
                  <tr
                    class="border-b border-slate-100 dark:border-slate-700/60 cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-900/40"
                    @click="togglePersonalDetail(row.participantId)"
                  >
                    <td class="py-1.5 px-2 tabular-nums text-slate-500">{{ row.rank }}</td>
                    <td class="py-1.5 px-2 font-bold truncate">
                      {{ row.displayName }}
                      <span v-if="row.isTl" class="ml-1 text-[10px] font-bold text-amber-600 dark:text-amber-300">TL</span>
                    </td>
                    <td class="py-1.5 px-2 truncate text-xs" :class="teamColorClass(row.teamName)">{{ row.teamName }}</td>
                    <td class="py-1.5 px-2 text-right tabular-nums text-emerald-600 dark:text-emerald-300">{{ row.wins }}</td>
                    <td class="py-1.5 px-2 text-right tabular-nums text-slate-500">{{ row.draws }}</td>
                    <td class="py-1.5 px-2 text-right tabular-nums text-rose-500 dark:text-rose-400">{{ row.losses }}</td>
                    <td class="py-1.5 px-2 text-right tabular-nums font-bold text-base">{{ row.points }}</td>
                    <!-- 起用: 起用された戦数と、そのうち何曲ぶんスコアが記録済みか (1 戦 = 2 曲)。 -->
                    <td class="py-1.5 px-2 text-right tabular-nums text-[11px] text-slate-500 whitespace-nowrap">
                      {{ row.assignedMatches }} 戦
                      <span class="text-slate-400">({{ row.recordedSongs }}/{{ row.assignedMatches * 2 }} 曲)</span>
                    </td>
                    <td class="py-1.5 px-2 text-right text-slate-400 text-[10px]">
                      {{ isPersonalExpanded(row.participantId) ? '▲' : '▼' }}
                    </td>
                  </tr>
                  <!-- 折り畳み: 1 曲ごとの詳細スコア。未記録の曲も「-」で並べて残り試合が分かるようにする。 -->
                  <tr v-if="isPersonalExpanded(row.participantId)" class="border-b border-slate-100 dark:border-slate-700/60">
                    <td colspan="9" class="py-2 px-2 bg-slate-50 dark:bg-slate-900/40">
                      <p v-if="row.songs.length === 0" class="text-[11px] text-slate-500 px-2 py-1">
                        まだ起用されていません。
                      </p>
                      <table v-else class="w-full text-xs">
                        <thead>
                          <tr class="text-[10px] font-mono text-slate-400 border-b border-slate-200 dark:border-slate-700">
                            <th class="text-left py-1 px-2">試合</th>
                            <th class="text-left py-1 px-2">戦</th>
                            <th class="text-left py-1 px-2">曲</th>
                            <th class="text-right py-1 px-2">自分</th>
                            <th class="text-right py-1 px-2">相手</th>
                            <th class="text-left py-1 px-2">対戦相手</th>
                            <th class="text-center py-1 px-2">勝敗</th>
                            <th class="text-right py-1 px-2">pt</th>
                          </tr>
                        </thead>
                        <tbody>
                          <tr
                            v-for="song in row.songs"
                            :key="`${song.matchId}-${song.slot}`"
                            class="border-b border-slate-100/70 dark:border-slate-700/40"
                          >
                            <!-- 「未設定」= matchupOrder 未採番の matchup。起用だけ入っている場合に出る。 -->
                            <td class="py-1 px-2 whitespace-nowrap text-slate-500">
                              <span v-if="song.isFinals" class="text-amber-600 dark:text-amber-300 font-bold">🏆 決勝</span>
                              <span v-else-if="song.matchupOrder > 0">第{{ song.matchupOrder }}試合</span>
                              <span v-else class="text-slate-400">未設定</span>
                            </td>
                            <td class="py-1 px-2 whitespace-nowrap text-slate-500">{{ KIND_LABEL[song.kind] }}</td>
                            <td class="py-1 px-2 max-w-[220px] truncate" :title="song.title ?? ''">
                              <span v-if="song.title">{{ song.title }}</span>
                              <span v-else class="text-slate-400">未決定</span>
                              <span class="ml-1 text-[10px] text-slate-400">({{ song.slot }}曲目)</span>
                            </td>
                            <td class="py-1 px-2 text-right tabular-nums font-bold">
                              {{ song.ownScore ?? '-' }}
                            </td>
                            <td class="py-1 px-2 text-right tabular-nums text-slate-500">
                              {{ song.oppScore ?? '-' }}
                            </td>
                            <td class="py-1 px-2 truncate">
                              {{ song.opponentName }}
                              <span class="ml-1 text-[10px]" :class="teamColorClass(song.opponentTeamName)">{{ song.opponentTeamName }}</span>
                            </td>
                            <td class="py-1 px-2 text-center font-bold" :class="personalResultClass(song.result)">
                              {{ personalResultLabel(song.result) }}
                            </td>
                            <td class="py-1 px-2 text-right tabular-nums" :class="song.points > 0 ? 'font-bold' : 'text-slate-400'">
                              {{ song.points > 0 ? `+${song.points}` : '-' }}
                            </td>
                          </tr>
                        </tbody>
                      </table>
                    </td>
                  </tr>
                </template>
              </tbody>
            </table>
          </div>
        </section>

        <!-- 起用クローズ日時 (JST): 手動ロックの代替。設定時刻を過ぎると TL 起用編集のみ締切 (プレイヤー自選曲提出は対象外)。 -->
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

        <!-- 起用公開日時 (JST): この時刻を過ぎるとオーダー(起用)が対戦相手・観戦URL・選手URLに自動公開される。起用クローズ日時とは独立。 -->
        <section
          v-if="currentCompetition.status !== 'draft'"
          class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-md p-4 space-y-3"
        >
          <div class="flex items-center justify-between flex-wrap gap-2">
            <h2 class="text-sm font-bold text-slate-500">
              起用公開日時 (JST)
            </h2>
            <span
              class="text-[11px] font-bold px-2 py-0.5 rounded"
              :class="currentCompetition.lineupPublished
                ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300'
                : 'bg-slate-100 text-slate-600 dark:bg-slate-700/40 dark:text-slate-300'"
            >
              {{ currentCompetition.lineupPublished ? '📢 オーダー公開済み' : '🕒 公開前' }}
            </span>
          </div>
          <p class="text-[11px] text-slate-500">
            設定した日時を過ぎると、<b>オーダー (起用) が対戦相手・観戦 URL・選手 URL に自動公開</b>されます。
            起用クローズ日時とは独立に設定できます (例: クローズ後しばらくしてから公開)。日時はあなたの端末のローカル時刻 = JST 想定で扱われます。
          </p>
          <div class="flex items-center gap-2 flex-wrap">
            <input
              type="datetime-local"
              v-model="lineupPublishInput"
              :disabled="currentCompetition.status === 'finished'"
              class="flex-1 min-w-[200px] px-3 py-2 text-sm rounded-lg bg-slate-50 dark:bg-slate-900 border border-slate-300 dark:border-slate-600 outline-none focus:border-blue-400 disabled:opacity-50"
            />
            <button
              type="button"
              @click="handleSaveLineupPublishAt"
              :disabled="isSavingLineupPublish || currentCompetition.status === 'finished'"
              class="shrink-0 px-4 py-2 rounded-md text-xs font-bold bg-emerald-600 hover:bg-emerald-700 text-white disabled:bg-slate-300"
            >保存</button>
            <button
              v-if="currentCompetition.lineupPublishAt"
              type="button"
              @click="handleClearLineupPublishAt"
              :disabled="isSavingLineupPublish || currentCompetition.status === 'finished'"
              class="shrink-0 px-3 py-2 rounded-lg text-xs font-bold bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600 disabled:opacity-50"
            >公開日時解除</button>
          </div>
          <p v-if="currentCompetition.lineupPublishAt" class="text-[10px] font-mono text-slate-400">
            現在の設定: {{ new Date(currentCompetition.lineupPublishAt).toLocaleString('ja-JP') }}
          </p>
          <p v-else class="text-[10px] font-mono text-slate-400">
            未設定 (自動公開しない)。日時を入れて「保存」すると有効になります。
          </p>
        </section>

        <!--
          決勝のスケジュール (JST)。決勝は予選終了後に生成されるため、予選のクローズ/公開日時とは別枠。
          どちらも未設定の間は「決勝の起用は編集可 & 非公開」= 決勝生成直後の状態。
        -->
        <section
          v-if="finalsGenerated"
          class="bg-white dark:bg-slate-800 border border-amber-300 dark:border-amber-700 rounded-md p-4 space-y-4"
        >
          <div class="flex items-center justify-between flex-wrap gap-2">
            <h2 class="text-sm font-bold text-amber-600 dark:text-amber-300">🏆 決勝のスケジュール (JST)</h2>
            <div class="flex items-center gap-2 flex-wrap">
              <span
                class="text-[11px] font-bold px-2 py-0.5 rounded"
                :class="currentCompetition.finalsLineupClosed
                  ? 'bg-rose-100 text-rose-700 dark:bg-rose-900/40 dark:text-rose-300'
                  : 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300'"
              >
                {{ currentCompetition.finalsLineupClosed ? '🔒 決勝の起用クローズ済み' : '✏ 決勝の起用受付中' }}
              </span>
              <span
                class="text-[11px] font-bold px-2 py-0.5 rounded"
                :class="currentCompetition.finalsLineupPublished
                  ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300'
                  : 'bg-slate-100 text-slate-600 dark:bg-slate-700/40 dark:text-slate-300'"
              >
                {{ currentCompetition.finalsLineupPublished ? '📢 決勝のオーダー公開済み' : '🕒 決勝のオーダー非公開' }}
              </span>
            </div>
          </div>
          <p class="text-[11px] text-slate-500">
            決勝 (7 戦) は予選とは別スケジュールです。<b>両方とも未設定の間は「進出 2 チームの TL が決勝の起用を編集でき、
            起用内容は相手・観戦 URL・選手 URL に非公開」</b>の状態になります。選手の自選曲提出は締切対象外です。
          </p>

          <div class="space-y-2">
            <p class="text-[11px] font-bold text-slate-500">決勝の起用クローズ日時</p>
            <div class="flex items-center gap-2 flex-wrap">
              <input
                type="datetime-local"
                v-model="finalsDeadlineInput"
                :disabled="currentCompetition.status === 'finished'"
                class="flex-1 min-w-[200px] px-3 py-2 text-sm rounded-lg bg-slate-50 dark:bg-slate-900 border border-slate-300 dark:border-slate-600 outline-none focus:border-blue-400 disabled:opacity-50"
              />
              <button
                type="button"
                @click="handleSaveFinalsDeadline"
                :disabled="isSavingFinalsDeadline || currentCompetition.status === 'finished'"
                class="shrink-0 px-4 py-2 rounded-md text-xs font-bold bg-emerald-600 hover:bg-emerald-700 text-white disabled:bg-slate-300"
              >保存</button>
              <button
                v-if="currentCompetition.finalsDeadlineAt"
                type="button"
                @click="handleClearFinalsDeadline"
                :disabled="isSavingFinalsDeadline || currentCompetition.status === 'finished'"
                class="shrink-0 px-3 py-2 rounded-lg text-xs font-bold bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600 disabled:opacity-50"
              >締切解除</button>
            </div>
            <p class="text-[10px] font-mono text-slate-400">
              {{ currentCompetition.finalsDeadlineAt
                ? '現在の設定: ' + new Date(currentCompetition.finalsDeadlineAt).toLocaleString('ja-JP')
                : '未設定 (決勝の起用を締め切らない)' }}
            </p>
          </div>

          <div class="space-y-2">
            <p class="text-[11px] font-bold text-slate-500">決勝の起用公開日時</p>
            <div class="flex items-center gap-2 flex-wrap">
              <input
                type="datetime-local"
                v-model="finalsPublishInput"
                :disabled="currentCompetition.status === 'finished'"
                class="flex-1 min-w-[200px] px-3 py-2 text-sm rounded-lg bg-slate-50 dark:bg-slate-900 border border-slate-300 dark:border-slate-600 outline-none focus:border-blue-400 disabled:opacity-50"
              />
              <button
                type="button"
                @click="handleSaveFinalsPublishAt"
                :disabled="isSavingFinalsPublish || currentCompetition.status === 'finished'"
                class="shrink-0 px-4 py-2 rounded-md text-xs font-bold bg-emerald-600 hover:bg-emerald-700 text-white disabled:bg-slate-300"
              >保存</button>
              <button
                v-if="currentCompetition.finalsLineupPublishAt"
                type="button"
                @click="handleClearFinalsPublishAt"
                :disabled="isSavingFinalsPublish || currentCompetition.status === 'finished'"
                class="shrink-0 px-3 py-2 rounded-lg text-xs font-bold bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600 disabled:opacity-50"
              >公開日時解除</button>
            </div>
            <p class="text-[10px] font-mono text-slate-400">
              {{ currentCompetition.finalsLineupPublishAt
                ? '現在の設定: ' + new Date(currentCompetition.finalsLineupPublishAt).toLocaleString('ja-JP')
                : '未設定 (決勝の起用は非公開のまま)' }}
            </p>
          </div>
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
            <!--
              総合 (先鋒〜大将の全戦合計)。戦ポイント = 勝ち曲数 × 戦の配点 (予選 先鋒2/中堅3/大将4)
              で、多い側がこの matchup の勝ち。順位表に載る前に運営がこの場で確認できるよう最上部に出す。
            -->
            <div
              class="px-4 py-2 flex items-center gap-x-3 gap-y-1 flex-wrap bg-slate-100 dark:bg-slate-900"
            >
              <span class="text-[10px] font-bold text-slate-400 shrink-0">
                総合 ({{ matchupTotals[mu.id]?.total ?? 0 }} 戦合計)
              </span>
              <template v-if="(matchupTotals[mu.id]?.recorded ?? 0) > 0">
                <span class="font-mono font-bold text-lg tabular-nums leading-none">
                  <span :class="matchupTotals[mu.id].aPoints >= matchupTotals[mu.id].bPoints
                    ? teamColorClassById(mu.teamAId) : 'text-slate-400'">{{ matchupTotals[mu.id].aPoints }}</span>
                  <span class="text-slate-400 mx-1">-</span>
                  <span :class="matchupTotals[mu.id].bPoints >= matchupTotals[mu.id].aPoints
                    ? teamColorClassById(mu.teamBId) : 'text-slate-400'">{{ matchupTotals[mu.id].bPoints }}</span>
                  <span class="text-[10px] font-normal text-slate-400 ml-1">pt</span>
                </span>
                <!-- 勝敗は全戦記録済みで確定。途中は「途中経過」バッジに留める。 -->
                <span
                  v-if="matchupTotals[mu.id].winner === 'a' || matchupTotals[mu.id].winner === 'b'"
                  class="text-[11px] font-bold px-2 py-0.5 rounded bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300"
                >○ {{ teamNameOf(matchupTotals[mu.id].winner === 'a' ? mu.teamAId : mu.teamBId) }} 勝ち</span>
                <span
                  v-else-if="matchupTotals[mu.id].winner === 'draw'"
                  class="text-[11px] font-bold px-2 py-0.5 rounded bg-slate-200 text-slate-600 dark:bg-slate-700 dark:text-slate-300"
                >△ 引分</span>
                <span
                  v-else
                  class="text-[11px] font-bold px-2 py-0.5 rounded bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300"
                >途中経過</span>
                <span class="text-[10px] font-mono text-slate-400">
                  曲数 {{ matchupTotals[mu.id].aSongs }} - {{ matchupTotals[mu.id].bSongs }}
                  ・ {{ matchupTotals[mu.id].recorded }}/{{ matchupTotals[mu.id].total }} 戦記録済み
                </span>
              </template>
              <span v-else class="text-[11px] text-slate-400 italic">
                未記録 (各戦の結果を記録すると合計が出ます)
              </span>
            </div>

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
                    type="button"
                    @click="handleSwapMatchupSides(mu.id)"
                    :disabled="currentCompetition.status === 'finished'"
                    class="px-2 py-0.5 rounded text-[10px] font-bold bg-slate-200 dark:bg-slate-700 text-slate-500 hover:bg-blue-100 hover:text-blue-600 dark:hover:bg-blue-900/40 dark:hover:text-blue-300 disabled:opacity-50"
                    title="対戦の左右 (A側/B側) を入れ替える"
                  >⇄ 左右入替</button>
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
              <!-- 起用公開は起用公開日時 (lineupPublishAt) 到達で自動公開。手動公開は廃止。 -->
              <div class="text-[10px] font-mono">
                <span class="text-slate-400">起用公開:</span>
                <span v-if="mu.lineupPublished" class="ml-1 font-bold text-emerald-600 dark:text-emerald-300">✓ 公開中 (起用公開日時経過)</span>
                <span v-else class="ml-1 text-slate-400">起用公開日時を過ぎると自動公開</span>
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
                    <p class="text-[10px] font-mono text-slate-400">{{ kindLevelLabel(match.matchKind) }}</p>
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
                    <!-- StrategyCard の意思決定状況 (TL が「発動する / 発動しない」を選択) -->
                    <span
                      v-if="strategyStatusOf(match, 'a') === 'use'"
                      class="inline-block mt-0.5 text-[9px] font-bold px-1.5 py-0.5 rounded bg-fuchsia-600 text-white"
                    >⚡ 発動予定</span>
                    <span
                      v-else-if="strategyStatusOf(match, 'a') === 'skip'"
                      class="inline-block mt-0.5 text-[9px] font-bold px-1.5 py-0.5 rounded bg-slate-200 dark:bg-slate-700 text-slate-500"
                    >✓ 発動しない</span>
                    <span
                      v-else-if="strategyStatusOf(match, 'a') === 'undecided'"
                      class="inline-block mt-0.5 text-[9px] font-bold px-1.5 py-0.5 rounded bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300"
                    >⚠ 未決定</span>
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
                    <!-- StrategyCard の意思決定状況 (TL が「発動する / 発動しない」を選択) -->
                    <span
                      v-if="strategyStatusOf(match, 'b') === 'use'"
                      class="inline-block mt-0.5 text-[9px] font-bold px-1.5 py-0.5 rounded bg-fuchsia-600 text-white"
                    >⚡ 発動予定</span>
                    <span
                      v-else-if="strategyStatusOf(match, 'b') === 'skip'"
                      class="inline-block mt-0.5 text-[9px] font-bold px-1.5 py-0.5 rounded bg-slate-200 dark:bg-slate-700 text-slate-500"
                    >✓ 発動しない</span>
                    <span
                      v-else-if="strategyStatusOf(match, 'b') === 'undecided'"
                      class="inline-block mt-0.5 text-[9px] font-bold px-1.5 py-0.5 rounded bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300"
                    >⚠ 未決定</span>
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
                    <p class="text-slate-400 mb-2">
                      スコア記録 (曲 = A/B 自選曲もしくは Strategy 抽選曲から自動決定。曲欄のクリックで管理番号つき全曲から変更可)
                      <span class="text-slate-500">— 発動状況は開くたびに再取得。開いたまま反映したい場合は上部の 🔄 で再読込。</span>
                    </p>
                    <div class="space-y-2">
                      <!-- Song 1 (A 側演奏曲) -->
                      <div class="grid grid-cols-[60px_1fr_70px_70px] gap-2 items-center">
                        <span class="text-slate-500">1 曲目<br /><span class="text-[9px] text-slate-600">A 演奏</span></span>
                        <SongSelect
                          :strategy-id="editingAutoSongs?.song1.strategyId ?? null"
                          :title="editingAutoSongs?.song1.title ?? null"
                          :badge-label="autoSongSourceLabel(editingAutoSongs?.song1)"
                          :badge-class="autoSongSourceClass(editingAutoSongs?.song1)"
                          @select="(s) => handleSongSelected(1, s)"
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
                      <div class="grid grid-cols-[60px_1fr_70px_70px] gap-2 items-center">
                        <span class="text-slate-500">2 曲目<br /><span class="text-[9px] text-slate-600">B 演奏</span></span>
                        <SongSelect
                          :strategy-id="editingAutoSongs?.song2.strategyId ?? null"
                          :title="editingAutoSongs?.song2.title ?? null"
                          :badge-label="autoSongSourceLabel(editingAutoSongs?.song2)"
                          :badge-class="autoSongSourceClass(editingAutoSongs?.song2)"
                          @select="(s) => handleSongSelected(2, s)"
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
                    <!--
                      手動指定した枠を自動導出 (自選曲 / Strategy 抽選曲) に戻すための解除ボタン。
                      手動指定中は 🔄 再読込や抽選確定でも曲が変わらないので、戻す手段を明示しておく。
                    -->
                    <p v-if="manualOverride.song1 || manualOverride.song2" class="mt-2 flex items-center gap-2 flex-wrap text-sky-700 dark:text-sky-300">
                      <span>✎ 手動指定中の枠があります (自動導出より優先されます)</span>
                      <button
                        v-if="manualOverride.song1"
                        type="button"
                        @click="clearSongOverride(1)"
                        class="px-2 py-0.5 rounded bg-slate-200 text-slate-600 hover:bg-slate-300 dark:bg-slate-700 dark:text-slate-200 dark:hover:bg-slate-600"
                      >1 曲目を自動に戻す</button>
                      <button
                        v-if="manualOverride.song2"
                        type="button"
                        @click="clearSongOverride(2)"
                        class="px-2 py-0.5 rounded bg-slate-200 text-slate-600 hover:bg-slate-300 dark:bg-slate-700 dark:text-slate-200 dark:hover:bg-slate-600"
                      >2 曲目を自動に戻す</button>
                    </p>
                    <!-- 自動決定できなかった枠の注意書き (提出状況未取得 / 自選曲未提出) -->
                    <p
                      v-if="editingAutoSongs && (editingAutoSongs.song1.source === 'recorded' || editingAutoSongs.song1.source === 'none'
                        || editingAutoSongs.song2.source === 'recorded' || editingAutoSongs.song2.source === 'none')"
                      class="mt-2 text-amber-600 dark:text-amber-300"
                    >
                      ⚠ 自選曲 / 抽選曲を取得できない枠があります (提出状況の読込失敗、または自選曲未提出)。
                      <button type="button" @click="handleRefreshAll" :disabled="isRefreshingAll" class="underline hover:no-underline disabled:opacity-50">🔄 最新の状況に更新</button>
                    </p>
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

    <!--
      更新 FAB: 観戦ページと同じく右下に固定追従させる。運営は大会詳細を開いている間つねに
      押せる必要があるので個々のセクションではなくルート直下に置き、iOS のホームバーに
      被らないよう safe-area ぶん底を空ける。

      body へ Teleport しているのは、App.vue 側でこの View を包む .animate-fade-in が
      `animation: fadeIn ... forwards` で transform を残し続け、包含ブロックになってしまうため。
      そのままだと fixed がビューポートではなく管理画面ブロック基準になり追従しない。
    -->
    <Teleport to="body">
      <button
        v-if="isOrganizer && currentCompetition"
        type="button"
        aria-label="最新の状況に更新"
        :disabled="isRefreshingAll"
        @click="handleRefreshAll"
        title="提出状況・アサイン・ジャンル・試合結果・順位表・運営チャットをまとめて最新化します"
        class="fixed z-40 right-4 bottom-[calc(1rem_+_env(safe-area-inset-bottom))] flex items-center gap-1.5 pl-4 pr-5 py-3 rounded-full shadow-lg bg-blue-600 hover:bg-blue-700 active:scale-95 disabled:opacity-60 text-white text-sm font-bold transition-all"
      >
        <svg
          aria-hidden="true"
          class="h-5 w-5"
          :class="isRefreshingAll ? 'animate-spin' : ''"
          fill="none"
          viewBox="0 0 24 24"
          stroke="currentColor"
        >
          <path
            stroke-linecap="round"
            stroke-linejoin="round"
            stroke-width="2"
            d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"
          />
        </svg>
        {{ isRefreshingAll ? '更新中' : '更新' }}
      </button>
    </Teleport>

    <!-- 楽曲選択モーダル (個人戦の試合結果編集中のみアクティブ) -->
    <SongPickerModal
      :open="songPickerOpen"
      :current-title="songPickerTargetSlot ? draftSongTitle(songPickerTargetSlot) : null"
      @close="closeSongPicker"
      @select="handleSongPicked"
    />
  </div>
</template>

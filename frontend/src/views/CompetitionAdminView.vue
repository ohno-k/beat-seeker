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
import { ref, computed, onMounted } from 'vue';
import { useAuth } from '../composables/useAuth';
import {
  useCompetitionAdmin,
  type CompetitionParticipantDto,
  type CompetitionTeamDto,
  type CompetitionMatchDto,
  type CompetitionSongGenre,
  type CompetitionRevealData,
  type CompetitionStandingsDto,
  type MatchResultPayload,
} from '../composables/useCompetitionAdmin';
import { useToast } from '../composables/useToast';

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
  publishPick,
  setMatchLock,
  deleteCompetition,
  regenerateParticipantToken,
  regenerateTlToken,
  setMatchResult,
  clearMatchResult,
  fetchStandings,
  generateFinals,
  fetchRevealData,
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
const isCreating = ref(false);
const handleCreate = async () => {
  if (!createName.value.trim()) {
    toast.error('大会名を入力してください');
    return;
  }
  isCreating.value = true;
  try {
    await createCompetition(createName.value.trim());
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
    await refreshRevealData(id);
    await refreshStandings();
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

// ── 参加者編集 (TL 昇格 / 削除) ───────────────────────────
const handleToggleTl = async (p: CompetitionParticipantDto) => {
  if (!currentCompetition.value) return;
  try {
    await updateParticipant(currentCompetition.value.id, p.id, { isTl: !p.isTl });
    toast.success(p.isTl ? 'TL を解除しました' : 'TL に設定しました');
  } catch (e) {
    toast.error((e as Error).message);
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
  if (!confirm('この大会を open に遷移しますか?\n10 試合 × 3 戦 = 30 試合が生成されます。')) return;
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

const handleSetLock = async (matchId: number, side: 'a' | 'b' | 'both', locked: boolean) => {
  if (!currentCompetition.value) return;
  try {
    await setMatchLock(currentCompetition.value.id, matchId, side, locked);
    toast.success(locked ? 'ロックしました (編集禁止)' : 'ロックを解除しました');
  } catch (e) {
    toast.error((e as Error).message);
  }
};

// ── REVEAL 再生 (Song Reveal 連携) ────────────────────
/**
 * 試合 1 件分の reveal メタを返す (両側自選曲の提出有無を判定するため)。
 * revealData がまだロードされていない場合は null。
 */
const revealMatchOf = (matchId: number) => {
  if (!revealData.value) return null;
  return revealData.value.matches.find(rm => rm.matchId === matchId) ?? null;
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
    <div v-if="!isOrganizer" class="max-w-2xl mx-auto bg-rose-50 dark:bg-rose-900/30 border border-rose-200 dark:border-rose-700 rounded-2xl p-6 text-center">
      <p class="text-lg font-bold text-rose-700 dark:text-rose-300">大会管理画面</p>
      <p class="text-sm text-rose-600 dark:text-rose-400 mt-2">主催権限がありません。サイドバーから他のページへ戻ってください。</p>
    </div>

    <template v-else>
      <!-- ────────── 一覧モード ────────── -->
      <div v-if="!currentCompetition" class="max-w-5xl mx-auto space-y-6">
        <div>
          <h1 class="text-3xl font-black tracking-tight">大会管理</h1>
          <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">5 チーム × 4 名の総当たり団体戦を作成・編成する</p>
        </div>

        <!-- 新規作成カード -->
        <div class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-2xl p-5 shadow-sm">
          <p class="text-sm font-bold mb-3">新規大会を作成</p>
          <div class="flex flex-col sm:flex-row gap-2">
            <input
              v-model="createName"
              type="text"
              placeholder="大会名 (例: BPL 模擬戦 2026 春)"
              class="flex-1 px-4 py-2.5 bg-slate-50 dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded-xl outline-none focus:border-blue-400"
              :disabled="isCreating"
              @keydown.enter="handleCreate"
            />
            <button
              type="button"
              @click="handleCreate"
              :disabled="isCreating || !createName.trim()"
              class="px-6 py-2.5 rounded-xl font-bold bg-blue-600 text-white hover:bg-blue-700 disabled:bg-slate-300 dark:disabled:bg-slate-600 disabled:cursor-not-allowed transition-colors"
            >
              {{ isCreating ? '作成中…' : '作成' }}
            </button>
          </div>
        </div>

        <!-- 一覧 -->
        <div class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-2xl overflow-hidden">
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
              <span class="text-[10px] font-black px-2 py-0.5 rounded uppercase tracking-wider" :class="statusColor(c.status)">{{ statusLabel(c.status) }}</span>
              <svg class="h-4 w-4 text-slate-400" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7" /></svg>
            </li>
          </ul>
        </div>
      </div>

      <!-- ────────── 詳細モード ────────── -->
      <div v-else class="max-w-6xl mx-auto space-y-6">
        <!-- ヘッダ -->
        <div class="flex flex-wrap items-center gap-3">
          <button type="button" @click="backToList" class="px-3 py-1.5 rounded-xl text-sm font-bold bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600">
            ← 一覧へ
          </button>
          <h1 class="text-2xl sm:text-3xl font-black tracking-tight">{{ currentCompetition.name }}</h1>
          <span class="text-[10px] font-black px-2 py-0.5 rounded uppercase tracking-wider" :class="statusColor(currentCompetition.status)">{{ statusLabel(currentCompetition.status) }}</span>
          <p class="text-xs text-slate-500 font-mono">ID #{{ currentCompetition.id }}</p>

          <button
            v-if="currentCompetition.status === 'draft'"
            type="button"
            @click="handleOpenStatus"
            class="ml-auto px-5 py-2 rounded-xl text-sm font-black tracking-wider uppercase bg-gradient-to-r from-emerald-500 to-teal-500 text-white hover:from-emerald-600 hover:to-teal-600 shadow-sm"
          >
            ▶ Open に遷移
          </button>

          <!-- 削除ボタン (常時表示。2 段階確認付き) -->
          <button
            type="button"
            @click="handleDeleteCompetition(currentCompetition.id, currentCompetition.name)"
            :class="currentCompetition.status === 'draft' ? '' : 'ml-auto'"
            class="px-3 py-2 rounded-xl text-xs font-bold bg-rose-50 text-rose-600 hover:bg-rose-100 dark:bg-rose-900/30 dark:text-rose-300 border border-rose-200 dark:border-rose-800"
          >
            🗑 大会を削除
          </button>
        </div>

        <!-- 5 チームグリッド -->
        <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          <div
            v-for="team in currentCompetition.teams"
            :key="team.id"
            class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-2xl shadow-sm overflow-hidden"
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
                <p class="flex-1 font-bold truncate">{{ team.teamName }}</p>
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
                    <div class="flex items-center gap-1.5">
                      <p class="font-bold truncate">{{ m.displayName }}</p>
                      <span
                        v-if="m.isTl"
                        class="text-[9px] font-black px-1.5 py-0.5 rounded bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300 tracking-wider"
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
          class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-2xl p-4 space-y-3"
        >
          <div class="flex items-center justify-between flex-wrap gap-2">
            <h2 class="text-sm font-black tracking-[0.3em] uppercase text-slate-500">
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
              <tr class="text-[10px] font-mono uppercase text-slate-400 border-b border-slate-200 dark:border-slate-700">
                <th class="text-left py-1 px-2">順位</th>
                <th class="text-left py-1 px-2">チーム</th>
                <th class="text-right py-1 px-2">勝</th>
                <th class="text-right py-1 px-2">分</th>
                <th class="text-right py-1 px-2">負</th>
                <th class="text-right py-1 px-2">戦pt</th>
                <th class="text-right py-1 px-2 font-black text-slate-700 dark:text-slate-200">勝点</th>
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
                <td class="py-1.5 px-2 truncate">{{ row.teamName }}</td>
                <td class="py-1.5 px-2 text-right tabular-nums text-emerald-600 dark:text-emerald-300">{{ row.wins }}</td>
                <td class="py-1.5 px-2 text-right tabular-nums text-slate-500">{{ row.draws }}</td>
                <td class="py-1.5 px-2 text-right tabular-nums text-rose-500 dark:text-rose-400">{{ row.losses }}</td>
                <td class="py-1.5 px-2 text-right tabular-nums">{{ row.songPoints }}</td>
                <td class="py-1.5 px-2 text-right tabular-nums font-black">{{ row.matchupPoints }}</td>
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
              class="px-4 py-2 rounded-xl text-xs font-black tracking-wider uppercase transition-all"
              :class="standings.allPrelimRecorded
                ? 'bg-gradient-to-r from-amber-400 to-rose-500 text-white hover:shadow-lg'
                : 'bg-slate-300 dark:bg-slate-600 text-slate-500 cursor-not-allowed'"
            >
              🏆 決勝を生成
            </button>
          </div>
        </section>

        <!-- 途中経過マトリクス: 5×5 で各 matchup の row 視点の総合ポイントを表示 -->
        <section
          v-if="standings && currentCompetition.teams && currentCompetition.teams.length > 0"
          class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-2xl p-4 space-y-3"
        >
          <h2 class="text-sm font-black tracking-[0.3em] uppercase text-slate-500">途中経過</h2>
          <p class="text-[11px] text-slate-500">
            セル「自軍戦pt ○/×/△ 相手戦pt」: ○=行チームが勝ち / ×=負け / △=引分。「?」 = 未記録、「-」 = 同チーム同士。合計列は勝ち点合計 (matchup 勝点のみ)。
          </p>
          <div class="overflow-x-auto">
            <table class="text-xs border-collapse">
              <thead>
                <tr>
                  <th class="py-1 px-2 text-[10px] font-mono uppercase text-slate-400"></th>
                  <th
                    v-for="colTeam in currentCompetition.teams"
                    :key="colTeam.id"
                    class="py-2 px-3 text-[10px] font-mono uppercase text-slate-400 border-b border-slate-200 dark:border-slate-700 text-center min-w-[90px]"
                  >
                    {{ colTeam.teamName }}
                  </th>
                  <th class="py-2 px-3 text-[10px] font-mono uppercase text-slate-700 dark:text-slate-200 font-black border-b border-slate-200 dark:border-slate-700 text-center min-w-[80px]">
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
                  <th class="py-2 px-3 text-left text-xs font-bold whitespace-nowrap">
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
                  <td class="py-2 px-3 text-center tabular-nums font-black text-base bg-slate-50 dark:bg-slate-900/30">
                    {{ teamMatchupPoints(rowTeam.id) }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <!-- 対戦表: 全 30 試合に対する運営ジャンル指定 (open 以降のみ表示) -->
        <section
          v-if="currentCompetition.matchups && currentCompetition.matches && currentCompetition.matchups.length > 0"
          class="space-y-3"
        >
          <div class="flex items-center justify-between flex-wrap gap-2">
            <h2 class="text-sm font-black tracking-[0.3em] uppercase text-slate-500">
              対戦表 ({{ currentCompetition.matchups.length }} matchup / {{ currentCompetition.matches.length }} 試合)
            </h2>
            <button
              type="button"
              @click="handleRefreshRevealData"
              class="px-3 py-1 text-[10px] font-bold rounded-lg bg-slate-200 dark:bg-slate-700 hover:bg-slate-300 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200"
              title="プレイヤーの提出状況を最新化します"
            >🔄 提出状況を再読込</button>
          </div>
          <p class="text-[11px] text-slate-500 leading-relaxed">
            各試合の運営指定ジャンルをセレクタから設定します。プレイヤーは指定されたジャンルの曲しか提出できません。<br />
            プレイヤーへのアサインは TL 専用 URL からチームごとに行います。両側の自選曲が揃った試合は「▶ REVEAL」で演出ページを開けます。
          </p>

          <div
            v-for="mu in currentCompetition.matchups"
            :key="mu.id"
            class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-2xl overflow-hidden"
          >
            <!-- matchup ヘッダ + ラインアップ公開トグル -->
            <div class="px-4 py-3 border-b border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/60 space-y-2">
              <div class="flex items-center justify-between flex-wrap gap-2">
                <p class="font-bold text-sm">
                  <span class="text-blue-600 dark:text-blue-400">{{ teamNameOf(mu.teamAId) }}</span>
                  <span class="text-slate-400 mx-2">vs</span>
                  <span class="text-blue-600 dark:text-blue-400">{{ teamNameOf(mu.teamBId) }}</span>
                  <span v-if="mu.isFinals" class="ml-2 text-[10px] font-black px-2 py-0.5 rounded bg-gradient-to-r from-amber-400 to-rose-500 text-white tracking-wider">
                    🏆 FINALS
                  </span>
                </p>
                <p class="text-[10px] font-mono text-slate-400 tracking-[0.25em] uppercase">
                  Matchup #{{ mu.matchupOrder }}
                </p>
              </div>
              <!-- ラインアップ公開ボタン群 -->
              <div class="flex items-center gap-2 flex-wrap text-[10px] font-mono">
                <span class="text-slate-400 uppercase tracking-wider">起用公開:</span>
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

                  <!-- A 側プレイヤー -->
                  <div class="text-xs">
                    <p class="text-[10px] font-mono text-slate-400 uppercase">A 側 ({{ teamNameOf(mu.teamAId) }})</p>
                    <p class="font-bold truncate" :class="match.playerAId ? '' : 'italic text-slate-400'">
                      {{ participantNameOf(match.playerAId) }}
                    </p>
                  </div>

                  <!-- B 側プレイヤー -->
                  <div class="text-xs">
                    <p class="text-[10px] font-mono text-slate-400 uppercase">B 側 ({{ teamNameOf(mu.teamBId) }})</p>
                    <p class="font-bold truncate" :class="match.playerBId ? '' : 'italic text-slate-400'">
                      {{ participantNameOf(match.playerBId) }}
                    </p>
                  </div>

                  <!-- ジャンル指定セレクタ -->
                  <div>
                    <p class="text-[10px] font-mono text-slate-400 uppercase mb-1">指定ジャンル</p>
                    <select
                      :value="match.requiredGenre ?? ''"
                      @change="handleGenreChange(match, ($event.target as HTMLSelectElement).value)"
                      :disabled="currentCompetition.status === 'finished'"
                      class="w-full px-2 py-1.5 rounded-lg text-sm bg-white dark:bg-slate-800 border outline-none focus:border-blue-400 disabled:opacity-50"
                      :class="match.requiredGenre
                        ? 'border-emerald-300 dark:border-emerald-700'
                        : 'border-slate-300 dark:border-slate-600'"
                    >
                      <option value="">未指定</option>
                      <option v-for="g in genresForKind(match.matchKind)" :key="g" :value="g">{{ g }}</option>
                    </select>
                  </div>
                </div>

                <!-- ロックトグル群 (締切時刻に編集禁止に切替) -->
                <div class="flex items-center gap-2 flex-wrap text-[10px] font-mono pt-1 border-t border-slate-100 dark:border-slate-700/40">
                  <span class="text-slate-400 uppercase tracking-wider">ロック:</span>
                  <button
                    type="button"
                    @click="handleSetLock(match.id, 'a', !match.lockedA)"
                    class="px-2 py-1 rounded transition-colors"
                    :class="match.lockedA
                      ? 'bg-amber-500 text-white hover:bg-amber-600'
                      : 'bg-slate-200 dark:bg-slate-700 text-slate-500 hover:bg-slate-300 dark:hover:bg-slate-600'"
                  >
                    A 側 {{ match.lockedA ? '🔒 ロック中' : '未ロック' }}
                  </button>
                  <button
                    type="button"
                    @click="handleSetLock(match.id, 'b', !match.lockedB)"
                    class="px-2 py-1 rounded transition-colors"
                    :class="match.lockedB
                      ? 'bg-amber-500 text-white hover:bg-amber-600'
                      : 'bg-slate-200 dark:bg-slate-700 text-slate-500 hover:bg-slate-300 dark:hover:bg-slate-600'"
                  >
                    B 側 {{ match.lockedB ? '🔒 ロック中' : '未ロック' }}
                  </button>
                  <button
                    type="button"
                    @click="handleSetLock(match.id, 'both', !(match.lockedA && match.lockedB))"
                    class="px-2 py-1 rounded bg-violet-500 text-white hover:bg-violet-600 ml-auto"
                  >
                    {{ match.lockedA && match.lockedB ? '両方解除' : '両方ロック' }}
                  </button>
                </div>

                <!-- 結果記録 UI (R-4: 曲管理番号 + スコア入力 → 勝敗自動表示) -->
                <div class="pt-1 border-t border-slate-100 dark:border-slate-700/40 text-[10px] font-mono">
                  <!-- 折り畳み: 編集中以外は 1 行サマリ -->
                  <template v-if="resultEditingMatchId !== match.id">
                    <div class="flex items-center gap-2 flex-wrap">
                      <span class="text-slate-400 uppercase tracking-wider">結果:</span>
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
                    <p class="text-slate-400 uppercase tracking-wider mb-2">スコア記録 (曲名 = A/B 自選曲もしくは Strategy 抽選曲を自動入力)</p>
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
                      <span class="text-slate-400 uppercase tracking-wider">判定:</span>
                      <span class="font-bold tabular-nums text-sm">
                        A {{ draftWinnerPreview.a }} - {{ draftWinnerPreview.b }} B
                      </span>
                      <span
                        class="text-[10px] font-black px-2 py-0.5 rounded uppercase tracking-wider"
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
                  <span class="text-slate-400 uppercase tracking-wider">Reveal:</span>
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
                    class="ml-auto px-3 py-1 rounded bg-gradient-to-r from-cyan-500 via-sky-500 to-amber-500 text-white font-bold tracking-wider uppercase hover:shadow-md transition-all"
                    title="新規タブで Song Reveal を開く"
                  >
                    ▶ REVEAL を再生
                  </button>
                </div>

                <!-- 自選曲公開トグル群 (試合直前に公開する想定) -->
                <div class="flex items-center gap-2 flex-wrap text-[10px] font-mono">
                  <span class="text-slate-400 uppercase tracking-wider">選曲公開:</span>
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
      </div>
    </template>
  </div>
</template>

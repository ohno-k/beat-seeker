/**
 * 【Composable の役割】 INFINITAS のリザルト画面を `getDisplayMedia` で監視し、
 * 固定座標のピクセル照合（inf-notebook 方式）でスコア情報を抽出する。**OCR は使わない。**
 *
 * 動作フロー:
 *  1. start(video) で画面共有を開始し、video 要素にストリームをバインド
 *  2. 800ms ごとに現フレームを FHD canvas に転写し、「PLAYER 01/02」ラベルのテンプレ照合で
 *     リザルト画面か否か & 1P/2P を検出（[[file:infinitasResultRecognizer]] の detectResultScreen）
 *  3. 検出したら全フィールドをピクセル認識 → InfinitasResult に整形
 *  4. onResult コールバックで結果を返す（親で確認モーダルを表示）
 *  5. resumeMonitoring() で再開（同一スコアの再抽出は 30 秒の重複抑制でブロック）
 *
 * 認識方式（すべて infinitasResultRecognizer に委譲）:
 *  - EX SCORE / MISS COUNT / NOTES … 数字テンプレ hash
 *  - PGREAT … JUDGE 先頭行の数字 hash（great は EX_SCORE - 2*PGREAT で逆算）
 *  - CLEAR TYPE … クリアタイプ・ハイライトの色分類（CYAN は文字幅で CLEAR/F-COMBO を判別）
 *  - DJ LEVEL … score/(notes×2) から計算
 *  - 難易度(ANOTHER/LEGGENDARIA) … 譜面情報行の色サンプリング
 *  - SP/DP … 中央ロゴのテンプレ照合
 *  - 曲特定 … (難易度, NOTES 数) で songData を逆引き（OCR の曲名読み取りは廃止）
 *
 * 前提: INFINITAS は FHD (1920x1080)。画面共有はフルスクリーン推奨。
 */
import { ref, readonly } from 'vue';
import { songData, type SongDataEntry } from './useGameData';
import {
  recognizeResultPanel,
  detectResultScreen,
  resolvePanelOrigin,
  captureSongTitleSnapshot,
  computeDjLevel,
  recognizeNumberFieldWithRecord,
  recognizeNotesWithRecord,
  recognizePgreatWithRecord,
  recognizeBpm,
  PANEL_LOCAL_ROI,
  type NumberFieldRecord,
  type RecognizerPlaySide,
} from './infinitasResultRecognizer';
import { matchSongTitleByOcr, terminateSongTitleOcr } from './infinitasSongTitleOcr';
import type { ImportDecision } from './infinitasAutoImport';

/** 認識した 1 リザルト分のデータ。null は「読み取れなかった」を意味する。 */
export interface InfinitasResult {
  /** (難易度, NOTES) 逆引きで一意特定できた songData エントリ。複数候補/不明なら null。 */
  songEntry: SongDataEntry | null;
  /** 互換のため残置（OCR 廃止により常に空文字）。 */
  ocrTitle: string;
  /** 互換のため残置（OCR 廃止により常に空文字）。 */
  ocrArtist: string;
  /** ANOTHER または LEGGENDARIA（[[feedback_difficulty_scope]] により他は対象外）。 */
  difficulty: 'ANOTHER' | 'LEGGENDARIA' | null;
  /** SP / DP の判別。 */
  playSide: 'SP' | 'DP' | null;
  // ── 今回プレイ（CURRENT）列 ──
  /** EX SCORE 数値。 */
  score: number | null;
  /** ミスカウント。 */
  missCount: number | null;
  /** AAA / AA / A / B / C / D / E / F のいずれか（score/notes から計算）。 */
  djLevel: string | null;
  /** "FAILED" / "EASY CLEAR" / "CLEAR" / "HARD CLEAR" / "EX HARD CLEAR" / "FULLCOMBO CLEAR"。 */
  clearType: string | null;
  /** PGREAT。 */
  pgreat: number | null;
  /** GREAT（EX_SCORE - 2*PGREAT で逆算）。 */
  great: number | null;
  /** GOOD（ピクセル認識対象外。常に null）。 */
  good: number | null;
  /** BAD（同上）。 */
  bad: number | null;
  /** POOR（同上）。 */
  poor: number | null;
  // ── 自己ベスト（BEST）列 ──
  /** 自己ベスト EX SCORE 数値。 */
  bestScore: number | null;
  /** 自己ベスト DJ LEVEL（score/notes から計算）。 */
  bestDjLevel: string | null;
  /** 自己ベスト クリアタイプ。 */
  bestClearType: string | null;
  /** 自己ベスト ミスカウント。 */
  bestMissCount: number | null;
  /** 譜面情報行から読み取った NOTES 数。曲特定の主キー。 */
  notesCount: number | null;
  /** 検出時刻。重複抑制に使う。 */
  capturedAt: Date;
  /** 確認モーダルでユーザーに見せるためのスナップショット（dataURL）。 */
  snapshot: string;
  /** 曲名 ROI 部分の dataURL。視認で曲を確かめる用。 */
  titleSnapshot: string | null;
  /** (難易度, NOTES 数) で絞り込んだ候補曲リスト。1 件なら songEntry に自動採用済み。 */
  candidates: SongDataEntry[];
  /**
   * 各数字フィールドの cell-hash 記録（auto-learn 用）。
   * 確認モーダルでユーザーが値を確定 → `learnFromConfirmation` で未知 hash を学習辞書へ追加。
   */
  hashRecords: {
    scoreCurrent: NumberFieldRecord;
    scoreBest: NumberFieldRecord;
    missCountCurrent: NumberFieldRecord;
    missCountBest: NumberFieldRecord;
    notes: NumberFieldRecord;
    pgreat: NumberFieldRecord;
  } | null;
}

/** 監視状態。 */
export type MonitorStatus =
  | 'idle'           // 未開始
  | 'initializing'   // 画面共有ストリーム取得中
  | 'monitoring'     // 検出ループ稼働中
  | 'extracting'     // リザルト検出 → 全フィールド認識中
  | 'awaiting'       // 親側で確認モーダル表示中（検出ループは停止）
  | 'error';         // エラー

/** ユーザーのプレイサイド指定。"1P" / "2P" / "auto"（PLAYER ラベルで自動判定）。 */
export type PlaySide = '1P' | '2P' | 'auto';

/** 検出ループの間隔（ms）。 */
const DETECTION_INTERVAL_MS = 800;
/**
 * リザルト画面を検出してから最初に抽出するまでの最小待機時間（ms）。
 *
 * INFINITAS のリザルト演出は約1秒以内で、EX SCORE はカウントアップせず演出明けに最終値が
 * 「ぱっと」表示される（＝途中値は無い）。よって長い固定待ちは不要で、演出が始まった瞬間に
 * 抽出して 0/空 を掴むのだけ避けられればよい。最小限の猶予として 300ms。
 *
 * その後は EX SCORE が出る（classify が skip を返さなくなる）まで SETTLE_POLL_MS 間隔で
 * ポーリングし、演出明けを検知した直後に確定する。
 */
const RESULT_SETTLE_DELAY_MS = 300;
/** 演出明け待ちのポーリング間隔（ms）。演出は約1秒なので 300ms で素早く拾う。 */
const SETTLE_POLL_MS = 300;
/** 同一スコアの再検出を抑制する期間（ms）。INFINITAS のリザルト表示時間より長め。 */
const DUPLICATE_SUPPRESS_MS = 30_000;

/**
 * @param playSide ユーザー登録のプレイサイド。
 * @param classify 抽出結果を auto/manual/skip に分類する関数。skip は「未確定（演出中等）」として
 *   無視し監視を継続する。未指定なら常に 'manual'（必ず確認モーダル）= 従来挙動。
 */
export function useInfinitasMonitor(
  playSide: PlaySide = 'auto',
  classify: (result: InfinitasResult) => ImportDecision = () => 'manual',
) {
  const status = ref<MonitorStatus>('idle');
  const errorMessage = ref('');
  /** 検出ループの起動からの累計検出回数（UI 表示用）。 */
  const detectionCount = ref(0);
  /** 直近の検出（履歴表示用、最大 10 件）。 */
  const recentResults = ref<InfinitasResult[]>([]);

  let stream: MediaStream | null = null;
  let videoEl: HTMLVideoElement | null = null;
  /** 認識用 FHD canvas（毎フレーム再利用）。 */
  let fhdCanvas: HTMLCanvasElement | null = null;
  let detectionTimer: number | null = null;
  let onResultDetected: ((result: InfinitasResult, decision: ImportDecision) => void) | null = null;

  /** 直近で確定したリザルトの (曲|score|diff) → タイムスタンプ。重複抑制用。 */
  const recentHashes = new Map<string, number>();

  /** リザルト画面を最初に検出した時刻（ms）。演出待機の起点。未検出なら null。 */
  let resultFirstSeenAt: number | null = null;

  /**
   * プレイ中に観測した BPM。リザルトより前のプレイ画面で読み取り、曲特定の補助に使う。
   * （BPM 読み取りは別途実装予定。未取得時は null で、(難易度, NOTES) のみで絞り込む。）
   */
  let observedBpm: number | null = null;

  /**
   * 現フレームを 1920x1080 の認識用 canvas へ転写して返す。
   * 入力映像が FHD でなくてもここで FHD にリサイズするため、ピクセル ROI が一定になる。
   */
  function captureFhdCanvas(): HTMLCanvasElement | null {
    if (!videoEl) return null;
    const vw = videoEl.videoWidth, vh = videoEl.videoHeight;
    if (vw === 0 || vh === 0) return null;
    if (!fhdCanvas) {
      fhdCanvas = document.createElement('canvas');
      fhdCanvas.width = 1920;
      fhdCanvas.height = 1080;
    }
    const ctx = fhdCanvas.getContext('2d', { willReadFrequently: true });
    if (!ctx) return null;
    ctx.imageSmoothingEnabled = true;
    ctx.imageSmoothingQuality = 'high';
    ctx.drawImage(videoEl, 0, 0, 1920, 1080);
    return fhdCanvas;
  }

  /** モーダル表示用に現フレームを小さい dataURL として保存する。 */
  function captureSnapshot(): string {
    if (!videoEl) return '';
    const w = videoEl.videoWidth, h = videoEl.videoHeight;
    if (w === 0 || h === 0) return '';
    const c = document.createElement('canvas');
    const targetW = 480;
    const scale = targetW / w;
    c.width = targetW;
    c.height = Math.round(h * scale);
    const ctx = c.getContext('2d');
    if (!ctx) return '';
    ctx.drawImage(videoEl, 0, 0, c.width, c.height);
    return c.toDataURL('image/jpeg', 0.7);
  }

  /**
   * 観測 BPM が songData の bpm 表記に合致するか判定する。
   *  - 単一 "155"      → observed == 155
   *  - 範囲 "33-130"   → 33 <= observed <= 130（変化曲: プレイ中に観測した値が範囲内なら可）
   *  - 不明 "〓" 等     → 判定不能 → ワイルドカード（除外しない）
   * observed が null（BPM 未取得）の場合も true（フィルタしない）。
   */
  function bpmMatches(songBpm: string, observed: number | null): boolean {
    if (observed == null) return true;
    const b = String(songBpm).trim();
    const m = b.match(/^(\d+)\s*-\s*(\d+)$/);
    if (m) {
      const lo = Number(m[1]), hi = Number(m[2]);
      return observed >= lo && observed <= hi;
    }
    const single = Number(b);
    if (Number.isNaN(single)) return true; // 不明 BPM はワイルドカード
    return observed === single;
  }

  /**
   * (難易度, NOTES 数[, BPM]) で songData をフィルタする。
   * INFINITAS は (難易度, notes) でほぼユニーク。さらに観測 BPM を足すと衝突が大幅に減る
   * （実測: 525 衝突グループ → BPM 追加で 113 まで減少）。ANOTHER=4 / LEGGENDARIA=10。
   *
   * @param observedBpm プレイ中に観測した BPM（変化曲はその時点の値）。null ならノーツ+難易度のみ。
   */
  function findCandidatesByChartInfo(
    diff: 'ANOTHER' | 'LEGGENDARIA' | null,
    notes: number | null,
    observedBpm: number | null = null,
  ): SongDataEntry[] {
    if (!diff || !notes) return [];
    const diffCode = diff === 'LEGGENDARIA' ? '10' : '4';
    return songData.value.filter(s =>
      s.difficulty === diffCode &&
      s.notes === notes &&
      bpmMatches(s.bpm, observedBpm),
    );
  }

  /**
   * 全フィールドをピクセル認識して InfinitasResult を組み立てる。
   * @param side 検出した 1P/2P。
   */
  function extractResult(side: RecognizerPlaySide): InfinitasResult {
    const snapshot = captureSnapshot();
    const canvas = fhdCanvas;
    if (!canvas) {
      // 通常ここには来ない（呼び出し前に capture 済み）。保険で空結果を返す。
      return emptyResult(snapshot);
    }

    const rec = recognizeResultPanel(canvas, side);
    const origin = resolvePanelOrigin(canvas, side);

    // auto-learn 用の hash 記録
    const hashRecords = {
      scoreCurrent:     recognizeNumberFieldWithRecord(canvas, origin, PANEL_LOCAL_ROI.scoreCurrent),
      scoreBest:        recognizeNumberFieldWithRecord(canvas, origin, PANEL_LOCAL_ROI.scoreBest),
      missCountCurrent: recognizeNumberFieldWithRecord(canvas, origin, PANEL_LOCAL_ROI.missCountCurrent),
      missCountBest:    recognizeNumberFieldWithRecord(canvas, origin, PANEL_LOCAL_ROI.missCountBest),
      notes:            recognizeNotesWithRecord(canvas),
      pgreat:           recognizePgreatWithRecord(canvas, origin.x),
    };

    const diff = rec.difficulty;
    const notes = rec.notesCount;
    const scoreCurrent = rec.scoreCurrent;
    const scoreBest = rec.scoreBest;
    // GREAT は EX_SCORE-2*PGREAT で確定（IIDX のスコア定義により厳密）。JUDGE 行読みより確実。
    const great = (scoreCurrent != null && rec.pgreat != null) ? scoreCurrent - 2 * rec.pgreat : null;

    // 曲特定: (難易度, NOTES[, BPM]) 候補が一意なら自動採用。
    // observedBpm はプレイ中に観測した BPM（recognizeBpm で随時更新、未取得なら null）。
    const candidates = findCandidatesByChartInfo(diff, notes, observedBpm);
    const songEntry = candidates.length === 1 ? candidates[0] : null;
    // DJ LEVEL は EX SCORE / (notes×2) で計算。曲が一意特定できたらその notes を優先（より確実）。
    const notesForDj = songEntry?.notes ?? notes;

    const titleSnapshot = captureSongTitleSnapshot(canvas);

    return {
      songEntry,
      ocrTitle: '',
      ocrArtist: '',
      difficulty: diff,
      playSide: rec.playMode,
      // 今回プレイ列
      clearType: rec.clearTypeCurrent,
      djLevel: computeDjLevel(scoreCurrent, notesForDj),
      score: scoreCurrent,
      missCount: rec.missCountCurrent,
      pgreat: rec.pgreat,
      great,
      good: rec.good,
      bad: rec.bad,
      poor: rec.poor,
      // 自己ベスト列
      bestScore: scoreBest,
      bestDjLevel: computeDjLevel(scoreBest, notesForDj),
      bestClearType: rec.clearTypeBest,
      bestMissCount: rec.missCountBest,
      notesCount: notes,
      capturedAt: new Date(),
      snapshot,
      titleSnapshot,
      candidates,
      hashRecords,
    };
  }

  /** 認識失敗時の空結果。 */
  function emptyResult(snapshot: string): InfinitasResult {
    return {
      songEntry: null, ocrTitle: '', ocrArtist: '', difficulty: null, playSide: null,
      clearType: null, djLevel: null, score: null, missCount: null,
      pgreat: null, great: null, good: null, bad: null, poor: null,
      bestScore: null, bestDjLevel: null, bestClearType: null, bestMissCount: null,
      notesCount: null, capturedAt: new Date(), snapshot, titleSnapshot: null,
      candidates: [], hashRecords: null,
    };
  }

  /** 重複抑制チェック。同一 (曲|score|diff) が DUPLICATE_SUPPRESS_MS 以内に確定済みなら true。 */
  function dupKey(result: InfinitasResult): string {
    const id = result.songEntry?.title ?? `notes:${result.notesCount}`;
    return `${id}|${result.score}|${result.difficulty}`;
  }
  function isDuplicate(result: InfinitasResult): boolean {
    const now = Date.now();
    for (const [k, ts] of recentHashes) {
      if (now - ts > DUPLICATE_SUPPRESS_MS) recentHashes.delete(k);
    }
    return recentHashes.has(dupKey(result));
  }
  function markHandled(result: InfinitasResult): void {
    recentHashes.set(dupKey(result), Date.now());
  }

  /**
   * 検出ループ本体。setTimeout で自己再帰。
   *
   * フロー:
   *  1. PLAYER ラベルでリザルト画面 & 1P/2P を検出
   *  2. 検出した瞬間は抽出せず、RESULT_SETTLE_DELAY_MS だけ待つ（CLEAR/FULL COMBO 演出 &
   *     EX SCORE カウントアップの終了待ち）。待機中は SETTLE_POLL_MS で再チェック
   *  3. 待機後に抽出 → classify で auto/manual/skip 判定
   *     - skip（まだ演出中=score 0 等）→ もう少し待って再抽出
   *     - auto/manual → onResult を発火（親が自動登録 or 確認モーダル）
   */
  async function detectionLoop(): Promise<void> {
    if (status.value !== 'monitoring') return;
    if (!videoEl || videoEl.readyState < 2 || videoEl.videoWidth === 0) {
      detectionTimer = window.setTimeout(detectionLoop, DETECTION_INTERVAL_MS);
      return;
    }

    const canvas = captureFhdCanvas();
    const detectedSide = canvas ? detectResultScreen(canvas) : null;
    if (status.value !== 'monitoring') return;

    if (detectedSide === null) {
      // リザルト画面ではない（プレイ中・曲間・選曲画面など）→ 演出待機をリセット
      resultFirstSeenAt = null;
      // プレイ中なら BPM 表示が出ているので随時読み取り、曲特定の補助に蓄える。
      // 選曲・曲間など BPM 非表示のフレームでは null が返るが、その場合は直前に読めた値を
      // 保持する（上書きしない）。リザルトはプレイ直後に来るため、最後に読めた BPM が
      // そのプレイ曲のものになる。次のリザルト確定時にリセットする。
      if (canvas) {
        const bpm = recognizeBpm(canvas);
        if (bpm != null) observedBpm = bpm;
      }
      detectionTimer = window.setTimeout(detectionLoop, DETECTION_INTERVAL_MS);
      return;
    }

    // リザルト画面を検出
    const now = Date.now();
    if (resultFirstSeenAt === null) {
      // 初検出 → 演出が終わるまで待つ
      resultFirstSeenAt = now;
      detectionTimer = window.setTimeout(detectionLoop, SETTLE_POLL_MS);
      return;
    }
    if (now - resultFirstSeenAt < RESULT_SETTLE_DELAY_MS) {
      // まだ演出待機中
      detectionTimer = window.setTimeout(detectionLoop, SETTLE_POLL_MS);
      return;
    }

    // 待機完了 → 抽出
    const useSide: RecognizerPlaySide = playSide === '1P' || playSide === '2P' ? playSide : detectedSide;
    status.value = 'extracting';
    try {
      const result = extractResult(useSide);
      const decision = classify(result);
      if (decision === 'skip') {
        // 待機後もまだ未確定（演出が長い等）→ さらに少し待って再挑戦
        resultFirstSeenAt = now;
        status.value = 'monitoring';
        detectionTimer = window.setTimeout(detectionLoop, SETTLE_POLL_MS);
        return;
      }
      // (難易度, NOTES[, BPM]) で一意特定できず候補が複数残ったときだけ、確定直前に曲名 ROI を
      // OCR し、文字列距離で最良候補へ絞る。OCR ワーカーはここで初めて遅延初期化される。
      // OCR でも決められなければ songEntry は null のまま（確認モーダルでユーザーが選ぶ）。
      if (result.songEntry === null && result.candidates.length >= 2 && fhdCanvas) {
        const ocrMatch = await matchSongTitleByOcr(fhdCanvas, result.candidates);
        // await 中に stop()/再開で状態が変わっていたら破棄して仕切り直す
        if (status.value !== 'extracting') return;
        if (ocrMatch) result.songEntry = ocrMatch;
      }
      if (!isDuplicate(result)) {
        markHandled(result);
        detectionCount.value++;
        recentResults.value = [result, ...recentResults.value].slice(0, 10);
        // このプレイの BPM は使い終わったのでリセット。次の曲のプレイ画面で読み直す。
        // （持ち越すと、次に BPM を読めなかった曲へ古い値が誤って効いてしまう）
        observedBpm = null;
        status.value = 'awaiting';
        onResultDetected?.(result, decision);
        // 親側で resumeMonitoring() が呼ばれるまで停止
        return;
      }
      status.value = 'monitoring';
    } catch (e: any) {
      console.warn('[infinitas-monitor] extraction failed:', e);
      status.value = 'monitoring';
    }

    detectionTimer = window.setTimeout(detectionLoop, DETECTION_INTERVAL_MS);
  }

  /**
   * 監視開始。video 要素にストリームをバインドし、検出ループを起動する。
   * @param video 親コンポーネント側の <video> 要素
   */
  async function start(video: HTMLVideoElement): Promise<void> {
    if (status.value !== 'idle' && status.value !== 'error') return;
    errorMessage.value = '';
    videoEl = video;

    try {
      status.value = 'initializing';
      stream = await navigator.mediaDevices.getDisplayMedia({
        video: { width: { ideal: 1920 }, height: { ideal: 1080 }, frameRate: { ideal: 5, max: 10 } },
        audio: false,
      });

      // 共有ダイアログから「停止」が押された時の検知
      stream.getVideoTracks()[0].addEventListener('ended', () => {
        if (status.value !== 'idle') stop();
      });

      videoEl.srcObject = stream;
      await videoEl.play();

      status.value = 'monitoring';
      detectionTimer = window.setTimeout(detectionLoop, DETECTION_INTERVAL_MS);
    } catch (e: any) {
      console.error('[infinitas-monitor] start failed:', e);
      errorMessage.value = e?.message || 'failed to start';
      status.value = 'error';
      cleanup();
    }
  }

  /** 検出ループを再開（親が確認モーダルを閉じたとき呼ぶ）。 */
  function resumeMonitoring(): void {
    if (status.value !== 'awaiting') return;
    status.value = 'monitoring';
    detectionTimer = window.setTimeout(detectionLoop, DETECTION_INTERVAL_MS);
  }

  /** 監視停止 + リソース解放。 */
  function stop(): void {
    status.value = 'idle';
    if (detectionTimer !== null) {
      clearTimeout(detectionTimer);
      detectionTimer = null;
    }
    cleanup();
    terminateSongTitleOcr();
    recentResults.value = [];
    detectionCount.value = 0;
    recentHashes.clear();
    resultFirstSeenAt = null;
    observedBpm = null;
  }

  /** リソース解放（内部用）。 */
  function cleanup(): void {
    if (stream) {
      stream.getTracks().forEach(t => t.stop());
      stream = null;
    }
    if (videoEl) {
      try { videoEl.srcObject = null; } catch { /* ignore */ }
    }
    fhdCanvas = null;
  }

  /** リザルト検出時のコールバック登録。第 2 引数 decision は auto/manual。 */
  function onResult(cb: (result: InfinitasResult, decision: ImportDecision) => void): void {
    onResultDetected = cb;
  }

  return {
    /** 監視状態。 */
    status: readonly(status),
    /** エラーメッセージ。 */
    errorMessage: readonly(errorMessage),
    /** 累計検出数。 */
    detectionCount: readonly(detectionCount),
    /** 直近の検出結果（最大 10 件、新しい順）。 */
    recentResults: readonly(recentResults),
    /** 監視開始。 */
    start,
    /** 監視停止 + リソース解放。 */
    stop,
    /** 確認モーダルを閉じたあとに検出ループを再開する。 */
    resumeMonitoring,
    /** リザルト検出時に呼ばれるコールバックを登録する。 */
    onResult,
  };
}

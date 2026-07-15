import { API_BASE, TOKEN_KEY } from './constants';

/**
 * 【Composable の役割】 ユーザー ⇄ 運営「お問い合わせチャット」API (`/api/support/**`) の薄いラッパ。
 *
 * - ユーザー側: 右下フローティングウィジェット ({@code SupportChatWidget.vue}) から利用。
 * - 運営側: 管理者パネルの「お問い合わせ」モーダル ({@code AdminSupportChatModal.vue}) から利用。
 *
 * すべて要ログイン。管理者エンドポイントの管理者判定はサーバ側 (SupportChatController) で行う。
 */

/** お問い合わせ 1 メッセージ。sender = 'user' (問い合わせ主) / 'admin' (運営)。 */
export interface SupportMessageDto {
  id: number;
  sender: 'user' | 'admin';
  body: string;
  createdAt: string;
}

/** ユーザー自身のスレッド取得レスポンス。 */
export interface MySupportChatDto {
  messages: SupportMessageDto[];
  /** 運営からの未読返信件数 (ウィジェットの未読バッジ用)。 */
  unreadCount: number;
}

/** 管理画面のスレッド一覧 1 件 (ユーザー単位)。 */
export interface SupportThreadDto {
  userId: number;
  displayName: string | null;
  iidxId: string | null;
  danRank: string | null;
  arenaRank: string | null;
  messageCount: number;
  /** このユーザーからの未読 (運営未読) 件数。 */
  unreadCount: number;
  lastMessageBody: string;
  lastMessageAt: string;
  lastSender: 'user' | 'admin';
}

/** Authorization ヘッダを共通生成 (useAuth と同じ TOKEN_KEY を参照)。 */
function authHeaders(): Record<string, string> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  const token = localStorage.getItem(TOKEN_KEY);
  if (token) headers['Authorization'] = `Bearer ${token}`;
  return headers;
}

async function throwIfError(res: Response): Promise<void> {
  if (res.ok) return;
  let msg = `HTTP ${res.status}`;
  try {
    const data = await res.json();
    if (data && typeof data.message === 'string') msg = data.message;
  } catch { /* not JSON */ }
  throw new Error(msg);
}

export function useSupportChat() {
  // ── ユーザー向け ─────────────────────────────────────────

  /** 自分のスレッドと未読件数を取得。 */
  const fetchMyChat = async (): Promise<MySupportChatDto> => {
    const res = await fetch(`${API_BASE}/api/support/chat`, { headers: authHeaders() });
    await throwIfError(res);
    return (await res.json()) as MySupportChatDto;
  };

  /** 運営へメッセージを送信 (送信時に運営へメール通知が飛ぶ)。 */
  const sendMyChat = async (body: string): Promise<SupportMessageDto> => {
    const res = await fetch(`${API_BASE}/api/support/chat`, {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({ body }),
    });
    await throwIfError(res);
    return (await res.json()) as SupportMessageDto;
  };

  /** 運営からの返信をすべて既読化 (ウィジェットを開いたとき)。 */
  const markMyChatRead = async (): Promise<void> => {
    const res = await fetch(`${API_BASE}/api/support/chat/mark-read`, {
      method: 'POST',
      headers: authHeaders(),
    });
    await throwIfError(res);
  };

  // ── 運営向け ─────────────────────────────────────────────

  /** 全ユーザーのお問い合わせスレッド一覧を取得 (最終メッセージが新しい順)。 */
  const fetchThreads = async (): Promise<SupportThreadDto[]> => {
    const res = await fetch(`${API_BASE}/api/support/admin/threads`, { headers: authHeaders() });
    await throwIfError(res);
    return (await res.json()) as SupportThreadDto[];
  };

  /** 指定ユーザーの会話を取得 (古い順)。 */
  const fetchThread = async (userId: number): Promise<SupportMessageDto[]> => {
    const res = await fetch(`${API_BASE}/api/support/admin/threads/${userId}`, { headers: authHeaders() });
    await throwIfError(res);
    return (await res.json()) as SupportMessageDto[];
  };

  /** 運営から指定ユーザーへ返信 (ユーザーへアプリ内通知が飛ぶ)。 */
  const sendReply = async (userId: number, body: string): Promise<SupportMessageDto> => {
    const res = await fetch(`${API_BASE}/api/support/admin/threads/${userId}/reply`, {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({ body }),
    });
    await throwIfError(res);
    return (await res.json()) as SupportMessageDto;
  };

  /** 指定ユーザーのユーザー発メッセージをすべて既読化 (スレッドを開いたとき)。 */
  const markThreadRead = async (userId: number): Promise<void> => {
    const res = await fetch(`${API_BASE}/api/support/admin/threads/${userId}/mark-read`, {
      method: 'POST',
      headers: authHeaders(),
    });
    await throwIfError(res);
  };

  return {
    fetchMyChat, sendMyChat, markMyChatRead,
    fetchThreads, fetchThread, sendReply, markThreadRead,
  };
}

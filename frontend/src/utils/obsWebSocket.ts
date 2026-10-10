/**
 * 【ファイルの役割】 obs-websocket（OBS 28 以降に標準搭載、プロトコル v5）の最小限のクライアント。
 *
 * 配信オーバーレイがキャプチャボードのソースの静止画を GetSourceScreenshot で取るためだけに使う。
 * イベント購読はしない（eventSubscriptions = 0）。
 *
 * 認証（パスワード設定時）:
 *   secret = base64(sha256(password + salt))
 *   auth   = base64(sha256(secret + challenge))
 *
 * 注意: crypto.subtle は安全なコンテキスト（https か localhost）でしか使えない。
 * OBS のブラウザソースで https のページを開く前提なので問題ない。
 */

const OP_HELLO = 0;
const OP_IDENTIFY = 1;
const OP_IDENTIFIED = 2;
const OP_REQUEST = 6;
const OP_REQUEST_RESPONSE = 7;

interface PendingRequest {
  resolve: (data: any) => void;
  reject: (err: Error) => void;
  timer: ReturnType<typeof setTimeout>;
}

async function sha256Base64(text: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  let bin = '';
  for (const b of new Uint8Array(digest)) bin += String.fromCharCode(b);
  return btoa(bin);
}

export class ObsWebSocketClient {
  private ws: WebSocket | null = null;
  private pending = new Map<string, PendingRequest>();
  private seq = 0;
  /** 接続が切れたときに呼ばれる（再接続は呼び出し側が行う）。 */
  onClose: ((reason: string) => void) | null = null;

  /**
   * 【メソッドの役割】 接続して Identify まで済ませる。
   * 認証失敗・接続拒否は reject する（OBS 側は close code 4009 = 認証失敗 を返す）。
   */
  connect(url: string, password: string, timeoutMs = 8000): Promise<void> {
    this.close();
    return new Promise((resolve, reject) => {
      let settled = false;
      const fail = (msg: string) => {
        if (settled) return;
        settled = true;
        reject(new Error(msg));
      };
      const timer = setTimeout(() => fail('OBS への接続がタイムアウトしました'), timeoutMs);
      let ws: WebSocket;
      try {
        ws = new WebSocket(url);
      } catch (e) {
        clearTimeout(timer);
        fail((e as Error).message);
        return;
      }
      this.ws = ws;
      ws.onmessage = async ev => {
        let msg: { op: number; d: any };
        try {
          msg = JSON.parse(String(ev.data));
        } catch {
          return;
        }
        if (msg.op === OP_HELLO) {
          const identify: Record<string, unknown> = { rpcVersion: 1, eventSubscriptions: 0 };
          const auth = msg.d?.authentication;
          if (auth) {
            if (!password) {
              clearTimeout(timer);
              fail('OBS の WebSocket にパスワードが設定されています。URL に password を指定してください');
              ws.close();
              return;
            }
            const secret = await sha256Base64(password + auth.salt);
            identify.authentication = await sha256Base64(secret + auth.challenge);
          }
          ws.send(JSON.stringify({ op: OP_IDENTIFY, d: identify }));
        } else if (msg.op === OP_IDENTIFIED) {
          clearTimeout(timer);
          if (!settled) {
            settled = true;
            resolve();
          }
        } else if (msg.op === OP_REQUEST_RESPONSE) {
          const p = this.pending.get(msg.d?.requestId);
          if (!p) return;
          this.pending.delete(msg.d.requestId);
          clearTimeout(p.timer);
          const status = msg.d.requestStatus;
          if (status?.result) p.resolve(msg.d.responseData ?? {});
          else p.reject(new Error(status?.comment || `OBS request failed (code ${status?.code})`));
        }
      };
      ws.onclose = ev => {
        clearTimeout(timer);
        const reason = ev.code === 4009 ? 'OBS の WebSocket の認証に失敗しました（パスワード違い）' : `OBS との接続が切れました (code ${ev.code})`;
        fail(reason);
        for (const p of this.pending.values()) {
          clearTimeout(p.timer);
          p.reject(new Error(reason));
        }
        this.pending.clear();
        if (this.ws === ws) {
          this.ws = null;
          this.onClose?.(reason);
        }
      };
      ws.onerror = () => {
        // 詳細は onclose 側で扱う
      };
    });
  }

  get connected(): boolean {
    return this.ws?.readyState === WebSocket.OPEN;
  }

  /** 【メソッドの役割】 リクエストを 1 件送り、responseData を返す。 */
  request<T = any>(requestType: string, requestData?: Record<string, unknown>, timeoutMs = 5000): Promise<T> {
    const ws = this.ws;
    if (!ws || ws.readyState !== WebSocket.OPEN) return Promise.reject(new Error('OBS に接続していません'));
    const requestId = `bs-${++this.seq}`;
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(requestId);
        reject(new Error(`${requestType} がタイムアウトしました`));
      }, timeoutMs);
      this.pending.set(requestId, { resolve, reject, timer });
      ws.send(JSON.stringify({ op: OP_REQUEST, d: { requestType, requestId, requestData } }));
    });
  }

  /**
   * 【メソッドの役割】 ソースの静止画を元の解像度で取る（配信画面での縮小や配置は関係ない）。
   * 戻り値は data URL。
   */
  async getSourceScreenshot(sourceName: string, format: 'jpg' | 'png' = 'jpg', quality = 95): Promise<string> {
    const res = await this.request<{ imageData: string }>('GetSourceScreenshot', {
      sourceName,
      imageFormat: format,
      imageCompressionQuality: quality,
    });
    return res.imageData;
  }

  close(): void {
    const ws = this.ws;
    this.ws = null;
    if (ws && ws.readyState <= WebSocket.OPEN) ws.close();
  }
}

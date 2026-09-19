// Web Push（VAPID）の鍵ペアを生成して vapid_keys.txt に書き出す。
//
// 使い方: node tools/generate_vapid.js
//   → リポジトリ直下に vapid_keys.txt（.gitignore 済み）を作る。
//     公開鍵は標準出力にも出すが、秘密鍵はファイルにしか書かない（端末履歴に残さないため）。
//
// 生成した鍵は Render の環境変数へ:
//   VAPID_PUBLIC_KEY  = 公開鍵
//   VAPID_PRIVATE_KEY = 秘密鍵
// 公開鍵はブラウザに配布されるので非機密。秘密鍵は絶対にコミットしない。
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

/** Buffer を base64url（パディング無し）にする。 */
function toBase64Url(buf) {
    return buf.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');
}

function generateVapidKeys() {
    const ecdh = crypto.createECDH('prime256v1');
    ecdh.generateKeys();

    // 公開鍵は非圧縮形式の P-256 点（0x04 + X32 + Y32 = 65 バイト）でなければならない。
    const publicRaw = ecdh.getPublicKey();
    if (publicRaw.length !== 65 || publicRaw[0] !== 0x04) {
        throw new Error('予期しない公開鍵の形式です: length=' + publicRaw.length);
    }

    // 秘密鍵は 32 バイト固定。getPrivateKey() は先頭が 0x00 のとき短いバッファを返すことがあり、
    // そのまま渡すとサーバー側のデコードで鍵長エラーになるため必ず左ゼロ埋めする。
    let privateRaw = ecdh.getPrivateKey();
    if (privateRaw.length < 32) {
        privateRaw = Buffer.concat([Buffer.alloc(32 - privateRaw.length), privateRaw]);
    }
    if (privateRaw.length !== 32) {
        throw new Error('予期しない秘密鍵の長さです: ' + privateRaw.length);
    }

    const publicKey = toBase64Url(publicRaw);
    const privateKey = toBase64Url(privateRaw);

    const outPath = path.resolve(__dirname, '..', 'vapid_keys.txt');
    fs.writeFileSync(outPath,
        'Public Key (VAPID_PUBLIC_KEY):\n' + publicKey + '\n\n' +
        'Private Key (VAPID_PRIVATE_KEY):\n' + privateKey + '\n');

    console.log('公開鍵 (VAPID_PUBLIC_KEY):');
    console.log(publicKey);
    console.log('');
    console.log('秘密鍵は ' + outPath + ' に書き出しました（このファイルはコミットしないこと）。');
}

generateVapidKeys();

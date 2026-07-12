const crypto = require('crypto');
const fs = require('fs');

function generateVapidKeys() {
    const ecdh = crypto.createECDH('prime256v1');
    ecdh.generateKeys();
    
    // VAPID keys must be base64url encoded
    const publicKey = ecdh.getPublicKey('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');
    const privateKey = ecdh.getPrivateKey('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');

    const out = 'Public Key:\n' + publicKey + '\n\nPrivate Key:\n' + privateKey;
    fs.writeFileSync('vapid_keys.txt', out);
}

generateVapidKeys();

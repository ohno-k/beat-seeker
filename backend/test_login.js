const fs = require('fs');

async function testLogin() {
    const response = await fetch('http://localhost:8080/api/auth/login', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify({
            iidxId: '9999-9998',
            password: 'password123'
        })
    });

    const status = response.status;
    const body = await response.text();
    const setCookie = response.headers.get('set-cookie');

    fs.writeFileSync('test_login_res.txt', `STATUS: ${status}\nSET-COOKIE: ${setCookie}\nBODY: ${body}`);
}

testLogin();

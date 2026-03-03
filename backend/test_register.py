import urllib.request
import json

url = 'http://localhost:8080/api/auth/register'
data = {
    'iidxId': '1234-5678',
    'password': 'password123',
    'displayName': 'TestUser',
    'danRank': '皆伝',
    'arenaRank': 'A1'
}

req = urllib.request.Request(url, data=json.dumps(data).encode('utf-8'), headers={'Content-Type': 'application/json'}, method='POST')

try:
    with urllib.request.urlopen(req) as response:
        print("Status:", response.status)
        print("Body:", response.read().decode('utf-8'))
except urllib.error.HTTPError as e:
    print("HTTPError:", e.code)
    print("Body:", e.read().decode('utf-8'))
except Exception as e:
    print("Error:", e)

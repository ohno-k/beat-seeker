import sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

with open('chart_cache/titletbl.js', 'rb') as f:
    content = f.read().decode('shift-jis', errors='replace')

# Search for 焱 (U+7131) - triple fire character
for line in content.split('\n'):
    if '\u7131' in line:
        print(f'Found 焱: {line.strip()[:200]}')

# Search for 影 with nearby fire-related chars
for line in content.split('\n'):
    if '\u5f71' in line:
        print(f'影: {line.strip()[:200]}')

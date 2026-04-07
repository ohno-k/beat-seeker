#!/usr/bin/env python3
"""
bms2jsh.js sp[] decoder + interval / chord pattern analysis
"""

import re, math, urllib.request, json
from collections import defaultdict, Counter

B64    = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
LNDEF  = 384   # 1 measure = 384 subdivisions (default, overridden per-song)
GAP    = -1    # gap-- -> -1

def b64i(c):
    i = B64.find(c)
    return i if i >= 0 else 0


def parse_ln_map(html):
    """
    HTMLからLNDEFとln[]配列をパースし、小節番号→subdivision数の辞書を返す。

    textageでは曲ごとに LNDEF=N; で基底subdivision数を設定し、
    ln[i]=M; や for(i=A;i<B;i++)ln[i]=M; で小節単位に上書きする。

    戻り値: (lndef, ln_map)
      lndef:  基底subdivision数 (int)
      ln_map: {measure_number: subdivision_count}  上書き分のみ
    """
    # LNDEF (default for the song)
    m = re.search(r'LNDEF\s*=\s*(\d+)', html)
    lndef = int(m.group(1)) if m else LNDEF

    ln_map = {}

    # Direct assignments: ln[N]=M;
    for m in re.finditer(r'ln\[(\d+)\]\s*=\s*(\d+)', html):
        ln_map[int(m.group(1))] = int(m.group(2))

    # Loop assignments: for(i=A;i<B;i++)ln[i]=M;
    for m in re.finditer(r'for\s*\(\s*\w+\s*=\s*(\d+)\s*;\s*\w+\s*<\s*(\d+)\s*;\s*\w+\+\+\s*\)\s*ln\[\w+\]\s*=\s*(\d+)', html):
        start, end, val = int(m.group(1)), int(m.group(2)), int(m.group(3))
        for i in range(start, end):
            ln_map[i] = val

    return lndef, ln_map


def get_ln(mes, lndef, ln_map):
    """小節mesのsubdivision数を返す。"""
    return ln_map.get(mes, lndef)

# -----------------------------------------------------------------------------
# measure decoder
# -----------------------------------------------------------------------------
def decode_measure(sdd, ln_n=LNDEF):
    """sdd -> list of (pos_in_384, key)   key: 0=scratch, 1-7=button"""
    if not sdd:
        return []
    notes = []

    if sdd[0] == '#':
        sft = 1
        v2c = 0

        while sft < len(sdd):
            c   = sdd[sft]
            v2o = ""
            v2v = (1 if v2c else 3) * ln_n // 6

            if   c == 'C': v2s=  0; v2p=192; v2t=0; v2o=(sdd[sft+1] if not v2c and sft+1<len(sdd) else ""); sft+=2
            elif c == 'c': v2s= 96; v2p=192; v2t=0; v2o=(sdd[sft+1] if not v2c and sft+1<len(sdd) else ""); sft+=2
            elif c == 'R': v2s=  0; v2p= 96; v2t=0; v2o=(sdd[sft+1] if not v2c and sft+1<len(sdd) else ""); sft+=2
            elif c == 'r': v2s= 48; v2p= 96; v2t=0; v2o=(sdd[sft+1] if not v2c and sft+1<len(sdd) else ""); sft+=2
            elif c == 'P': v2s=  0; v2p= 48; v2t=0; v2o=(sdd[sft+1] if not v2c and sft+1<len(sdd) else ""); sft+=2
            elif c == 'p': v2s= 24; v2p= 48; v2t=0; v2o=(sdd[sft+1] if not v2c and sft+1<len(sdd) else ""); sft+=2
            elif c == 'B': v2s=  0; v2p=192; v2t=1; v2b=math.ceil(v2v/192)+1; v2o=sdd[sft+1:sft+v2b]; sft+=v2b
            elif c == 'b': v2s= 96; v2p=192; v2t=1; v2b=math.ceil(v2v/192)+1; v2o=sdd[sft+1:sft+v2b]; sft+=v2b
            elif c == 'Q': v2s=  0; v2p= 96; v2t=1; v2b=math.ceil(v2v/ 96)+1; v2o=sdd[sft+1:sft+v2b]; sft+=v2b
            elif c == 'q': v2s= 48; v2p= 96; v2t=1; v2b=math.ceil(v2v/ 96)+1; v2o=sdd[sft+1:sft+v2b]; sft+=v2b
            elif c == 'O': v2s=  0; v2p= 48; v2t=1; v2b=math.ceil(v2v/ 48)+1; v2o=sdd[sft+1:sft+v2b]; sft+=v2b
            elif c == 'o': v2s= 24; v2p= 48; v2t=1; v2b=math.ceil(v2v/ 48)+1; v2o=sdd[sft+1:sft+v2b]; sft+=v2b
            elif c == 'X': v2s=  0; v2p= 24; v2t=1; v2b=math.ceil(v2v/ 24)+1; v2o=sdd[sft+1:sft+v2b]; sft+=v2b
            elif c == 'x': v2s= 12; v2p= 24; v2t=1; v2b=math.ceil(v2v/ 24)+1; v2o=sdd[sft+1:sft+v2b]; sft+=v2b
            elif c == 'Z': v2s=  0; v2p= 12; v2t=1; v2b=math.ceil(v2v/ 12)+1; v2o=sdd[sft+1:sft+v2b]; sft+=v2b
            elif c == 'S': v2s=  0; v2p= 64; v2t=1; v2b=math.ceil(v2v/ 64)+1; v2o=sdd[sft+1:sft+v2b]; sft+=v2b
            elif c == 's': v2s= 32; v2p= 64; v2t=1; v2b=math.ceil(v2v/ 64)+1; v2o=sdd[sft+1:sft+v2b]; sft+=v2b
            elif c == 'T': v2s=  0; v2p= 32; v2t=1; v2b=math.ceil(v2v/ 32)+1; v2o=sdd[sft+1:sft+v2b]; sft+=v2b
            elif c == 't': v2s= 16; v2p= 32; v2t=1; v2b=math.ceil(v2v/ 32)+1; v2o=sdd[sft+1:sft+v2b]; sft+=v2b
            elif c == 'U': v2s=  0; v2p= 16; v2t=1; v2b=math.ceil(v2v/ 16)+1; v2o=sdd[sft+1:sft+v2b]; sft+=v2b
            elif c in '1234567':
                v2o = sdd[sft:sft+3]; sft += 3
                if len(v2o) < 3: continue
                ob2 = int(v2o[0])
                vh  = b64i(v2o[1])*64 + b64i(v2o[2])
                if vh < ln_n: notes.append((vh, ob2))
                continue
            elif c in '89':
                bits = b64i(sdd[sft+1]) if sft+1<len(sdd) else 0
                abs_v2o = ""
                if c == '9': abs_v2o += '1' + sdd[sft+2:sft+4]
                for ii in range(6):
                    if bits & (1<<ii):
                        abs_v2o += chr(ord('2')+ii) + sdd[sft+2:sft+4]
                sft += 4
                j = 0
                while j+3 <= len(abs_v2o):
                    ob2 = int(abs_v2o[j]) if abs_v2o[j].isdigit() else 0
                    vh  = b64i(abs_v2o[j+1])*64 + b64i(abs_v2o[j+2])
                    if vh < ln_n: notes.append((vh, ob2))
                    j += 3
                continue
            elif c == '-':
                v2c = 1; sft += 1; continue
            elif c == '_':
                rem = "AA" if sft == len(sdd)-1 else sdd[sft+1:]
                j = 0
                while j+2 <= len(rem):
                    vh = b64i(rem[j])*64 + b64i(rem[j+1])
                    if vh < ln_n: notes.append((vh, 0))
                    j += 2
                v2c = 2; sft = len(sdd); break
            else:
                break

            # build v2k
            v2k = ""
            if v2t == 1:
                for ch in v2o:
                    vx = b64i(ch)
                    if v2c == 0:
                        v2k += str(vx//8) + str(vx%8)
                    else:
                        for i3 in range(5, -1, -1):
                            v2k += '1' if (vx>>i3)&1 else '0'
            elif v2t == 0:
                steps = (ln_n - v2s + v2p - 1) // v2p
                val   = '1' if v2c else (v2o if v2o else '0')
                v2k   = val * steps

            # place notes
            if v2t != 2:
                vi = 0
                i2 = v2s
                while i2 < ln_n:
                    if vi < len(v2k):
                        ch = v2k[vi]
                        if ch != '0':
                            if v2c == 1:
                                notes.append((i2, 0))
                            else:
                                key = int(ch) if ch.isdigit() else 0
                                if key > 0:
                                    notes.append((i2, key))
                    vi += 1
                    i2 += v2p

            if v2c == 2:
                break

    else:
        # hex / simple format
        if sdd[0] == 'x':
            try:   len_val = int(sdd[1:4], 16)
            except: len_val = len(sdd) - 4
            if len_val == 0: len_val = 1
            sft = 4
        else:
            len_val = len(sdd)
            if len_val == 0: return []
            sft = 0

        div = 0
        while sft < len(sdd):
            while sft < len(sdd) and sdd[sft] == '@':
                try:   div += int(sdd[sft+1:sft+3], 16) * 2
                except: pass
                sft += 3
            if sft + 2 > len(sdd): break
            pair = sdd[sft:sft+2]
            try:   y = int(pair, 16)
            except ValueError: sft+=2; div+=2; continue

            pos384 = (ln_n * div) // len_val
            for j in range(8):
                if y & (1 << j):
                    notes.append((pos384, j))

            sft += 2; div += 2

    return notes


# -----------------------------------------------------------------------------
# 難易度コード -> if(?)ブロックの文字
DIFF_LETTER = {'4': 'a', '10': 'l'}


# sp[] parser
# -----------------------------------------------------------------------------
def _parse_sp_array(array_str, sp_context=None):
    """sp=[item0, item1, ...]; の中身を解析して {measure: sdd_string} を返す。"""
    if sp_context is None:
        sp_context = {}
    items = re.split(r',(?=(?:[^"]*"[^"]*")*[^"]*$)', array_str)
    sp = {}
    for idx, item in enumerate(items):
        mes  = idx + 2
        item = item.strip()
        if item.startswith('"') and item.endswith('"'):
            sp[mes] = item[1:-1]
        elif item.startswith('sp['):
            ref = re.search(r'sp\[(\d+)\]', item)
            if ref:
                rn = int(ref.group(1))
                # JavaScript では sp=[...] のRHS評価は代入前に行われるため
                # 参照は常にsp_context（代入前のグローバル値）から解決する
                sp[mes] = sp_context.get(rn, '')
    return sp


def _parse_sp_format_b(block, sp_context=None):
    """sp[N]="..." / sp[N]=sp[M] / sp[N]=sp[M]="..." 形式をパースして返す。"""
    if sp_context is None:
        sp_context = {}
    sp = {}
    stmts = re.split(r';', block)
    for stmt in stmts:
        stmt = stmt.strip()
        if not stmt.startswith('sp['):
            continue
        # チェーン代入: sp[N]=sp[M]="value"
        mc = re.match(r'((?:sp\[\s*\d+\s*\]\s*=\s*)+)"([^"]*)"', stmt)
        if mc:
            val = mc.group(2)
            for mn in re.findall(r'sp\[\s*(\d+)\s*\]', mc.group(1)):
                sp[int(mn)] = val
            continue
        # 単純代入: sp[N]="value"
        mq = re.match(r'sp\[\s*(\d+)\s*\]\s*=\s*"([^"]*)"', stmt)
        if mq:
            sp[int(mq.group(1))] = mq.group(2)
            continue
        # 参照: sp[N]=sp[M]
        mr = re.match(r'sp\[\s*(\d+)\s*\]\s*=\s*sp\[\s*(\d+)\s*\]', stmt)
        if mr:
            tgt = int(mr.group(1))
            src = int(mr.group(2))
            sp[tgt] = sp.get(src) or sp_context.get(src, '')
    return sp


def _find_hyper_sp(html, before_pos):
    """
    指定位置より前の if(k)/else if(k) ブロックの sp データを取得して返す。
    sp=[...] 配列と、その後続の sp[N]="..." 上書き両方を処理する。
    if(k) が sp=[...] を持たず sp[N]="..." のみの場合も対応。
    見つからない場合は空辞書を返す。
    """
    hyper_mlist = list(re.finditer(r'(?:else\s+)?if\(k\)\s*\{', html[:before_pos]))
    if not hyper_mlist:
        return {}
    hyper_match = hyper_mlist[-1]
    brace_pos = html.index('{', hyper_match.start())
    content_start = brace_pos + 1
    hyper_content = html[content_start:before_pos]
    m_arr = re.search(r'sp=\[(.*?)\];', hyper_content, re.DOTALL)
    if m_arr:
        sp_arr = _parse_sp_array(m_arr.group(1))
        after_arr = hyper_content[m_arr.end():]
        sp_overrides = _parse_sp_format_b(after_arr, sp_arr)
        merged = dict(sp_arr)
        merged.update(sp_overrides)
        return merged
    return _parse_sp_format_b(hyper_content)


def _find_global_sp(html):
    """
    if() ブロックの外（深度0）にあるグローバル sp=[...] 配列を取得してパースする。
    古い曲や複数難易度共有ページで、各難易度ブロックが参照する基底データとして使われる。
    """
    first_if = re.search(r'(?:else\s+)?if\([a-z]+\)\s*\{', html)
    scope_end = first_if.start() if first_if else len(html)
    m = re.search(r'sp=\[(.*?)\];', html[:scope_end], re.DOTALL)
    if not m:
        return {}
    return _parse_sp_array(m.group(1), {})


def _strip_nested_blocks(text):
    """テキストからネストされた if(){...} ブロックの内容を除去する。
    深度0の sp[N]= のみ残す。"""
    result = []
    depth = 0
    for ch in text:
        if ch == '{':
            depth += 1
        elif ch == '}':
            depth -= 1
        elif depth == 0:
            result.append(ch)
    return ''.join(result)


def _extract_block(html, start):
    """
    html[start] の '{' から対応する '}' までの内容を返す（ブレースを追跡）。
    戻り値: (block_content_str, end_pos)  ← end_pos は '}' の次
    """
    depth = 0
    i = start
    while i < len(html):
        if html[i] == '{':
            depth += 1
        elif html[i] == '}':
            depth -= 1
            if depth == 0:
                return html[start:i+1], i + 1
        i += 1
    return html[start:], len(html)


def _find_if_blocks(html, diff_letter):
    """
    html 中の if(diff_letter){ ... } ブロックを全て抽出する。
    ネスト構造を正しく追跡し、ブレース境界を越えない。
    戻り値: list of (block_content, block_start, brace_start, end_pos)
    """
    pat = re.compile(r'(?:else\s+)?if\s*\(\s*' + re.escape(diff_letter) + r'\s*\)\s*\{')
    blocks = []
    for m in pat.finditer(html):
        brace_pos = html.index('{', m.start())
        content, end = _extract_block(html, brace_pos)
        blocks.append((content, m.start(), brace_pos, end))
    return blocks


def _sp_from_block(block_content, ref_ctx):
    """
    ブロック内容から sp データを取り出す。
    構造 A（sp=[...]配列）→ 構造 B（sp[N]="..." スパース） の順に試す。
    sp=[] と sp[N]= が両方ある場合は sp=[] をベースに sp[N]= をマージする。
    """
    m_arr = re.search(r'sp=\[(.*?)\];', block_content, re.DOTALL)
    if m_arr:
        sp = _parse_sp_array(m_arr.group(1), ref_ctx)
        # sp=[] の後に続く sp[N]= オーバーライドも適用
        # ネストされたif(){}ブロックの中身は除外する
        after_arr = _strip_nested_blocks(block_content[m_arr.end():])
        overrides = _parse_sp_format_b(after_arr, sp)
        if overrides:
            sp.update(overrides)
        return sp
    overrides = _parse_sp_format_b(block_content, ref_ctx)
    return overrides if overrides else None


def parse_sp_chart(html, diff_letter='a'):
    """
    指定難易度ブロックの SP譜面 sp{} を返す。
    ブレース追跡により複数 if(diff) ブロックを正しく処理する。

    対応構造:
      A) if(diff){ sp=[...フル配列...]; }
      B) グローバルベース + if(diff){ sp[N]=...スパース上書き; }
      C) else if(k){ sp=[...]; if(a){ sp[N]=...上書き; } }
      D) if(kuro){ if(k){ sp=[...LEGG...]; } }  ← ネスト構造
      E) if(a){ln[...];}  if(a){notes=N; sp=[...]; }  ← 複数 if(a) ブロック
    """
    # LEGG: IIDX 22+ では実際の LEGG 譜面が if(kuro) に入っている（if(l) ではない）
    # if(kuro) は if(a) にネストされている場合が多い → ANOTHERデータを継承
    if diff_letter == 'l' and 'if(kuro)' in html:
        diff_letter = 'kuro'

    # グローバル sp=[...] 配列（参照コンテキストの基底）
    sp_global = _find_global_sp(html)

    # kuro (LEGG) の場合: 親の if(a) ブロックの ANOTHER データを先に取得して
    # ref_ctx に含める（kuro は if(a) にネストされ ANOTHER を継承するため）
    sp_another_base = {}
    if diff_letter == 'kuro':
        a_blocks = _find_if_blocks(html, 'a')
        for a_content, a_start, a_brace, a_end in a_blocks:
            # DP ブロックをスキップ
            if 'dp[' in a_content or 'c2[' in a_content:
                continue
            sp_hyper = _find_hyper_sp(html, a_start)
            a_ref = {**sp_global, **sp_hyper}
            a_sp = _sp_from_block(a_content, a_ref)
            if a_sp:
                # ベースとマージ
                if a_ref and len(a_sp) < len(a_ref) * 0.8:
                    merged = dict(a_ref)
                    merged.update(a_sp)
                    a_sp = merged
                sp_another_base = a_sp
                break

    # 対象難易度の全 if(diff) ブロックを抽出
    all_blocks = _find_if_blocks(html, diff_letter)

    # LNDEF / ln[] を取得（デコードノーツ数の計算に使用）
    lndef_val, ln_map_val = parse_ln_map(html)

    # 各ブロックについて実際のデコードノーツ数で最良ブロックを選択
    best_sp = None
    best_decoded = -1

    for block_content, block_start, brace_start, end in all_blocks:
        # ANOTHER ブロックで DP データを含む場合はスキップ
        # c2[] = 2P CN配列、dp[] = DP譜面データ
        # kuro（LEGG）ブロックはネスト if(k) で保護されているのでスキップしない
        if diff_letter not in ('kuro', 'l') and ('c2[' in block_content or 'dp[' in block_content):
            continue

        # if(k) HYPERベース（このブロックより前）
        sp_hyper = _find_hyper_sp(html, block_start)
        ref_ctx = {**sp_global, **sp_hyper, **sp_another_base}

        # ---- 構造 D: ネスト if(k){ sp=[...] } を先に確認（DP混在対策）----
        sp = None
        inner_k_blocks = _find_if_blocks(block_content, 'k')
        for ik_content, ik_start, ik_brace, ik_end in inner_k_blocks:
            sp_k = _sp_from_block(ik_content, ref_ctx)
            if sp_k:
                sp = sp_k
                break

        # ---- 構造 A / B: ネスト if(k) でデータが取れなかった場合のみ直接解析 ----
        if sp is None:
            sp = _sp_from_block(block_content, ref_ctx)

        if sp is None:
            continue

        # ベース (global / hyper) がある場合: スパース上書きをマージ
        if ref_ctx and len(sp) < len(ref_ctx) * 0.8:
            merged = dict(ref_ctx)
            merged.update(sp)
            sp = merged

        # 実際のデコードノーツ数で最良ブロックを選択
        decoded_notes = 0
        for mes in sp:
            sdd = sp[mes]
            if sdd:
                ln_n = get_ln(mes, lndef_val, ln_map_val)
                decoded_notes += len(decode_measure(sdd, ln_n))

        if decoded_notes > best_decoded:
            best_decoded = decoded_notes
            best_sp = sp

    if best_sp:
        return best_sp

    # ---- フォールバック: グローバルベース + if(k) HYPER ----
    first_start = all_blocks[0][1] if all_blocks else (
        html.find(f'if({diff_letter})') if html.find(f'if({diff_letter})') >= 0 else len(html)
    )
    sp_hyper = _find_hyper_sp(html, first_start)
    ref_ctx = {**sp_global, **sp_hyper}

    before = html[:first_start]
    sp_pre = {}
    depth = 0
    for m in re.finditer(r'[{}]|sp\[\s*(\d+)\s*\]\s*=\s*"([^"]*)"', before):
        ch = m.group(0)[0]
        if ch == '{':            depth += 1
        elif ch == '}':            depth -= 1
        elif depth == 0 and m.group(1) is not None:
            sp_pre[int(m.group(1))] = m.group(2)

    if sp_global:
        merged = dict(sp_global)
        merged.update(sp_pre)
        merged.update(sp_hyper)
        return merged
    if sp_hyper:
        return sp_hyper
    return sp_pre


def parse_sp_another(html):
    """後方互換エイリアス"""
    return parse_sp_chart(html, diff_letter='a')

# -----------------------------------------------------------------------------
# CN (Charge Note) parser
# -----------------------------------------------------------------------------
def parse_c1(html, diff_letter='a'):
    """
    c1[] データを解析し、CN（チャージノート）の情報を返す。

    c1[N] = [[key_or_chord, nbar_pos, (duration_nbar), (flags)], ...]
      - key_or_chord <  10: 単キー
      - key_or_chord >= 10: 2キー同時 (key1=value%10, key2=value//10)
      - nbar_pos: 小節内位置（subdivision = nbar_pos * 3 に変換）
      - duration_nbar: CN長さ（省略時デフォルト30 nbar）

    戻り値: list of (abs_start, abs_end, key)
    """
    if diff_letter == 'l' and 'if(kuro)' in html:
        diff_letter = 'kuro'

    # 対象難易度ブロック（+ネスト if(k)）の文字列を収集
    # kuro（LEGG）の場合: DP混入を防ぐため if(k) ネストブロックのみを使用
    # a（ANOTHER）の場合: ネストされた if(kuro) ブロックの c1[] を除外
    search_texts = []
    for block_content, block_start, brace_start, end in _find_if_blocks(html, diff_letter):
        inner_k = _find_if_blocks(block_content, 'k')
        if inner_k:
            for ik_content, _, _, _ in inner_k:
                search_texts.append(ik_content)
        else:
            # ANOTHER の場合: ネストされた if(kuro) を除外して LEGG の c1[] 混入を防ぐ
            if diff_letter == 'a':
                kuro_blocks = _find_if_blocks(block_content, 'kuro')
                if kuro_blocks:
                    cleaned = block_content
                    for kb_content, kb_start, kb_brace, kb_end in reversed(kuro_blocks):
                        cleaned = cleaned[:kb_start] + cleaned[kb_end:]
                    search_texts.append(cleaned)
                else:
                    search_texts.append(block_content)
            else:
                search_texts.append(block_content)

    if not search_texts:
        return []

    full_text = chr(10).join(search_texts)

    # c1[N1]=c1[N2]=...=[[...]] を解析
    pat = re.compile(r'((?:c1\[\s*\d+\s*\]\s*=\s*)+)\[\[([^\]]*(?:\][,\s]*\[?[^\]]*)*?)\]\]')
    raw_cn = {}

    for m in pat.finditer(full_text):
        targets = [int(x) for x in re.findall(r'c1\[\s*(\d+)\s*\]', m.group(1))]
        data_str = m.group(2)
        try:
            entries = json.loads('[[' + data_str + ']]')
        except Exception:
            continue
        for mes in targets:
            raw_cn[mes] = entries

    cn_events = []
    for mes, entries in raw_cn.items():
        for entry in entries:
            if not isinstance(entry, list) or len(entry) < 2:
                continue
            key_or_chord  = entry[0]
            nbar_pos      = entry[1]
            duration_nbar = entry[2] if len(entry) >= 3 else 30
            sub_start = nbar_pos * 3
            sub_end   = (nbar_pos + duration_nbar) * 3
            abs_start = (mes + GAP) * LNDEF + sub_start
            abs_end   = (mes + GAP) * LNDEF + sub_end
            if key_or_chord < 10:
                cn_events.append((abs_start, abs_end, key_or_chord))
            else:
                key1 = key_or_chord % 10
                key2 = key_or_chord // 10
                cn_events.append((abs_start, abs_end, key1))
                cn_events.append((abs_start, abs_end, key2))

    cn_events.sort()
    return cn_events


# -----------------------------------------------------------------------------
# note name helper
# -----------------------------------------------------------------------------
INAMES = {
    384:"1measure", 192:"half", 96:"quarter", 64:"quarter-triplet",
    48:"8th", 32:"12th(triplet)", 24:"16th", 16:"24th", 12:"32nd",
    8:"48th", 6:"64th",
}
def iname(d):
    if d in INAMES: return INAMES[d]
    for k, v in sorted(INAMES.items(), reverse=True):
        if abs(d-k) <= 1: return f"~{v}"
    return f"({d})"


# -----------------------------------------------------------------------------
# core analysis
# -----------------------------------------------------------------------------
def collect_events(textage_path):
    url = f"https://textage.cc/score/{textage_path}"
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    with urllib.request.urlopen(req) as r:
        html = r.read().decode('shift_jis', errors='replace')

    sp = parse_sp_another(html)
    all_notes = []
    for mes in sorted(sp.keys()):
        sdd = sp.get(mes)
        if not sdd: continue
        for (p, k) in decode_measure(sdd):
            all_notes.append(((mes + GAP) * LNDEF + p, k))
    all_notes.sort()

    pos_to_keys = defaultdict(list)
    for (pos, key) in all_notes:
        pos_to_keys[pos].append(key)
    events = sorted(pos_to_keys.items())
    return all_notes, events


def analyze_song(textage_path, bpm_raw, title="", expected_notes=0):
    print(f"  fetching {title} ...", end=" ", flush=True)
    all_notes, events = collect_events(textage_path)
    print(f"done ({len(all_notes)} notes)")

    intervals = [events[i][0]-events[i-1][0] for i in range(1, len(events))]
    icounts   = Counter(intervals)
    total_w   = sum(icounts.values()) or 1

    # BPM parsing  (soflan -> use max BPM)
    bpm_nums = [int(x) for x in re.findall(r'\d+', bpm_raw)]
    bpm_main = max(bpm_nums) if bpm_nums else 0
    is_soflan = '-' in bpm_raw

    # eff16 = 24 * BPM / interval  (16th-note equivalent BPM)
    dominant_interval = icounts.most_common(1)[0][0] if icounts else 24
    dominant_eff16    = 24.0 * bpm_main / dominant_interval if dominant_interval else 0

    weighted_eff16 = 0.0
    for d, cnt in icounts.items():
        if d > 0:
            weighted_eff16 += (24.0 * bpm_main / d) * (cnt / total_w)

    # chord stats
    chord_sizes = [len(keys) for _, keys in events]
    chord_dist  = Counter(chord_sizes)
    total_ev    = len(events)
    scratch_cnt = sum(1 for _, k in all_notes if k == 0)
    scratch_pct = scratch_cnt / len(all_notes) * 100 if all_notes else 0
    chord_pct   = (total_ev - chord_dist[1]) / total_ev * 100 if total_ev else 0
    single_pct  = chord_dist[1] / total_ev * 100 if total_ev else 0

    # ranuchi: 2+keys -> 1key -> 2+keys -> 1key  pattern count
    pats = [len(keys) for _, keys in events]
    ranuchi = sum(
        1 for i in range(len(pats)-3)
        if pats[i] >= 2 and pats[i+1] == 1 and pats[i+2] >= 2 and pats[i+3] == 1
    )

    # ---------- print ----------
    sep = "-" * 62
    print(f"\n{sep}")
    print(f"  {title}")
    suffix = f" (soflan, max={bpm_main})" if is_soflan else ""
    exp_str = f"  expected={expected_notes}" if expected_notes else ""
    print(f"  BPM: {bpm_raw}{suffix}    notes: {len(all_notes)}{exp_str}")
    print(sep)

    print(f"\n  [16th-note equivalent BPM]")
    print(f"    Dominant interval : {iname(dominant_interval)}"
          f"  ->  eff16 = {dominant_eff16:.0f} BPM")
    print(f"    Weighted average  :              eff16 = {weighted_eff16:.0f} BPM")

    print(f"\n  [Interval distribution]")
    print(f"    {'interval':>6}  {'name':>18}  {'%':>5}  {'eff16 BPM':>9}  bar")
    for d, cnt in sorted(icounts.items(), key=lambda x: -x[1])[:10]:
        eff = 24.0 * bpm_main / d if d > 0 else 0
        pct = cnt / total_w * 100
        bar = '#' * int(pct / 2)
        print(f"    {d:6d}  {iname(d):>18}  {pct:5.1f}%  {eff:9.0f}  {bar}")

    print(f"\n  [Chord distribution]")
    for n in sorted(chord_dist.keys()):
        pct = chord_dist[n] / total_ev * 100
        bar = '#' * int(pct / 2)
        print(f"    {n}-key: {pct:5.1f}%  {bar}")

    print(f"\n  Scratch : {scratch_cnt} notes ({scratch_pct:.1f}%)")
    print(f"  Single  : {single_pct:.1f}%   Chord: {chord_pct:.1f}%")
    print(f"  Ranuchi (chord-1-chord-1) patterns: {ranuchi}")

    # per-measure effective BPM  (show first 20 non-empty measures)
    print(f"\n  [Per-measure eff16 BPM  -- first 20 measures with notes]")
    print(f"    {'measure':>7}  {'events':>6}  {'dom.interval':>14}  {'eff16':>7}")
    shown = 0
    mes_events = defaultdict(list)
    for pos, keys in events:
        mes = pos // LNDEF + 1
        mes_events[mes].append(pos)

    for mes in sorted(mes_events.keys())[:20]:
        positions = sorted(mes_events[mes])
        if len(positions) < 2:
            continue
        local_intervals = [positions[i]-positions[i-1] for i in range(1, len(positions))]
        local_cnt = Counter(local_intervals)
        dom_intv  = local_cnt.most_common(1)[0][0]
        e16       = 24.0 * bpm_main / dom_intv if dom_intv else 0
        print(f"    measure {mes:3d}  {len(positions):6d} ev  "
              f"{iname(dom_intv):>14}  {e16:7.0f} BPM")
        shown += 1

    return {
        'title': title,
        'bpm_raw': bpm_raw, 'bpm_main': bpm_main, 'is_soflan': is_soflan,
        'notes': len(all_notes), 'events': total_ev,
        'dominant_interval': dominant_interval,
        'dominant_eff16': dominant_eff16,
        'weighted_eff16': weighted_eff16,
        'scratch_pct': scratch_pct,
        'chord_pct': chord_pct,
        'single_pct': single_pct,
        'interval_dist': dict(icounts),
        'chord_dist': dict(chord_dist),
        'ranuchi': ranuchi,
    }


# -----------------------------------------------------------------------------
# fetch / analyze API  (for batch use)
# -----------------------------------------------------------------------------
def fetch_raw(textage_path, diff_letter='a'):
    """
    textageのページを取得し、sp[]生文字列辞書と cn_events を返す。
    diff_letter: 'a'=ANOTHER, 'l'=LEGGENDARIA
    戻り値: (sp_dict, cn_events, lndef, ln_map)
      sp_dict   : {mes_number: sdd_string}
      cn_events : list of (abs_start, abs_end, key)
      lndef     : 基底subdivision数
      ln_map    : {mes_number: subdivision_count} 上書き分
    """
    url = f"https://textage.cc/score/{textage_path}"
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    with urllib.request.urlopen(req, timeout=30) as r:
        html = r.read().decode('shift_jis', errors='replace')
    sp = parse_sp_chart(html, diff_letter)
    cn_events = parse_c1(html, diff_letter)
    lndef, ln_map = parse_ln_map(html)
    return sp, cn_events, lndef, ln_map


def profile_from_sp(sp, bpm_raw, cn_events=None, lndef=None, ln_map=None):
    """
    sp辞書とBPM、CNイベントリストから譜面傾向プロファイルを計算して返す（出力なし）。
    cn_events: parse_c1() の戻り値 list of (abs_start, abs_end, key) または None
    lndef:     基底subdivision数（Noneの場合はグローバルLNDEF=384を使用）
    ln_map:    小節ごとのsubdivision上書き辞書（Noneの場合は空）
    戻り値: dict
    """
    if lndef is None:
        lndef = LNDEF
    if ln_map is None:
        ln_map = {}

    # 小節ごとの累積オフセットを計算（可変長小節に対応）
    sorted_measures = sorted(sp.keys())
    max_mes = max(sorted_measures) if sorted_measures else 0
    cum_offset = {}
    offset = 0
    for m in range(min(sorted_measures) if sorted_measures else 0, max_mes + 1):
        cum_offset[m] = offset
        offset += get_ln(m, lndef, ln_map)

    all_notes = []
    for mes in sorted_measures:
        sdd = sp.get(mes)
        if not sdd:
            continue
        ln_n = get_ln(mes, lndef, ln_map)
        base = cum_offset.get(mes, (mes + GAP) * lndef)
        for (p, k) in decode_measure(sdd, ln_n):
            all_notes.append((base + p, k))
    all_notes.sort()

    pos_to_keys = defaultdict(list)
    for (pos, key) in all_notes:
        pos_to_keys[pos].append(key)
    events = sorted(pos_to_keys.items())

    intervals = [events[i][0] - events[i-1][0] for i in range(1, len(events))]
    icounts   = Counter(intervals)
    total_w   = sum(icounts.values()) or 1

    bpm_nums  = [int(x) for x in re.findall(r'\d+', bpm_raw)]
    bpm_main  = max(bpm_nums) if bpm_nums else 0
    is_soflan = '-' in bpm_raw

    dominant_interval = icounts.most_common(1)[0][0] if icounts else 24
    dominant_eff16    = 24.0 * bpm_main / dominant_interval if dominant_interval else 0

    weighted_eff16 = 0.0
    for d, cnt in icounts.items():
        if d > 0:
            weighted_eff16 += (24.0 * bpm_main / d) * (cnt / total_w)

    chord_sizes = [len(keys) for _, keys in events]
    chord_dist  = Counter(chord_sizes)
    total_ev    = len(events)
    scratch_cnt = sum(1 for _, k in all_notes if k == 0)
    scratch_pct = scratch_cnt / len(all_notes) * 100 if all_notes else 0
    chord_pct   = (total_ev - chord_dist[1]) / total_ev * 100 if total_ev else 0
    single_pct  = chord_dist[1] / total_ev * 100 if total_ev else 0

    pats    = [len(keys) for _, keys in events]
    ranuchi = sum(
        1 for i in range(len(pats) - 3)
        if pats[i] >= 2 and pats[i+1] == 1 and pats[i+2] >= 2 and pats[i+3] == 1
    )

    # interval内訳（名称付き、上位10件）
    interval_detail = {}
    for d, cnt in sorted(icounts.items(), key=lambda x: -x[1])[:10]:
        interval_detail[d] = {
            'name': iname(d),
            'count': cnt,
            'pct': round(cnt / total_w * 100, 1),
            'eff16': round(24.0 * bpm_main / d, 1) if d > 0 else 0,
        }

    # タグ付け
    tags = []
    if scratch_pct >= 15:   tags.append('scratch_very_heavy')
    elif scratch_pct >= 8:  tags.append('scratch_heavy')
    elif scratch_pct < 3:   tags.append('scratch_low')
    if chord_pct >= 65:     tags.append('chord_heavy')
    if single_pct >= 60:    tags.append('single_heavy')
    if any(d <= 12 for d in icounts):   tags.append('has_32nd')
    if any(30 <= d <= 34 for d in icounts): tags.append('has_triplet')
    if is_soflan:           tags.append('soflan')
    if dominant_eff16 >= 180:    tags.append('high_effective_bpm')
    elif dominant_eff16 >= 120:  tags.append('mid_effective_bpm')
    else:                        tags.append('low_effective_bpm')

    # ---- 鍵盤のみ（スクラッチ除外）のインターバル分布 ----
    kbd_notes = sorted(pos for pos, k in all_notes if k != 0)
    kbd_profile = {}
    if len(kbd_notes) >= 2:
        kbd_intervals = [kbd_notes[i] - kbd_notes[i-1] for i in range(1, len(kbd_notes))]
        kbd_icounts   = Counter(kbd_intervals)
        kbd_total_w   = sum(kbd_icounts.values()) or 1
        kbd_interval_detail = {}
        for d, cnt in sorted(kbd_icounts.items(), key=lambda x: -x[1])[:10]:
            kbd_interval_detail[d] = {
                'name': iname(d),
                'count': cnt,
                'pct': round(cnt / kbd_total_w * 100, 1),
                'eff16': round(24.0 * bpm_main / d, 1) if d > 0 else 0,
            }
        kbd_profile = {'kbd_interval_dist': kbd_interval_detail}

    # ---- スクラッチ単体のインターバル分布 ----
    scr_profile = {}
    scr_positions = sorted(pos for pos, k in all_notes if k == 0)
    if len(scr_positions) >= 2:
        scr_intervals = [scr_positions[i] - scr_positions[i-1] for i in range(1, len(scr_positions))]
        scr_icounts   = Counter(scr_intervals)
        scr_total_w   = sum(scr_icounts.values()) or 1
        scr_interval_detail = {}
        for d, cnt in sorted(scr_icounts.items(), key=lambda x: -x[1])[:10]:
            scr_interval_detail[d] = {
                'name': iname(d),
                'count': cnt,
                'pct': round(cnt / scr_total_w * 100, 1),
                'eff16': round(24.0 * bpm_main / d, 1) if d > 0 else 0,
            }
        scr_profile = {'scr_interval_dist': scr_interval_detail}

    # ---- CN（チャージノート）の個別分析 ----
    # cn_events: list of (abs_start, abs_end, key)
    # デュアルキーCNは同じ (start, end) に2エントリあるが、オブジェクト数は1
    cn_profile = {}
    if cn_events:
        # CN オブジェクト数: 同一 (start, end) は同一CN（デュアルキー）
        cn_objects = sorted(set((s, e) for s, e, k in cn_events))  # unique CN objects
        cn_obj_count = len(cn_objects)

        # CN キー別の scratch カウント（key=0 がスクラッチ）
        cn_scratch_cnt = sum(1 for s, e, k in cn_events if k == 0)
        cn_scratch_pct = cn_scratch_cnt / len(cn_events) * 100 if cn_events else 0

        # CN 開始タイミングのインターバル分布（ユニークな開始位置のみ）
        cn_start_positions = sorted(set(s for s, e, k in cn_events))
        cn_intervals = [cn_start_positions[i] - cn_start_positions[i-1]
                        for i in range(1, len(cn_start_positions))]
        cn_icounts  = Counter(cn_intervals)
        cn_total_w  = sum(cn_icounts.values()) or 1

        cn_interval_detail = {}
        for d, cnt in sorted(cn_icounts.items(), key=lambda x: -x[1])[:10]:
            cn_interval_detail[d] = {
                'name': iname(d),
                'count': cnt,
                'pct': round(cnt / cn_total_w * 100, 1),
                'eff16': round(24.0 * bpm_main / d, 1) if d > 0 else 0,
            }

        # CN保持中に他の鍵盤（non-CN）が降ってくるノーツの割合
        # CN hold 区間 [start, end] に重なる非CNノーツをカウント
        # スクラッチCNは除外（鍵盤との絡みが少ないため）
        kbd_cn_holds = [(s, e) for s, e, k in cn_events if k != 0]  # 鍵盤CNのみ
        non_cn_kbd_notes = [pos for pos, k in all_notes if k != 0]   # 非CNの鍵盤ノーツ
        if kbd_cn_holds and non_cn_kbd_notes:
            overlap_cnt = sum(
                1 for pos in non_cn_kbd_notes
                if any(s < pos < e for s, e in kbd_cn_holds)
            )
            cn_kbd_overlap_pct = round(overlap_cnt / len(non_cn_kbd_notes) * 100, 2)
        else:
            cn_kbd_overlap_pct = 0.0

        cn_profile = {
            'cn_notes': cn_obj_count,
            'cn_scratch_pct': round(cn_scratch_pct, 2),
            'cn_kbd_overlap_pct': cn_kbd_overlap_pct,  # CN保持中の他鍵盤割合
            'cn_interval_dist': cn_interval_detail,
        }

    return {
        'notes': len(all_notes),
        'events': total_ev,
        'bpm_main': bpm_main,
        'is_soflan': is_soflan,
        'dominant_interval': dominant_interval,
        'dominant_eff16': round(dominant_eff16, 1),
        'weighted_eff16': round(weighted_eff16, 1),
        'scratch_pct': round(scratch_pct, 2),
        'chord_pct': round(chord_pct, 2),
        'single_pct': round(single_pct, 2),
        'ranuchi': ranuchi,
        'interval_dist': interval_detail,
        'chord_dist': {k: v for k, v in sorted(chord_dist.items())},
        'tags': tags,
        **kbd_profile,
        **scr_profile,
        **cn_profile,
    }


# -----------------------------------------------------------------------------
# main
# -----------------------------------------------------------------------------
if __name__ == '__main__':
    results = []

    r1 = analyze_song(
        "22/chrono_p.html?1AC00", "196",
        "Chrono Diver -PENDULUMs- ANOTHER [Lv12]", expected_notes=2222,
    )
    results.append(r1)

    r2 = analyze_song(
        "7/max_300.html?1AC00", "12-300",
        "MAX 300 ANOTHER [Lv12]", expected_notes=1429,
    )
    results.append(r2)

    print(f"\n{'=' * 72}")
    print("  COMPARISON SUMMARY")
    print(f"{'=' * 72}")
    hdr = (f"  {'title':<40}  {'dom.eff16':>9}  {'avg.eff16':>9}"
           f"  {'scratch':>7}  {'chord':>6}  {'ranuchi':>7}")
    print(hdr)
    print(f"  {'-'*68}")
    for r in results:
        print(f"  {r['title']:<40}  {r['dominant_eff16']:>9.0f}  "
              f"{r['weighted_eff16']:>9.0f}  {r['scratch_pct']:>6.1f}%  "
              f"{r['chord_pct']:>5.1f}%  {r['ranuchi']:>7}")

    print(f"\n{'=' * 72}")
    print("  NOTE: eff16 = 16th-note equivalent BPM = 24 * raw_BPM / interval")
    print(f"{'=' * 72}")

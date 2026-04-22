/**
 * 【ユーティリティの役割】 beat-seeker 汎用ブックマークレットのソース文字列を提供する。
 *
 * ブックマークレットとは: ブラウザの「お気に入り」に `javascript:` 始まりの URL を登録し、
 * クリックするだけで任意のページ上でスクリプトを実行できる仕組み。
 *
 * このブックマークレットは e-amusement GATE（IIDX）のどのページからでも動くよう設計されており:
 *  1. URL から IIDX のバージョン番号（例: 33）を推定
 *  2. ARENA モードの対戦データページを取得し、DOM からバトル情報をパース
 *  3. スコア CSV のダウンロードページを取得し、textarea の中身を抜き出す
 *  4. 取得した全データを JSON 化し、Base64 エンコードして URL fragment に載せる
 *     （fragment にすることで iPhone Safari でも長大データを扱える）
 *  5. beat-seeker の本番 URL へリダイレクトし、取り込みダイアログを自動オープン
 *
 * 使い方:
 *  - フロント側の「ブックマークレットを追加」機能などからこの文字列をコピーし、
 *    ブラウザのブックマーク URL として登録する。
 *
 * 注意:
 *  - コード全体が 1 行になっているのはブックマーク URL の仕様上の都合。
 *  - データが 50000 文字を超える場合は CSV を外して URL fragment を圧縮し、
 *    代わりにクリップボードへ CSV を書き込む（手動貼り付け用のフェイルオーバー）。
 */
export const BOOKMARKLET_CODE = `javascript:void(async function(){var vMatch=location.pathname.match(/\\/game\\/2dx\\/(\\d+)\\//);var ver=vMatch?vMatch[1]:'33';var base=location.origin+'/game/2dx/'+ver;var myName='';var battles=[];try{var arenaDoc=document;if(!document.querySelector('.arena-title')){var ar=await fetch(base+'/djdata/arena_mode/index.html',{credentials:'same-origin'});var ah=await ar.text();var p1=new DOMParser();arenaDoc=p1.parseFromString(ah,'text/html');}var n=arenaDoc.querySelector('a[href*="djdata/status"] .on-name li:last-child');myName=n?(n.innerText||n.textContent||'').trim():'';var titles=arenaDoc.querySelectorAll('.arena-title');var battleDivs=arenaDoc.querySelectorAll('.arena-battle');titles.forEach(function(t,i){var b=battleDivs[i];if(!b)return;var bt=t.querySelector('.battle');var dt=t.querySelector('.date');var battleType=bt?(bt.innerText||bt.textContent||'').trim():'';var dateRaw=dt?(dt.innerText||dt.textContent||'').replace(/\u5bfe\u6226\u65e5\u6642[\\s\\S]*?\uff1a/,'').trim():'';var tbl=b.querySelector('table');if(!tbl)return;var rows=tbl.querySelectorAll('tr');var ths=rows[0].querySelectorAll('th');var songs=[];for(var j=2;j<ths.length;j++){var txt=(ths[j].innerText||ths[j].textContent||'').replace(/\\u00a0/g,' ').replace(/\\n/g,' ').trim();var sm2=txt.match(/^(.+?)\\s*\\/\\s*(.+)$/);var title=sm2?sm2[1].trim():txt;var diff=sm2?sm2[2].trim():'';songs.push({title:title,difficulty:diff});}var players=[];for(var k=1;k<rows.length;k++){var tds=rows[k].querySelectorAll('td');if(tds.length<3)continue;var djName=(tds[0].innerText||tds[0].textContent||'').trim();var img=tds[1].querySelector('img');var cls='';if(img){var m=img.src.match(/arena_icon\\/(a\\d+)\\.png/);if(m)cls=m[1].toUpperCase();}var ptTxt=(tds[2].innerText||tds[2].textContent||'').replace(/\\u00a0/g,' ').trim();var pmTotal=ptTxt.match(/(\\d+)pt/);var pmRank=ptTxt.match(/(\\d+)\u4f4d/);var totalPt=pmTotal?parseInt(pmTotal[1]):0;var rank=pmRank?parseInt(pmRank[1]):0;var songScores=[];for(var s=3;s<tds.length;s++){var stxt=(tds[s].innerText||tds[s].textContent||'').replace(/\\u00a0/g,' ').trim();var sm=stxt.match(/(\\d+)/);var spm=stxt.match(/(\\d+)pt/);songScores.push({score:sm?parseInt(sm[1]):0,pt:spm?parseInt(spm[1]):0});}players.push({djName:djName,arenaClass:cls,totalPt:totalPt,rank:rank,songScores:songScores});}battles.push({battleType:battleType,date:dateRaw,songs:songs,players:players});});}catch(e){console.warn('ARENA data failed',e);}var scoresCsv='';try{var sr=await fetch(base+'/djdata/score_download.html?style=SP',{credentials:'same-origin'});var sh=await sr.text();var p2=new DOMParser();var sd=p2.parseFromString(sh,'text/html');var ta=sd.querySelector('textarea');if(ta){var tv=(ta.textContent||ta.innerHTML||'').trim();if(tv.includes('\u30bf\u30a4\u30c8\u30eb'))scoresCsv=tv;}}catch(e){console.warn('Score CSV failed',e);}var fullData=JSON.stringify({type:'beat-seeker-combined',scoresCsv:scoresCsv,myDjName:myName,year:String(new Date().getFullYear()),battles:battles});var urlData=fullData;if(fullData.length>50000){urlData=JSON.stringify({type:'beat-seeker-combined',scoresCsv:'',myDjName:myName,year:String(new Date().getFullYear()),battles:battles});}var encoded=btoa(unescape(encodeURIComponent(urlData)));var url='https://beat-seeker-1.onrender.com?import=open#data='+encoded;var clipOk=false;try{await navigator.clipboard.writeText(fullData);clipOk=true;}catch(e){}var msg='\u53d6\u5f97\u5b8c\u4e86\uff01\\nARENA\u30c7\u30fc\u30bf: '+battles.length+'\u4ef6\\n\u30b9\u30b3\u30a2CSV: '+(scoresCsv?'\u53d6\u5f97\u6210\u529f':'\u53d6\u5f97\u5931\u6557')+'\\n';if(fullData.length>50000&&!clipOk){msg+='\\n\u26a0\ufe0f \u30c7\u30fc\u30bf\u304c\u5927\u304d\u3044\u305f\u3081CSV\u306f\u624b\u52d5\u3067\u30b3\u30d4\u30fc\u3057\u3066\u304f\u3060\u3055\u3044\u3002';}alert(msg);location.href=url;}());`;

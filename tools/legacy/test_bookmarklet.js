// UNUSED: ブックマークレット検証用のワンショットスクリプト。詳細は UNUSED.md 参照。
const fs = require('fs');

// Read the TS source
const src = fs.readFileSync('frontend/src/utils/bookmarklet.ts', 'utf8');

// Find the template literal boundaries
const startMarker = '= `';
const endMarker = '`;';
const idx1 = src.indexOf(startMarker);
const idx2 = src.lastIndexOf(endMarker);

if (idx1 === -1 || idx2 === -1) {
  fs.writeFileSync('/tmp/bm_test.txt', 'Could not find template literal boundaries');
  process.exit(1);
}

// Evaluate the template literal to get the runtime string value
// Since it's a simple template literal with no ${} interpolation, 
// the only processing is escape sequence resolution
const templateBody = src.substring(idx1 + 3, idx2);

// Write the raw template body for inspection
fs.writeFileSync('/tmp/bm_raw.txt', templateBody);

// The runtime value is what you get when JS processes the template literal
// For a template literal, \\ becomes \, \n becomes newline, etc.
let runtime;
try {
  runtime = eval('`' + templateBody + '`');
} catch(e) {
  fs.writeFileSync('/tmp/bm_test.txt', 'Template eval error: ' + e.message);
  process.exit(1);
}

// Extract just the JS code (strip 'javascript:' prefix)
const jsCode = runtime.substring(11); // 'javascript:'.length = 11

const results = [];
results.push('Runtime string length: ' + runtime.length);
results.push('JS code length: ' + jsCode.length);
results.push('');

// Try parsing via eval in a safe wrapper
try {
  // Just check syntax by wrapping in a non-executing check
  new Function('"use strict"; return ' + jsCode);
  results.push('SYNTAX: OK - the bookmarklet JS parses correctly');
} catch(e) {
  results.push('SYNTAX ERROR: ' + e.message);
  
  // Try to find where the error is
  // Split into statements and try parsing each
  results.push('');
  results.push('Attempting to locate error...');
  results.push('Last 300 chars of JS: ' + jsCode.substring(jsCode.length - 300));
}

results.push('');
results.push('=== First 200 chars ===');
results.push(jsCode.substring(0, 200));
results.push('');
results.push('=== Last 200 chars ===');
results.push(jsCode.substring(jsCode.length - 200));

fs.writeFileSync('/tmp/bm_test.txt', results.join('\n'));

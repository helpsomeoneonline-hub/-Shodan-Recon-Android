import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';

const file = 'app/src/main/assets/index.html';
const html = fs.readFileSync(file, 'utf8');
const scripts = [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)].map((match, index) => {
  const source = match[1].match(/\bsrc\s*=\s*(["'])([^"']+)\1/i)?.[2];
  if (source) {
    const filename = path.resolve(path.dirname(file), source);
    if (!filename.startsWith(path.resolve(path.dirname(file)) + path.sep)) throw new Error('Dashboard scripts must be local assets');
    return { filename, code: fs.readFileSync(filename, 'utf8') };
  }
  return { filename: `${file}:script-${index + 1}`, code: match[2] };
});
if (!scripts.length) throw new Error(`${file}: no embedded dashboard script found`);
for (const script of scripts) {
  new vm.Script(script.code, { filename: script.filename });
}
const combined = [html, ...scripts.map(script => script.code)].join('\n');
const declaredIds = new Set([...combined.matchAll(/\bid\s*=\s*["']([^"']+)["']/g)].map(match => match[1]));
// The camera form uses a field(label, id, ...) renderer for its input markup.
for (const match of combined.matchAll(/\bfield\(\s*(["'])[^"']*\1\s*,\s*(["'])([^"']+)\2/g)) declaredIds.add(match[3]);
const referencedIds = new Set(scripts.flatMap(script =>
  [...script.code.matchAll(/\$\(\s*(["'])([^"']+)\1\s*\)/g)].map(match => match[2])
));
const missing = [...referencedIds].filter(id => !declaredIds.has(id));
if (missing.length) throw new Error(`Dashboard references missing controls: ${missing.join(', ')}`);
console.log(`Dashboard JavaScript syntax and ${referencedIds.size} control references verified.`);

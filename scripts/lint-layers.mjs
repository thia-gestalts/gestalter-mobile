// Enforces the layered package structure described in doc/internal/architecture.md.
// Every reference to one of our packages (import or fully qualified name) must point to the
// same unit or to a layer listed below the referencing layer.
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve('src/main/java');
const base = 'thia.gestalter';
// Layer -> layers it may reference. `res` is the generated resource class thia.gestalter.mobile.R.
const layers = {
  core: [],
  'android.platform': ['core'],
  'android.ui': ['res'],
  'android.workspace': ['core', 'android.platform'],
  'android.navigation': ['core', 'android.ui', 'android.workspace'],
  'android.features': ['core', 'android.platform', 'android.ui', 'android.workspace', 'android.navigation'],
  'android.screens': ['core', 'android.platform', 'android.ui', 'android.workspace', 'android.navigation',
    'android.features'],
  'android.shell': ['core', 'android.platform', 'android.ui', 'android.workspace', 'android.navigation',
    'android.features', 'android.screens'],
};
// Layers whose sub-packages are independent units that must not reference one another.
const unitLayers = new Set(['android.features', 'android.screens']);
// Packages that must stay free of the Android SDK, so the record, sync and Queue rules port to iOS.
const portable = new Set(['core']);

/** Maps a package name to its layer and unit, or null when it is not one of ours. */
function locate(pkg) {
  if (pkg === `${base}.mobile`) return { layer: 'res', unit: 'res' };
  if (!pkg.startsWith(`${base}.`)) return null;
  const rest = pkg.slice(base.length + 1).split('.');
  for (const layer of Object.keys(layers)) {
    const parts = layer.split('.');
    if (parts.every((part, index) => rest[index] === part)) {
      const extra = rest.slice(parts.length);
      if (unitLayers.has(layer)) {
        return extra.length === 1 ? { layer, unit: `${layer}.${extra[0]}` } : { layer, unit: null };
      }
      return extra.length === 0 ? { layer, unit: layer } : { layer, unit: null };
    }
  }
  return { layer: null, unit: null };
}

/** Drops comments, string and character literals so only code is scanned. */
function code(source) {
  return source.replace(/\/\*[\s\S]*?\*\/|\/\/[^\n]*|"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'/g, ' ');
}

const errors = [];
for (const name of fs.readdirSync(root, { recursive: true })) {
  const file = path.join(root, name);
  if (fs.statSync(file).isDirectory() || !name.endsWith('.java')) continue;
  const source = code(fs.readFileSync(file, 'utf8'));
  const declared = /^\s*package\s+([\w.]+)\s*;/m.exec(source)?.[1];
  const where = path.relative(process.cwd(), file);
  if (!declared) { errors.push(`${where}: missing package declaration`); continue; }
  if (path.dirname(name).split(path.sep).join('.') !== declared) {
    errors.push(`${where}: package ${declared} does not match its directory`);
  }
  const self = locate(declared);
  if (!self?.layer || !self.unit) {
    errors.push(`${where}: ${declared} is not a known layer${self?.layer ? ' unit' : ''}`);
    continue;
  }
  if (portable.has(self.layer) && /\bandroid\s*\.\s*[a-z]/.test(source.replace(/^\s*package[^;]*;/m, ''))) {
    errors.push(`${where}: ${self.layer} must not use the Android SDK`);
  }
  const seen = new Set();
  for (const match of source.matchAll(/\bthia\s*\.\s*gestalter(?:\s*\.\s*[a-z][a-z0-9_]*)*/g)) {
    const pkg = match[0].replace(/\s+/g, '');
    if (pkg === declared || seen.has(pkg)) continue;
    seen.add(pkg);
    const target = locate(pkg);
    if (!target) continue;
    if (!target.layer || !target.unit) { errors.push(`${where}: references unknown package ${pkg}`); continue; }
    if (target.unit === self.unit) continue;
    if (target.layer === self.layer) {
      errors.push(`${where}: ${self.unit} must not reference its sibling ${target.unit}`);
    } else if (!layers[self.layer].includes(target.layer)) {
      errors.push(`${where}: ${self.layer} must not reference ${target.layer} (${pkg})`);
    }
  }
}

if (errors.length) {
  console.error(errors.join('\n'));
  console.error(`\n${errors.length} layer violation(s). See doc/internal/architecture.md.`);
  process.exit(1);
}
console.log('Layer check passed.');

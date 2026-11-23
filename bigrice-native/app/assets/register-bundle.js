// Register one bundle in a dsh profile: dependency entry + bundles list.
//   node add-bundle.js <profileDir> <packageName> [externalDir]
//
// With an external directory the entry is written as `link:<dir>`, a plain
// symlink. The `file:` form makes pnpm import (hard-link) the package into
// .pnpm, which this application domain is not allowed to do - see install.sh.
const fs = require('node:fs')
const path = require('node:path')
const [dir, name, external] = process.argv.slice(2)
const file = path.join(dir, 'package.json')
fs.mkdirSync(dir, { recursive: true })
// A first install has no profile yet, so seed the one the app ships with.
const fresh = () => ({
  name: 'dsh-profile-web',
  private: true,
  dependencies: {},
  dsh: { profile: { bundles: ['@deepseek-ai/dsh-base', '@deepseek-ai/dsh-web-app'] } },
})
const json = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : fresh()
json.dependencies = json.dependencies || {}
json.dependencies[name] = external
  ? `link:${path.resolve(external)}`
  : `file:./node_modules/${name}`
json.dsh = json.dsh || {}
json.dsh.profile = json.dsh.profile || {}
const bundles = (json.dsh.profile.bundles = json.dsh.profile.bundles || [])
if (!bundles.includes(name)) bundles.push(name)
fs.writeFileSync(file, JSON.stringify(json, null, 2) + '\n')
console.log(fs.readFileSync(file, 'utf8'))

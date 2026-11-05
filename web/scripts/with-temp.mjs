// 本机用户 TEMP 对部分进程不可写（DECISIONS.md #本地开发数据库 附注，esbuild 构建实测复现），
// 统一重定向到仓库内 .tmp-node/（已被根 .gitignore 的 .tmp-*/ 覆盖）
import { spawnSync } from 'node:child_process'
import { mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const tmp = fileURLToPath(new URL('../.tmp-node/', import.meta.url))
mkdirSync(tmp, { recursive: true })
const env = { ...process.env, TEMP: tmp, TMP: tmp, TMPDIR: tmp }

const [cmd, ...args] = process.argv.slice(2)
if (!cmd) {
  console.error('usage: node scripts/with-temp.mjs <command> [args...]')
  process.exit(2)
}
const result = spawnSync(cmd, args, {
  stdio: 'inherit',
  env,
  shell: process.platform === 'win32',
})
process.exit(result.status ?? 1)

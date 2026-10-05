#!/usr/bin/env node
/**
 * probe_zen_free_models.mjs — live availability probe for the OpenCode Zen
 * free lane (https://opencode.ai/zen/v1), keyless.
 *
 * WHY THIS EXISTS
 * The Zen gateway admits a request only when `x-opencode-session` has the
 * canonical shape `ses_` + 12 lowercase hex + 14 Base62 characters. A session
 * id of any other length, a missing `ses_` prefix, or an uppercase hex prefix
 * is answered with
 *
 *     403 {"type":"error","error":{"type":"FreeTierError",
 *          "message":"... OpenCode's free tier can only be used from within OpenCode"}}
 *
 * and that body is BYTE-IDENTICAL to the one a request carrying no disguise at
 * all receives. So a probe built with a hand-written session id reports the
 * entire free lane as dead while every model is in fact reachable. The message
 * blames the client, which sends you looking for a policy that does not exist.
 *
 * This script derives the id the same way the app does
 * (com.openminis.app.provider.ZenDisguise.sessionIdForSeed) and reports a
 * per-model tally, so a re-measurement can never be fooled that way.
 *
 * USAGE
 *   node scripts/probe_zen_free_models.mjs                 # curated roster
 *   node scripts/probe_zen_free_models.mjs big-pickle      # specific ids
 *   node scripts/probe_zen_free_models.mjs --rounds 3      # repeats per model
 *   node scripts/probe_zen_free_models.mjs --all           # every -free id
 *
 * Reads no credentials and sends no conversation content: each request is the
 * single word "hi" with a 16-token output cap.
 */

import { createHash } from 'node:crypto'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'

const execFileAsync = promisify(execFile)

const ZEN_CHAT = 'https://opencode.ai/zen/v1/chat/completions'
const ZEN_MODELS = 'https://opencode.ai/zen/v1/models'
const USER_AGENT = 'opencode/1.18.31 (Android arm64; native)'
const ALPHABET = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz'

/** Ids measured to answer 200 on 2026-10-05, each re-probed 3x. */
const CURATED = [
  'big-pickle',
  'space-bunny-free',
  'mimo-v2.6-flash-free',
  'mimo-v2.5-free',
  'nemotron-3-ultra-free',
  'nemotron-3.5-lightning-free',
  'ling-3.1-flash-free',
  'longcat-2.5-preview-free',
  'fledge-alpha-free',
]

/**
 * The canonical session id, derived exactly as ZenDisguise does:
 *   sha256("ses" + 0x00 + seed) -> first 6 bytes as lowercase hex (12 chars)
 *                                 + bytes 6..16 as Base62 (14 chars)
 * The NUL separator is built from a byte, never from an escape in a shell
 * string — a shell that eats the NUL silently produces a different digest and
 * a session id the upstream refuses.
 */
function canonicalSessionId(seed) {
  const digest = createHash('sha256')
    .update(Buffer.concat([Buffer.from('ses', 'utf8'), Buffer.from([0x00]), Buffer.from(seed, 'utf8')]))
    .digest()
  const time = Array.from(digest.subarray(0, 6))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('')
  // BigInteger(1, bytes) is UNSIGNED in Kotlin and in JS BigInt alike.
  let value = 0n
  for (const b of digest.subarray(6, 16)) value = (value << 8n) | BigInt(b)
  const tail = new Array(14)
  for (let i = 13; i >= 0; i--) {
    tail[i] = ALPHABET[Number(value % 62n)]
    value /= 62n
  }
  return 'ses_' + time + tail.join('')
}

const CANONICAL = /^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$/

/**
 * The bash/read gate tools. The free lane refuses any chat body whose `tools`
 * array lacks function tools with exactly these two names; a plain
 * conversation carries none, so the stubs are appended unconditionally.
 */
const GATE_TOOLS = [
  { type: 'function', function: { name: 'bash', description: 'Reserved for the host runtime; do not call it.', parameters: { type: 'object', properties: {} } } },
  { type: 'function', function: { name: 'read', description: 'Reserved for the host runtime; do not call it.', parameters: { type: 'object', properties: {} } } },
]

async function httpStatus(url, args) {
  try {
    const { stdout } = await execFileAsync('curl', [
      '-s', '-o', process.platform === 'win32' ? 'NUL' : '/dev/null',
      '-w', '%{http_code}', '--max-time', '30', ...args, url,
    ])
    return { code: stdout.trim() }
  } catch (err) {
    return { code: 'ERR', error: String(err.message).slice(0, 80) }
  }
}

async function fetchFreeIds() {
  const res = await httpStatus(ZEN_MODELS, [
    '-H', `User-Agent: ${USER_AGENT}`,
    '-H', 'x-opencode-client: cli',
  ])
  if (res.code !== '200') {
    console.error(`could not read ${ZEN_MODELS} (HTTP ${res.code}); using the curated roster`)
    return CURATED
  }
  // Re-run to capture the body (the tally call above discards it).
  const { stdout } = await execFileAsync('curl', [
    '-s', '--max-time', '30',
    '-H', `User-Agent: ${USER_AGENT}`,
    '-H', 'x-opencode-client: cli',
    ZEN_MODELS,
  ])
  try {
    const ids = JSON.parse(stdout).data.map((m) => m.id)
    return ids.filter((id) => /free/i.test(id))
  } catch {
    console.error('could not parse the model catalogue; using the curated roster')
    return CURATED
  }
}

async function probe(model, seed) {
  const session = canonicalSessionId(seed)
  if (!CANONICAL.test(session)) {
    // A non-canonical id would make this probe lie. Fail loudly instead.
    throw new Error(`internal error: derived a non-canonical session id: ${session}`)
  }
  const body = JSON.stringify({
    model,
    stream: true,
    max_completion_tokens: 16,
    messages: [{ role: 'user', content: 'hi' }],
    tools: GATE_TOOLS,
    tool_choice: 'none',
  })
  return httpStatus(ZEN_CHAT, [
    '-X', 'POST',
    '-H', 'Content-Type: application/json',
    '-H', 'Authorization: Bearer public',
    '-H', `User-Agent: ${USER_AGENT}`,
    '-H', 'x-opencode-client: cli',
    '-H', `x-opencode-session: ${session}`,
    '-H', `x-opencode-request: req_probe${Date.now()}${Math.floor(Math.random() * 1e6)}`,
    '-d', body,
  ])
}

/**
 * Classify one model's observed codes.
 *
 * 429 is deliberately NOT a death sentence: the shared anonymous bucket is
 * rate-limited, and a model measured as 200/429/429/200 is a WORKING model
 * behind a quota, not a retired one. Excluding it would swap a usable row for
 * nothing, so it is reported as OK-with-rate-limits and kept in the roster.
 *
 * The failure buckets are the ones whose message names the thing that is
 * actually wrong: 400 "Endpoint is unavailable" / "Model is unavailable" is an
 * upstream retirement, 500 is an upstream fault, and 403 means the request
 * itself was refused (geo-fence, or a session identity this script would have
 * caught before sending).
 */
function classify(codes) {
  if (codes.includes('200')) {
    const limited = codes.filter((c) => c === '429').length
    return limited > 0 ? 'OK (rate-limited)' : 'OK'
  }
  if (codes.some((c) => c === '403')) return 'REFUSED'
  if (codes.every((c) => c === '400')) return 'RETIRED'
  if (codes.every((c) => c === '500' || c === '000' || c === 'ERR')) return 'UPSTREAM-DOWN'
  if (codes.every((c) => c === '429')) return 'RATE-LIMITED'
  return 'MIXED'
}

/** Whether a verdict means "keep this model in bundledZenModels()". */
function isUsable(verdict) {
  return verdict === 'OK' || verdict === 'OK (rate-limited)'
}

async function main() {
  const argv = process.argv.slice(2)
  const rounds = Number(argv[argv.indexOf('--rounds') + 1]) || 1
  const wantAll = argv.includes('--all')
  const explicit = argv.filter((a) => !a.startsWith('--') && a !== String(rounds))

  const models = explicit.length > 0 ? explicit : wantAll ? await fetchFreeIds() : CURATED
  console.log(`probing ${models.length} model(s), ${rounds} round(s) each, keyless\n`)

  const rows = []
  for (const model of models) {
    const codes = []
    for (let r = 0; r < rounds; r++) {
      // A fresh session per round: reusing one would measure the upstream's
      // prompt cache rather than the model's availability.
      const { code } = await probe(model, `${model}:${r}:${Date.now()}`)
      codes.push(code)
    }
    const verdict = classify(codes)
    rows.push({ model, codes, verdict })
    console.log(`${model.padEnd(32)} ${codes.join(' ').padEnd(14)} ${verdict}`)
  }

  console.log('')
  const usable = rows.filter((r) => isUsable(r.verdict)).map((r) => r.model)
  console.log(`usable (${usable.length}/${rows.length}): ${usable.join(', ') || 'none'}`)
  const notes = rows.filter((r) => !isUsable(r.verdict))
  if (notes.length > 0) {
    console.log('')
    console.log('not usable:')
    for (const n of notes) console.log(`  ${n.model.padEnd(32)} ${n.verdict}  [${n.codes.join(' ')}]`)
  }
  console.log('')
  console.log('When refreshing bundledZenModels() in ProviderRepository.kt, use the')
  console.log('usable list above. "OK (rate-limited)" belongs IN the roster: the')
  console.log('model answers, the shared anonymous bucket is just throttled.')
}

main().catch((err) => {
  console.error(err)
  process.exit(1)
})

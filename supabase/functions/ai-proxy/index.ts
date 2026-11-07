// Supabase Edge Function: the ONLY place AI keys live. Deploy: supabase functions deploy ai-proxy
//
// SECRETS (Edge Functions → Secrets). Each can hold SEVERAL keys, one per line (or comma-separated):
//   GROQ_API_KEYS, GEMINI_API_KEYS, OPENROUTER_API_KEYS (+ CEREBRAS_, NVIDIA_, DEEPSEEK_, MISTRAL_, TOGETHER_, OPENAI_ …_API_KEYS)
// If one key hits its limit, the next key is used; then the next model/provider in the chain below.
// Optional: MODEL_CHAIN (JSON, see CREDITS-SYSTEM.md) to change the model order and prices without redeploying code.
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const CREDIT_USD = 0.001;                          // 1 credit = $0.001 of AI cost
type Entry = { p: keyof typeof BASES; m: string; in: number; out: number };   // prices: USD per 1M tokens
// ORDER = PRIORITY: the first entry is tried first, the next one only if it fails. CHECK the prices before launch.
const DEFAULT_CHAIN: Record<string, Entry[]> = {
  big: [
    { p: "groq", m: "openai/gpt-oss-120b", in: 0.15, out: 0.60 },
    { p: "openrouter", m: "openai/gpt-oss-120b", in: 0.15, out: 0.60 },
    { p: "gemini", m: "gemini-2.5-flash", in: 0.30, out: 2.50 },
  ],
  small: [
    { p: "groq", m: "openai/gpt-oss-20b", in: 0.075, out: 0.30 },
    { p: "gemini", m: "gemini-2.5-flash-lite", in: 0.10, out: 0.40 },
    { p: "openrouter", m: "openai/gpt-oss-20b", in: 0.075, out: 0.30 },
  ],
  vision: [   // photo → text and photo analysis: Gemini Flash first, Groq as the back-up
    { p: "gemini", m: "gemini-2.5-flash", in: 0.30, out: 2.50 },
    { p: "groq", m: "meta-llama/llama-4-scout-17b-16e-instruct", in: 0.11, out: 0.34 },
  ],
};
let CHAIN = DEFAULT_CHAIN;
try { const c = Deno.env.get("MODEL_CHAIN"); if (c) CHAIN = { ...DEFAULT_CHAIN, ...JSON.parse(c) }; } catch (_) { /* bad JSON → defaults */ }

// Any OpenAI-compatible provider works. Its keys live in <NAME>_API_KEYS (one per line); add it to MODEL_CHAIN to use it.
const BASES = {
  groq: "https://api.groq.com/openai/v1",
  gemini: "https://generativelanguage.googleapis.com/v1beta/openai",
  openrouter: "https://openrouter.ai/api/v1",
  cerebras: "https://api.cerebras.ai/v1",
  nvidia: "https://integrate.api.nvidia.com/v1",
  deepseek: "https://api.deepseek.com/v1",
  mistral: "https://api.mistral.ai/v1",
  together: "https://api.together.xyz/v1",
  openai: "https://api.openai.com/v1",
} as const;
const keysOf = (p: keyof typeof BASES): string[] => {
  const out: string[] = [];
  const P = p.toUpperCase();
  for (const n of [`${P}_API_KEYS`, `${P}_API_KEY`]) for (const raw of (Deno.env.get(n) ?? "").split(/[\s,;]+/)) {
    // tolerate quotes and a pasted "NAME=" prefix; ignore anything that cannot be a key
    const k = raw.replace(/^[A-Z_]+=/, "").replace(/^["'`]+|["'`]+$/g, "");
    if (k.length >= 20 && !k.includes("...") && !out.includes(k)) out.push(k);
  }
  return out;                                       // order in the secret = order of use
};

const WHISPER_ID = "whisper-large-v3-turbo", WHISPER_PER_HOUR = 0.04;
const TTS_ID = "canopylabs/orpheus-v1-english", TTS_PER_M_CHARS = Number(Deno.env.get("TTS_PER_M_CHARS") ?? 22);
const MIN_CHARGE = 0.05, MAX_OUT_TOKENS = 4096, MAX_IN_CHARS = 120_000, MAX_AUDIO_SECONDS = 180, MAX_TTS_CHARS = 400, MAX_IMAGE_B64 = 5_000_000;
const RESERVE_FOR_PAID = 1;                         // when a provider has 2+ keys, the LAST one is kept for paid users
const WAIT_MS = [25_000, 15_000, 8_000];            // how long a request may wait for a free slot: paid, new free user (<7 days), older free user

const cors = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type" };
const json = (o: unknown, status = 200) => new Response(JSON.stringify(o), { status, headers: { ...cors, "Content-Type": "application/json" } });
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

// per-key "cooling down" memory (lives while the function instance is warm)
const cool = new Map<string, number>();
const rot = new Map<string, number>();

type Out = { res: Response | null; status: number; text: string };

/** Tries every allowed key of one provider for one model. Returns the first success, or the last failure. */
async function tryProvider(p: keyof typeof BASES, model: string, prio: number, doFetch: (base: string, key: string) => Promise<Response>): Promise<Out> {
  let keys = keysOf(p);
  if (prio > 0 && keys.length > RESERVE_FOR_PAID) keys = keys.slice(0, keys.length - RESERVE_FOR_PAID);
  const start = (rot.get(p) ?? 0); rot.set(p, start + 1);
  let last: Out = { res: null, status: keys.length ? 0 : 503, text: keys.length ? "" : `no ${p} key` };
  for (let i = 0; i < keys.length; i++) {
    const k = keys[(start + i) % keys.length];
    const id = p + k.slice(-6) + "|" + model;
    if ((cool.get(id) ?? 0) > Date.now()) { last = { res: null, status: 429, text: "cooling down" }; continue; }
    let r: Response;
    try { r = await doFetch(BASES[p], k); } catch (e) { last = { res: null, status: 502, text: String(e) }; cool.set(id, Date.now() + 5_000); continue; }
    if (r.ok) return { res: r, status: 200, text: "" };
    const text = await r.text();
    last = { res: null, status: r.status, text };
    console.error(`[ai-proxy] ${p} ${model} key…${k.slice(-4)} → HTTP ${r.status}: ${text.slice(0, 200)}`);   // visible in Edge Functions → Logs
    if (r.status === 429) cool.set(id, Date.now() + Math.max(15_000, Number(r.headers.get("retry-after") ?? 0) * 1000));
    else if (r.status === 401 || r.status === 403) cool.set(id, Date.now() + 10 * 60_000);
    else if (r.status >= 500) cool.set(id, Date.now() + 8_000);
    // 413 / 400: not the key's fault → try the next key (other accounts can have bigger limits), then the next model
  }
  return last;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "POST only" }, 405);

  const token = (req.headers.get("Authorization") ?? "").replace("Bearer ", "");
  const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
  const { data: u, error: ue } = await admin.auth.getUser(token);
  if (ue || !u?.user) return json({ error: "not signed in" }, 401);
  const uid = u.user.id;

  let body: any;
  try { body = await req.json(); } catch { return json({ error: "bad json" }, 400); }
  const kind = String(body.kind ?? "chat");
  const action = String(body.action ?? "AI request").slice(0, 60);
  const task = String(body.task ?? "").slice(0, 64) || null;     // all calls of one user action share this → one log line

  // who is this? paid users first, then NEW free users, then older free users
  const { data: prof } = await admin.from("profiles").select("credits,paid,free_used,free_day,created_at").eq("id", uid).single();
  const { data: cfg } = await admin.from("app_private").select("value").eq("key", "free_daily_credits").single();
  const balance = Number(prof?.credits ?? 0);
  const paid = !!prof?.paid;
  const ageDays = (Date.now() - new Date(prof?.created_at ?? Date.now()).getTime()) / 86_400_000;
  const prio = paid ? 0 : ageDays < 7 ? 1 : 2;
  const today = new Date().toISOString().slice(0, 10);
  const freeLeft = Math.max(0, Number(cfg?.value ?? 150) - (prof?.free_day === today ? Number(prof?.free_used ?? 0) : 0));
  const budget = WAIT_MS[prio];

  // paid credits first; when they are used up the daily free allowance pays
  const canPay = (worst: number) => {
    const w = Math.max(MIN_CHARGE, Math.min(worst, 1));
    return balance >= w || freeLeft >= w;
  };
  const charge = async (cost: number, detail: Record<string, unknown>) => {
    const c = Math.max(MIN_CHARGE, cost);
    if (balance >= 1) {
      const { data } = await admin.rpc("charge_task", { p_user: uid, p_cost: c, p_action: action, p_task: task, p_detail: detail });
      return { charged: Number(data?.charged ?? 0), balance: Number(data?.balance ?? 0), free_left: freeLeft, plan: "credits" };
    }
    const { data: fl } = await admin.rpc("apply_free", { p_user: uid, p_amount: c });
    return { charged: 0, balance, free_left: Number(fl ?? 0), plan: "free" };
  };
  const noCredit = () => json({ error: "insufficient credits", balance, free_left: freeLeft }, 402);
  // a provider's 401/403 means OUR key is wrong – never pass it on as if the user's login failed
  const fail = (o: Out) => (o.status === 401 || o.status === 403)
    ? json({ error: "ai service unavailable (server key rejected) – check the keys in Edge Functions → Secrets and the Logs" }, 503)
    : new Response(o.text || JSON.stringify({ error: "AI busy" }), { status: o.status === 429 ? 429 : o.status === 413 ? 413 : o.status >= 500 || o.status === 0 ? 502 : o.status, headers: { ...cors, "Content-Type": "application/json" } });
  const llmCost = (m: { in: number; out: number }, i: number, o: number) => (i * m.in + o * m.out) / 1e6 / CREDIT_USD;

  // ---------------- listening (Whisper, Groq only)
  if (kind === "stt") {
    const secs = Math.min(Number(body.seconds) || 10, MAX_AUDIO_SECONDS);
    const cost = Math.max(10, secs) * WHISPER_PER_HOUR / 3600 / CREDIT_USD;
    if (!canPay(cost)) return noCredit();
    const bin = Uint8Array.from(atob(String(body.audio_b64 ?? "")), (c) => c.charCodeAt(0));
    if (bin.length > 6_000_000) return json({ error: "audio too large" }, 413);
    const deadline = Date.now() + budget; let o: Out;
    do {
      o = await tryProvider("groq", WHISPER_ID, prio, (base, key) => {
        const fd = new FormData();
        fd.append("model", WHISPER_ID); fd.append("response_format", "json"); fd.append("temperature", "0");
        if (body.language) fd.append("language", String(body.language));
        fd.append("file", new Blob([bin], { type: "audio/mp4" }), "audio.m4a");
        return fetch(`${base}/audio/transcriptions`, { method: "POST", headers: { Authorization: `Bearer ${key}` }, body: fd });
      });
      if (o.res || o.status !== 429 || Date.now() > deadline) break;
      await sleep(2000);
    } while (true);
    if (!o.res) return fail(o);
    const out = await o.res.json();
    out.credits = await charge(cost, { model: WHISPER_ID, seconds: secs });
    return json(out);
  }

  // ---------------- speaking (Orpheus, Groq only)
  if (kind === "tts") {
    const t = String(body.text ?? "").slice(0, MAX_TTS_CHARS);
    if (!t) return json({ error: "empty text" }, 400);
    const cost = t.length * TTS_PER_M_CHARS / 1e6 / CREDIT_USD;
    if (!canPay(cost)) return noCredit();
    const deadline = Date.now() + budget; let o: Out;
    do {
      o = await tryProvider("groq", TTS_ID, prio, (base, key) => fetch(`${base}/audio/speech`, {
        method: "POST", headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json" },
        body: JSON.stringify({ model: TTS_ID, input: t, voice: String(body.voice ?? "autumn"), response_format: "wav" }),
      }));
      if (o.res || o.status !== 429 || Date.now() > deadline) break;
      await sleep(2000);
    } while (true);
    if (!o.res) return fail(o);
    const buf = new Uint8Array(await o.res.arrayBuffer());
    let bin = ""; for (let i = 0; i < buf.length; i += 0x8000) bin += String.fromCharCode(...buf.subarray(i, i + 0x8000));
    return json({ audio_b64: btoa(bin), credits: await charge(cost, { model: TTS_ID, chars: t.length }) });
  }

  // ---------------- chat + vision
  const tier = kind === "vision" ? "vision" : body.tier === "small" ? "small" : "big";
  let messages: unknown[]; let tools = body.tools;
  if (kind === "vision") {
    const img = String(body.image_b64 ?? "");
    if (!img || img.length > MAX_IMAGE_B64) return json({ error: "image missing or too large" }, 413);
    tools = undefined;
    messages = [{ role: "user", content: [
      { type: "text", text: String(body.prompt ?? "Describe the image.").slice(0, 4000) },
      { type: "image_url", image_url: { url: `data:image/jpeg;base64,${img}` } },
    ] }];
  } else messages = Array.isArray(body.messages) ? body.messages : [];
  const inChars = kind === "vision" ? 4000 + Math.ceil(String(body.image_b64).length / 400) * 1000 : JSON.stringify(messages).length + JSON.stringify(tools ?? []).length;
  if (!messages.length || inChars > MAX_IN_CHARS * 2) return json({ error: "request too large" }, 413);
  const maxOut = Math.min(Number(body.max_tokens) || 1024, MAX_OUT_TOKENS);
  const chain = CHAIN[tier];
  if (!canPay(llmCost(chain[0], inChars / 3, maxOut))) return noCredit();

  // walk the chain (priority order); when everything is busy wait a little (longer for paid users) and sweep again
  const deadline = Date.now() + budget;
  let used: Entry | null = null, resp: Response | null = null, lastFail: Out = { res: null, status: 502, text: "" };
  sweep: while (true) {
    let sawRate = false;
    for (const e of chain) {
      const payload: Record<string, unknown> = { model: e.m, messages, max_tokens: maxOut, temperature: kind === "vision" ? 0 : (body.temperature ?? 0.6), stream: false };
      if (tools) { payload.tools = tools; payload.tool_choice = "auto"; }
      const o = await tryProvider(e.p, e.m, prio, (base, key) => fetch(`${base}/chat/completions`, {
        method: "POST", headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json" }, body: JSON.stringify(payload),
      }));
      if (o.res) { used = e; resp = o.res; break sweep; }
      lastFail = o;
      if (o.status === 429) sawRate = true;
    }
    if (!sawRate || Date.now() > deadline) break;
    await sleep(2000);
  }
  if (!resp || !used) return fail(lastFail);                         // failed calls cost nothing
  const out = await resp.json();
  const inT = out.usage?.prompt_tokens ?? Math.ceil(inChars / 3);
  const outT = out.usage?.completion_tokens ?? maxOut;
  out.credits = await charge(llmCost(used, inT, outT), { model: used.m, provider: used.p, in: inT, out: outT });
  return json(out);
});

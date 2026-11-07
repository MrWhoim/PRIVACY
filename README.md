# P.R.I.V.A.C.Y. – App Guide (v1.0.0)

## What is this app?
**Private Reminders & Intelligence with Voice, Automation, Companion – for You.**
P.R.I.V.A.C.Y. is a private, all-in-one personal assistant for Android. You talk or type to an AI that knows your tasks, health, notes and habits, and it can act for you: add tasks, write notes, log meals, set reminders and automations. It keeps your day in three scores – Efficiency (tasks), Health (food, activity, sleep, water) and Mind (focus vs. distraction from your phone use) – and turns them into reports with honest advice. There is no account and no server of ours: your data stays on your phone, the vault and backups are encrypted, and the only thing that leaves is what you send to the AI provider you chose with your own API key.

## Features
- **Chat & Agent** – quick answers; big or research-style requests switch to Agent mode (a big model plans, small models work in parallel, the big model writes the answer). Tools: web search, notes, tasks, memories, automations, phone actions.
- **Tasks** – daily/repeating/due-date tasks with priority, difficulty, optional and core flags; pixel progress bar; tap the date for every earlier day. Efficiency of the Day = up to 85 task points + up to 15 commitment-star points.
- **Health** – log food, exercise, sleep, water, weight by chat or form; nutrition numbers are labelled estimates. Health = 0.35 Nutrition + 0.25 Activity + 0.20 Sleep + 0.20 Water.
- **Mind** – every app you use is tracked. Distracting apps and games cost credit (dopamine), useful apps (study, fitness, productivity) earn credit (detox), unknown apps give nothing until you decide (Distracting / Useful / Ignore). Tap an app for hourly graphs of minutes and credits. A distracting app you rate “Useful” (YouTube for study) counts as learning time. Morning guard nudges you in the first two hours after waking.
- **Reports** – Day / Week / Month for Efficiency, Health and Mind with rings, bars, trends and an AI section (what is going well, what keeps going wrong, how to fix it). Share as a picture.
- **Notes** – Markdown, photos, audio, pinning, AI rewrite, share as image, live app-to-app sharing (one editor at a time).
- **Private vault** – encrypted; you choose which items the assistant may see. **Memory** – lasting facts the assistant keeps about you.
- **Automations & Morning Brief** – reminders, task lists, AI prompts at chosen times (exact alarms, reboot-safe). The brief covers yesterday, today, leftovers, what is going well and what keeps going wrong; it retries when the app opens if it was missed.
- **Tools** – countdown, stopwatch, alarm, calendar, currency converter, calculator.
- **Voice & Companion** – pixel assistant that listens, talks, reacts, and floats over other apps.
- **Widgets** – Tasks, Health, Mind (Dopamine vs Detox), Note, Note view, Mic; refreshed when the app opens and every 15 minutes.
- **Telegram sync** – pair your own bot with a friend's bot (Bot-to-Bot Communication Mode on both) to share an AI chat or a note app-to-app with Accept/Reject, a live list of connections and one editor at a time. No message goes to anyone's chat.
- **Modes** – Feeling Distracted, Music Mode, Telegram AI Connect, Telegram Notes Connect.
- **Backup** – one AES-256 file with a password you choose: tasks, health (entries, confirmed days, plan, goals), Mind logs and app settings, notes with photos, chats with pictures, memories, vault, automations, settings, API keys, paired friends.

## How the scores work
- **Efficiency** – each planned task has a weight (difficulty × priority × type, max 6.5); credit 0 / 50 / 100 % for not started / in progress / done; tasks give up to 85 points, four commitment stars 3.75 each = 15.
- **Health** – H = 0.35 N + 0.25 A + 0.20 S + 0.20 W; a category not logged counts as 0.
- **Mind** – Detox % = 100 × (30 + P) / (60 + P + D); Dopamine % = 100 × (30 + D) / (60 + P + D).
  - P (useful): work/task 1.0, learning 0.9, exercise 1.2, personal 0.7 per minute; ×1.25 in the first 2 h after waking; +5 per finished task (max 15). Useful apps use their category weight.
  - D (distracting): per minute × category (short videos 1.2, social 1.0, entertainment video 0.8, games 0.9) × rating (somewhat relevant 0.45, low value 0.75, mindless 1.0) × timing (first 2 h after waking 1.5, normal 1.0, 30 min before a nap 0.45, last 90 min before bedtime 0.65). Rated “Useful” → learning time instead.
  - Unknown apps: 0 credit until you decide.
- **Overall** – average of the parts that have data.

## Token system (how much the AI uses and costs)
*Estimates read from the code (not billing data). Check current prices before relying on them.*

**What one request sends:** about 2.7k tokens of instructions + about 4.5k tokens of tool definitions (≈56 tools) + memories, open tasks, a health snapshot and up to 20 chat messages ≈ **8–10k tokens in**. If a provider's per-minute limit rejects it, the app retries with 18 core tools (≈5k). A turn that uses tools calls the model about twice.

| Task | Calls | Tokens (approx.) |
|---|---|---|
| Normal chat turn | 1–3 | 9k–25k in, 0.2–0.6k out |
| Agent mode (plan → 1–5 workers → final) | 4–10 | 30k–80k |
| Morning Brief (with 7-day data) | 1 | 3–4k in, 0.5k out |
| Report AI analysis | 1 | 1.5k in, up to 0.7k out |
| Photo / text rewrite, health coach, food parse | 1 | 0.5–2k |
| Companion line / health nudge | 1 | ≈0.5k in, ≤0.16k out |
| Memory digest (every 4th message) | 1 | 2–3k |

**A normal user** (≈20 chats a day plus background): ≈300k tokens a day ≈ **9M in + 0.3M out per month**. A light user (8 chats a day) is about 40 % of that.

**Can one free key cover it?** Light use, yes. Example: a free Groq key allows 8,000 tokens per minute and 200,000 per day on its large models, shared by the whole Groq account – about 30 messages a day. Heavy use needs keys from several providers (the app rotates them when one hits a limit). The pre-installed trial keys are shared by everyone and run out fast.

**One paid key – about 9M in / 0.3M out a month:**

| Model | Price per 1M tokens (in / out) | ≈ per month |
|---|---|---|
| DeepSeek V4 Flash | $0.14 / $0.28 (cache hits cost far less) | **≈ $1.4** (≈ $0.6 with good caching) |
| GPT-4o mini | $0.15 / $0.60 | ≈ $1.5 |
| Gemini Flash-class | about $0.75 / $3.75 | ≈ $8 |
| Sonnet-class | about $3 / $15 | ≈ $30 |

Tip: cheaper models are fine for chat; use a stronger one only for Agent mode (Models page: FAST vs BIG).

## Timeline – how the app changed
*Dates are the build file times (IST, approximate). Early builds used other numbers (v2, v2.1, v1.3 …); everything is now released as **v1.0.0**.*

- **14 Oct 2026 · build v2** – Widget rewritten (Tasks ⇄ Voice switch); Agent mode (big model plans, small models work in parallel); web search; automations with the full agent; permissions screen; chat history; pixel talking face; first look.
- **16 Oct 2026 · build v2.1** – Black & white animated look; left slide-out menu; AI decides when to use Agent mode; Groq Whisper listening and natural speaking; photo → text; redesigned Tasks with efficiency ring and stars; notes page; 3×2 widget; database v3.
- **20 Oct 2026 · build 1.3.0** – Chat dates and copy; AI personality for the on-screen assistant; memory every 4th message; trial keys; Efficiency 85/15 split; light/dark/follow-phone theme; notification targets; morning briefing note; automations by date or weekday.
- **21 Oct 2026 · build 1.4.0 / 1.4.1** – Tasks & Efficiency formula, Health of Day (profile targets, food/exercise/sleep/water log shared by chat and form), Reports (Efficiency / Health / Combined, week/month), companion fixes (no spam, dizziness); one Tasks & Health screen, 4 commitment stars, Add Health Entry sheet, report overview; drawer fix.
- **24 Oct 2026 · build 1.5.0** – First-run setup animation; new menu (Settings / Account / About); animations switch; backup now saves settings and history; Markdown tables; your own key removes trial keys, new providers (Claude, Grok, Perplexity, Mistral, Together); Health widget; new Health formula 0.35/0.25/0.20/0.20; AI food and exercise estimates.
- **24 Oct 2026 · build 1.6.0** – Reply-to-text, swipeable stats bar (quick add, music player), proactive messages, “I'm Feeling Distracted”, new task timing (repeating or due dates), AI note rewrite, Telegram bot (first version), music and call commands, smart-watch steps via Health Connect, floating assistant, database v8.
- **25 Oct 2026 · build 1.6.1** – Account = Vault page (credentials, philosophy, memory, health plan, Telegram, backup); navigation swipe rules; calmer pixel animations; reports as shareable picture; automation patience and audio output; music fixes.
- **25 Oct 2026 · build 1.7.0** – Modes button (Distracted, Music, Telegram AI, Telegram Notes); friend requests; shared AI chat and live shared notes through Telegram messages; “share to my Telegram”.
- **26 Oct 2026 · build 1.7.1** – App automations (later removed), calendar sync, timer + countdown, currency dropdowns, AI never saves notes by itself, smoother graphs, widget previews, offline messages finish when internet returns.
- **27 Oct 2026 · build 1.7.2** – Audio output for AI tasks, note widget with Markdown, drawer-swipe fixes, files and pictures open in-app, floating note improvements, model overrides for voice/listening/OCR.
- **27 Oct 2026 · build 1.7.3** – App-to-app sync through two Telegram bots (Bot-to-Bot Communication Mode): shared notes and shared AI chat that stay in sync.
- **28 Oct 2026 · build 1.7.4** – Mind (focus) tab with app usage, Dopamine vs Detox formula, manual entries, morning guard, Mind widget; overall score = (Tasks + Health + Mind) / 3; report picture with assistant and graphs; routines card removed; keyboard fix on the name screen; database v9.
- **29 Oct 2026 · build 1.7.5** – Share-picture dialog fixed; Mind simplified (one boxed graph with Expand/Contrast, Health-style entries, history calendar, “Detox status”); About sections collapse; Account order; Telegram card reduced to token + friend's bot; Modes live-connection list with Cancel; new chat “Telegram AI <name>”; notes with one editor at a time.
- **29 Oct 2026 · version 1.0.0 (release)** – Accept/Reject pop-ups for shared AI/notes, real name sent to friends, “Live” and “Shared” tags, already-shared friends greyed out; Samsung timer crash fixed; Mind widget preview picture, all widgets refresh on app open and every 15 minutes; Morning Brief on an exact alarm with catch-up and richer advice; automations retry on app open; backup extended (photos, Mind, confirmed days); About now has this guide.
- **29 Oct 2026 (later) · 1.0.0 update** – Mind tracks every app: unknown apps give no credit until you decide; useful apps (study, fitness, productivity) earn credit; per-app hourly graphs of usage and credits; “Useful” rating counts as learning time; backup also keeps per-app choices, paired friends and small settings files; this guide, change history and token system added in the app.
- **3 Nov 2026 · 1.0.0 update (credits)** – “APIs & models” is now **API & Subscription**: a big Credits button (tap → what each action costs + usage Log), Plans (Starter $1/200, Plus $3/600, Pro $5/1,800; open by default) and Top Up (Spark $1/150, Boost $2/400, Power $4/800; closed) that never stay open together, “Proceed for Payment” shows your login email to type on the Buy Me a Coffee page, credits are added within 24 hours. Google-only sign-in through Supabase (only name + email), the Groq key stays on the server, every call is metered from real tokens and shown in the Log; your own API keys cost 0 credits. “Fund the project” button removed from About. Fixes: automated notes no longer end with “would you like me to…”; tapping the assistant gives a real insight about your chat, data or the setting you are on (jokes only come on their own timer); full-screen pictures now cover the whole screen.
- **3 Nov 2026 (evening) · 1.0.0 update** – Photo → text, listening and natural spoken replies now work with credits, so a paying user never needs an API key; own keys moved into an optional “Advanced” fold.
- **5 Nov 2026 · 1.0.0 update** – Automations: a result is written once (into the waiting-for-internet note, no second note), one run per automation at a time, and automation runs can no longer create or change automations; new “Also create audio” switch on AI tasks (off = no sound); Credits button opens its own window with costs and the Log; credit requests that were too large for the AI’s per-minute allowance now retry smaller; clearer message when you have no credits.
- **5 Nov 2026 (later) · 1.0.0 update** – Sign in with Google inside the app (also on first run: fills your name and picks Sir / Ma’am); FREE plan (depends on API load) shown instead of “0 Credits”, Log only for paid; “Proceed to Pay” sits inside Plans and Top Up and shows the admin’s Telegram with the payment steps; shared Trial keys removed; your own keys are always used before credits; server keys can be stacked with automatic failover and paid-first priority. See CREDITS-SYSTEM.md.
- **5 Nov 2026 (evening) · 1.0.0 update** – The app is now called PRIVACY everywhere: package `com.privacy.app`, folder `Privacy`, classes `PrivacyApp` / `PrivacyTheme`. Installing it creates a NEW app next to the old one – use Export / Import to move your data.
- **6 Nov 2026 · 1.0.0 update** – Real one-colour Google “G” on the sign-in button; clearer messages (“Sign in with Google for FREE API credits, or add your own key in API & Subscription”); the FREE plan is described as “depends on API load” with no numbers; the server now reports a wrong server-side key as “AI service unavailable” instead of a raw provider error.
- **6 Nov 2026 (later) · 1.0.0 update** – Credits are whole numbers now: one task (however many AI calls it needed) is ONE line in the Log, and very small tasks cost nothing until they add up to a whole credit. The Credits button is a light card; Sign out moved to the top of the Credits window; cost details that were not for users were removed.
- **6 Nov 2026 (afternoon) · 1.0.0 update** – Startup hardened and a crash reporter added: if the app ever closes unexpectedly, the next start shows the error with a Copy button. API & Subscription now shows your active monthly plan and last purchase. Monthly subscribers get a daily encrypted cloud backup (your own backup password; only the latest copy is kept; nothing is sent when nothing changed). Pictures are no longer included in ANY backup or export.
- **7 Nov 2026 · 1.0.0 update** – Fixed the app closing right after importing a backup (settings were restored with the wrong number type; already-broken installs repair themselves on the next start). Cloud backup moved to Account → Export & backup (shows "only for monthly plan users" otherwise). A brand-new user now hears nothing – no morning brief, water/meal nudges or assistant chat messages – until they have actually used the app.
- **7 Nov 2026 (later) · 1.0.0 update** – The morning brief can no longer notify twice (only one run at a time, one notification a day; a later real brief after an offline first try is saved silently). Cloud backup: on/off switch under your subscription in API & Subscription; password, Back up now and Restore in Account under the backup card; monthly plan users only. Photo reading and photo analysis now fall back to the server when your own keys fail, and Gemini Flash is tried first.
- **7 Nov 2026 (evening) · 1.0.0 update** – The Export dialog no longer says "with photos / with pictures": backups are text only and the options now match what is really saved (tasks, health, Mind and focus history, notes, chats, routines and automations, vault, API keys; name, settings and Telegram friends always).

*From here on the version stays **1.0.0** and new changes are added to this timeline with their date and time.*

## Honest limits
- Credits: payment is on Buy Me a Coffee and credits are added by hand within 24 hours; Google Play may require its own billing for in-app credits.
- Not every build was compiled where it was written; fixes follow from your build output.
- Telegram sync needs both bots in Bot-to-Bot mode, both apps active in the background, and is limited by Telegram rate limits; the last edit wins.
- Food and calorie numbers are estimates. The app never diagnoses medical conditions.
- App usage needs “Usage access”; it only reads time per app and stays on the phone.

## Made by
Vishal Sharma · github.com/mrwhoim · x.com/v_shal_

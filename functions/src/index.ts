/**
 * Yolda İngilizce: Claude proxy.
 *
 * The app never holds the Claude API key. It calls this function with a
 * Firebase anonymous sign-in, and only phones listed in ALLOWED_UIDS get in.
 */
import { onCall, HttpsError } from "firebase-functions/v2/https";
import { defineSecret, defineString } from "firebase-functions/params";
import * as logger from "firebase-functions/logger";
import Anthropic from "@anthropic-ai/sdk";
import { betaZodOutputFormat } from "@anthropic-ai/sdk/helpers/beta/zod";
import { z } from "zod";

const ANTHROPIC_API_KEY = defineSecret("ANTHROPIC_API_KEY");
const ALLOWED_UIDS = defineString("ALLOWED_UIDS", {
  description: "Comma separated Firebase user ids that may use the tutor (shown in the app).",
});

const MODEL = "claude-opus-5-5";
const LEVELS = ["A1", "A2", "B1", "B2", "C1"] as const;

const WordBatch = z.object({
  words: z.array(
    z.object({
      w: z.string().describe("English word or short phrase"),
      tr: z.string().describe("Short Turkish meaning, 1-3 words"),
      ex: z.string().describe("Short natural example sentence"),
      lv: z.enum(LEVELS),
    }),
  ),
});

const GrammarBatch = z.object({
  items: z.array(
    z.object({
      s: z.string().describe("The sentence the learner hears"),
      ok: z.boolean().describe("true if the sentence is grammatically correct"),
      fix: z.string().describe("Corrected sentence; empty when ok is true"),
      why: z.string().describe("One short Turkish explanation of the error; empty when ok is true"),
    }),
  ),
});

const ChatTurn = z.object({
  fixed: z
    .string()
    .describe("The learner's last sentence rewritten as natural, correct English; empty if it was already fine"),
  note: z.string().describe("One short Turkish sentence explaining the main correction; empty when fixed is empty"),
  reply: z.string().describe("Your next spoken line in English: react briefly and ask one simple question"),
});

const SYSTEM =
  "You write content for a Turkish adult learning English by listening while driving. " +
  "Everything will be read aloud by text-to-speech, so avoid symbols, brackets, abbreviations " +
  "and anything that only makes sense on screen. Keep sentences short, natural and everyday.";

export const tutor = onCall(
  {
    region: "europe-west1",
    secrets: [ANTHROPIC_API_KEY],
    maxInstances: 2,
    timeoutSeconds: 120,
    memory: "512MiB",
  },
  async (request) => {
    const uid = request.auth?.uid;
    if (!uid) throw new HttpsError("unauthenticated", "Sign-in required.");
    const allowed = ALLOWED_UIDS.value()
      .split(",")
      .map((s) => s.trim())
      .filter(Boolean);
    if (!allowed.includes(uid)) {
      logger.warn("Rejected uid", { uid });
      throw new HttpsError("permission-denied", `This phone is not allowed yet. UID: ${uid}`);
    }

    const data = (request.data ?? {}) as Record<string, unknown>;
    const client = new Anthropic({ apiKey: ANTHROPIC_API_KEY.value() });

    switch (data.task) {
      case "words":
        return { json: JSON.stringify(await words(client, data)) };
      case "grammar":
        return { json: JSON.stringify(await grammar(client, data)) };
      case "chat":
        return { json: JSON.stringify(await chat(client, data)) };
      default:
        throw new HttpsError("invalid-argument", "Unknown task.");
    }
  },
);

function level(value: unknown): (typeof LEVELS)[number] {
  return LEVELS.find((l) => l === value) ?? "A1";
}

function count(value: unknown, max: number): number {
  const n = Number(value);
  return Number.isFinite(n) ? Math.max(1, Math.min(max, Math.round(n))) : 10;
}

function text(value: unknown, max = 200): string {
  return typeof value === "string" ? value.slice(0, max) : "";
}

async function words(client: Anthropic, data: Record<string, unknown>) {
  const lv = level(data.level);
  const n = count(data.count, 40);
  const exclude = Array.isArray(data.exclude)
    ? data.exclude.filter((w): w is string => typeof w === "string").slice(-1500)
    : [];
  const topic = text(data.topic, 100);
  const theme = topic
    ? `that are most useful for the theme "${topic}" (words, phrasal verbs or short set phrases), most useful first. `
    : `that belong to the 3000 most frequently used words of everyday English, most frequent first. `;
  const prompt =
    `List ${n} English words or common phrasal verbs at CEFR level ${lv} ` +
    theme +
    `Do not include any of these words the learner already has: ${exclude.join(", ")}.\n` +
    `For each give: w (the word), tr (its most common Turkish meaning, 1 to 3 words), ` +
    `ex (an example sentence of at most 10 words at level ${lv}, ` +
    `${topic ? `about ${topic}` : "useful in daily or office life"}), ` +
    `lv ("${lv}").`;
  return ask(client, prompt, WordBatch);
}

async function grammar(client: Anthropic, data: Record<string, unknown>) {
  const lv = level(data.level);
  const n = count(data.count, 20);
  const topic = text(data.topic);
  const topicTr = text(data.topicTr);
  const prompt =
    `Write ${n} short spoken English sentences that test the grammar topic "${topic}" ` +
    `(Turkish: ${topicTr}) at CEFR level ${lv}. The learner hears one sentence and says whether it is ` +
    `correct. Make about half of them correct and half contain exactly one typical mistake a Turkish ` +
    `speaker makes on this topic. Sentences at most 12 words, about work, commuting, family and daily life. ` +
    `For each give: s, ok, fix (the corrected sentence, empty if ok), ` +
    `why (one short Turkish sentence explaining the mistake, empty if ok).`;
  return ask(client, prompt, GrammarBatch);
}

async function chat(client: Anthropic, data: Record<string, unknown>) {
  const lv = level(data.level);
  const topic = text(data.topic, 100);
  const history = Array.isArray(data.history)
    ? data.history
        .slice(-16)
        .map((t) => (t ?? {}) as Record<string, unknown>)
        .map((t) => ({ who: t.who === "learner" ? "Learner" : "Tutor", text: text(t.text, 400) }))
        .filter((t) => t.text)
    : [];
  const transcript = history.length
    ? history.map((t) => `${t.who}: ${t.text}`).join("\n")
    : "(The conversation has not started yet.)";
  const lastIsLearner = history.length > 0 && history[history.length - 1].who === "Learner";
  const prompt =
    `You are a friendly English conversation partner for a Turkish adult at CEFR level ${lv}. ` +
    `The learner is driving, so everything is spoken: keep it light and easy, never ask them to look ` +
    `at, read or write anything, and avoid stressful or emotional subjects. ` +
    (topic ? `Prefer the theme: ${topic}. ` : `Talk about everyday life: work, commuting, family, plans, hobbies. `) +
    `Use simple words for level ${lv} and at most two short sentences per reply, ending with one easy question.\n\n` +
    `Conversation so far:\n${transcript}\n\n` +
    (lastIsLearner
      ? `The learner's last line comes from speech recognition, so ignore missing punctuation, capital letters ` +
        `and obvious recognition mistakes. If it has a real grammar or word choice mistake, put the natural ` +
        `correct version in "fixed" and explain the main point in one short Turkish sentence in "note". ` +
        `If it is fine or only slightly unnatural, leave both empty. Then write your reply.`
      : `Start the conversation: greet the learner in one short sentence and ask one easy question. ` +
        `Leave "fixed" and "note" empty.`);
  return ask(client, prompt, ChatTurn);
}

async function ask<T extends z.ZodType>(client: Anthropic, prompt: string, schema: T): Promise<z.infer<T>> {
  try {
    const response = await client.beta.messages.parse({
      model: MODEL,
      max_tokens: 16000,
      betas: ["server-side-fallback-2026-07-01"],
      fallbacks: "default",
      system: SYSTEM,
      output_config: { effort: "low", format: betaZodOutputFormat(schema) },
      messages: [{ role: "user", content: prompt }],
    });
    if (response.stop_reason === "refusal") {
      throw new HttpsError("failed-precondition", "The model declined this request.");
    }
    if (response.parsed_output == null) {
      throw new HttpsError("internal", `No structured output (stop_reason: ${response.stop_reason}).`);
    }
    return response.parsed_output as z.infer<T>;
  } catch (err) {
    if (err instanceof HttpsError) throw err;
    if (err instanceof Anthropic.RateLimitError) {
      throw new HttpsError("resource-exhausted", "Claude rate limit, try again soon.");
    }
    if (err instanceof Anthropic.APIError) {
      logger.error("Claude API error", { status: err.status, message: err.message });
      throw new HttpsError("unavailable", `Claude API error ${err.status ?? ""}`.trim());
    }
    logger.error("Unexpected error", err);
    throw new HttpsError("internal", "Unexpected error.");
  }
}

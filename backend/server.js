// Tiny proxy: the Android app calls THIS server, and only this server knows the Claude API key.
// Requires Node 18+ (built-in fetch). No npm packages needed.
//
//   export ANTHROPIC_API_KEY=...        (PowerShell:  $env:ANTHROPIC_API_KEY="...")
//   node server.js
//
// Request :  POST /ask   {"question": "Where can I find a doctor?"}
// Response:  {"answer": "..."}

const http = require("http");

const API_KEY = process.env.ANTHROPIC_API_KEY;
const MODEL = process.env.CLAUDE_MODEL || "claude-haiku-5-5";
const PORT = Number(process.env.PORT) || 8787;
const MAX_QUESTION_CHARS = 500;
const MAX_REQUESTS_PER_HOUR = 30; // per IP - protects your bill

const SYSTEM_PROMPT =
  "You are Semmozhi, a friendly travel companion for tourists in India. " +
  "Answer in 2 to 4 short, plain sentences that are easy to read aloud. " +
  "Focus on travel safety, transport, money, language, food, hotels and local help. " +
  "If the user may be in danger or needs urgent help, tell them to call 112 first. " +
  "If the question is not about travel, politely steer back to travel. " +
  "Never ask for passwords, card numbers or other sensitive data.";

if (!API_KEY) {
  console.error("Set the ANTHROPIC_API_KEY environment variable first.");
  process.exit(1);
}

const hits = new Map(); // ip -> { count, resetAt }

function allowed(ip) {
  const now = Date.now();
  const entry = hits.get(ip);
  if (!entry || now > entry.resetAt) {
    hits.set(ip, { count: 1, resetAt: now + 3600_000 });
    return true;
  }
  entry.count += 1;
  return entry.count <= MAX_REQUESTS_PER_HOUR;
}

function send(res, status, obj) {
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(JSON.stringify(obj));
}

function readBody(req, limit = 4096) {
  return new Promise((resolve, reject) => {
    let data = "";
    req.on("data", (chunk) => {
      data += chunk;
      if (data.length > limit) {
        reject(new Error("too large"));
        req.destroy();
      }
    });
    req.on("end", () => resolve(data));
    req.on("error", reject);
  });
}

const server = http.createServer(async (req, res) => {
  if (req.method !== "POST" || req.url !== "/ask") {
    return send(res, 404, { error: "not found" });
  }

  const ip = req.socket.remoteAddress || "unknown";
  if (!allowed(ip)) return send(res, 429, { error: "too many requests" });

  let question;
  try {
    question = String(JSON.parse(await readBody(req)).question || "").trim();
  } catch {
    return send(res, 400, { error: "bad request" });
  }
  if (!question) return send(res, 400, { error: "question is required" });
  question = question.slice(0, MAX_QUESTION_CHARS);

  try {
    const upstream = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: {
        "x-api-key": API_KEY,
        "anthropic-version": "2023-06-01",
        "content-type": "application/json",
      },
      body: JSON.stringify({
        model: MODEL,
        max_tokens: 300,
        system: SYSTEM_PROMPT,
        messages: [{ role: "user", content: question }],
      }),
    });

    if (!upstream.ok) {
      console.error("Claude API error:", upstream.status);
      return send(res, 502, { error: "upstream error" });
    }

    const data = await upstream.json();
    const answer = (data.content || [])
      .filter((block) => block.type === "text")
      .map((block) => block.text)
      .join("\n")
      .trim();

    return send(res, 200, { answer });
  } catch (err) {
    console.error("Request failed:", err.message);
    return send(res, 502, { error: "upstream unreachable" });
  }
});

server.listen(PORT, () => console.log(`Semmozhi proxy listening on port ${PORT}`));

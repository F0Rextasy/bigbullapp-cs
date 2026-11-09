// Calistir: node tools/telegram-bot/test.mjs  (ag ve Cloudflare hesabi gerekmez; Telegram API taklit edilir)
import assert from "node:assert/strict";
import worker from "./worker.js";

const TOKEN = "123:TEST";
const ENV = { TELEGRAM_BOT_TOKEN: TOKEN, SECRET_TOKEN: "s3" };
const BOT = { id: 999, is_bot: true, username: "BBTestBot" };

let calls = [];
let members = {};      // "chat:user" -> getChatMember sonucu
let chatPerms = { can_send_messages: true, can_send_photos: true, can_invite_users: true };
let override = {};     // method -> (payload) => yanit (hata simulasyonu)
let nextMsg = 1000;

globalThis.fetch = async (url, init) => {
  const method = url.split("/").pop();
  const payload = JSON.parse(init.body);
  calls.push({ method, ...payload });
  if (override[method]) {
    const r = override[method](payload);
    if (r) return { json: async () => r };
  }
  let result = true;
  if (method === "getMe") result = BOT;
  if (method === "getChat") result = { id: payload.chat_id, permissions: chatPerms };
  if (method === "sendMessage") result = { message_id: ++nextMsg };
  if (method === "getChatMember") result = members[`${payload.chat_id}:${payload.user_id}`] || { status: "member" };
  return { json: async () => ({ ok: true, result }) };
};

async function send(update, env = ENV) {
  const pending = [];
  const res = await worker.fetch(
    new Request("https://w/", {
      method: "POST",
      headers: { "X-Telegram-Bot-Api-Secret-Token": env.SECRET_TOKEN },
      body: JSON.stringify(update)
    }),
    env,
    { waitUntil: (p) => pending.push(p) }
  );
  await Promise.all(pending);
  return res;
}

const of = (m) => calls.filter((c) => c.method === m);
const reset = () => { calls = []; override = {}; };

let seq = 0;
const ids = () => { seq += 1; return { chat: -1009000 - seq, user: 5000 + seq, admin: 7000 + seq }; };
const joinUpdate = (chat, user, actor, old = "left") => ({
  chat_member: {
    chat: { id: chat, title: "G", type: "supergroup" },
    from: actor,
    old_chat_member: { status: old, user },
    new_chat_member: { status: "member", user }
  }
});
const msg = (chat, from, text, extra = {}) => ({
  message: { message_id: ++nextMsg, chat: { id: chat, type: "supergroup" }, from, text, ...extra }
});

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

test("webhook: gizli anahtar yoksa 403, /health 200", async () => {
  const r = await worker.fetch(new Request("https://w/", { method: "POST", body: "{}" }), ENV, { waitUntil() {} });
  assert.equal(r.status, 403);
  const h = await worker.fetch(new Request("https://w/health"), ENV, {});
  assert.equal((await h.json()).ok, true);
});

test("katilma isteği onaylanir", async () => {
  reset();
  const { chat, user } = ids();
  await send({ chat_join_request: { chat: { id: chat }, from: { id: user } } });
  assert.equal(of("approveChatJoinRequest")[0].user_id, user);
});

test("yeni uye sessize alinir, dogrulama mesaji ve imzali link olusur", async () => {
  reset();
  const { chat, user } = ids();
  await send(joinUpdate(chat, { id: user, first_name: "Ali" }, { id: user }));
  assert.equal(of("restrictChatMember")[0].permissions.can_send_messages, false);
  const url = of("editMessageReplyMarkup")[0].reply_markup.inline_keyboard[0][0].url;
  assert.match(url, /^https:\/\/t\.me\/BBTestBot\?start=v_-\d+_\d+_\d+_[0-9a-f]{16}$/);
  assert.ok(url.split("start=")[1].length <= 64);
});

test("katilma isteğini botun kendisi onayladiysa dogrulama atlanmaz (eski hata)", async () => {
  reset();
  const { chat, user } = ids();
  members[`${chat}:${BOT.id}`] = { status: "administrator", can_restrict_members: true };
  await send(joinUpdate(chat, { id: user, first_name: "Veli" }, BOT));
  assert.equal(of("restrictChatMember").length, 1);
});

test("yonetici ekleyen uye dogrulamaya girmez; restricted+uye degil -> katilim da yakalanir", async () => {
  reset();
  const { chat, user, admin } = ids();
  members[`${chat}:${admin}`] = { status: "administrator" };
  await send(joinUpdate(chat, { id: user, first_name: "A" }, { id: admin }));
  assert.equal(of("restrictChatMember").length, 0);

  reset();
  const u2 = user + 500;
  await send({
    chat_member: {
      chat: { id: chat, title: "G" }, from: { id: u2 },
      old_chat_member: { status: "restricted", is_member: false, user: { id: u2 } },
      new_chat_member: { status: "restricted", is_member: true, user: { id: u2, first_name: "B" } }
    }
  });
  assert.equal(of("restrictChatMember").length, 1);
});

test("admin olmayan kisi bot eklerse bot banlanir; admin eklerse banlanmaz", async () => {
  reset();
  const { chat, user, admin } = ids();
  await send(joinUpdate(chat, { id: 4242, is_bot: true, username: "Spam_bot" }, { id: user }));
  assert.equal(of("banChatMember")[0].user_id, 4242);
  reset();
  members[`${chat}:${admin}`] = { status: "administrator" };
  await send(joinUpdate(chat, { id: 4243, is_bot: true, username: "Ok_bot" }, { id: admin }));
  assert.equal(of("banChatMember").length, 0);
});

test("giris servis mesaji silinir, ayrica dogrulama BASLATMAZ (cift islem yok)", async () => {
  reset();
  const { chat, user } = ids();
  await send(msg(chat, { id: user }, undefined, { new_chat_members: [{ id: user }] }));
  assert.equal(of("deleteMessage").length, 1);
  assert.equal(of("restrictChatMember").length + of("sendMessage").length, 0);
});

async function startLink(chat, user) {
  reset();
  await send(joinUpdate(chat, { id: user, first_name: "Z" }, { id: user }));
  const url = of("editMessageReplyMarkup")[0].reply_markup.inline_keyboard[0][0].url;
  const msgId = of("editMessageReplyMarkup")[0].message_id;
  return { payload: url.split("start=")[1], msgId };
}
const startMsg = (user, payload) => ({
  message: { message_id: ++nextMsg, chat: { id: user, type: "private" }, from: { id: user }, text: `/start ${payload}` }
});

test("/start dogrulama: mesaj silinir, grup varsayilan izinleri verilir, DM basari", async () => {
  const { chat, user } = ids();
  const { payload, msgId } = await startLink(chat, user);
  members[`${chat}:${user}`] = { status: "restricted", is_member: true, can_send_messages: false };
  calls = [];
  await send(startMsg(user, payload));
  assert.equal(of("deleteMessage")[0].message_id, msgId);
  assert.deepEqual(of("restrictChatMember")[0].permissions, chatPerms);
  assert.match(of("sendMessage").at(-1).text, /Doğrulama Başarılı/);
});

test("/start tekrar kullanim (zaten dogrulanmis) reddedilir", async () => {
  const { chat, user } = ids();
  const { payload } = await startLink(chat, user);
  members[`${chat}:${user}`] = { status: "member" };
  calls = [];
  await send(startMsg(user, payload));
  assert.equal(of("restrictChatMember").length, 0);
  assert.match(of("sendMessage")[0].text, /daha önce kullanılmış/);
});

test("/start mesaj zaten silinmisse reddedilir", async () => {
  const { chat, user } = ids();
  const { payload } = await startLink(chat, user);
  members[`${chat}:${user}`] = { status: "restricted", is_member: true, can_send_messages: false };
  calls = [];
  override.deleteMessage = () => ({ ok: false, error_code: 400, description: "message to delete not found" });
  await send(startMsg(user, payload));
  assert.equal(of("restrictChatMember").length, 0);
});

test("/start baska kullanici veya sahte imza reddedilir", async () => {
  const { chat, user } = ids();
  const { payload } = await startLink(chat, user);
  members[`${chat}:${user}`] = { status: "restricted", is_member: true, can_send_messages: false };
  calls = [];
  await send(startMsg(user + 1, payload));
  await send(startMsg(user, payload.replace(/.$/, (c) => (c === "0" ? "1" : "0"))));
  assert.equal(of("restrictChatMember").length, 0);
  assert.equal(of("sendMessage").length, 2);
});

test("/start: kisitli ama yazabilen (admin unmute) uye tekrar islenmez", async () => {
  const { chat, user } = ids();
  const { payload } = await startLink(chat, user);
  members[`${chat}:${user}`] = { status: "restricted", is_member: true, can_send_messages: true };
  calls = [];
  await send(startMsg(user, payload));
  assert.equal(of("restrictChatMember").length, 0);
});

test("/start payload'siz karsilama mesaji", async () => {
  reset();
  await send(startMsg(8888, ""));
  assert.match(of("sendMessage")[0].text, /Güvenlik/);
});

async function adminCmd(text, rights, { chat, user }, replyExtra = {}) {
  const admin = 70000 + ++seq; // yonetici onbellegi 60 sn: her cagrida taze kimlik
  members[`${chat}:${admin}`] = { status: "administrator", ...rights };
  reset();
  await send(msg(chat, { id: admin }, text, {
    reply_to_message: { message_id: 1, from: { id: user, first_name: "T" }, ...replyExtra }
  }));
}

test("/mute: 10s -> en az 30 sn, 1h dogru, gecersiz -> hata ve susturma yok", async () => {
  const i = ids();
  const now = Math.floor(Date.now() / 1000);
  await adminCmd("/mute 10s", { can_restrict_members: true }, i);
  const d = of("restrictChatMember")[0].until_date - now;
  assert.ok(d >= 30 && d <= 32, `until_date farki ${d}`);
  await adminCmd("/mute 1h", { can_restrict_members: true }, i);
  assert.ok(Math.abs(of("restrictChatMember")[0].until_date - now - 3600) <= 2);
  await adminCmd("/mute abc", { can_restrict_members: true }, i);
  assert.equal(of("restrictChatMember").length, 0);
  assert.match(of("sendMessage")[0].text, /Geçersiz süre/);
  await adminCmd("/mute 999d", { can_restrict_members: true }, i);
  assert.equal(of("restrictChatMember").length, 0);
  await adminCmd("/mute", { can_restrict_members: true }, i);
  assert.equal(of("restrictChatMember")[0].until_date, undefined);
});

test("yetki: yalniz pin hakki olan admin /ban atamaz; restrict hakki olan atar", async () => {
  const i = ids();
  await adminCmd("/ban", { can_pin_messages: true }, i);
  assert.equal(of("banChatMember").length, 0);
  await adminCmd("/ban", { can_restrict_members: true }, i);
  assert.equal(of("banChatMember").length, 1);
  await adminCmd("/sil", { can_restrict_members: true }, i);
  assert.equal(of("deleteMessage").length, 0);
});

test("yonetici uzerinde moderasyon uygulanmaz", async () => {
  const i = ids();
  members[`${i.chat}:${i.user}`] = { status: "administrator" };
  await adminCmd("/ban", { can_restrict_members: true }, i);
  assert.equal(of("banChatMember").length, 0);
});

test("anonim yonetici komut kullanabilir ve linki silinmez", async () => {
  const { chat, user } = ids();
  reset();
  await send(msg(chat, { id: 1087968824 }, "t.me/baskasi", { sender_chat: { id: chat } }));
  assert.equal(of("deleteMessage").length, 0);
  reset();
  await send(msg(chat, { id: 1087968824 }, "/ban", {
    sender_chat: { id: chat },
    reply_to_message: { message_id: 1, from: { id: user, first_name: "T" } }
  }));
  assert.equal(of("banChatMember")[0].user_id, user);
});

test("kanal adina yazilmis mesaja /ban -> banChatSenderChat", async () => {
  const i = ids();
  await adminCmd("/ban", { can_restrict_members: true }, i, { sender_chat: { id: -1005, title: "Reklam" } });
  assert.equal(of("banChatSenderChat")[0].sender_chat_id, -1005);
  assert.equal(of("banChatMember").length, 0);
});

test("flood: 7 mesaj susturur; 8 parcali album susturmaz", async () => {
  const { chat, user } = ids();
  reset();
  for (let n = 0; n < 7; n++) await send(msg(chat, { id: user, first_name: "F" }, `m${n}`));
  assert.equal(of("restrictChatMember").length, 1);

  const b = ids();
  reset();
  for (let n = 0; n < 8; n++) {
    await send(msg(b.chat, { id: b.user, first_name: "F" }, undefined, { media_group_id: "alb1", photo: [{}] }));
  }
  assert.equal(of("restrictChatMember").length, 0);
});

test("reklam: bigbull_app serbest, bigbull_free_vip ve davet linki silinir + uyari", async () => {
  const { chat, user } = ids();
  reset();
  await send(msg(chat, { id: user, first_name: "U" }, "bak https://t.me/BigBull_App/12"));
  assert.equal(of("deleteMessage").length, 0);
  await send(msg(chat, { id: user, first_name: "U" }, "t.me/bigbull_free_vip"));
  assert.equal(of("deleteMessage").length, 1);
  assert.match(of("sendMessage").at(-1).text, /1\/3/);
  await send(msg(chat, { id: user, first_name: "U" }, "t.me/+AbCdEf"));
  assert.equal(of("deleteMessage").length, 2);
  assert.match(of("sendMessage").at(-1).text, /2\/3/);
});

test("3 uyari -> ban (bellek), KV varsa KV tek kaynak", async () => {
  const { chat, user } = ids();
  reset();
  for (let n = 0; n < 3; n++) await send(msg(chat, { id: user, first_name: "U" }, "t.me/spam1"));
  assert.equal(of("banChatMember").length, 1);

  const b = ids();
  const kv = new Map();
  const env = { ...ENV, BOT_KV: { get: async (k) => kv.get(k) ?? null, put: async (k, v) => kv.set(k, v), delete: async (k) => kv.delete(k) } };
  reset();
  await send(msg(b.chat, { id: b.user, first_name: "U" }, "t.me/spam1"), env);
  assert.equal(kv.get(`warn:${b.chat}:${b.user}`), "1");
  await send(msg(b.chat, { id: b.user, first_name: "U" }, "t.me/spam1"), env);
  await send(msg(b.chat, { id: b.user, first_name: "U" }, "t.me/spam1"), env);
  assert.equal(of("banChatMember").length, 1);
  assert.equal(kv.has(`warn:${b.chat}:${b.user}`), false);
});

test("tg(): ag hatasi akisi cokertmez; 429 kisa beklemeyle bir kez tekrarlanir", async () => {
  const { chat, user } = ids();
  reset();
  override.restrictChatMember = () => { throw new Error("ag koptu"); };
  const r = await send(joinUpdate(chat, { id: user, first_name: "X" }, { id: user }));
  assert.equal(r.status, 200);
  assert.equal(of("sendMessage").length, 1); // restrict patlasa da akis surdu

  const b = ids();
  reset();
  let hit = 0;
  override.sendMessage = () => (hit++ === 0 ? { ok: false, error_code: 429, parameters: { retry_after: 0 } } : null);
  await send(msg(b.chat, { id: b.user }, "/id"));
  assert.equal(of("sendMessage").length, 2);
});

let failed = 0;
for (const [name, fn] of tests) {
  try {
    await fn();
    console.log(`  ok   ${name}`);
  } catch (err) {
    failed++;
    console.log(`  FAIL ${name}\n       ${err.message}`);
  }
}
console.log(failed ? `\n${failed}/${tests.length} test basarisiz` : `\n${tests.length}/${tests.length} test gecti`);
process.exit(failed ? 1 : 0);

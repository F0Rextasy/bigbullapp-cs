/**
 * BigBullApp Telegram Gatekeeper & Moderasyon Botu
 * Cloudflare Workers - %100 Ucretsiz, Serverless
 *
 * Dogrulama Akisi:
 * 1. Yeni uye gruba girer -> Aninda sessize alinir (mesaj yazamaz).
 * 2. Grupta "🤖 Doğrulamak İçin Tıkla" butonu cikar (bota yonlendirir).
 * 3. Kullanici butona basip bota "Başlat (Start)" dedigi anda:
 *    - Gruptaki tum mesaj ve medya izinleri acilir.
 *    - Gruptaki dogrulama mesaji otomatik silinir.
 *    - Kullaniciya ozelden "Doğrulama başarılı!" bilgisi verilir.
 */

const recentJoins = new Map();     // Cift tetiklemeyi onler
const pendingCaptchas = new Map(); // Suresi dolanlari temizlemek icin
const warnings = new Map();        // Uyari sayaci (3/3 = ban)
const floodTracker = new Map();    // Anti-flood sayaci
const adminCache = new Map();      // Admin onbellegi
let cachedBotUsername = null;      // getMe ile otomatik ogrenilir

const CAPTCHA_TIMEOUT_MS = 15 * 60 * 1000; // 15 dakika
const FLOOD_LIMIT = 6;                     // 6 saniyede 6 mesaj
const FLOOD_WINDOW_MS = 6000;
const FLOOD_MUTE_SEC = 3600;               // 1 saat

// Tam uye izinleri (Fotograf, dosya, video dahil)
const FULL_PERMISSIONS = {
  can_send_messages: true,
  can_send_audios: true,
  can_send_documents: true,
  can_send_photos: true,
  can_send_videos: true,
  can_send_video_notes: true,
  can_send_voice_notes: true,
  can_send_polls: true,
  can_send_other_messages: true,
  can_add_web_page_previews: true
};

const MUTE_PERMISSIONS = {
  can_send_messages: false,
  can_send_audios: false,
  can_send_documents: false,
  can_send_photos: false,
  can_send_videos: false,
  can_send_video_notes: false,
  can_send_voice_notes: false,
  can_send_polls: false,
  can_send_other_messages: false,
  can_add_web_page_previews: false
};

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/health") {
      return Response.json({ ok: Boolean(env.TELEGRAM_BOT_TOKEN), status: "active" });
    }

    if (request.method !== "POST") {
      return new Response("BigBullApp Telegram Bot Aktif", { status: 200 });
    }

    const token = env.TELEGRAM_BOT_TOKEN;
    if (!token) {
      return new Response("Missing token", { status: 500 });
    }

    const expectedSecret = env.SECRET_TOKEN || (await signHex(token, "webhook-secret", 32));
    const headerToken = request.headers.get("X-Telegram-Bot-Api-Secret-Token");
    if (headerToken !== expectedSecret) {
      return new Response("Unauthorized", { status: 403 });
    }

    try {
      const update = await request.json();
      ctx.waitUntil(handleUpdate(update, env));
      return new Response("OK", { status: 200 });
    } catch (err) {
      console.error("Webhook hatasi:", err);
      return new Response("OK", { status: 200 });
    }
  }
};

async function handleUpdate(update, env) {
  const token = env.TELEGRAM_BOT_TOKEN;
  if (!token) return;

  await cleanupExpiredCaptchas(token);

  if (update.chat_join_request) {
    await tg(token, "approveChatJoinRequest", {
      chat_id: update.chat_join_request.chat.id,
      user_id: update.chat_join_request.from.id
    });
    return;
  }

  if (update.chat_member) {
    await handleChatMemberUpdate(update.chat_member, token);
    return;
  }

  if (update.message) {
    await handleMessage(update.message, token, env);
  }
}

async function getBotUsername(token) {
  if (cachedBotUsername) return cachedBotUsername;
  const res = await tg(token, "getMe", {});
  if (res?.ok && res.result?.username) {
    cachedBotUsername = res.result.username;
  }
  return cachedBotUsername;
}

async function cleanupExpiredCaptchas(token) {
  const now = Date.now();
  for (const [key, item] of pendingCaptchas.entries()) {
    if (now >= item.expiresAt) {
      pendingCaptchas.delete(key);
      await kickUser(token, item.chatId, item.userId);
      if (item.msgId) {
        await tg(token, "deleteMessage", { chat_id: item.chatId, message_id: item.msgId });
      }
    }
  }
}

async function handleChatMemberUpdate(cm, token) {
  const oldStatus = cm.old_chat_member?.status;
  const newStatus = cm.new_chat_member?.status;
  const user = cm.new_chat_member?.user;
  const actor = cm.from;

  if (!user) return;

  const wasOutside = oldStatus === "left" || oldStatus === "kicked";
  const isNowMember = newStatus === "member" || (newStatus === "restricted" && cm.new_chat_member?.is_member);

  if (wasOutside && isNowMember) {
    await processNewMember(cm.chat, user, actor, null, token);
  }
}

/**
 * Gruba yeni uye girdiginde: sessize alir ve bota /start linki verir
 */
async function processNewMember(chat, member, actor, threadId, token) {
  const key = `${chat.id}:${member.id}`;
  const now = Date.now();

  if (recentJoins.has(key) && now - recentJoins.get(key) < 10000) {
    return;
  }
  recentJoins.set(key, now);

  const botUsername = await getBotUsername(token);
  if (member.is_bot && member.username === botUsername) {
    return;
  }

  const actorIsAdmin = actor && actor.id !== member.id
    ? await isUserAdmin(token, chat.id, actor.id)
    : false;

  // Yabanci bot eklendiyse ve ekleyen admin degilse banla
  if (member.is_bot) {
    if (!actorIsAdmin) {
      await tg(token, "banChatMember", { chat_id: chat.id, user_id: member.id });
    }
    return;
  }

  if (actorIsAdmin) return;

  // 1. Yeni uyeyi hemen sessize al
  await tg(token, "restrictChatMember", {
    chat_id: chat.id,
    user_id: member.id,
    permissions: MUTE_PERMISSIONS
  });

  // 2. Bota yonlendiren dogrulama mesajini gonder
  const mention = `<a href="tg://user?id=${member.id}">${escapeHtml(member.first_name || "Üye")}</a>`;
  const text =
    `👋 Merhaba ${mention}, <b>${escapeHtml(chat.title || "BigBullApp")}</b> topluluğuna hoş geldin!\n\n` +
    `🔒 <b>Gruba mesaj yazabilmek için doğrulama yapmalısın:</b>\n` +
    `Aşağıdaki butona tıklayıp açılan botta <b>Başlat (Start)</b> tuşuna basman yeterlidir.`;

  // Deep-link: https://t.me/<BotUsername>?start=v_<chatId>_<userId>_<msgId>_<ts36>_<sig>
  const ts36 = Math.floor(now / 1000).toString(36);
  const sig0 = await signHex(token, `${chat.id}:${member.id}:0:${ts36}`, 8);
  const baseStartUrl = `https://t.me/${botUsername}?start=v_${chat.id}_${member.id}`;

  const params = {
    chat_id: chat.id,
    text,
    parse_mode: "HTML",
    reply_markup: {
      inline_keyboard: [
        [{ text: "🤖 Doğrulamak İçin Tıkla (Start)", url: `${baseStartUrl}_0_${ts36}_${sig0}` }]
      ]
    }
  };

  const effectiveThreadId = threadId || (String(chat.id) === "-1003994138025" ? 6 : null);
  if (effectiveThreadId) {
    params.message_thread_id = effectiveThreadId;
  }

  const res = await tg(token, "sendMessage", params);
  if (res?.ok && res.result?.message_id) {
    const msgId = res.result.message_id;
    const sig = await signHex(token, `${chat.id}:${member.id}:${msgId}:${ts36}`, 8);
    pendingCaptchas.set(key, {
      chatId: chat.id,
      userId: member.id,
      msgId,
      expiresAt: now + CAPTCHA_TIMEOUT_MS
    });
    await tg(token, "editMessageReplyMarkup", {
      chat_id: chat.id,
      message_id: msgId,
      reply_markup: {
        inline_keyboard: [
          [{ text: "🤖 Doğrulamak İçin Tıkla (Start)", url: `${baseStartUrl}_${msgId}_${ts36}_${sig}` }]
        ]
      }
    });
  }
}

/**
 * Kullanici bota ozelden /start yazdiginda (Dogrulama + Karsilama)
 */
async function handlePrivateCommand(message, token) {
  const text = (message.text || "").trim();
  const userId = message.from.id;
  const privateChatId = message.chat.id;

  if (!text.startsWith("/start")) return;

  const parts = text.split(/\s+/);
  const payload = parts[1] || "";

  // Dogrulama parametresi varsa: v_<chatId>_<userId>_<msgId>_<ts36>_<sig>
  if (payload.startsWith("v_")) {
    const [, targetChatId, targetUserStr, msgIdStr, ts36, sig] = payload.split("_");
    const targetUserId = parseInt(targetUserStr, 10);
    const embeddedMsgId = msgIdStr ? parseInt(msgIdStr, 10) : 0;
    const tsSec = ts36 ? parseInt(ts36, 36) : 0;
    const ageMs = Date.now() - tsSec * 1000;
    const expectedSig = ts36
      ? await signHex(token, `${targetChatId}:${targetUserId}:${embeddedMsgId}:${ts36}`, 8)
      : "";

    // Baska bir kullanici linke tikladiysa, suresi dolduysa veya imza gecersizse reddet
    if (
      userId !== targetUserId ||
      !sig ||
      sig !== expectedSig ||
      !Number.isFinite(ageMs) ||
      ageMs < -60000 ||
      ageMs > CAPTCHA_TIMEOUT_MS
    ) {
      await tg(token, "sendMessage", {
        chat_id: privateChatId,
        text: "⚠️ Geçersiz, süresi dolmuş veya size ait olmayan doğrulama bağlantısı!",
        parse_mode: "HTML"
      });
      return;
    }

    // 1. Gruptaki dogrulama mesajini sil (daha once silindiyse tekrar kullanimi reddet)
    const key = `${targetChatId}:${targetUserId}`;
    const pending = pendingCaptchas.get(key);
    pendingCaptchas.delete(key);
    const msgIdToDelete = embeddedMsgId || pending?.msgId;
    const delRes = msgIdToDelete
      ? await tg(token, "deleteMessage", {
          chat_id: targetChatId,
          message_id: msgIdToDelete
        })
      : null;
    if (!msgIdToDelete || (!delRes?.ok && !pending)) {
      await tg(token, "sendMessage", {
        chat_id: privateChatId,
        text: "⚠️ Bu doğrulama bağlantısı daha önce kullanılmış!",
        parse_mode: "HTML"
      });
      return;
    }

    // 2. Gruptaki tum mesaj ve medya izinlerini ac
    await tg(token, "restrictChatMember", {
      chat_id: targetChatId,
      user_id: targetUserId,
      permissions: FULL_PERMISSIONS
    });

    // 3. Kullaniciya ozelden basari mesaji gonder
    await tg(token, "sendMessage", {
      chat_id: privateChatId,
      text:
        `✅ <b>Doğrulama Başarılı!</b>\n\n` +
        `Artık <b>BigBullApp</b> topluluk grubunda mesaj ve görsel paylaşabilirsiniz.`,
      parse_mode: "HTML",
      reply_markup: {
        inline_keyboard: [
          [
            { text: "💬 Gruba Dön", url: "https://t.me/BigBull_App" },
            { text: "🌐 Web Sitemiz", url: "https://bigbullapp.com" }
          ]
        ]
      }
    });
    return;
  }

  // Normal /start mesaji
  const welcome =
    `🤖 <b>BigBullApp Güvenlik & Moderasyon Botu</b>\n\n` +
    `Topluluğumuza hoş geldiniz! Bu bot BigBullApp grubunda üye doğrulama ve grup güvenliğini sağlar.\n\n` +
    `🌐 <b>Web:</b> https://bigbullapp.com\n` +
    `🐙 <b>GitHub:</b> https://github.com/F0Rextasy`;

  await tg(token, "sendMessage", {
    chat_id: privateChatId,
    text: welcome,
    parse_mode: "HTML",
    reply_markup: {
      inline_keyboard: [
        [
          { text: "💬 Topluluk Grubuna Git", url: "https://t.me/BigBull_App" },
          { text: "🌐 Web Sitemiz", url: "https://bigbullapp.com" }
        ]
      ]
    },
    disable_web_page_preview: true
  });
}

/**
 * Grup mesajlari, servis mesajlari ve moderasyon komutlari
 */
async function handleMessage(message, token, env) {
  const chat = message.chat;
  const sender = message.from;

  if (chat.type === "private") {
    if (message.text) await handlePrivateCommand(message, token);
    return;
  }

  if (message.new_chat_members && message.new_chat_members.length > 0) {
    await tg(token, "deleteMessage", { chat_id: chat.id, message_id: message.message_id });
    for (const member of message.new_chat_members) {
      await processNewMember(chat, member, sender, message.message_thread_id, token);
    }
    return;
  }

  if (message.left_chat_member) {
    await tg(token, "deleteMessage", { chat_id: chat.id, message_id: message.message_id });
    return;
  }

  if (!sender) return;

  const key = `${chat.id}:${sender.id}`;

  if (pendingCaptchas.has(key)) {
    await tg(token, "deleteMessage", { chat_id: chat.id, message_id: message.message_id });
    return;
  }

  const text = (message.text || message.caption || "").trim();
  const isAdmin = await isUserAdmin(token, chat.id, sender.id);

  if (!isAdmin) {
    if (checkFlood(key)) {
      await tg(token, "deleteMessage", { chat_id: chat.id, message_id: message.message_id });
      const untilDate = Math.floor(Date.now() / 1000) + FLOOD_MUTE_SEC;
      await tg(token, "restrictChatMember", {
        chat_id: chat.id,
        user_id: sender.id,
        permissions: MUTE_PERMISSIONS,
        until_date: untilDate
      });
      await sendHtml(
        token,
        chat.id,
        `🚫 <a href="tg://user?id=${sender.id}">${escapeHtml(sender.first_name)}</a> flood (ardışık spam) nedeniyle <b>1 saat</b> susturuldu.`,
        message.message_thread_id
      );
      return;
    }

    const entityUrls = [...(message.entities || []), ...(message.caption_entities || [])]
      .map((e) => e.url || "")
      .filter(Boolean)
      .join(" ");
    if (hasUnauthorizedTelegramLink(`${text} ${entityUrls}`)) {
      await tg(token, "deleteMessage", { chat_id: chat.id, message_id: message.message_id });
      await addWarning(token, chat.id, sender, "İzinsiz Telegram grup/kanal reklamı", message.message_thread_id, env);
      return;
    }
  }

  if (text.startsWith("/") || text.startsWith("!")) {
    await handleGroupCommand(message, text, isAdmin, token, env);
  }
}

async function handleGroupCommand(message, rawText, isAdmin, token, env) {
  const chat = message.chat;
  const threadId = message.message_thread_id;
  const parts = rawText.slice(1).split(/\s+/);
  const cmd = parts[0].split("@")[0].toLowerCase();
  const args = parts.slice(1);

  if (cmd === "id") {
    const target = message.reply_to_message?.from || message.from;
    const info =
      `🆔 <b>Kimlik Bilgileri:</b>\n` +
      `• <b>Kullanıcı:</b> <code>${target.id}</code>\n` +
      `• <b>Grup:</b> <code>${chat.id}</code>` +
      (threadId ? `\n• <b>Konu (Thread):</b> <code>${threadId}</code>` : "");
    await replyHtml(token, message, info);
    return;
  }

  if (cmd === "kurallar" || cmd === "rules") {
    const rules =
      `📜 <b>BigBullApp Topluluk Kuralları</b>\n\n` +
      `1️⃣ Saygılı olun; hakaret, küfür ve tartışma yasaktır.\n` +
      `2️⃣ Sorularınızı ve hataları ilgili konu başlığına (<i>Hata & Sorun</i>, <i>İstek & Öneri</i>) yazın.\n` +
      `3️⃣ Başka Telegram kanalı/grubu reklamı ve spam link paylaşımı yasaktır.\n` +
      `4️⃣ Hata bildirirken eklenti adını ve ekran görüntüsünü ekleyin.`;
    await replyHtml(token, message, rules);
    return;
  }

  if (cmd === "linkler" || cmd === "site" || cmd === "repo") {
    const linksText =
      `🌐 <b>BigBullApp Resmi Bağlantılar</b>\n\n` +
      `• <b>Web Sitesi:</b> https://bigbullapp.com\n` +
      `• <b>GitHub:</b> https://github.com/F0Rextasy`;
    await replyHtml(token, message, linksText);
    return;
  }

  if (!isAdmin) return;

  const replyMsg = message.reply_to_message;
  const targetUser = replyMsg?.from;

  if (cmd === "sil" || cmd === "del") {
    if (replyMsg) {
      await tg(token, "deleteMessage", { chat_id: chat.id, message_id: replyMsg.message_id });
    }
    await tg(token, "deleteMessage", { chat_id: chat.id, message_id: message.message_id });
    return;
  }

  if (cmd === "pin") {
    if (replyMsg) {
      await tg(token, "pinChatMessage", {
        chat_id: chat.id,
        message_id: replyMsg.message_id,
        disable_notification: true
      });
      await tg(token, "deleteMessage", { chat_id: chat.id, message_id: message.message_id });
    }
    return;
  }

  if (cmd === "unpin") {
    if (replyMsg) {
      await tg(token, "unpinChatMessage", { chat_id: chat.id, message_id: replyMsg.message_id });
      await tg(token, "deleteMessage", { chat_id: chat.id, message_id: message.message_id });
    }
    return;
  }

  if (!targetUser) return;

  if (await isUserAdmin(token, chat.id, targetUser.id)) {
    await replyHtml(token, message, "⚠️ Bir yönetici üzerinde moderasyon işlemi uygulanamaz.");
    return;
  }

  const targetMention = `<a href="tg://user?id=${targetUser.id}">${escapeHtml(targetUser.first_name)}</a>`;

  if (cmd === "ban") {
    const reason = args.join(" ") || "Kurallara aykırı davranış";
    await tg(token, "banChatMember", { chat_id: chat.id, user_id: targetUser.id });
    await tg(token, "deleteMessage", { chat_id: chat.id, message_id: replyMsg.message_id });
    await tg(token, "deleteMessage", { chat_id: chat.id, message_id: message.message_id });
    await sendHtml(token, chat.id, `🔨 ${targetMention} gruptan <b>yasaklandı (ban)</b>.\n📝 <b>Sebep:</b> ${escapeHtml(reason)}`, threadId);
    return;
  }

  if (cmd === "unban") {
    await tg(token, "unbanChatMember", { chat_id: chat.id, user_id: targetUser.id, only_if_banned: true });
    await tg(token, "deleteMessage", { chat_id: chat.id, message_id: message.message_id });
    await sendHtml(token, chat.id, `✅ ${targetMention} kullanıcısının yasağı kaldırıldı.`, threadId);
    return;
  }

  if (cmd === "kick") {
    await kickUser(token, chat.id, targetUser.id);
    await tg(token, "deleteMessage", { chat_id: chat.id, message_id: replyMsg.message_id });
    await tg(token, "deleteMessage", { chat_id: chat.id, message_id: message.message_id });
    await sendHtml(token, chat.id, `👢 ${targetMention} gruptan çıkarıldı.`, threadId);
    return;
  }

  if (cmd === "mute") {
    const durationSec = parseDuration(args[0]);
    const payload = {
      chat_id: chat.id,
      user_id: targetUser.id,
      permissions: MUTE_PERMISSIONS
    };
    if (durationSec > 0) {
      payload.until_date = Math.floor(Date.now() / 1000) + durationSec;
    }
    await tg(token, "restrictChatMember", payload);
    await tg(token, "deleteMessage", { chat_id: chat.id, message_id: message.message_id });
    const timeLabel = args[0] ? ` (${escapeHtml(args[0])})` : " (süresiz)";
    await sendHtml(token, chat.id, `🔇 ${targetMention} susturuldu${timeLabel}.`, threadId);
    return;
  }

  if (cmd === "unmute") {
    await tg(token, "restrictChatMember", {
      chat_id: chat.id,
      user_id: targetUser.id,
      permissions: FULL_PERMISSIONS
    });
    await tg(token, "deleteMessage", { chat_id: chat.id, message_id: message.message_id });
    await sendHtml(token, chat.id, `🔊 ${targetMention} kullanıcısının susturması kaldırıldı.`, threadId);
    return;
  }

  if (cmd === "warn") {
    const reason = args.join(" ") || "Grup kuralları ihlali";
    await tg(token, "deleteMessage", { chat_id: chat.id, message_id: replyMsg.message_id });
    await tg(token, "deleteMessage", { chat_id: chat.id, message_id: message.message_id });
    await addWarning(token, chat.id, targetUser, reason, threadId, env);
    return;
  }

  if (cmd === "unwarn") {
    const warnKey = `${chat.id}:${targetUser.id}`;
    warnings.delete(warnKey);
    if (env?.BOT_KV) await env.BOT_KV.delete(`warn:${warnKey}`);
    await tg(token, "deleteMessage", { chat_id: chat.id, message_id: message.message_id });
    await sendHtml(token, chat.id, `✨ ${targetMention} kullanıcısının uyarıları sıfırlandı.`, threadId);
    return;
  }
}

async function addWarning(token, chatId, user, reason, threadId, env) {
  const key = `${chatId}:${user.id}`;
  let prev = warnings.get(key) || 0;
  if (!prev && env?.BOT_KV) {
    prev = parseInt((await env.BOT_KV.get(`warn:${key}`)) || "0", 10) || 0;
  }
  const count = prev + 1;
  const mention = `<a href="tg://user?id=${user.id}">${escapeHtml(user.first_name)}</a>`;

  if (count >= 3) {
    warnings.delete(key);
    if (env?.BOT_KV) await env.BOT_KV.delete(`warn:${key}`);
    await tg(token, "banChatMember", { chat_id: chatId, user_id: user.id });
    await sendHtml(
      token,
      chatId,
      `🔨 ${mention} <b>3/3</b> uyarı sınırına ulaştığı için gruptan <b>yasaklandı</b>!\n📝 <b>Son ihlal:</b> ${escapeHtml(reason)}`,
      threadId
    );
  } else {
    warnings.set(key, count);
    if (env?.BOT_KV) await env.BOT_KV.put(`warn:${key}`, String(count), { expirationTtl: 30 * 86400 });
    await sendHtml(
      token,
      chatId,
      `⚠️ ${mention} uyarıldı (<b>${count}/3</b>).\n📝 <b>Sebep:</b> ${escapeHtml(reason)}`,
      threadId
    );
  }
}

function checkFlood(key) {
  const now = Date.now();
  const entry = floodTracker.get(key);
  if (!entry || now > entry.resetAt) {
    floodTracker.set(key, { count: 1, resetAt: now + FLOOD_WINDOW_MS });
    return false;
  }
  entry.count += 1;
  if (entry.count > FLOOD_LIMIT) {
    floodTracker.delete(key);
    return true;
  }
  return false;
}

function hasUnauthorizedTelegramLink(text) {
  if (!text) return false;
  const re = /(?:https?:\/\/)?(?:t\.me|telegram\.me|telegram\.dog)\/([a-z0-9_+]+)/gi;
  for (const match of text.matchAll(re)) {
    const target = match[1].toLowerCase();
    if (target !== "bigbull_app" && !target.startsWith("bigbull")) {
      return true;
    }
  }
  return false;
}

function parseDuration(str) {
  if (!str) return 0;
  const m = str.trim().match(/^(\d+)([smhd])$/i);
  if (!m) return 0;
  const val = parseInt(m[1], 10);
  const unit = m[2].toLowerCase();
  if (unit === "s") return val;
  if (unit === "m") return val * 60;
  if (unit === "h") return val * 3600;
  if (unit === "d") return val * 86400;
  return 0;
}

async function isUserAdmin(token, chatId, userId) {
  const key = `${chatId}:${userId}`;
  const now = Date.now();
  const cached = adminCache.get(key);
  if (cached && now < cached.expiresAt) return cached.isAdmin;

  const res = await tg(token, "getChatMember", { chat_id: chatId, user_id: userId });
  const status = res?.result?.status;
  const isAdmin = status === "creator" || status === "administrator";
  adminCache.set(key, { isAdmin, expiresAt: now + 60 * 1000 });
  return isAdmin;
}

async function kickUser(token, chatId, userId) {
  await tg(token, "banChatMember", { chat_id: chatId, user_id: userId });
  await tg(token, "unbanChatMember", { chat_id: chatId, user_id: userId });
}

async function sendHtml(token, chatId, text, threadId) {
  const payload = { chat_id: chatId, text, parse_mode: "HTML", disable_web_page_preview: true };
  if (threadId) payload.message_thread_id = threadId;
  return await tg(token, "sendMessage", payload);
}

async function replyHtml(token, message, text) {
  const payload = {
    chat_id: message.chat.id,
    text,
    parse_mode: "HTML",
    reply_to_message_id: message.message_id,
    disable_web_page_preview: true
  };
  if (message.message_thread_id) payload.message_thread_id = message.message_thread_id;
  return await tg(token, "sendMessage", payload);
}

async function tg(token, method, payload) {
  const url = `https://api.telegram.org/bot${token}/${method}`;
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  });
  return await res.json();
}

function escapeHtml(text) {
  if (!text) return "";
  return String(text)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

async function signHex(secret, data, length = 16) {
  const raw = new TextEncoder().encode(`${secret}:${data}`);
  const hashBuffer = await crypto.subtle.digest("SHA-256", raw);
  const hashArray = Array.from(new Uint8Array(hashBuffer));
  const hex = hashArray.map((b) => b.toString(16).padStart(2, "0")).join("");
  return hex.slice(0, length);
}

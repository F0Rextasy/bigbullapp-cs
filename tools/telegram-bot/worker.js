/**
 * BigBullApp Telegram Gatekeeper & Moderasyon Botu
 * Cloudflare Workers - ucretsiz plan, serverless
 *
 * Dogrulama akisi (captcha yok, yalniz Start):
 * 1. Yeni uye gruba girer (chat_member) -> aninda sessize alinir.
 * 2. Grupta "Doğrulamak İçin Tıkla" butonu cikar (bota yonlendirir).
 * 3. Kullanici bota /start dedigi anda izinleri grubun varsayilanina doner,
 *    gruptaki dogrulama mesaji silinir, ozelden bilgi verilir.
 *
 * Durum tasimaz: baglanti HMAC imzalidir, tek kullanimlik olmasi mesajin silinmesine
 * ve kullanicinin hala "kisitli" olmasina baglidir. Uyari sayaci icin istege bagli
 * BOT_KV (ucretsiz KV) kullanilir; yoksa bellekte tutulur (README).
 */

const warnings = new Map();     // BOT_KV yoksa yedek uyari sayaci (isolate basina)
const floodTracker = new Map(); // Anti-flood sayaci
const adminCache = new Map();   // Yonetici onbellegi
let cachedBotUsername = null;   // getMe ile otomatik ogrenilir

// env.GROUP_ID / env.VERIFY_THREAD_ID ile ezilebilir (telegram-topics.json: "sohbet" konusu)
const DEFAULT_GROUP_ID = "-1003994138025";
const DEFAULT_VERIFY_THREAD = 6;

const ALLOWED_TG_NAMES = new Set(["bigbull_app"]); // izinli t.me adlari (tam eslesme)
const ANON_ADMIN_BOT_ID = 1087968824;              // GroupAnonymousBot

const FLOOD_LIMIT = 6;       // 6 saniyede 6 mesaj
const FLOOD_WINDOW_MS = 6000;
const FLOOD_MUTE_SEC = 3600; // 1 saat
const MIN_MUTE_SEC = 30;     // Telegram 30 sn'den kisayi "sonsuz" sayar
const MAX_MUTE_SEC = 366 * 86400;

// Grup varsayilani okunamazsa kullanilan yedek izinler
const FALLBACK_PERMISSIONS = {
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

const MUTE_PERMISSIONS = Object.fromEntries(
  Object.keys(FALLBACK_PERMISSIONS).map((k) => [k, false])
);

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
      ctx.waitUntil(
        handleUpdate(update, env).catch((err) => console.error("handleUpdate hatasi:", err))
      );
    } catch (err) {
      console.error("Webhook hatasi:", err);
    }
    return new Response("OK", { status: 200 });
  }
};

async function handleUpdate(update, env) {
  const token = env.TELEGRAM_BOT_TOKEN;
  if (!token) return;

  if (update.chat_join_request) {
    await tg(token, "approveChatJoinRequest", {
      chat_id: update.chat_join_request.chat.id,
      user_id: update.chat_join_request.from.id
    });
    return;
  }

  if (update.chat_member) {
    await handleChatMemberUpdate(update.chat_member, token, env);
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

async function handleChatMemberUpdate(cm, token, env) {
  const old = cm.old_chat_member;
  const now = cm.new_chat_member;
  const user = now?.user;
  if (!user) return;

  const wasOutside =
    old?.status === "left" || old?.status === "kicked" ||
    (old?.status === "restricted" && !old.is_member);
  const isNowMember = now.status === "member" || (now.status === "restricted" && now.is_member);

  if (wasOutside && isNowMember) {
    await processNewMember(cm.chat, user, cm.from, token, env);
  }
}

/**
 * Gruba yeni uye girdiginde: sessize alir ve bota /start linki verir
 */
async function processNewMember(chat, member, actor, token, env) {
  const botUsername = await getBotUsername(token);
  if (!botUsername || (member.is_bot && member.username === botUsername)) return;

  // Katilma isteklerini onaylayan botun kendisi de "actor" olur; yonetici sayilmaz.
  const actorIsAdmin = actor && actor.id !== member.id && !actor.is_bot
    ? Boolean(await getAdminInfo(token, chat.id, actor.id))
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

  const params = {
    chat_id: chat.id,
    text,
    parse_mode: "HTML",
    reply_markup: { inline_keyboard: [[await verifyButton(token, botUsername, chat.id, member.id, 0)]] }
  };

  const groupId = String(env?.GROUP_ID || DEFAULT_GROUP_ID);
  const verifyThread = Number(env?.VERIFY_THREAD_ID || DEFAULT_VERIFY_THREAD);
  if (String(chat.id) === groupId && verifyThread) {
    params.message_thread_id = verifyThread;
  }

  const res = await tg(token, "sendMessage", params);
  const msgId = res?.result?.message_id;
  if (res?.ok && msgId) {
    // Baglanti mesaj id'sini tasir (start'ta mesaj silinerek tek kullanimlik olur)
    await tg(token, "editMessageReplyMarkup", {
      chat_id: chat.id,
      message_id: msgId,
      reply_markup: { inline_keyboard: [[await verifyButton(token, botUsername, chat.id, member.id, msgId)]] }
    });
  }
}

// Deep-link: https://t.me/<Bot>?start=v_<chatId>_<userId>_<msgId>_<sig16>  (Telegram siniri 64 karakter)
async function verifyButton(token, botUsername, chatId, userId, msgId) {
  const sig = await signHex(token, `${chatId}:${userId}:${msgId}`, 16);
  return {
    text: "🤖 Doğrulamak İçin Tıkla (Start)",
    url: `https://t.me/${botUsername}?start=v_${chatId}_${userId}_${msgId}_${sig}`
  };
}

/**
 * Kullanici bota ozelden /start yazdiginda (Dogrulama + Karsilama)
 */
async function handlePrivateCommand(message, token) {
  const text = (message.text || "").trim();
  const userId = message.from.id;
  const privateChatId = message.chat.id;

  if (!text.startsWith("/start")) return;

  const payload = text.split(/\s+/)[1] || "";

  if (payload.startsWith("v_")) {
    const [, targetChatId, targetUserStr, msgIdStr, sig] = payload.split("_");
    const targetUserId = parseInt(targetUserStr, 10);
    const msgId = parseInt(msgIdStr, 10) || 0;
    const expectedSig = await signHex(token, `${targetChatId}:${targetUserId}:${msgId}`, 16);

    // Baska bir kullanici linke tikladiysa veya imza gecersizse reddet
    if (userId !== targetUserId || !sig || sig !== expectedSig) {
      await privateReply(token, privateChatId, "⚠️ Geçersiz veya size ait olmayan doğrulama bağlantısı!");
      return;
    }

    // Yalniz hala kisitli (dogrulanmamis) uye dogrulanir; uye degilse veya zaten dogrulandiysa reddet
    const member = (await tg(token, "getChatMember", { chat_id: targetChatId, user_id: targetUserId }))?.result;
    if (member?.status !== "restricted" || !member.is_member || member.can_send_messages) {
      await privateReply(token, privateChatId, "⚠️ Bu doğrulama bağlantısı daha önce kullanılmış veya artık geçerli değil!");
      return;
    }

    // Gruptaki dogrulama mesajini sil (silinmisse baglanti daha once kullanilmistir)
    if (msgId) {
      const del = await tg(token, "deleteMessage", { chat_id: targetChatId, message_id: msgId });
      if (!del?.ok) {
        await privateReply(token, privateChatId, "⚠️ Bu doğrulama bağlantısı daha önce kullanılmış!");
        return;
      }
    }

    // Gruptaki tum mesaj ve medya izinlerini ac (grubun varsayilani)
    const res = await tg(token, "restrictChatMember", {
      chat_id: targetChatId,
      user_id: targetUserId,
      permissions: await groupPermissions(token, targetChatId)
    });
    if (!res?.ok) {
      await privateReply(token, privateChatId, "⚠️ Doğrulama tamamlanamadı, lütfen bir yöneticiye bildirin.");
      return;
    }

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

  // Giris/cikis servis mesajlari silinir; girisleri chat_member olayi isler (cift islem olmasin)
  if (message.new_chat_members?.length || message.left_chat_member) {
    await tg(token, "deleteMessage", { chat_id: chat.id, message_id: message.message_id });
    return;
  }

  if (!sender || message.is_automatic_forward) return;

  const key = `${chat.id}:${sender.id}`;
  const text = (message.text || message.caption || "").trim();
  const adminInfo = await senderAdminInfo(token, message);
  const isAdmin = Boolean(adminInfo);

  if (!isAdmin) {
    if (checkFlood(key, message.media_group_id)) {
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
    if (hasUnauthorizedTelegramLink(`${text} ${entityUrls}`, await getBotUsername(token))) {
      await tg(token, "deleteMessage", { chat_id: chat.id, message_id: message.message_id });
      await addWarning(token, chat.id, sender, "İzinsiz Telegram grup/kanal reklamı", message.message_thread_id, env);
      return;
    }
  }

  if (text.startsWith("/") || text.startsWith("!")) {
    await handleGroupCommand(message, text, adminInfo, token, env);
  }
}

// Anonim yonetici (grubun kendi sender_chat'i) tum haklara sahip sayilir.
async function senderAdminInfo(token, message) {
  if (message.sender_chat?.id === message.chat.id || message.from.id === ANON_ADMIN_BOT_ID) {
    return { status: "creator" };
  }
  return getAdminInfo(token, message.chat.id, message.from.id);
}

// Komut -> gereken yonetici hakki
const COMMAND_RIGHTS = {
  sil: "can_delete_messages", del: "can_delete_messages",
  pin: "can_pin_messages", unpin: "can_pin_messages",
  ban: "can_restrict_members", unban: "can_restrict_members", kick: "can_restrict_members",
  mute: "can_restrict_members", unmute: "can_restrict_members",
  warn: "can_restrict_members", unwarn: "can_restrict_members"
};

async function handleGroupCommand(message, rawText, adminInfo, token, env) {
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

  const right = COMMAND_RIGHTS[cmd];
  if (!right || !hasRight(adminInfo, right)) return;

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

  if (!replyMsg) return;

  // Kanal (sender_chat) adina yazilmis mesaj: from ortak bir bot hesabidir, hedef kanaldir.
  const targetChat = replyMsg.sender_chat;
  if (targetChat) {
    if (targetChat.id === chat.id) {
      await replyHtml(token, message, "⚠️ Bir yönetici üzerinde moderasyon işlemi uygulanamaz.");
    } else if (cmd === "ban") {
      await tg(token, "banChatSenderChat", { chat_id: chat.id, sender_chat_id: targetChat.id });
      await tg(token, "deleteMessage", { chat_id: chat.id, message_id: replyMsg.message_id });
      await tg(token, "deleteMessage", { chat_id: chat.id, message_id: message.message_id });
      await sendHtml(token, chat.id, `🔨 <b>${escapeHtml(targetChat.title || "Kanal")}</b> kanalı gruptan <b>yasaklandı (ban)</b>.`, threadId);
    } else {
      await replyHtml(token, message, "⚠️ Kanal hesaplarına yalnızca /ban uygulanır.");
    }
    return;
  }

  if (!targetUser) return;

  if (targetUser.id === ANON_ADMIN_BOT_ID || await getAdminInfo(token, chat.id, targetUser.id)) {
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
    if (durationSec === null) {
      await replyHtml(token, message, "⚠️ Geçersiz süre. Örnek: <code>/mute 10m</code>, <code>1h</code>, <code>1d</code> (30 sn – 366 gün).");
      return;
    }
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
    const timeLabel = durationSec > 0 ? ` (${escapeHtml(args[0])})` : " (süresiz)";
    await sendHtml(token, chat.id, `🔇 ${targetMention} susturuldu${timeLabel}.`, threadId);
    return;
  }

  if (cmd === "unmute") {
    await tg(token, "restrictChatMember", {
      chat_id: chat.id,
      user_id: targetUser.id,
      permissions: await groupPermissions(token, chat.id)
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
    await clearWarnings(env, `${chat.id}:${targetUser.id}`);
    await tg(token, "deleteMessage", { chat_id: chat.id, message_id: message.message_id });
    await sendHtml(token, chat.id, `✨ ${targetMention} kullanıcısının uyarıları sıfırlandı.`, threadId);
  }
}

// BOT_KV (ucretsiz KV) varsa tek kaynak odur; yoksa isolate bellegi.
async function readWarnings(env, key) {
  if (env?.BOT_KV) return parseInt((await env.BOT_KV.get(`warn:${key}`)) || "0", 10) || 0;
  return warnings.get(key) || 0;
}

async function clearWarnings(env, key) {
  warnings.delete(key);
  if (env?.BOT_KV) await env.BOT_KV.delete(`warn:${key}`);
}

async function addWarning(token, chatId, user, reason, threadId, env) {
  const key = `${chatId}:${user.id}`;
  const count = (await readWarnings(env, key)) + 1;
  const mention = `<a href="tg://user?id=${user.id}">${escapeHtml(user.first_name)}</a>`;

  if (count >= 3) {
    await clearWarnings(env, key);
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

function checkFlood(key, mediaGroupId) {
  const now = Date.now();
  if (floodTracker.size > 1000) floodTracker.clear();
  const entry = floodTracker.get(key);
  if (!entry || now > entry.resetAt) {
    floodTracker.set(key, { count: 1, resetAt: now + FLOOD_WINDOW_MS, group: mediaGroupId });
    return false;
  }
  // Album parcalari ayri guncelleme gelir; ilki sayilir, kalani tek mesaj sayilir.
  if (mediaGroupId && entry.group === mediaGroupId) return false;
  entry.group = mediaGroupId;
  entry.count += 1;
  if (entry.count > FLOOD_LIMIT) {
    floodTracker.delete(key);
    return true;
  }
  return false;
}

function hasUnauthorizedTelegramLink(text, botUsername) {
  if (!text) return false;
  const re = /(?:https?:\/\/)?(?:t\.me|telegram\.me|telegram\.dog)\/([a-z0-9_+]+)/gi;
  for (const match of text.matchAll(re)) {
    const target = match[1].toLowerCase();
    if (!ALLOWED_TG_NAMES.has(target) && target !== String(botUsername || "").toLowerCase()) {
      return true;
    }
  }
  return false;
}

// Bos -> 0 (suresiz), gecersiz -> null, aksi halde Telegram'in kabul ettigi araliga sikistirilmis saniye.
function parseDuration(str) {
  if (!str) return 0;
  const m = str.trim().match(/^(\d+)([smhd])$/i);
  if (!m) return null;
  const sec = parseInt(m[1], 10) * { s: 1, m: 60, h: 3600, d: 86400 }[m[2].toLowerCase()];
  return sec > MAX_MUTE_SEC ? null : Math.max(sec, MIN_MUTE_SEC);
}

function hasRight(info, right) {
  return Boolean(info) && (info.status === "creator" || info[right] === true);
}

// Yonetici degilse null, aksi halde getChatMember sonucu (haklarla birlikte), 60 sn onbellekli.
async function getAdminInfo(token, chatId, userId) {
  const key = `${chatId}:${userId}`;
  const now = Date.now();
  const cached = adminCache.get(key);
  if (cached && now < cached.expiresAt) return cached.info;

  const res = await tg(token, "getChatMember", { chat_id: chatId, user_id: userId });
  const member = res?.result;
  const info = member?.status === "creator" || member?.status === "administrator" ? member : null;
  if (adminCache.size > 1000) adminCache.clear();
  adminCache.set(key, { info, expiresAt: now + 60 * 1000 });
  return info;
}

// Grubun varsayilan uye izinleri; okunamazsa yedek izinler.
async function groupPermissions(token, chatId) {
  const res = await tg(token, "getChat", { chat_id: chatId });
  return res?.result?.permissions || FALLBACK_PERMISSIONS;
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

async function privateReply(token, chatId, text) {
  return await tg(token, "sendMessage", { chat_id: chatId, text, parse_mode: "HTML" });
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

// Hata firlatmaz: { ok:false } doner ve loglar. 429'da kisa retry_after ise bir kez yeniden dener.
async function tg(token, method, payload, retry = true) {
  try {
    const res = await fetch(`https://api.telegram.org/bot${token}/${method}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    const data = await res.json();
    if (!data.ok) {
      const wait = data.parameters?.retry_after;
      if (data.error_code === 429 && retry && wait <= 5) {
        await new Promise((r) => setTimeout(r, wait * 1000));
        return tg(token, method, payload, false);
      }
      console.log(`[tg] ${method} ${data.error_code}: ${data.description}`);
    }
    return data;
  } catch (err) {
    console.log(`[tg] ${method} hata: ${err}`);
    return { ok: false, description: String(err) };
  }
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

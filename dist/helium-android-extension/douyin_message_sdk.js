// This function is serialized into the platform's MAIN world, so it must be self-contained.
async function sendDouyinMessageWithSdk(recipient, content, attemptId) {
  let attempted = false;
  let timedOut = false;
  let clientId = "";
  const failure = error => ({ ok: false, attempted, clientId, error: error.message || String(error) });
  try {
    if (location.hostname !== "www.douyin.com" || !recipient || !content.trim() || content.length > 500) {
      throw new Error("私信参数或平台页面不正确。");
    }
    const attempts = window.__commentFilterSdkSendAttempts ||= new Map();
    if (attempts.has(attemptId)) return await attempts.get(attemptId);
    const run = async () => {
      const containerKey = Object.keys(window).find(key => key.startsWith("__VMOK_@pc-im/im:") && typeof window[key]?.get === "function");
      if (!containerKey) throw new Error("抖音私信 SDK 尚未加载，请确认已登录并打开私信会话。");
      const exports = (await window[containerKey].get("."))();
      const service = exports.Context?.instance?.imSdkService;
      const sdk = service?.imSdkManager?.getImSdkInstance();
      const conversation = window.conversationStore?.curConversation;
      if (!sdk || !service?.sendMessageManager?.createMessageBuilder) throw new Error("当前抖音版本未提供可用的发送 SDK。");
      if (!conversation || conversation.type !== 1 || conversation.toParticipantSecUserId !== recipient) {
        throw new Error("当前私信会话与目标用户不一致，已停止发送。");
      }
      const history = sdk.getConversationMessages({ conversation });
      if (history.some(message => {
        try { return JSON.parse(message.content).text === content; } catch { return false; }
      })) throw new Error("聊天记录中已有相同内容，已停止以避免重复发送。");
      const builder = service.sendMessageManager.createMessageBuilder({ messageType: 7 })
        .conversation(conversation)
        .content({ aweType: 700, type: 0, richTextInfos: [], text: content });
      await builder.buildMessage();
      const message = builder.getMessage();
      if (!message || message.conversationId !== conversation.id || window.conversationStore?.curConversation?.toParticipantSecUserId !== recipient) {
        throw new Error("消息构造或会话校验失败，未发送。");
      }
      clientId = message.clientId;
      if (timedOut) throw new Error("发送准备已超时，未发送。");
      attempted = true;
      // Use the SDK directly, without the UI manager's automatic retry hook.
      const result = await sdk.sendMessage({ message });
      const serverId = String(message.serverId || "");
      if (result?.success !== true || result.statusCode !== 0 || !serverId || serverId === "0") {
        throw new Error("平台未确认发送成功，请检查聊天记录，勿立即重复发送。");
      }
      return { ok: true, attempted: true, platform: "douyin", confirmation: "im-sdk", clientId, serverId };
    };
    let timer;
    const timeout = new Promise(resolve => {
      timer = setTimeout(() => {
        timedOut = true;
        resolve(failure(new Error("发送确认超时，请检查平台聊天记录，勿立即重复发送。")));
      }, 20000);
    });
    const pending = Promise.race([run().catch(failure), timeout]).finally(() => clearTimeout(timer));
    attempts.set(attemptId, pending);
    return await pending;
  } catch (error) {
    return failure(error);
  }
}

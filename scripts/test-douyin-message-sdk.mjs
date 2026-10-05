import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL("../extension/background/douyin_message_sdk.js", import.meta.url), "utf8");
function setup({ mismatch = false, duplicate = false, rejected = false, broken = false, noServerId = false } = {}) {
  const recipient = "MS4w-test-recipient";
  const conversation = { id: "conversation-1", type: 1, toParticipantSecUserId: mismatch ? "someone-else" : recipient };
  let sends = 0;
  let builtContent;
  let builtType;
  const sdk = {
    getConversationMessages: () => duplicate ? [{ content: JSON.stringify({ text: "test message" }) }] : [],
    async sendMessage({ message }) {
      sends++;
      if (broken) throw new Error("connection lost");
      if (!noServerId) message.serverId = "12345";
      return { success: !rejected, statusCode: rejected ? 1 : 0 };
    }
  };
  const service = {
    imSdkManager: { getImSdkInstance: () => sdk },
    sendMessageManager: {
      createMessageBuilder({ messageType }) {
        builtType = messageType;
        let message;
        return {
          conversation(value) { assert.equal(value, conversation); return this; },
          content(value) { builtContent = value; return this; },
          async buildMessage() { message = { conversationId: conversation.id, clientId: "client-1" }; },
          getMessage() { return message; }
        };
      }
    }
  };
  const window = {
    conversationStore: { curConversation: conversation },
    "__VMOK_@pc-im/im:test__": { get: async key => {
      assert.equal(key, ".");
      return () => ({ Context: { instance: { imSdkService: service } } });
    } }
  };
  const context = vm.createContext({ window, location: { hostname: "www.douyin.com" }, setTimeout, clearTimeout });
  vm.runInContext(source, context);
  return { send: id => context.sendDouyinMessageWithSdk(recipient, "test message", id), sends: () => sends, payload: () => builtContent, type: () => builtType, window };
}

const success = setup();
const [first, second] = await Promise.all([success.send("one"), success.send("one")]);
assert.equal(first.ok, true);
assert.equal(second.serverId, "12345");
assert.equal(success.sends(), 1, "same attempt must not be sent twice");
assert.equal(success.type(), 7);
assert.equal(success.payload().text, "test message");
assert.equal(first.confirmation, "im-sdk");
for (const options of [{ mismatch: true }, { duplicate: true }]) {
  const test = setup(options);
  const result = await test.send("one");
  assert.equal(result.ok, false);
  assert.equal(result.attempted, false);
  assert.equal(test.sends(), 0);
}
for (const options of [{ rejected: true }, { broken: true }, { noServerId: true }]) {
  const test = setup(options);
  const result = await test.send("one");
  assert.equal(result.ok, false);
  assert.equal(result.attempted, true);
  await test.send("one");
  assert.equal(test.sends(), 1, "uncertain/rejected send must not be retried");
}
const missing = setup();
delete missing.window["__VMOK_@pc-im/im:test__"];
assert.equal((await missing.send("one")).attempted, false);
console.log("Douyin SDK send tests passed: payload, recipient validation, deduplication, unavailable SDK, rejection, ambiguous results.");

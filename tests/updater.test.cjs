const test = require("node:test");
const assert = require("node:assert/strict");
const { updateErrorMessage } = require("../src/lib/updater.cjs");

test("missing legacy update metadata is shown as a normal compatibility notice", () => {
  const result = updateErrorMessage(
    new Error(
      "Cannot find latest-mac.yml in the latest release artifacts (https://example.invalid/latest-mac.yml): HttpError: 404"
    ),
    "0.5.1"
  );
  assert.equal(result.status, "current");
  assert.match(result.message, /暂未提供应用内更新文件/);
  assert.doesNotMatch(result.message, /HttpError|createHttpError|404/);
});

test("macOS signature failures offer a one-time repair install", () => {
  const result = updateErrorMessage(
    new Error("signature verification failed\n    at verify (/private/app.js:12:3)")
  );
  assert.equal(result.status, "repair");
  assert.match(result.message, /修复安装包/);
  assert.match(result.message, /安全更新通道/);
  assert.doesNotMatch(result.message, /private|ShipIt|signature verification/);
});

test("an unpublished secure mac manifest is shown as a normal compatibility notice", () => {
  const result = updateErrorMessage(new Error("更新服务器返回 404"), "0.7.0");
  assert.equal(result.status, "current");
  assert.match(result.message, /下次正式发布后/);
  assert.doesNotMatch(result.message, /404/);
});

test("generic updater errors never expose a stack trace", () => {
  const result = updateErrorMessage(
    new Error("unexpected update failure\n    at verify (/private/app.js:12:3)")
  );
  assert.equal(result.status, "error");
  assert.equal(result.message, "更新失败：unexpected update failure");
});

test("network updater failures have an actionable short message", () => {
  const result = updateErrorMessage(new Error("connect ETIMEDOUT github.com"));
  assert.equal(result.status, "error");
  assert.match(result.message, /检查网络/);
});

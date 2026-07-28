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

test("generic updater errors never expose a stack trace", () => {
  const result = updateErrorMessage(
    new Error("signature verification failed\n    at verify (/private/app.js:12:3)")
  );
  assert.equal(result.status, "error");
  assert.equal(result.message, "更新失败：signature verification failed");
});

test("network updater failures have an actionable short message", () => {
  const result = updateErrorMessage(new Error("connect ETIMEDOUT github.com"));
  assert.equal(result.status, "error");
  assert.match(result.message, /检查网络/);
});

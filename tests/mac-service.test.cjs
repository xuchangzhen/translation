const test = require("node:test");
const assert = require("node:assert/strict");
const { isPrivateClient } = require("../src/lib/mac-service.cjs");

test("Mac mini companion only accepts loopback, wired-link, and private LAN clients", () => {
  assert.equal(isPrivateClient("::1"), true);
  assert.equal(isPrivateClient("::ffff:192.168.1.8"), true);
  assert.equal(isPrivateClient("169.254.20.5"), true);
  assert.equal(isPrivateClient("172.20.0.3"), true);
  assert.equal(isPrivateClient("8.8.8.8"), false);
});

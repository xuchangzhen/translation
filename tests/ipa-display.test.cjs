const test = require("node:test");
const assert = require("node:assert/strict");
const { formatIpaForDisplay } = require("../src/lib/ipa-display.cjs");

test("formats IPA stress marks for display without altering other IPA glyphs", () => {
  const storedIpa = "/ˈklɑk/";
  assert.equal(formatIpaForDisplay(storedIpa), "/klɑk/");
  assert.equal(storedIpa, "/ˈklɑk/");
  assert.equal(formatIpaForDisplay("/ˈɑsəˌleɪtɚ/"), "/ɑsəleɪtɚ/");
  assert.equal(formatIpaForDisplay("/əˈbændən/"), "/əbændən/");
  assert.equal(formatIpaForDisplay("/ˈθðŋæɚ/"), "/θðŋæɚ/");
});

test("does not change non-stress punctuation or empty IPA values", () => {
  assert.equal(formatIpaForDisplay("/'a’b/"), "/'a’b/");
  assert.equal(formatIpaForDisplay(""), "");
  assert.equal(formatIpaForDisplay(null), "");
  assert.equal(formatIpaForDisplay(undefined), "");
});

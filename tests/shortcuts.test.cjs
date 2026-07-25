const test = require("node:test");
const assert = require("node:assert/strict");
const {
  isReservedMacShortcut,
  normalizeMacShortcut
} = require("../src/lib/shortcuts.cjs");

test("normalizes macOS command shortcut aliases", () => {
  assert.equal(normalizeMacShortcut("CommandOrControl + Q"), "command+q");
  assert.equal(normalizeMacShortcut("Cmd+Space"), "command+space");
});

test("rejects macOS system shortcuts but allows the screenshot default", () => {
  assert.equal(isReservedMacShortcut("CommandOrControl+Q", "darwin"), true);
  assert.equal(isReservedMacShortcut("Command+M", "darwin"), true);
  assert.equal(
    isReservedMacShortcut("CommandOrControl+Shift+S", "darwin"),
    false
  );
  assert.equal(isReservedMacShortcut("Control+Q", "win32"), false);
});

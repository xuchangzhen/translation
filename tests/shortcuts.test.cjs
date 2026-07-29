const test = require("node:test");
const assert = require("node:assert/strict");
const {
  hookShortcutMatches,
  isModifierOnlyShortcut,
  isReservedMacShortcut,
  normalizeMacShortcut,
  normalizeModifierShortcut,
  parseHookShortcut
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

test("normalizes single modifier shortcuts for the global hook", () => {
  assert.equal(normalizeModifierShortcut("Option"), "Alt");
  assert.equal(normalizeModifierShortcut("Ctrl"), "Control");
  assert.equal(normalizeModifierShortcut("Command"), "Meta");
  assert.equal(isModifierOnlyShortcut("Shift"), true);
  assert.equal(isModifierOnlyShortcut("Control+Shift+D"), false);
});

test("parses Windows Electron accelerators for the hook fallback", () => {
  assert.deepEqual(parseHookShortcut("CommandOrControl+Shift+D", "win32"), {
    key: "D",
    altKey: false,
    ctrlKey: true,
    metaKey: false,
    shiftKey: true
  });
  assert.deepEqual(parseHookShortcut("Alt+F8", "win32"), {
    key: "F8",
    altKey: true,
    ctrlKey: false,
    metaKey: false,
    shiftKey: false
  });
  assert.equal(parseHookShortcut("Alt", "win32"), null);
  assert.equal(parseHookShortcut("Control+UnknownKey", "win32"), null);
});

test("matches hook shortcuts only when the key and modifiers are exact", () => {
  const shortcut = parseHookShortcut("CommandOrControl+Shift+H", "win32");
  assert.equal(
    hookShortcutMatches(
      shortcut,
      {
        keycode: 35,
        altKey: false,
        ctrlKey: true,
        metaKey: false,
        shiftKey: true
      },
      35
    ),
    true
  );
  assert.equal(
    hookShortcutMatches(
      shortcut,
      {
        keycode: 35,
        altKey: true,
        ctrlKey: true,
        metaKey: false,
        shiftKey: true
      },
      35
    ),
    false
  );
});

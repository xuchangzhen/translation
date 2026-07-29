function normalizeMacShortcut(value) {
  return String(value || "")
    .toLowerCase()
    .replace(/\s+/g, "")
    .replace(/commandorcontrol|cmd|meta|super/g, "command")
    .replace(/option/g, "alt");
}

function normalizeModifierShortcut(value) {
  const shortcut = String(value || "").trim().toLowerCase();
  if (["alt", "option"].includes(shortcut)) return "Alt";
  if (["control", "ctrl"].includes(shortcut)) return "Control";
  if (shortcut === "shift") return "Shift";
  if (["command", "cmd", "meta", "super"].includes(shortcut)) return "Meta";
  return "";
}

function isModifierOnlyShortcut(value) {
  return Boolean(normalizeModifierShortcut(value));
}

function canonicalHookKey(value) {
  const key = String(value || "").trim();
  if (/^[a-z0-9]$/i.test(key)) return key.toUpperCase();
  if (/^f(?:[1-9]|1\d|2[0-4])$/i.test(key)) return key.toUpperCase();
  const aliases = {
    up: "ArrowUp",
    arrowup: "ArrowUp",
    down: "ArrowDown",
    arrowdown: "ArrowDown",
    left: "ArrowLeft",
    arrowleft: "ArrowLeft",
    right: "ArrowRight",
    arrowright: "ArrowRight",
    space: "Space",
    tab: "Tab",
    enter: "Enter",
    return: "Enter",
    escape: "Escape",
    esc: "Escape",
    backspace: "Backspace",
    delete: "Delete",
    insert: "Insert",
    home: "Home",
    end: "End",
    pageup: "PageUp",
    pagedown: "PageDown"
  };
  return aliases[key.toLowerCase()] || "";
}

function parseHookShortcut(value, platform = process.platform) {
  const tokens = String(value || "")
    .split("+")
    .map((token) => token.trim())
    .filter(Boolean);
  if (tokens.length < 2) return null;
  const shortcut = {
    key: "",
    altKey: false,
    ctrlKey: false,
    metaKey: false,
    shiftKey: false
  };
  for (const token of tokens) {
    const normalized = token.toLowerCase().replace(/\s+/g, "");
    if (normalized === "commandorcontrol" || normalized === "cmdorctrl") {
      if (platform === "darwin") shortcut.metaKey = true;
      else shortcut.ctrlKey = true;
      continue;
    }
    if (["control", "ctrl"].includes(normalized)) {
      shortcut.ctrlKey = true;
      continue;
    }
    if (["command", "cmd", "meta", "super"].includes(normalized)) {
      shortcut.metaKey = true;
      continue;
    }
    if (["alt", "option"].includes(normalized)) {
      shortcut.altKey = true;
      continue;
    }
    if (normalized === "shift") {
      shortcut.shiftKey = true;
      continue;
    }
    if (shortcut.key) return null;
    shortcut.key = canonicalHookKey(token);
    if (!shortcut.key) return null;
  }
  return shortcut.key ? shortcut : null;
}

function hookShortcutMatches(shortcut, event, keycode) {
  return Boolean(
    shortcut &&
      event &&
      event.keycode === keycode &&
      Boolean(event.altKey) === shortcut.altKey &&
      Boolean(event.ctrlKey) === shortcut.ctrlKey &&
      Boolean(event.metaKey) === shortcut.metaKey &&
      Boolean(event.shiftKey) === shortcut.shiftKey
  );
}

function isReservedMacShortcut(value, platform = process.platform) {
  if (platform !== "darwin") return false;
  return new Set([
    "command+q",
    "command+h",
    "command+m",
    "command+w",
    "command+tab",
    "command+space"
  ]).has(normalizeMacShortcut(value));
}

module.exports = {
  hookShortcutMatches,
  isModifierOnlyShortcut,
  isReservedMacShortcut,
  normalizeModifierShortcut,
  parseHookShortcut,
  normalizeMacShortcut
};

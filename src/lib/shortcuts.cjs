function normalizeMacShortcut(value) {
  return String(value || "")
    .toLowerCase()
    .replace(/\s+/g, "")
    .replace(/commandorcontrol|cmd|meta|super/g, "command")
    .replace(/option/g, "alt");
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
  isReservedMacShortcut,
  normalizeMacShortcut
};

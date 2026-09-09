/**
 * Removes IPA stress marks for UI rendering only. Stored and synchronized IPA
 * remains untouched so the standard phonemic transcription is always available.
 *
 * @param {string | null | undefined} ipa
 * @returns {string}
 */
function formatIpaForDisplay(ipa) {
  if (typeof ipa !== "string" || !ipa) return "";
  return ipa.replace(/[\u02C8\u02CC]/g, "");
}

module.exports = { formatIpaForDisplay };

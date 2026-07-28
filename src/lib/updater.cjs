function updateErrorMessage(error, currentVersion = "") {
  const raw = String(error?.message || error || "");
  const version = currentVersion ? ` v${currentVersion}` : "";

  if (
    /Cannot find latest(?:-mac)?\.yml|latest(?:-mac)?\.yml[\s\S]*404|HttpError:\s*404[\s\S]*latest release artifacts/i.test(
      raw
    )
  ) {
    return {
      status: "current",
      message: `当前发布页暂未提供应用内更新文件；现有版本${version}可继续使用，下次正式发布后即可一键更新。`
    };
  }

  if (
    /ENOTFOUND|ECONNREFUSED|ETIMEDOUT|ERR_NETWORK|net::ERR_|fetch failed|network/i.test(
      raw
    )
  ) {
    return {
      status: "error",
      message: "无法连接更新服务器，请检查网络后重试。"
    };
  }

  const firstLine = raw
    .split(/\r?\n/)[0]
    .replace(/^Error:\s*/i, "")
    .replace(/^HttpError:\s*/i, "")
    .trim();
  const concise = firstLine.length > 160
    ? `${firstLine.slice(0, 157)}…`
    : firstLine;
  return {
    status: "error",
    message: concise ? `更新失败：${concise}` : "更新失败，请稍后重试。"
  };
}

module.exports = {
  updateErrorMessage
};

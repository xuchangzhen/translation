// Shared Chat Completions transport for translation, enrichment and diagnostics.
function normalizeBaseUrl(value) {
  return String(value || "").trim().replace(/\/+$/, "");
}
function compatibleBaseUrl(value) {
  const normalized = normalizeBaseUrl(value).replace(/\/(chat\/completions|models)$/i, "");
  let url;
  try { url = new URL(normalized); } catch { throw new Error("请填写有效的 API Base URL，例如 https://api.example.com/v1"); }
  if (!["http:", "https:"].includes(url.protocol) || url.username || url.password || url.search || url.hash) {
    throw new Error("API Base URL 仅支持 HTTP/HTTPS 地址，请勿包含凭据、查询参数或片段");
  }
  return normalizeBaseUrl(url.href);
}
function statusMessage(status) {
  if ([401, 403].includes(status)) return "API Key 无效或没有权限";
  if (status === 404) return "API 地址或接口路径错误，请确认 Base URL 是否包含 /v1";
  if (status === 429) return "请求频率或账户额度受限";
  if (status >= 500) return "中转服务暂时不可用";
  return `API 请求失败（HTTP ${status}），请检查模型 ID 和服务配置`;
}
function unsupportedExtension(payload) {
  const error = payload?.error || payload;
  const parameter = String(error?.param || error?.parameter || "");
  const code = String(error?.code || error?.type || "");
  const message = String(error?.message || "");
  const extension = /^(reasoning_effort|response_format)$/i;
  if (extension.test(parameter) && /^(unsupported_parameter|unknown_parameter|unknown_field|unsupported_field|extra_forbidden)$/i.test(code)) return true;
  // Require the rejection phrase and parameter to describe the same clause.
  // An unrelated invalid model mentioned alongside an extension is not enough.
  return message.split(/[.;\n]/).some(clause =>
    /(?:unsupported|unknown|unrecognized|unexpected)\s+(?:(?:request\s+)?(?:field|parameter|argument)\s*:?\s*)?["'`]?(?:reasoning_effort|response_format)\b/i.test(clause) ||
    /\b(?:reasoning_effort|response_format)\b["'`]?\s*(?::|is|are)?\s*(?:unsupported|not supported|not permitted|not allowed)\b/i.test(clause) ||
    /(?:does not support|doesn't support)\s+(?:(?:the|parameter|field)\s+)*["'`]?(?:reasoning_effort|response_format)\b/i.test(clause)
  );
}
async function request(settings, apiKey, endpoint, body, timeoutMs = 30000) {
  const url = `${compatibleBaseUrl(settings.compatibleBaseUrl)}${endpoint}`;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const headers = { "Content-Type": "application/json" };
    if (apiKey) headers.Authorization = `Bearer ${apiKey}`;
    const response = await fetch(url, { method: body ? "POST" : "GET", headers,
      ...(body ? { body: JSON.stringify(body) } : {}), signal: controller.signal, redirect: "error" });
    const raw = await response.text();
    let payload;
    try { payload = JSON.parse(raw); } catch { payload = null; }
    if (!response.ok) {
      const error = new Error(statusMessage(response.status));
      // Inspect structured API errors internally; never expose a server body or key.
      error.unsupported = response.status === 400 && unsupportedExtension(payload);
      throw error;
    }
    if (!payload || typeof payload !== "object") throw new Error("API 返回了无效的 JSON 响应");
    return payload;
  } catch (error) {
    if (controller.signal.aborted) throw new Error("请求超时，请检查网络或中转站状态");
    if (error instanceof TypeError) throw new Error("无法连接 API，请检查网络和 Base URL");
    throw error;
  } finally { clearTimeout(timer); }
}
async function compatibleChat(settings, apiKey, messages, structured = true) {
  if (!String(settings.compatibleModel || "").trim()) throw new Error("请填写模型 ID");
  const basic = { model: settings.compatibleModel, messages, temperature: 0.1 };
  const body = structured ? { ...basic, reasoning_effort: settings.useThinking === true ? "low" : "none", response_format: { type: "json_object" } } : basic;
  try { return await request(settings, apiKey, "/chat/completions", body); }
  catch (error) {
    if (!structured || !error.unsupported) throw error;
    return request(settings, apiKey, "/chat/completions", basic);
  }
}
async function compatibleModels(settings, apiKey) {
  try {
    const payload = await request(settings, apiKey, "/models");
    const models = [...new Set((Array.isArray(payload.data) ? payload.data : [])
      .map(item => typeof item?.id === "string" ? item.id.trim() : "").filter(Boolean))].sort();
    if (!models.length) throw new Error("empty");
    return models;
  } catch { throw new Error("该服务没有提供模型列表，请手动填写模型 ID。"); }
}
module.exports = { normalizeBaseUrl, compatibleBaseUrl, compatibleChat, compatibleModels };

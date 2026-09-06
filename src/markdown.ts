import MarkdownIt from "markdown-it";

const markdown = new MarkdownIt({
  html: false,
  linkify: false,
  typographer: false
});

markdown.renderer.rules.link_open = (tokens, index, options, _env, self) => {
  tokens[index].attrSet("target", "_blank");
  tokens[index].attrSet("rel", "noreferrer noopener");
  return self.renderToken(tokens, index, options);
};

export function looksLikeMarkdown(value: string) {
  const text = String(value || "");
  return (
    /(^|\n)\s{0,3}(?:#{1,6}\s+|>\s+|[-+*]\s+|\d+[.)]\s+)/m.test(text) ||
    /(^|\n)\s*(```|~~~)/m.test(text) ||
    /(^|\n)\s*\|.+\|\s*\n\s*\|?\s*:?-{3,}/m.test(text) ||
    /(?:\*\*[^*\n]+\*\*|__[^_\n]+__|~~[^~\n]+~~|`[^`\n]+`|!?\[[^\]\n]+\]\([^)\n]+\))/m.test(text)
  );
}

export function renderTranslatedText(
  element: HTMLElement,
  translation: string,
  sourceText: string,
  sourceFormat?: "plain" | "markdown"
) {
  const isMarkdown =
    sourceFormat === "markdown" ||
    (sourceFormat !== "plain" && looksLikeMarkdown(sourceText));
  element.classList.toggle("markdown-body", isMarkdown);
  if (isMarkdown) {
    element.innerHTML = markdown.render(translation);
  } else {
    element.textContent = translation;
  }
}

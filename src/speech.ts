function speechLanguage(language: string, text: string) {
  const value = String(language || "").toLowerCase();
  if (/繁体|zh-tw|cantonese/.test(value)) return "zh-TW";
  if (/简体|中文|chinese|^zh(?:-|$)|mandarin/.test(value)) return "zh-CN";
  if (/英语|英文|english|^en(?:-|$)/.test(value)) return "en-US";
  if (/日语|日文|japanese|^ja(?:-|$)/.test(value)) return "ja-JP";
  if (/韩语|韩文|korean|^ko(?:-|$)/.test(value)) return "ko-KR";
  if (/德语|german|^de(?:-|$)/.test(value)) return "de-DE";
  if (/法语|french|^fr(?:-|$)/.test(value)) return "fr-FR";
  if (/西班牙|spanish|^es(?:-|$)/.test(value)) return "es-ES";
  if (/俄语|russian|^ru(?:-|$)/.test(value)) return "ru-RU";
  if (/[\u3040-\u30ff]/.test(text)) return "ja-JP";
  if (/[\uac00-\ud7af]/.test(text)) return "ko-KR";
  if (/[\u3400-\u9fff]/.test(text)) return "zh-CN";
  return "en-US";
}

let activeAudio: HTMLAudioElement | null = null;
let activeAudioUrl = "";
let voicesPromise: Promise<SpeechSynthesisVoice[]> | null = null;
let speechRequest = 0;

function loadVoices() {
  const available = window.speechSynthesis.getVoices();
  if (available.length) return Promise.resolve(available);
  if (voicesPromise) return voicesPromise;
  voicesPromise = new Promise((resolve) => {
    const finish = () => {
      window.clearTimeout(timer);
      window.speechSynthesis.removeEventListener("voiceschanged", finish);
      voicesPromise = null;
      resolve(window.speechSynthesis.getVoices());
    };
    const timer = window.setTimeout(finish, 1200);
    window.speechSynthesis.addEventListener("voiceschanged", finish, {
      once: true
    });
  });
  return voicesPromise;
}

void loadVoices();

function stopCurrentSpeech() {
  window.speechSynthesis.cancel();
  if (activeAudio) {
    activeAudio.pause();
    activeAudio = null;
  }
  if (activeAudioUrl) {
    URL.revokeObjectURL(activeAudioUrl);
    activeAudioUrl = "";
  }
}

export async function speakText(text: string, language: string) {
  const content = String(text || "").trim();
  if (!content) return;
  const request = ++speechRequest;
  stopCurrentSpeech();
  const lang = speechLanguage(language, content);

  if (lang === "zh-CN" && content.length <= 1200) {
    let mamboError = "";
    for (let attempt = 0; attempt < 2; attempt += 1) {
      if (request !== speechRequest) return;
      try {
        const result = await window.lingua.synthesizeSpeech(content, lang);
        if (request !== speechRequest) return;
        if (!result.ok && !result.error) break;
        if (result.error) mamboError = result.error;
        if (result.ok && result.audio) {
          const bytes = new Uint8Array(result.audio);
          activeAudioUrl = URL.createObjectURL(
            new Blob([bytes.buffer as ArrayBuffer], {
              type: result.mimeType || "audio/wav"
            })
          );
          const audio = new Audio(activeAudioUrl);
          const audioUrl = activeAudioUrl;
          activeAudio = audio;
          audio.addEventListener(
            "ended",
            () => {
              URL.revokeObjectURL(audioUrl);
              if (activeAudio === audio) {
                activeAudioUrl = "";
                activeAudio = null;
              }
            },
            { once: true }
          );
          await audio.play();
          return;
        }
      } catch (error) {
        mamboError = error instanceof Error ? error.message : String(error);
      }
      if (request !== speechRequest) return;
      if (attempt === 0) {
        await new Promise((resolve) => window.setTimeout(resolve, 450));
      }
    }
    if (mamboError) throw new Error(`曼波朗读失败：${mamboError}`);
  }

  const voices = await loadVoices();
  if (request !== speechRequest) return;
  const utterance = new SpeechSynthesisUtterance(content);
  utterance.lang = lang;
  utterance.rate = 0.9;
  utterance.pitch = 1;

  const prefix = lang.split("-")[0].toLowerCase();
  const languageVoices = voices.filter((voice) =>
    voice.lang.toLowerCase().startsWith(prefix)
  );
  const exactVoices = languageVoices.filter(
    (voice) => voice.lang.toLowerCase().replace("_", "-") === lang.toLowerCase()
  );
  const matchingVoices = exactVoices.length ? exactVoices : languageVoices;
  const preferredNames: Record<string, string[]> = {
    en: ["Samantha", "Ava", "Alex", "Microsoft Aria", "Microsoft Jenny"],
    zh: ["Tingting", "Ting-Ting", "Meijia", "Microsoft Xiaoxiao", "Microsoft Huihui"],
    ja: ["Kyoko", "Otoya", "Microsoft Nanami"],
    ko: ["Yuna", "Microsoft SunHi"]
  };
  const preferred = preferredNames[prefix] || [];
  utterance.voice =
    preferred
      .map((name) =>
        matchingVoices.find((voice) =>
          voice.name.toLowerCase().includes(name.toLowerCase()) &&
          /enhanced|premium|优化音质|高音质/i.test(`${voice.name} ${voice.voiceURI}`)
        )
      )
      .find(Boolean) ||
    preferred
      .map((name) =>
        matchingVoices.find((voice) =>
          voice.name.toLowerCase().includes(name.toLowerCase())
        )
      )
      .find(Boolean) ||
    matchingVoices.find(
      (voice) =>
        voice.localService &&
        !/whisper|zarvox|bells|boing|bubbles|cellos|organ|trinoids|wobble/i.test(
          voice.name
        )
    ) ||
    matchingVoices[0] ||
    null;
  await new Promise<void>((resolve, reject) => {
    utterance.onend = () => resolve();
    utterance.onerror = (event) => {
      if (request !== speechRequest || event.error === "canceled" || event.error === "interrupted") {
        resolve();
      } else {
        reject(new Error(`系统语音朗读失败：${event.error || "未知错误"}`));
      }
    };
    window.speechSynthesis.speak(utterance);
  });
}

export { speechLanguage };

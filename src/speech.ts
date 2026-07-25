function speechLanguage(language: string, text: string) {
  const value = String(language || "").toLowerCase();
  if (/[\u3400-\u9fff]/.test(text)) return "zh-CN";
  if (/[\u3040-\u30ff]/.test(text)) return "ja-JP";
  if (/[\uac00-\ud7af]/.test(text)) return "ko-KR";
  if (/简体|中文|chinese|zh-cn|mandarin/.test(value)) return "zh-CN";
  if (/繁体|zh-tw|cantonese/.test(value)) return "zh-TW";
  if (/英语|英文|english|^en(?:-|$)/.test(value)) return "en-US";
  if (/日语|日文|japanese|^ja(?:-|$)/.test(value)) return "ja-JP";
  if (/韩语|韩文|korean|^ko(?:-|$)/.test(value)) return "ko-KR";
  if (/德语|german|^de(?:-|$)/.test(value)) return "de-DE";
  if (/法语|french|^fr(?:-|$)/.test(value)) return "fr-FR";
  if (/西班牙|spanish|^es(?:-|$)/.test(value)) return "es-ES";
  if (/俄语|russian|^ru(?:-|$)/.test(value)) return "ru-RU";
  return "en-US";
}

let activeAudio: HTMLAudioElement | null = null;
let activeAudioUrl = "";
let voicesPromise: Promise<SpeechSynthesisVoice[]> | null = null;

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
  stopCurrentSpeech();
  const lang = speechLanguage(language, content);

  if (lang.startsWith("zh")) {
    for (let attempt = 0; attempt < 2; attempt += 1) {
      try {
        const result = await window.lingua.synthesizeSpeech(content, lang);
        if (result.ok && result.audio) {
          const bytes = new Uint8Array(result.audio);
          activeAudioUrl = URL.createObjectURL(
            new Blob([bytes.buffer as ArrayBuffer], {
              type: result.mimeType || "audio/wav"
            })
          );
          activeAudio = new Audio(activeAudioUrl);
          activeAudio.addEventListener(
            "ended",
            () => {
              if (activeAudioUrl) URL.revokeObjectURL(activeAudioUrl);
              activeAudioUrl = "";
              activeAudio = null;
            },
            { once: true }
          );
          await activeAudio.play();
          return;
        }
      } catch {
        // Retry once before falling back to an operating-system voice.
      }
      if (attempt === 0) {
        await new Promise((resolve) => window.setTimeout(resolve, 450));
      }
    }
  }

  const voices = await loadVoices();
  const utterance = new SpeechSynthesisUtterance(content);
  utterance.lang = lang;
  utterance.rate = 0.9;
  utterance.pitch = 1;

  const prefix = lang.split("-")[0].toLowerCase();
  const matchingVoices = voices.filter((voice) =>
    voice.lang.toLowerCase().startsWith(prefix)
  );
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
  window.speechSynthesis.speak(utterance);
}

export { speechLanguage };

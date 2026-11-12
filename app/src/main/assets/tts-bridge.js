// Android WebView has no Web Speech API; this routes speechSynthesis to Android's native TextToSpeech.
// Sentences are queued natively in one go, so playback is continuous (no gap between sentences).
(function () {
  if (!window.AndroidTTS) return;
  var queue = [], idx = 0, speaking = false, paused = false, cur = null;
  function Utter(text) { this.text = text; this.lang = 'en-US'; this.rate = 1; this.onend = null; }
  window.SpeechSynthesisUtterance = Utter;
  function play() {
    if (!speaking || paused || !cur) return;
    AndroidTTS.speakAll(JSON.stringify(queue.slice(idx)), cur.rate, cur.lang);
  }
  var synth = {
    get speaking() { return speaking; },
    get paused() { return paused; },
    speak: function (u) {
      cur = u;
      queue = (u.text.match(/[^.!?]+[.!?]*/g) || [u.text]).map(function (s) { return s.trim(); })
                .filter(function (s) { return s.length > 0; });
      idx = 0; speaking = true; paused = false; play();
    },
    cancel: function () { speaking = false; paused = false; cur = null; queue = []; AndroidTTS.stop(); },
    pause: function () { if (speaking && !paused) { paused = true; AndroidTTS.stop(); } },
    resume: function () { if (paused) { paused = false; play(); } }
  };
  window.__ttsDone = function () {
    if (!speaking || paused) return;
    idx++;
    if (idx >= queue.length) {
      speaking = false; var u = cur; cur = null;
      if (u && u.onend) u.onend();
    }
  };
  window.__ttsRetry = function () { play(); };
  try { Object.defineProperty(window, 'speechSynthesis', { value: synth, configurable: true, writable: true }); }
  catch (e) { window.speechSynthesis = synth; }
})();

(function () {
  if (window.__bluefinNative) return;

  function safeId(id) {
    return String(id || "").replace(/[^a-z0-9_-]/gi, "");
  }

  function act(id) {
    var word = safeId(id);
    try {
      if (window.bluefin && typeof window.bluefin.do === "function") {
        window.bluefin.do(word);
        return "bluefin";
      }
      var btn = document.querySelector('button[data-word="' + word + '"]');
      if (btn) {
        btn.click();
        return "click";
      }
      var body = window.qa && window.qa.body;
      if (body && typeof body.do === "function") {
        body.do(word);
        return "do";
      }
      return "missing";
    } catch (error) {
      return "error:" + (error && error.message ? error.message : error);
    }
  }

  function reset() {
    try {
      if (window.bluefin && typeof window.bluefin.reset === "function") {
        window.bluefin.reset();
        return "bluefin";
      }
      var btn = document.getElementById("reset");
      if (btn) {
        btn.click();
        return "click";
      }
      if (window.qa && typeof window.qa.reset === "function") {
        window.qa.reset();
        return "qa";
      }
      return "missing";
    } catch (error) {
      return "error:" + (error && error.message ? error.message : error);
    }
  }

  function ready() {
    if (window.__BLUEFIN_READY === true) return true;
    if (window.bluefin && window.bluefin.ready === true) return true;
    if (window.qa && window.qa.ready === true) return true;
    return false;
  }

  function setSound(on) {
    var enabled = !!on;
    window.__bluefinSoundOn = enabled;
    var media = document.querySelectorAll("audio,video");
    for (var i = 0; i < media.length; i++) media[i].muted = !enabled;
    if (window.bluefin && typeof window.bluefin.setSound === "function") {
      window.bluefin.setSound(enabled);
    }
    if (window.qa && typeof window.qa.setSound === "function") {
      window.qa.setSound(enabled);
    }
  }

  // Stage box for the unchanged pack: width is the WebView (≥360 CSS px on phones),
  // height is 220–290px so preferredScale lands in S≈0.7–1.0, and the pack's own
  // floorY = stageHeight - 40 stays 40px above the stage bottom.
  function stageHeight(sizeName) {
    if (sizeName === "small") return 220;
    if (sizeName === "large") return 290;
    return 252;
  }

  function fitStage() {
    var style = document.getElementById("bluefin-native-fit");
    if (!style) {
      style = document.createElement("style");
      style.id = "bluefin-native-fit";
      document.head.appendChild(style);
    }
    style.textContent = [
      "header{position:absolute;z-index:4;left:0;right:64px;top:0;pointer-events:none}",
      "#panel{position:absolute!important;z-index:3;left:0;right:0;top:64px;max-height:32vh}",
      "#stage{position:absolute!important;z-index:1;left:0;right:0;top:auto!important;",
      "bottom:max(40px, env(safe-area-inset-bottom, 0px))!important;",
      "width:auto!important;min-height:220px!important;min-width:100%!important;",
      "height:" + stageHeight(window.__bluefinSize) + "px}"
    ].join("");
  }

  function desiredRoam() {
    if (window.__bluefinHidden || window.__bluefinRoam === "off") return "off";
    return "free";
  }

  var boundBody = null;
  function applyRoam(body) {
    if (!body || typeof body.set !== "function") return;
    var mode = desiredRoam();
    body.set({ roam: mode });
    boundBody = body;
    if (typeof body.do !== "function" || !body.layout) return;
    try {
      var now = body.layout().mode;
      if (mode === "free" && (!now || now === "idle")) body.do("walk");
      if (mode === "off" && !window.__bluefinAllowRest && (now === "walk" || now === "run" || now === "sit" || now === "sleep")) body.do("stand");
    } catch (err) {}
  }

  function applySize(sizeName) {
    window.__bluefinSize = sizeName || "medium";
    fitStage();
    var stage = document.querySelector("#stage");
    if (stage) stage.style.height = stageHeight(window.__bluefinSize) + "px";
    window.dispatchEvent(new Event("resize"));
  }

  function applyPrefs(roamOn, sizeName, hidden) {
    window.__bluefinHidden = !!hidden;
    window.__bluefinRoam = roamOn ? "free" : "off";
    var stage = document.querySelector("#stage");
    if (stage) stage.style.visibility = hidden ? "hidden" : "visible";
    if (window.qa && typeof window.qa.manual === "function") window.qa.manual(!!hidden);
    applySize(sizeName || "medium");
    applyRoam(window.qa && window.qa.body);
  }

  setInterval(function () {
    var body = window.qa && window.qa.body;
    if (body && body !== boundBody) applyRoam(body);
  }, 300);

  // Finger contact always includes pointerdown, but the engine pets only on
  // move-without-press and turns a >6px drag into a lift. Classify first:
  // tap = poke, in-place scrub = pet, longer drag = lift / throw.
  function installStageGestures() {
    var stage = document.querySelector("#stage");
    if (!stage || stage.dataset.bfGestures === "1") return;
    stage.dataset.bfGestures = "1";
    var track = null;
    function petBody() {
      return window.qa && window.qa.body;
    }
    function pt(e) {
      var rect = stage.getBoundingClientRect();
      return { x: e.clientX - rect.left, y: e.clientY - rect.top, t: e.timeStamp };
    }
    function onDown(e) {
      if (!petBody()) return;
      if (e.cancelable) e.preventDefault();
      e.stopPropagation();
      var p = pt(e);
      track = { id: e.pointerId, start: p, last: p, path: 0, net: 0, downSent: false, petting: false };
      try { stage.setPointerCapture(e.pointerId); } catch (err) {}
    }
    function onMove(e) {
      if (!track || e.pointerId !== track.id) return;
      var b = petBody();
      if (!b) return;
      if (e.cancelable) e.preventDefault();
      e.stopPropagation();
      var p = pt(e);
      track.path += Math.hypot(p.x - track.last.x, p.y - track.last.y);
      track.last = p;
      track.net = Math.hypot(p.x - track.start.x, p.y - track.start.y);
      if (!track.downSent && track.net > 28) {
        b.pointer("down", track.start);
        track.downSent = true;
        b.pointer("move", p);
        return;
      }
      if (track.downSent) {
        b.pointer("move", p);
        return;
      }
      if (track.path > 36 && track.net < 24) {
        track.petting = true;
        b.pointer("move", p);
      }
    }
    function finish(e, cancel) {
      if (!track || e.pointerId !== track.id) return;
      e.stopPropagation();
      var p = pt(e);
      var b = petBody();
      var dt = p.t - track.start.t;
      if (b) {
        if (cancel && track.downSent) b.pointer("cancel", p);
        else if (track.downSent) b.pointer("up", p);
        else if (track.petting) b.pointer("leave", p);
        else if (dt < 400 && track.net < 10) {
          b.pointer("down", track.start);
          b.pointer("up", p);
        }
      }
      track = null;
    }
    stage.addEventListener("pointerdown", onDown, true);
    stage.addEventListener("pointermove", onMove, true);
    stage.addEventListener("pointerup", function (e) { finish(e, false); }, true);
    stage.addEventListener("pointercancel", function (e) { finish(e, true); }, true);
  }

  window.addEventListener("error", function (event) {
    if (window.BluefinNative && window.BluefinNative.report) {
      window.BluefinNative.report(String(event.message || "error"));
    }
  });
  window.addEventListener("unhandledrejection", function (event) {
    if (window.BluefinNative && window.BluefinNative.report) {
      window.BluefinNative.report(String(event.reason || "rejection"));
    }
  });

  fitStage();
  installStageGestures();
  (function watchTouches() {
    var seen = 0;
    setInterval(function () {
      var ev = window.qa && window.qa.events;
      var body = window.qa && window.qa.body;
      if (!ev || !body || !window.BluefinNative || !window.BluefinNative.report) return;
      while (seen < ev.length) {
        var item = ev[seen++];
        var reaction = item && item.kind;
        if (["poke", "pet", "grab", "throw", "drop"].indexOf(reaction) < 0) continue;
        var hits = [];
        var mode = "";
        try {
          var layout = body.layout();
          mode = layout.mode || "";
          hits = (layout.hit || []).map(function (h) {
            return Math.round(h.x) + "," + Math.round(h.y) + ",r" + Math.round(h.r);
          });
        } catch (err) {}
        var surface = window.__bluefinOverlay ? "overlay" : "in-app";
        window.BluefinNative.report(
          "touch surface=" + surface +
          " reaction=" + reaction +
          " mode=" + mode +
          " scale=" + (window.__bluefinScale || "") +
          " hits=" + hits.join(";")
        );
      }
    }, 200);
  })();
  window.__bluefinNative = {
    act: act,
    reset: reset,
    ready: ready,
    setSound: setSound,
    fitStage: fitStage,
    applyPrefs: applyPrefs
  };
  window.__bluefinApplySound = setSound;
})();

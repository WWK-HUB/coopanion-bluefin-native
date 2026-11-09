(function () {
  if (window.__bluefinOverlay) return;

  var HOLD_MS = 520;
  var LIFT_NET = 28;
  var PAT_PATH = 36;
  var PAT_NET = 24;
  var track = null;
  var restTimer = 0;

  function body() {
    return window.qa && window.qa.body;
  }

  function pt(x, y) {
    return { x: x, y: y, t: Date.now() };
  }

  function grab(start, at) {
    var b = body();
    if (!b) return;
    b.pointer("down", start);
    b.pointer("move", at || { x: start.x + 10, y: start.y + 8, t: Date.now() });
  }

  function pointer(type, x, y) {
    var b = body();
    if (!b) return;
    if (type === "down") {
      if (track) return;
      var start = pt(x, y);
      track = { start: start, last: start, path: 0, net: 0, lifted: false, patting: false };
      window.__bluefinAllowRest = false;
      if (window.__bluefinAsleep) {
        window.__bluefinAsleep = "";
        window.__bluefinRoam = window.__bluefinRoamPref || "free";
      }
      track.timer = setTimeout(function () {
        // A scrub that has already started moving is a head-pat, not a lift.
        if (!track || track.lifted || track.path > 10) return;
        track.lifted = true;
        grab(track.start, null);
      }, HOLD_MS);
      return;
    }
    if (!track) return;
    if (type === "move") {
      var p = pt(x, y);
      track.path += Math.hypot(p.x - track.last.x, p.y - track.last.y);
      track.last = p;
      track.net = Math.hypot(p.x - track.start.x, p.y - track.start.y);
      if (!track.lifted && track.net > LIFT_NET) {
        clearTimeout(track.timer);
        track.lifted = true;
        grab(track.start, p);
        return;
      }
      if (track.lifted) {
        b.pointer("move", p);
        return;
      }
      if (track.path > PAT_PATH && track.net < PAT_NET) {
        track.patting = true;
        b.pointer("move", p);
      }
      return;
    }
    if (type === "up" || type === "cancel") {
      clearTimeout(track.timer);
      var end = pt(x, y);
      var dt = end.t - track.start.t;
      if (track.lifted) b.pointer(type === "cancel" ? "cancel" : "up", end);
      else if (track.patting) b.pointer("leave", end);
      else if (type !== "cancel" && dt < 400 && track.net < 10) {
        b.pointer("down", track.start);
        b.pointer("up", end);
      }
      track = null;
    }
  }

  function walkTo(x, run) {
    var b = body();
    if (!b || typeof b.walk !== "function") return;
    var mode = b.layout && b.layout().mode;
    if (mode === "drag" || mode === "air" || mode === "sleep" || mode === "sit" || mode === "dizzy") return;
    b.walk(x, !!run, 0);
  }

  function act(word) {
    var b = body();
    if (!b) return;
    window.__bluefinAllowRest = false;
    window.__bluefinAsleep = "";
    if (window.__bluefinNative && window.__bluefinNative.act) window.__bluefinNative.act(word);
    else if (typeof b.do === "function") b.do(word);
  }

  function rest() {
    var b = body();
    if (!b) return;
    window.__bluefinAllowRest = true;
    window.__bluefinRoam = "off";
    window.__bluefinAsleep = "sitting";
    if (window.__bluefinNative && window.__bluefinNative.applyPrefs) {
      window.__bluefinNative.applyPrefs(false, window.__bluefinSize || "medium", false);
    }
    b.set({ roam: "off" });
    b.do("sit");
    clearTimeout(restTimer);
    restTimer = setTimeout(function () {
      if (window.__bluefinAsleep !== "sitting") return;
      var live = body();
      if (!live) return;
      window.__bluefinAsleep = "sleep";
      live.do("sleep");
    }, 1400);
  }

  function setScale(value) {
    var s = Math.max(0.7, Math.min(1, Number(value) || 0.85));
    window.__bluefinForceScale = s;
    var b = body();
    if (b && typeof b.resize === "function") b.resize();
    publish();
  }

  function publish() {
    var b = body();
    if (!b || !b.layout || !window.BluefinOverlay || !window.BluefinOverlay.layout) return;
    var L = b.layout();
    var stage = document.querySelector("#stage");
    var s = (typeof window.__bluefinForceScale === "number") ? window.__bluefinForceScale : 0.85;
    var minX = 104 * s + 8;
    var maxX = Math.max(minX + 1, (stage ? stage.clientWidth : 0) - 104 * s - 8);
    var floorY = (stage ? stage.clientHeight : 0) - 40;
    var hits = (L.hit || []).map(function (h) {
      return { x: h.x, y: h.y, r: h.r };
    });
    window.BluefinOverlay.layout(JSON.stringify({
      mode: L.mode || "",
      facing: L.facing > 0 ? 1 : -1,
      x: L.x || 0,
      minX: minX,
      maxX: maxX,
      floorY: floorY,
      hits: hits
    }));
  }

  function fit() {
    var style = document.getElementById("bluefin-overlay-style");
    if (!style) {
      style = document.createElement("style");
      style.id = "bluefin-overlay-style";
      document.head.appendChild(style);
    }
    style.textContent = [
      "html,body{background:transparent!important;overflow:hidden!important;margin:0!important}",
      "main{padding:0!important;height:100%!important;display:block!important;background:transparent!important}",
      "#wrap{height:100%!important;display:block!important;background:transparent!important}",
      "header,#panel,#hint,#events,#stage:after{display:none!important}",
      "#stage{position:fixed!important;left:0!important;top:0!important;right:0!important;bottom:0!important;",
      "width:100%!important;height:100%!important;min-width:0!important;min-height:0!important;",
      "background:transparent!important;border:0!important}"
    ].join("");
    window.dispatchEvent(new Event("resize"));
  }

  fit();
  setInterval(function () {
    fit();
    publish();
  }, 80);

  window.__bluefinOverlay = {
    pointer: pointer,
    walkTo: walkTo,
    act: act,
    rest: rest,
    setScale: setScale,
    publish: publish
  };
})();

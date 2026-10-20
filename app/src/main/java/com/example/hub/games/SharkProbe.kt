package com.example.hub.games

/**
 * Compact Shark startup probe. It reports offline-calculation state and whether
 * the main interface or the offline overlay is actually on screen.
 *
 * The payload omits the save document. [latest_time] is reduced to an age in
 * seconds, and visible strings are truncated.
 */
object SharkProbe {
    const val JS: String =
        "(function(){ try {" +
            " function box(id){" +
            " var n = document.getElementById(id);" +
            " if (!n) return null;" +
            " var cs = getComputedStyle(n);" +
            " var r = n.getBoundingClientRect();" +
            " var vw = window.innerWidth || 0;" +
            " var vh = window.innerHeight || 0;" +
            " var hit = r.width > 1 && r.height > 1 && r.bottom > 0 && r.right > 0 && r.top < vh && r.left < vw;" +
            " return {d:n.style.display, cd:cs.display, v:cs.visibility, o:cs.opacity," +
            " w:Math.round(r.width), h:Math.round(r.height), t:Math.round(r.top), l:Math.round(r.left)," +
            " cw:n.clientWidth, ch:n.clientHeight, hit:hit};" +
            " }" +
            " var p = (typeof player !== 'undefined' && player) ? player : null;" +
            " var off = (typeof offline !== 'undefined' && offline) ? offline : null;" +
            " var elapsed = off && typeof off.elapsed === 'number' ? off.elapsed : null;" +
            " var prev = window.__hubOfflineElapsed;" +
            " var delta = (typeof prev === 'number' && typeof elapsed === 'number') ? Math.round((elapsed - prev) * 10) / 10 : null;" +
            " window.__hubOfflineElapsed = elapsed;" +
            " var played = p ? p.totalTimePlayed : null;" +
            " var prevPlayed = window.__hubPlayed;" +
            " var playedDelta = (typeof prevPlayed === 'number' && typeof played === 'number') ? Math.round((played - prevPlayed) * 10) / 10 : null;" +
            " window.__hubPlayed = played;" +
            " var ot = document.getElementById('offline-time');" +
            " var done = document.getElementById('offline-done');" +
            " var speed = document.getElementById('offline-speed');" +
            " var vv = window.visualViewport;" +
            " var age = (p && typeof p.latest_time === 'number') ? Math.round((Date.now() - p.latest_time) / 1000) : null;" +
            " return JSON.stringify({" +
            " boot: window.__hubStorageBootstrap || ''," +
            " phase: document.readyState || ''," +
            " title: (document.title || '').slice(0, 40)," +
            " player: !!p," +
            " ver: p ? p.VERSION : null," +
            " age: age," +
            " radio: (p && p.radios) ? p.radios.offline : null," +
            " off: off ? {a:!!off.active, e:elapsed == null ? null : Math.round(elapsed), s:off.speed, n:!!off.nosave, d:delta} : null," +
            " app: box('app')," +
            " offline: box('offline')," +
            " done: done ? {d:done.style.display, cd:getComputedStyle(done).display, hit:box('offline-done').hit, t:(done.textContent||'').slice(0,24)} : null," +
            " speed: speed ? {d:speed.style.display, cd:getComputedStyle(speed).display, hit:box('offline-speed').hit} : null," +
            " time: ot ? String(ot.textContent || '').replace(/\\s+/g,' ').slice(0, 80) : null," +
            " vp: {iw:window.innerWidth, ih:window.innerHeight, sx:Math.round(window.scrollX||0), sy:Math.round(window.scrollY||0)," +
            " vw: vv ? Math.round(vv.width) : null, vh: vv ? Math.round(vv.height) : null, vs: vv ? vv.scale : null," +
            " ox: vv ? Math.round(vv.offsetLeft) : null, oy: vv ? Math.round(vv.offsetTop) : null}," +
            " loop: typeof loop === 'function'," +
            " played: played," +
            " playedDelta: playedDelta," +
            " layout: !!document.getElementById('hub-shark-layout-v2') || !!document.getElementById('hub-shark-layout-v3')," +
            " zoom: document.documentElement ? (document.documentElement.style.zoom || '') : ''" +
            " });" +
            " } catch (e) { return JSON.stringify({error: String(e).slice(0, 180)}); } })()"
}

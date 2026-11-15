package com.example.hub.games

import org.json.JSONObject

/**
 * Fits a game document to the live viewport.
 *
 * Independent rows and grids wrap. Boards whose columns are the mechanic
 * keep their column count and scale down. A box that still sticks out can
 * scroll on its own. The document is not reloaded and storage is not read.
 *
 * [stableMetrics] is a stylesheet floor for a few known sentences.
 * Recurring jumps elsewhere are reserved by one layout-shift observer.
 * Saved reservations are passed in and only grow for the same viewport,
 * type size, and element structure. The script does not read or write a save.
 */
object DocumentLayout {
    const val MARKER = "hub-layout-fit"
    const val METRIC_STYLE_ID = "hub-stable-metrics"

    fun injectionScript(
        limitCssPx: Int = 0,
        stableMetrics: String = "",
        stableLine: String = "",
        savedReservations: String = "{}",
        textZoomPercent: Int = 100,
        pageZoomLiteral: String = "1.00",
    ): String {
        val limit = limitCssPx.coerceAtLeast(0)
        return "(function(){" +
            stableMetricInstaller(stableMetrics, stableLine) +
            stablePreamble(savedReservations, textZoomPercent, pageZoomLiteral) +
            "window.__hubLayoutLimit = $limit;" +
            "if (window.__hubLayoutInstalled) {" +
            " if (window.__hubStableReapply) window.__hubStableReapply();" +
            " if (window.__hubLayoutFit) window.__hubLayoutFit();" +
            " return '$MARKER'; }" +
            "var SPATIAL = '#evolution-tree,#decay-chain,#runes-div,#rune-grid,#core-assembler-grid,#solar-system';" +
            "var SKIP = '#tooltip-div,#popup,#offline,#notify,#hadron-cutscene,#omni-cutscene,#black-hole,#title-center,#offlineWrapper,#main-background';" +
            "function bad(el) {" +
            " if (!el || el.nodeType !== 1) return true;" +
            " var tag = el.tagName;" +
            " if (tag === 'CANVAS' || tag === 'SCRIPT' || tag === 'STYLE' || tag === 'SVG') return true;" +
            " return !!(el.closest && el.closest(SKIP));" +
            "}" +
            "function hidden(el) { return el.getClientRects().length === 0; }" +
            "function inSpatial(el) { return !!(el.closest && el.closest(SPATIAL)); }" +
            "function outermost(el) {" +
            " var parent = el.parentElement;" +
            " return !(parent && parent.closest && parent.closest(SPATIAL));" +
            "}" +
            "function visibleWidth() {" +
            " var iw = window.innerWidth || 0;" +
            " var cap = window.__hubLayoutLimit || 0;" +
            " if (cap > 40 && iw > 40) return Math.min(cap, iw);" +
            " return cap > 40 ? cap : iw;" +
            "}" +
            "function sticksOut(el) {" +
            " var rect = el.getBoundingClientRect();" +
            " var width = visibleWidth();" +
            " if (!(width > 0) || rect.width < 2) return false;" +
            " return rect.left < -16 || rect.right > width + 16;" +
            "}" +
            "function overflows(el) {" +
            " if (hidden(el)) return false;" +
            " if (el.scrollWidth > el.clientWidth + 8) return true;" +
            " var position = getComputedStyle(el).position;" +
            " if (position === 'absolute' && el.scrollHeight > el.clientHeight + 8) return true;" +
            " return sticksOut(el);" +
            "}" +
            "function loosen(el) {" +
            " var limit = el.clientWidth || window.innerWidth || 0;" +
            " if (!(limit > 0)) return;" +
            " var kids = el.children;" +
            " for (var i = 0; i < kids.length; i++) {" +
            " var kid = kids[i];" +
            " if (bad(kid) || hidden(kid)) continue;" +
            " var style = getComputedStyle(kid);" +
            " var minW = parseFloat(style.minWidth);" +
            " var declared = parseFloat(style.width);" +
            " var rect = kid.getBoundingClientRect();" +
            " var fixed = style.width.indexOf('px') >= 0 && declared > limit;" +
            " if (rect.width > limit + 1 || minW > limit || fixed) {" +
            " kid.style.maxWidth = '100%';" +
            " kid.style.minWidth = '0';" +
            " kid.style.boxSizing = 'border-box';" +
            " if (fixed) kid.style.width = 'auto';" +
            " }" +
            " }" +
            "}" +
            "function reflowGrid(el) {" +
            " var track = parseFloat(el.getAttribute('data-hub-track') || '');" +
            " if (!(track >= 40)) {" +
            " var columns = getComputedStyle(el).gridTemplateColumns.trim().split(/\\s+/).filter(Boolean);" +
            " if (columns.length < 2) return;" +
            " var widest = 0;" +
            " for (var i = 0; i < columns.length; i++) {" +
            " var size = parseFloat(columns[i]);" +
            " if (size > widest) widest = size;" +
            " }" +
            " if (!(widest >= 40)) return;" +
            " track = widest;" +
            " el.setAttribute('data-hub-track', String(Math.round(track)));" +
            " }" +
            " var box = el.clientWidth || 0;" +
            " var visible = visibleWidth();" +
            " var limit = box > 0 && visible > 0 ? Math.min(box, visible) : (box || visible || track);" +
            " var basis = track > limit - 16 ? Math.max(80, limit - 16) : track;" +
            " el.style.gridTemplateColumns = 'repeat(auto-fit, minmax(' + Math.round(basis) + 'px, 1fr))';" +
            " el.style.maxWidth = '100%';" +
            " el.style.width = '100%';" +
            " el.style.justifyContent = 'start';" +
            " el.setAttribute('data-hub-fit', 'grid');" +
            " loosen(el);" +
            "}" +
            "function reflowFlex(el) {" +
            " var style = getComputedStyle(el);" +
            " el.style.flexWrap = 'wrap';" +
            " el.style.maxWidth = '100%';" +
            " el.style.minWidth = '0';" +
            " if (style.position === 'absolute') {" +
            " el.style.position = 'relative';" +
            " el.style.height = 'auto';" +
            " el.style.top = 'auto';" +
            " el.style.right = 'auto';" +
            " el.style.bottom = 'auto';" +
            " el.style.left = 'auto';" +
            " if (el.parentElement) el.parentElement.style.height = 'auto';" +
            " }" +
            " loosen(el);" +
            " el.setAttribute('data-hub-fit', 'flex');" +
            "}" +
            "function stackTable(table) {" +
            " if (table.getAttribute('data-hub-fit') === 'table') return;" +
            " table.style.display = 'block';" +
            " table.style.width = '100%';" +
            " table.style.maxWidth = '100%';" +
            " var rows = table.querySelectorAll('tr');" +
            " for (var i = 0; i < rows.length; i++) {" +
            " rows[i].style.display = 'flex';" +
            " rows[i].style.flexWrap = 'wrap';" +
            " rows[i].style.width = '100%';" +
            " rows[i].style.maxWidth = '100%';" +
            " }" +
            " var cells = table.querySelectorAll('td, th');" +
            " for (var j = 0; j < cells.length; j++) {" +
            " cells[j].style.display = 'block';" +
            " cells[j].style.width = '100%';" +
            " cells[j].style.maxWidth = '100%';" +
            " cells[j].style.boxSizing = 'border-box';" +
            " }" +
            " table.setAttribute('data-hub-fit', 'table');" +
            "}" +
            "function zoomText(value) {" +
            " if (!value || value === 'normal') return '';" +
            " var n = parseFloat(value);" +
            " if (!(n > 0) || n >= 0.999) return '';" +
            " return String(Math.round(n * 1000) / 1000);" +
            "}" +
            "function scale(el) {" +
            " if (hidden(el)) return;" +
            " var parent = el.parentElement;" +
            " var avail = parent ? parent.clientWidth : visibleWidth();" +
            " var visible = visibleWidth();" +
            " if (visible > 0) avail = Math.min(avail || visible, visible);" +
            " if (!(avail > 8)) return;" +
            " var previous = zoomText(el.style.zoom);" +
            " var zoomNow = parseFloat(previous);" +
            " if (!(zoomNow > 0)) zoomNow = 1;" +
            " var inset = 0;" +
            " if (parent) {" +
            " var bounds = el.getBoundingClientRect();" +
            " var host = parent.getBoundingClientRect();" +
            " inset = (bounds.left - host.left) / zoomNow;" +
            " }" +
            " if (!(inset > 0) || inset >= avail) inset = 0;" +
            " var apparent = el.scrollWidth;" +
            " var storedApparent = parseFloat(el.getAttribute('data-hub-apparent') || '');" +
            " var geo = Math.round(avail) + ':' + Math.round(inset);" +
            " var storedRoom = el.getAttribute('data-hub-room') || '';" +
            " var storedGeo = storedRoom.split(':').slice(0, 2).join(':');" +
            " if (el.getAttribute('data-hub-fit') === 'zoom' && geo === storedGeo &&" +
            " Math.abs(apparent - storedApparent) <= 1) {" +
            " el.removeAttribute('data-hub-dirty');" +
            " return;" +
            " }" +
            " var needed = zoomNow < 0.999 ? apparent / zoomNow : apparent;" +
            " var dirty = el.getAttribute('data-hub-dirty') === '1';" +
            " if (!(needed > 8)) {" +
            " if (dirty) el.removeAttribute('data-hub-dirty');" +
            " return;" +
            " }" +
            " var room = avail - inset;" +
            " var zoom = Math.min(1, (room - 4) / needed);" +
            " var next = zoom < 0.999 ? String(Math.round(zoom * 1000) / 1000) : '';" +
            " var signature = Math.round(avail) + ':' + Math.round(inset) + ':' + Math.round(needed);" +
            " if (!dirty && el.getAttribute('data-hub-fit') === 'zoom' &&" +
            " el.getAttribute('data-hub-room') === signature && previous === next) return;" +
            " /* Assigning the same zoom again resets scroll anchoring on mobile WebView. */" +
            " if (zoomNow < 0.999 && previous !== next) {" +
            " el.style.zoom = '1';" +
            " var measured = el.scrollWidth;" +
            " if (!(measured > 8)) {" +
            " el.style.zoom = previous;" +
            " el.removeAttribute('data-hub-dirty');" +
            " return;" +
            " }" +
            " needed = measured;" +
            " room = avail - inset;" +
            " zoom = Math.min(1, (room - 4) / needed);" +
            " next = zoom < 0.999 ? String(Math.round(zoom * 1000) / 1000) : '';" +
            " signature = Math.round(avail) + ':' + Math.round(inset) + ':' + Math.round(needed);" +
            " }" +
            " el.setAttribute('data-hub-fit', 'zoom');" +
            " el.setAttribute('data-hub-room', signature);" +
            " el.removeAttribute('data-hub-dirty');" +
            " if (previous === next) {" +
            " if (zoomText(el.style.zoom) !== previous) el.style.zoom = previous;" +
            " el.setAttribute('data-hub-apparent', String(Math.round(el.scrollWidth)));" +
            " return;" +
            " }" +
            " window.__hubZoomWrites = (window.__hubZoomWrites || 0) + 1;" +
            " el.style.zoom = next;" +
            " el.setAttribute('data-hub-apparent', String(Math.round(el.scrollWidth)));" +
            "}" +
            "function spill(el) {" +
            " if (!el || el === document.body || el === document.documentElement) return;" +
            " if (inSpatial(el)) return;" +
            " var target = (el.tagName === 'BUTTON' || el.tagName === 'A') ? el.parentElement : el;" +
            " if (!target || target === document.body || target === document.documentElement) return;" +
            " if (target.getAttribute('data-hub-fit') === 'spill' || target.getAttribute('data-hub-fit') === 'grid') return;" +
            " target.style.overflowX = 'auto';" +
            " target.style.maxWidth = '100%';" +
            " target.setAttribute('data-hub-fit', 'spill');" +
            "}" +
            "function fit() {" +
            " if (window.__hubStableSuppress) window.__hubStableSuppress();" +
            " var root = document.body;" +
            " if (!root) return 'no-body';" +
            " var nodes = root.querySelectorAll('table, div, section, ul, ol');" +
            " var i;" +
            " for (i = 0; i < nodes.length; i++) {" +
            " var table = nodes[i];" +
            " if (bad(table) || hidden(table) || inSpatial(table)) continue;" +
            " if (table.tagName === 'TABLE' && overflows(table)) stackTable(table);" +
            " }" +
            " var boards = root.querySelectorAll(SPATIAL);" +
            " for (i = 0; i < boards.length; i++) {" +
            " var board = boards[i];" +
            " if (bad(board) || hidden(board) || !outermost(board)) continue;" +
            " scale(board);" +
            " }" +
            " for (i = 0; i < nodes.length; i++) {" +
            " var node = nodes[i];" +
            " if (bad(node) || hidden(node) || inSpatial(node) || !overflows(node)) continue;" +
            " var display = getComputedStyle(node).display;" +
            " if (display === 'grid' || display === 'inline-grid') reflowGrid(node);" +
            " else if (display === 'flex' || display === 'inline-flex') reflowFlex(node);" +
            " else if (node.tagName === 'TABLE') stackTable(node);" +
            " }" +
            " var stuck = root.querySelectorAll('button, div, table');" +
            " for (i = 0; i < stuck.length; i++) {" +
            " var box = stuck[i];" +
            " if (bad(box) || hidden(box) || !sticksOut(box)) continue;" +
            " spill(box);" +
            " }" +
            " return '$MARKER';" +
            "}" +
            "var pending = false;" +
            "function schedule() {" +
            " if (pending) return;" +
            " pending = true;" +
            " setTimeout(function(){ pending = false; fit(); }, 120);" +
            "}" +
            "function watchOne(obs, node, options) {" +
            " if (!node || node.nodeType !== 1) return false;" +
            " try { obs.observe(node, options); return true; } catch (e) { return false; }" +
            "}" +
            "var tabObs = new MutationObserver(function(records){" +
            " var changed = false;" +
            " for (var i = 0; i < records.length; i++) {" +
            " var tab = records[i].target;" +
            " if (!tab || tab.nodeType !== 1) continue;" +
            " var showing = getComputedStyle(tab).display !== 'none';" +
            " if (tab.__hubShowing !== showing) {" +
            " tab.__hubShowing = showing; changed = true; }" +
            " }" +
            " if (changed) { if (window.__hubStableSuppress) window.__hubStableSuppress(); }" +
            " if (changed) schedule();" +
            "});" +
            "function watchTabs() {" +
            " var tabs = document.querySelectorAll('[id' + '$=' + '\"-tab\"]');" +
            " for (var t = 0; t < tabs.length; t++) {" +
            " if (!tabs[t] || tabs[t].nodeType !== 1 || tabs[t].__hubWatch) continue;" +
            " tabs[t].__hubShowing = getComputedStyle(tabs[t]).display !== 'none';" +
            " if (watchOne(tabObs, tabs[t], {attributes:true, attributeFilter:['style','class']})) {" +
            " tabs[t].__hubWatch = true; }" +
            " }" +
            "}" +
            "function markBoard(node) {" +
            " if (!node || node.nodeType !== 1) return;" +
            " var board = (node.matches && node.matches(SPATIAL)) ? node :" +
            " (node.closest ? node.closest(SPATIAL) : null);" +
            " if (board) board.setAttribute('data-hub-dirty', '1');" +
            "}" +
            "var appObs = new MutationObserver(function(records){" +
            " var structural = false;" +
            " for (var i = 0; i < records.length; i++) {" +
            " var target = records[i].target;" +
            " if (!target || target.nodeType !== 1) continue;" +
            " var arrived = records[i].addedNodes;" +
            " for (var n = 0; n < arrived.length; n++) {" +
            " if (arrived[n].nodeType === 1 && window.__hubStableQueue) window.__hubStableQueue(); }" +
            " if (target.closest && target.closest(SPATIAL)) {" +
            " markBoard(target); structural = true; break; }" +
            " if (target.matches && target.matches('[id' + '$=' + '\"-tab\"]')) {" +
            " structural = true; break; }" +
            " var added = records[i].addedNodes;" +
            " for (var j = 0; j < added.length; j++) {" +
            " var node = added[j];" +
            " if (node.nodeType !== 1) continue;" +
            " var mounted = (node.matches && node.matches('table,section,[id' + '$=' + '\"-tab\"],' + SPATIAL)) ||" +
            " (node.closest && node.closest(SPATIAL)) ||" +
            " (node.querySelector && node.querySelector(SPATIAL));" +
            " if (!mounted) continue;" +
            " markBoard(node.querySelector ? (node.querySelector(SPATIAL) || node) : node);" +
            " structural = true; break; }" +
            " if (structural) break;" +
            " }" +
            " if (!structural) return;" +
            " watchTabs(); schedule();" +
            "});" +
            "function install() {" +
            " if (window.__hubLayoutInstalled) {" +
            " if (window.__hubStableReapply) window.__hubStableReapply();" +
            " if (window.__hubLayoutFit) window.__hubLayoutFit();" +
            " return '$MARKER'; }" +
            " var root = document.body;" +
            " if (!root || root.nodeType !== 1) return 'no-body';" +
            " var app = document.getElementById('app') || root;" +
            " if (!app || app.nodeType !== 1) return 'no-body';" +
            " if (!window.__hubLayoutListening) {" +
            " window.__hubLayoutListening = 1;" +
            " window.addEventListener('resize', function(){ if (window.__hubStableSuppress) window.__hubStableSuppress(); schedule(); });" +
            " if (window.visualViewport) window.visualViewport.addEventListener('resize', function(){ if (window.__hubStableSuppress) window.__hubStableSuppress(); schedule(); });" +
            " document.addEventListener('scroll', function(){ if (window.__hubStableSuppress) window.__hubStableSuppress(); }, true);" +
            " document.addEventListener('click', schedule, true);" +
            " }" +
            " watchTabs();" +
            " if (!watchOne(appObs, app, {childList:true, subtree:true})) return 'no-body';" +
            " window.__hubLayoutFit = fit;" +
            " window.__hubLayoutInstalled = 1;" +
            " window.__hubLayoutInstalls = (window.__hubLayoutInstalls || 0) + 1;" +
            " installStable();" +
            " if (window.__hubStableReapply) window.__hubStableReapply();" +
            " fit();" +
            " return '$MARKER';" +
            "}" +
            "function armWait() {" +
            " if (window.__hubLayoutWait) return;" +
            " window.__hubLayoutWait = 1;" +
            " function kick() {" +
            " if (window.__hubLayoutInstalled) return;" +
            " if (install() === '$MARKER' && window.__hubLayoutBootObs) {" +
            " try { window.__hubLayoutBootObs.disconnect(); } catch (e) {}" +
            " window.__hubLayoutBootObs = null; }" +
            " }" +
            " document.addEventListener('DOMContentLoaded', kick);" +
            " var docEl = document.documentElement;" +
            " if (docEl && docEl.nodeType === 1) {" +
            " var boot = new MutationObserver(function() {" +
            " if (!document.body || document.body.nodeType !== 1) return;" +
            " try { boot.disconnect(); } catch (e) {}" +
            " kick();" +
            " });" +
            " if (watchOne(boot, docEl, {childList:true})) window.__hubLayoutBootObs = boot;" +
            " }" +
            "}" +
            stableRuntime() +
            "var ready = install();" +
            "if (ready === 'no-body' && (!document.body || document.body.nodeType !== 1)) armWait();" +
            "return ready;" +
            "})()"
    }

    private fun stableMetricInstaller(selectors: String, line: String): String {
        if (selectors.isBlank()) return ""
        val css = JSONObject.quote(stableMetricCss(selectors, line))
        val id = METRIC_STYLE_ID
        return "var hubMetricNode=document.getElementById('$id');" +
            "if(!hubMetricNode){hubMetricNode=document.createElement('style');" +
            "hubMetricNode.id='$id';" +
            "(document.head||document.documentElement).appendChild(hubMetricNode);}" +
            "if(hubMetricNode.textContent!==$css)hubMetricNode.textContent=$css;"
    }

    private fun stableMetricCss(selectors: String, line: String): String {
        val size = line.trim().ifBlank { "1.2em" }
        return """
            $selectors {
              --hub-metric-line: $size;
              box-sizing: border-box;
              overflow-wrap: anywhere;
              min-height: calc(2 * var(--hub-metric-line) * 1.3);
            }
            @media (max-width: 700px) {
              $selectors {
                min-height: calc(3 * var(--hub-metric-line) * 1.3);
              }
            }
        """.trimIndent()
    }

    private fun stablePreamble(savedReservations: String, textZoomPercent: Int, pageZoomLiteral: String): String {
        val saved = runCatching { JSONObject(savedReservations).toString() }.getOrDefault("{}")
        val quotedSaved = JSONObject.quote(saved)
        val quotedZoom = JSONObject.quote(pageZoomLiteral.ifBlank { "1.00" })
        val zoom = textZoomPercent.coerceIn(50, 200)
        return "window.__hubTextZoom=$zoom;" +
            "window.__hubPageZoom=$quotedZoom;" +
            "window.__hubStableSuppress=function(){window.__hubStableUntil=Date.now()+200;};" +
            "(function(){var incoming={};try{incoming=JSON.parse($quotedSaved)||{};}catch(e){incoming={};}" +
            "var cur=window.__hubStableSaved||(window.__hubStableSaved={});" +
            "for(var cfg in incoming){if(!Object.prototype.hasOwnProperty.call(incoming,cfg))continue;" +
            "if(!cur[cfg]||typeof cur[cfg]!=='object')cur[cfg]={};var bucket=incoming[cfg];" +
            "if(!bucket||typeof bucket!=='object')continue;" +
            "for(var key in bucket){if(!Object.prototype.hasOwnProperty.call(bucket,key))continue;" +
            "var n=+bucket[key];if(!(n>0))continue;" +
            "if(!(cur[cfg][key]>=n))cur[cfg][key]=n;}}})();"
    }

    /**
     * One layout-shift observer, plus a text-triggered resize fallback
     * used only when shift entries do not name a source.
     */
    private fun stableRuntime(): String = """
        function configKey(){
          var w=window.innerWidth||0;
          var bucket=Math.round(w/8)*8;
          return bucket+'x'+(window.__hubTextZoom||100)+'x'+(window.__hubPageZoom||'1.00');
        }
        function suppressed(){return Date.now()<(window.__hubStableUntil||0);}
        function cssEscape(value){
          if(window.CSS&&CSS.escape)return CSS.escape(value);
          return String(value).replace(/[^a-zA-Z0-9_-]/g,'$&');
        }
        function identity(el){
          if(!el||el.nodeType!==1)return '';
          if(el.id)return '#'+cssEscape(el.id);
          var parts=[];
          var node=el;
          while(node&&node.nodeType===1&&node!==document.body&&node!==document.documentElement){
            if(node.id){parts.unshift('#'+cssEscape(node.id));break;}
            var parent=node.parentElement;
            if(!parent)break;
            var nth=0;
            var kids=parent.children;
            for(var i=0;i<kids.length;i++){
              if(kids[i].tagName!==node.tagName)continue;
              nth++;
              if(kids[i]===node)break;
            }
            parts.unshift(node.tagName.toLowerCase()+':nth-of-type('+nth+')');
            node=parent;
          }
          return parts.join('>');
        }
        function signature(el){
          var tags=[];
          var kids=el.children;
          for(var i=0;i<kids.length;i++)tags.push(kids[i].tagName);
          var cs=getComputedStyle(el);
          return tags.join(',')+'|'+(cs.fontSize||'')+'|'+(cs.lineHeight||'');
        }
        function blockBox(el){
          var n=el;
          while(n&&n.nodeType===1&&n!==document.body&&n!==document.documentElement){
            var display=getComputedStyle(n).display;
            if(display!=='inline'&&display!=='contents')return n;
            n=n.parentElement;
          }
          return null;
        }
        function samples(){
          var cfg=configKey();
          var all=window.__hubStableSamples||(window.__hubStableSamples={});
          if(!all[cfg])all[cfg]={};
          return all[cfg];
        }
        function remember(id,sig,px){
          var next=Math.ceil(px);
          if(!(next>0)||next>320)return;
          var cfg=configKey();
          var cur=window.__hubStableSaved||(window.__hubStableSaved={});
          if(!cur[cfg])cur[cfg]={};
          var key=id+'\n'+sig;
          if(cur[cfg][key]>=next)return;
          cur[cfg][key]=next;
          var pending=window.__hubStablePending;
          if(pending&&pending.config!==cfg)flushRemember();
          window.__hubStablePending={config:cfg,entries:cur[cfg]};
          if(window.__hubStableRememberTimer)clearTimeout(window.__hubStableRememberTimer);
          window.__hubStableRememberTimer=setTimeout(flushRemember,500);
        }
        function flushRemember(){
          if(window.__hubStableRememberTimer)clearTimeout(window.__hubStableRememberTimer);
          window.__hubStableRememberTimer=0;
          var pending=window.__hubStablePending;
          window.__hubStablePending=null;
          if(!pending)return;
          var bridge=window.hubLayout;
          if(!bridge||!bridge.remember)return;
          try{bridge.remember(JSON.stringify(pending));}catch(e){}
        }
        function reserve(box,id,sig,px){
          var next=Math.ceil(px);
          if(!(next>0)||next>320)return;
          var inline=parseFloat(box.style.minHeight);
          if(!isFinite(inline))inline=0;
          var computed=parseFloat(getComputedStyle(box).minHeight);
          if(!isFinite(computed))computed=0;
          var floor=Math.max(inline,computed);
          if(next>floor+0.5)box.style.minHeight=next+'px';
          if(box.getAttribute('data-hub-stable')!==sig)box.setAttribute('data-hub-stable',sig);
          remember(id,sig,Math.max(next,Math.ceil(floor)));
        }
        function note(box,prevH,nextH){
          if(suppressed()||!box||hidden(box))return;
          var id=identity(box);
          if(!id)return;
          var sig=signature(box);
          var marked=box.getAttribute('data-hub-stable');
          if(marked&&marked!==sig){box.style.minHeight='';box.removeAttribute('data-hub-stable');}
          var tall=Math.max(prevH||0,nextH||0);
          if(!(tall>=1)||tall>320)return;
          var key=id+'\n'+sig;
          var book=samples();
          var rec=book[key];
          if(!rec)rec=book[key]={last:nextH,max:tall,grew:false,shrank:false};
          if(nextH>prevH+0.5)rec.grew=true;
          else if(nextH<prevH-0.5)rec.shrank=true;
          rec.last=nextH;
          if(tall>rec.max)rec.max=tall;
          if(rec.grew&&rec.shrank)reserve(box,id,sig,rec.max);
        }
        function previousVisible(el){
          var n=el?el.previousElementSibling:null;
          while(n){if(!hidden(n))return n;n=n.previousElementSibling;}
          return null;
        }
        function considerSources(sources){
          var boxes=[];
          var info=[];
          for(var i=0;i<sources.length;i++){
            var src=sources[i];
            var node=src.node;
            if(!node||node.nodeType!==1)continue;
            if(node===document.body||node===document.documentElement)continue;
            if(bad(node)||inSpatial(node))continue;
            var prev=src.previousRect,cur=src.currentRect;
            if(!prev||!cur||Math.abs(cur.height-prev.height)<1)continue;
            var box=blockBox(node);
            if(!box||box===document.body||box===document.documentElement)continue;
            if(bad(box)||inSpatial(box)||hidden(box))continue;
            var live=box.offsetHeight||0;
            var estimated=live-(cur.height-prev.height);
            if(!(estimated>=0))estimated=prev.height;
            var tall=Math.max(estimated,live,prev.height,cur.height);
            if(tall>320)continue;
            boxes.push(box);
            info.push({prev:estimated,next:live});
          }
          var chosen=[];
          for(var a=0;a<boxes.length;a++){
            var ancestor=false;
            for(var b=0;b<boxes.length;b++){
              if(a!==b&&boxes[a].contains(boxes[b])){ancestor=true;break;}
            }
            if(!ancestor)chosen.push(a);
          }
          if(chosen.length){
            for(var c=0;c<chosen.length;c++)note(boxes[chosen[c]],info[chosen[c]].prev,info[chosen[c]].next);
          }
          var topmost=null,top=1e15;
          for(var s=0;s<sources.length;s++){
            var item=sources[s];
            var moved=item.node;
            if(!moved||moved.nodeType!==1||bad(moved)||inSpatial(moved))continue;
            var before=item.previousRect,after=item.currentRect;
            if(!before||!after||Math.abs(after.top-before.top)<1)continue;
            if(after.top<top){top=after.top;topmost=moved;}
          }
          if(!topmost)return;
          var neighbor=blockBox(previousVisible(topmost));
          var movedBox=blockBox(topmost);
          if(!listed(boxes,neighbor))trackLive(neighbor);
          if(movedBox!==neighbor&&!listed(boxes,movedBox))trackLive(movedBox);
        }
        function listed(boxes,box){
          if(!box)return false;
          for(var i=0;i<boxes.length;i++)if(boxes[i]===box)return true;
          return false;
        }
        function trackLive(box){
          if(!box||box===document.body||box===document.documentElement)return;
          if(bad(box)||inSpatial(box)||hidden(box))return;
          var height=box.offsetHeight||0;
          if(!(height>=1)||height>320)return;
          var prior=box.__hubStableLastH||0;
          box.__hubStableLastH=height;
          if(prior<1||Math.abs(height-prior)<1)return;
          note(box,prior,height);
        }
        function locate(root,selector){
          try{
            if(root&&root.nodeType===1){
              if(root.matches&&root.matches(selector))return root;
              if(root.querySelector){var found=root.querySelector(selector);if(found)return found;}
            }
            return document.querySelector(selector);
          }catch(e){return null;}
        }
        function reapply(root){
          var bucket=(window.__hubStableSaved||{})[configKey()];
          if(!bucket)return;
          for(var key in bucket){
            if(!Object.prototype.hasOwnProperty.call(bucket,key))continue;
            var splitAt=key.indexOf('\n');
            if(splitAt<1)continue;
            var id=key.slice(0,splitAt);
            var sig=key.slice(splitAt+1);
            var el=locate(root,id);
            if(!el)continue;
            var live=signature(el);
            if(live!==sig){
              if(el.getAttribute('data-hub-stable')===sig){el.style.minHeight='';el.removeAttribute('data-hub-stable');}
              continue;
            }
            var px=+bucket[key];
            if(!(px>0))continue;
            var inline=parseFloat(el.style.minHeight);
            if(!isFinite(inline))inline=0;
            var computed=parseFloat(getComputedStyle(el).minHeight);
            if(!isFinite(computed))computed=0;
            if(px>inline+0.5&&px>computed+0.5)el.style.minHeight=Math.ceil(px)+'px';
            if(el.getAttribute('data-hub-stable')!==sig)el.setAttribute('data-hub-stable',sig);
          }
        }
        function queueReapply(){
          if(window.__hubStableReapplyPending)return;
          window.__hubStableReapplyPending=1;
          setTimeout(function(){window.__hubStableReapplyPending=0;reapply();},0);
        }
        function watchTextBlock(box,watched,observer){
          if(!box||watched.has(box)||watched.count>=80)return;
          if(bad(box)||inSpatial(box)||hidden(box))return;
          var height=box.offsetHeight||0;
          if(!(height>=1)||height>320)return;
          watched.add(box);
          watched.count++;
          box.__hubStableLastH=height;
          try{observer.observe(box);}catch(e){}
        }
        function installFallback(){
          if(window.__hubStableFallbackWatch)return;
          window.__hubStableFallbackWatch=1;
          if(typeof ResizeObserver!=='function')return;
          var watched=new WeakSet();
          watched.count=0;
          var observer=new ResizeObserver(function(records){
            if(suppressed())return;
            for(var i=0;i<records.length;i++){
              var el=records[i].target;
              if(!el||el.nodeType!==1)continue;
              var height=el.offsetHeight||0;
              if(!(height>=1)||height>320)continue;
              var prev=el.__hubStableLastH||0;
              el.__hubStableLastH=height;
              if(prev<1||Math.abs(height-prev)<1)continue;
              note(el,prev,height);
            }
          });
          var textObs=new MutationObserver(function(records){
            for(var i=0;i<records.length;i++){
              var rec=records[i];
              var textish=rec.type==='characterData';
              var added=rec.addedNodes||[];
              for(var k=0;k<added.length&&!textish;k++)if(added[k].nodeType===3)textish=true;
              if(!textish)continue;
              var target=rec.target;
              var el=target&&target.nodeType===1?target:(target?target.parentElement:null);
              if(!el)continue;
              watchTextBlock(blockBox(el),watched,observer);
            }
          });
          try{textObs.observe(document.body,{subtree:true,characterData:true,childList:true});}catch(e){}
        }
        function installStable(){
          if(window.__hubStableInstalled)return;
          window.__hubStableInstalled=1;
          window.__hubStableReapply=reapply;
          window.__hubStableQueue=queueReapply;
          var supported=false;
          try{
            var types=PerformanceObserver.supportedEntryTypes||[];
            for(var i=0;i<types.length;i++)if(types[i]==='layout-shift')supported=true;
          }catch(e){}
          if(!supported){installFallback();return;}
          var misses=0;
          var shiftObs=new PerformanceObserver(function(list){
            var entries=list.getEntries();
            for(var i=0;i<entries.length;i++){
              var entry=entries[i];
              if(entry.hadRecentInput||suppressed())continue;
              var sources=entry.sources;
              if(sources&&sources.length){window.__hubStableAttributed=1;considerSources(sources);continue;}
              if(!window.__hubStableAttributed)misses++;
            }
            if(!window.__hubStableAttributed&&misses>=2&&!window.__hubStableFallback){
              window.__hubStableFallback=1;
              try{shiftObs.disconnect();}catch(e){}
              installFallback();
            }
          });
          try{shiftObs.observe({type:'layout-shift',buffered:true});}
          catch(e){installFallback();}
        }
        """.trimIndent()
}

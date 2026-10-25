package com.example.hub.games

/**
 * Fits a game document to the live viewport.
 *
 * Independent rows and grids wrap. Boards whose columns are the mechanic
 * keep their column count and scale down. A box that still sticks out can
 * scroll on its own. The document is not reloaded and storage is not read.
 */
object DocumentLayout {
    const val MARKER = "hub-layout-fit"

    fun injectionScript(limitCssPx: Int = 0): String {
        val limit = limitCssPx.coerceAtLeast(0)
        return "(function(){" +
            "window.__hubLayoutLimit = $limit;" +
            "if (window.__hubLayoutFit) { window.__hubLayoutFit(); return '$MARKER'; }" +
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
            "function scale(el) {" +
            " if (hidden(el)) return;" +
            " el.style.zoom = '1';" +
            " var needed = el.scrollWidth;" +
            " var parent = el.parentElement;" +
            " var avail = parent ? parent.clientWidth : visibleWidth();" +
            " var visible = visibleWidth();" +
            " if (visible > 0) avail = Math.min(avail || visible, visible);" +
            " if (!(needed > 8) || !(avail > 8)) return;" +
            " var inset = el.getBoundingClientRect().left;" +
            " if (!(inset > 0) || inset >= avail) inset = 0;" +
            " var room = avail - inset;" +
            " var zoom = Math.min(1, (room - 4) / needed);" +
            " el.style.zoom = zoom < 0.999 ? String(Math.round(zoom * 1000) / 1000) : '';" +
            " el.setAttribute('data-hub-fit', 'zoom');" +
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
            "window.__hubLayoutFit = fit;" +
            "window.addEventListener('resize', schedule);" +
            "if (window.visualViewport) window.visualViewport.addEventListener('resize', schedule);" +
            "document.addEventListener('click', schedule, true);" +
            "var tabObs = new MutationObserver(schedule);" +
            "function watchTabs() {" +
            " var tabs = document.querySelectorAll('[id' + '$=' + '\"-tab\"]');" +
            " for (var t = 0; t < tabs.length; t++) {" +
            " if (tabs[t].__hubWatch) continue;" +
            " tabs[t].__hubWatch = true;" +
            " tabObs.observe(tabs[t], {attributes:true, attributeFilter:['style','class']});" +
            " }" +
            "}" +
            "watchTabs();" +
            "var app = document.getElementById('app') || document.body;" +
            "new MutationObserver(function(){ watchTabs(); schedule(); }).observe(app, {childList:true, subtree:true});" +
            "fit();" +
            "return '$MARKER';" +
            "})()"
    }
}

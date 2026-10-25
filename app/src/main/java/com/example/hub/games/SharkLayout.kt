package com.example.hub.games

import org.json.JSONObject

/**
 * Shark presentation that does not depend on a viewport breakpoint.
 *
 * Upstream writes `flex-wrap: row wrap`, which is invalid, so those rows stay
 * on one line. The offline graph is a fixed overlay. Wrapping and scaling of
 * every other screen is [DocumentLayout]. This does not save or reload.
 */
object SharkLayout {
    const val VERSION = 5
    const val STYLE_ID = "hub-shark-layout-v5"

    val css: String = """
        #offline {
          position: fixed !important;
          top: 0 !important;
          right: 0 !important;
          bottom: 0 !important;
          left: 0 !important;
          width: auto !important;
          height: auto !important;
          min-height: 0 !important;
          z-index: 30 !important;
          box-sizing: border-box;
        }
        #offline-graph,
        #offline .graph {
          width: 100% !important;
          max-width: 100% !important;
          height: 200px !important;
          max-height: 36vh !important;
        }
        #offline-speed,
        #offline-done {
          position: sticky;
          bottom: 8px;
          z-index: 2;
        }
        .shark-upgrade-div {
          flex-wrap: wrap !important;
          max-width: 100%;
        }
        .explore-desc {
          display: flex !important;
          flex-wrap: wrap !important;
          justify-content: flex-start !important;
          max-width: 100% !important;
          min-width: 0 !important;
          box-sizing: border-box;
        }
        .explore-desc > button,
        .explore-desc > div,
        .explore-cost,
        .shark-upgrade-div > div,
        .shark-upgrade-div > div:not(:last-child) {
          max-width: 100% !important;
          box-sizing: border-box;
        }
        .explore-desc > div,
        .shark-upgrade-div > div,
        .shark-upgrade-div > div:not(:last-child) {
          min-width: 0 !important;
          width: auto !important;
          flex: 1 1 220px;
        }
        #popup-ctn {
          width: min(720px, calc(100vw - 24px));
          max-width: calc(100vw - 24px);
          box-sizing: border-box;
        }
    """.trimIndent()

    fun injectionScript(): String {
        val quoted = JSONObject.quote(css)
        return "(function(){ var id = '$STYLE_ID'; var css = $quoted;" +
            " ['hub-shark-layout-v2','hub-shark-layout-v3','hub-shark-layout-v4'].forEach(function(old){" +
            " var stale = document.getElementById(old);" +
            " if (stale && stale.parentNode) stale.parentNode.removeChild(stale); });" +
            " var node = document.getElementById(id);" +
            " if (!node) { node = document.createElement('style'); node.id = id;" +
            " (document.head || document.documentElement).appendChild(node); }" +
            " if (node.textContent !== css) node.textContent = css; return id; })()"
    }
}

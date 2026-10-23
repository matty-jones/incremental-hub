package com.example.hub.games

import org.json.JSONObject

/**
 * Narrow-screen layout for Shark Incremental.
 *
 * Shark positions upgrade rows absolutely and gives them fixed pixel widths.
 * On the Pixel Fold cover those rows overlap. This stylesheet only changes
 * presentation. It does not call the game's save routine or reload the page.
 */
object SharkLayout {
    const val VERSION = 4
    const val STYLE_ID = "hub-shark-layout-v4"

    val css: String = """
        /* The 600px offline graph pushes Speed and Done below the Pixel Fold
           inner viewport while #app stays hidden. Keep both controls on screen. */
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
        /* Upstream uses `flex-wrap: row wrap`, which is invalid, and a 200px
           button beside two 400px columns. Below the inner panel, wrap instead
           of centring that row off the left edge. */
        @media (max-width: 1040px) {
          .explore-desc {
            flex-wrap: wrap !important;
            justify-content: flex-start !important;
          }
          .explore-desc > button,
          .explore-desc > div,
          .explore-cost {
            max-width: 100% !important;
            box-sizing: border-box;
          }
          .explore-desc > div {
            min-width: 0 !important;
            width: auto !important;
            flex: 1 1 240px;
          }
        }
        @media (max-width: 560px) {
          .shark-upgrade {
            height: auto !important;
            min-height: 0;
          }
          .shark-upgrade-div {
            position: relative !important;
            height: auto !important;
            flex-wrap: wrap;
          }
          .shark-upgrade-div > div,
          .shark-upgrade-div > div:not(:last-child) {
            width: 100% !important;
          }
          .shark-upgrade-requirement {
            position: relative;
            width: auto;
            height: auto;
            min-height: 72px;
          }
          .shark-upgrade-requirement > div {
            position: relative;
            top: auto;
            transform: none;
            font-size: 22px;
          }
          .shark-upgrade-buttons {
            width: 100%;
          }
          .tab-button, .tab-button.stab {
            min-width: 0;
            max-width: 100%;
          }
          .option-div > div {
            min-width: 0;
            width: 100%;
          }
          #popup-ctn {
            width: calc(100vw - 24px);
            max-width: calc(100vw - 24px);
          }
          #shark-div > div,
          .curr-top {
            width: 100%;
          }
          #shark-button {
            width: 100%;
            margin: 8px 0;
          }
          button.huge-btn, button.half-huge-btn, button.big-btn {
            width: 100%;
            max-width: 100%;
          }
          #fish-div h2 {
            font-size: 22px;
          }
          #explore-tab,
          #explore-table,
          .explore-div,
          .explore-desc {
            width: 100% !important;
            max-width: 100% !important;
            min-width: 0 !important;
            box-sizing: border-box;
          }
          .explore-desc {
            display: flex !important;
            flex-flow: column nowrap !important;
            align-items: stretch !important;
            justify-content: flex-start !important;
          }
          .explore-desc > button {
            position: relative !important;
            left: auto !important;
            width: 100% !important;
            height: auto !important;
            min-height: 72px;
            flex: 0 0 auto !important;
            margin: 8px 0 !important;
          }
          .explore-desc > div {
            width: 100% !important;
            min-width: 0 !important;
            flex: 0 0 auto;
          }
          .explore-desc .table-left {
            width: 100%;
            max-width: 100%;
          }
          .explore-cost {
            width: 100% !important;
            height: auto !important;
            min-height: 48px;
            margin: 4px 0;
          }
        }
    """.trimIndent()

    fun injectionScript(): String {
        val quoted = JSONObject.quote(css)
        return "(function(){ var id = '$STYLE_ID'; var css = $quoted;" +
            " ['hub-shark-layout-v2','hub-shark-layout-v3'].forEach(function(old){" +
            " var stale = document.getElementById(old);" +
            " if (stale && stale.parentNode) stale.parentNode.removeChild(stale); });" +
            " var node = document.getElementById(id);" +
            " if (!node) { node = document.createElement('style'); node.id = id;" +
            " (document.head || document.documentElement).appendChild(node); }" +
            " if (node.textContent !== css) node.textContent = css; return id; })()"
    }
}

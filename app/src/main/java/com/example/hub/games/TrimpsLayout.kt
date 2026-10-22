package com.example.hub.games

/**
 * Trimps has no viewport meta. This only adds one when it is missing.
 * It does not change zoom and it does not reload the document.
 */
object TrimpsLayout {
    fun injectionScript(): String =
        "(function(){" +
            "var head=document.head||document.documentElement;" +
            "if(!head) return;" +
            "var meta=document.querySelector('meta[name=\"viewport\"]');" +
            "if(!meta){meta=document.createElement('meta');meta.setAttribute('name','viewport');head.appendChild(meta);}" +
            "if(!meta.getAttribute('content')) meta.setAttribute('content','width=device-width, initial-scale=1');" +
            "if(document.getElementById('hub-trimps-layout')) return;" +
            "var style=document.createElement('style');" +
            "style.id='hub-trimps-layout';" +
            "style.textContent=" +
            "'#offlineWrapper{position:fixed !important;left:0;top:0;z-index:1000;}" +
            "#settingsTable > tbody > tr > td{font-size:13px !important;width:auto !important;min-width:4.6em !important;}" +
            "#settingsRow{overflow-x:auto;}" +
            "#logBtnGroup{height:auto !important;min-height:32px;}" +
            "#logBtnGroup div button,.message{font-size:13px !important;line-height:16px !important;}" +
            "#logBtnGroup div button{padding:6px 8px !important;}" +
            "#topRow{height:auto !important;min-height:150px;}" +
            "#food,#foodOwned{font-size:16px !important;}" +
            ".fightBtn{font-size:14px !important;min-height:32px;}" +
            "#offlineTitle,#offlineProgressText,.offlineExtraBtn{font-size:16px !important;}';" +
            "head.appendChild(style);" +
            "})()"
}

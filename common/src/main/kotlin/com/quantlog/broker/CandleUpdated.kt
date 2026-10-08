package com.quantlog.broker

/** 진행 중인 분봉이 갱신됐다는 이벤트(틱마다). 화면 중계(앱의 ChartBroadcaster)가 듣는다. 게이트웨이가 내고, 앱이 받는다. */
data class CandleUpdated(val market: Market, val symbol: String, val candle: MinuteCandle)

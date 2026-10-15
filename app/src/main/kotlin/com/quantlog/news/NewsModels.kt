package com.quantlog.news

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Instant

/** 뉴스·공시 소스 접속 정보. 전부 시크릿이라 application-local.yml 이나 환경변수에만 적는다(비우면 그 소스는 꺼짐). */
@ConfigurationProperties(prefix = "quantlog.news")
data class NewsProperties(
    /** 네이버 검색 API(뉴스) client id / secret. */
    val naverClientId: String = "",
    val naverClientSecret: String = "",
    /** DART OpenAPI 인증키. */
    val dartApiKey: String = "",
    /** 네이버 뉴스 검색어. 검색어마다 한 번씩 부른다. */
    val queries: List<String> = listOf("코스피", "증시 속보", "미국 CPI", "환율"),
    /** 같은 요청을 다시 보내지 않고 직전 결과를 보여 주는 시간(초). 소스의 호출 한도를 아끼려는 값이다. */
    val cacheSeconds: Long = 60,
)

enum class NewsKind(val label: String) {
    NEWS("뉴스"),
    DISCLOSURE("공시"),
}

/** 화면에 한 줄로 보이는 뉴스·공시. 기사 원문은 저장하지 않고 제목·링크·요약만 쓴다. */
data class NewsItem(
    val kind: NewsKind,
    val title: String,
    val link: String,
    /** 발행 시각. DART 는 날짜만 주므로 그날 0시(KST)다. */
    val publishedAt: Instant,
    /** 기사 요약(뉴스) 또는 회사명(공시). */
    val summary: String = "",
    /** 공시의 종목코드. 뉴스는 없다. */
    val symbol: String? = null,
    /** 같은 항목을 두 번 가져와도 한 번만 보이게 하는 키. */
    val key: String,
)

/** 한 소스의 결과. 소스가 꺼져 있거나 실패해도 다른 소스는 그대로 보여 주려고 오류를 값으로 든다. */
data class NewsSourceResult(
    val name: String,
    val items: List<NewsItem> = emptyList(),
    /** null 이면 정상. */
    val error: String? = null,
    /** 키가 없어 부르지 않았으면 true. */
    val disabled: Boolean = false,
)

/** 뉴스·공시 한 소스. 테스트에서는 가짜로 바꾼다. */
interface NewsSource {
    val name: String

    fun fetch(): NewsSourceResult
}

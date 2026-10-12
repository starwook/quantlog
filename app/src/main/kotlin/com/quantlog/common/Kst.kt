package com.quantlog.common

import java.time.ZoneId

/** 서비스의 기준 시간대. 화면 표기·장 시간·날짜 경계가 모두 한국 시간이다. */
val KST: ZoneId = ZoneId.of("Asia/Seoul")

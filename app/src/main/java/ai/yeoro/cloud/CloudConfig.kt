package ai.yeoro.cloud

import ai.yeoro.BuildConfig

/**
 * Supabase 프로젝트 주소와 publishable 키. 둘 다 local.properties(git 제외)에서 BuildConfig로 들어온다.
 * 키가 없으면(enabled = false) 클라우드 기능은 아무 호출도 하지 않는다.
 */
object CloudConfig {
    val baseUrl: String = BuildConfig.SUPABASE_URL.trimEnd('/')
    val key: String = BuildConfig.SUPABASE_KEY
    val enabled: Boolean = key.isNotBlank() && baseUrl.isNotBlank()
    fun fn(name: String) = "$baseUrl/functions/v1/$name"
}

package ai.yeoro.cloud

import android.content.Context
import java.io.File

/** 받은 사진 파일: filesDir/photos/<tripId>/<id>.jpg(원본, 표지만), <id>_t.jpg(썸네일 720px). 캐시가 아니라 영구 저장(오프라인 앨범). 여행을 지우면 폴더째 지운다 */
class PhotoFiles(private val root: File) {
    fun dir(tripId: Long) = File(root, tripId.toString())
    fun full(tripId: Long, photoId: String) = File(dir(tripId), "${safe(photoId)}.jpg")
    fun thumb(tripId: Long, photoId: String) = File(dir(tripId), "${safe(photoId)}_t.jpg")
    fun deleteTrip(tripId: Long) { dir(tripId).deleteRecursively() }
    /** 사진 한 장의 원본·썸네일 */
    fun deletePhoto(tripId: Long, photoId: String) { full(tripId, photoId).delete(); thumb(tripId, photoId).delete() }

    /** 서버 id(uuid)만 파일 이름으로 쓴다: 경로 문자가 섞이면 거부 */
    private fun safe(id: String): String { require(id.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "bad photo id" }; return id }

    companion object { fun of(c: Context) = PhotoFiles(File(c.applicationContext.filesDir, "photos")) }
}

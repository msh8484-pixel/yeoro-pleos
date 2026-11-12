package ai.yeoro.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoPlanTest {
    private fun r(id: String) = RemotePhoto(id, 1L, "exif", 2L, 4, 3, "t/$id", "f/$id")

    @Test fun `서버에만 있는 것은 넣고, 차에만 있는 것은 지우고, 숨김 목록은 다시 넣지 않는다`() {
        val plan = planPhotoSync(local = setOf("a", "b", "gone"), remote = listOf(r("a"), r("b"), r("new"), r("hidden")), hidden = setOf("hidden"))
        assertEquals(listOf("new"), plan.add.map { it.id })
        assertEquals(setOf("gone"), plan.prune)
    }

    @Test fun `숨긴 사진이 서버에서도 사라지면 지울 것도 넣을 것도 없다`() {
        val plan = planPhotoSync(local = setOf("a"), remote = listOf(r("a")), hidden = setOf("h"))
        assertEquals(emptyList<RemotePhoto>(), plan.add)
        assertEquals(emptySet<String>(), plan.prune)
    }

    @Test fun `서버 목록이 비면 차의 사진을 모두 지운다`() {
        assertEquals(setOf("a", "b"), planPhotoSync(setOf("a", "b"), emptyList(), emptySet()).prune)
    }
}

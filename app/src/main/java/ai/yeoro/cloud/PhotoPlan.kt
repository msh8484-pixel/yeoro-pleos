package ai.yeoro.cloud

/** 서버 목록과 차의 사진 맞추기 결과: add = 새로 넣을 사진, prune = 차에서 지울 id(올린 사람이 서버에서 지움) */
data class PhotoPlan(val add: List<RemotePhoto>, val prune: Set<String>)

/**
 * 순수 병합. local: 차에 있는 사진 id, remote: 서버 목록(성공한 응답만 넘긴다), hidden: 차에서 빼고 서버 삭제를 기다리는 id.
 * 숨긴 사진은 서버 목록에 아직 있어도 다시 넣지 않는다. 차에만 있는 사진은 서버에서 지워진 것이라 지운다.
 */
fun planPhotoSync(local: Set<String>, remote: List<RemotePhoto>, hidden: Set<String>): PhotoPlan {
    val remoteIds = remote.mapTo(HashSet()) { it.id }
    return PhotoPlan(
        add = remote.filter { it.id !in local && it.id !in hidden }.distinctBy { it.id },
        prune = local.filterTo(LinkedHashSet()) { it !in remoteIds },
    )
}

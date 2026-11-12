# 영상 만들기 (숏폼 · 시연 영상)

- `record_demo.sh`: 에뮬레이터 화면을 녹화하면서 `scripts/scenario_demo.sh`를 돌리고, 장면 시작 시각을 남긴다 (`rec/`).
- `make_demo.py`: 1인칭 인트로 → 실제 동작 녹화(장면별 자막, 결과 화면은 1배속) → 아웃트로. 16:9, 약 3분.
- `make_ad.py`: 숏폼 광고(9:16, 약 48초). 드론·1인칭 장면 + 앱 화면 + 에디토리얼 키네틱 타이포(`typo.py`). `make_short.py`는 이전 버전.

1인칭 장면(`src/clip*.mp4`, `src/h_clip*.mp4`)과 배경음악(`src/music_*.mp3`)은 Higgsfield(Seedance 2.5, ElevenLabs Music)로 만든 생성물이다. 실제 브랜드 로고·차종은 넣지 않았다. 글씨는 Pretendard(OFL).
원본 소스와 결과물은 용량 때문에 저장소 밖(`제출/영상/`)에 둔다.

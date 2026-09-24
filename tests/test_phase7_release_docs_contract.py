from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def test_phase7_release_candidate_is_non_production_and_builds_release_variant():
    workflow = (ROOT / ".github/workflows/phase7-release-candidate.yml").read_text(encoding="utf-8")
    assert ":app:assembleRelease" in workflow
    assert "openstream-android-release-candidate.apk" in workflow
    assert "Ephemeral CI signing key" in workflow
    assert "gh release create" not in workflow
    assert 'packages: ""' in workflow


def test_public_release_uses_synclab_public_api_signing():
    workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
    config = (ROOT / "synclab-release.json").read_text(encoding="utf-8")
    app_gradle = (ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")

    assert "synclab-CICD-framework/.github/workflows/android-release.yml" in workflow
    assert "signingMode: public-api" in workflow
    assert "signingUrl: https://sign.synclab.com.vn" in workflow
    assert "secrets: inherit" in workflow
    assert '"profile": "prod"' in config
    assert '"tlsVerify": true' in config
    assert "OPENSTREAM_RELEASE_KEYSTORE" not in workflow
    assert "OPENSTREAM_RELEASE_KEYSTORE" not in app_gradle


def test_phase7_user_guide_keeps_primary_flow_cli_free_and_names_release_assets():
    guide = (ROOT / "docs/phase7-user-guide.md").read_text(encoding="utf-8")
    for expected in (
        "openstream-android.apk",
        "openstream-obs-plugin-installer-windows-x64.exe",
        "1080p30",
        "1080p60",
        "4K30",
        "4K60",
        "Profile",
        "Lưu và kết nối",
    ):
        assert expected in guide
    assert "không cần terminal hay lệnh CLI" in guide
    assert "discovery/pairing cùng LAN" in guide
    assert "Tailscale không dùng để nghiệm thu throughput production" in guide
